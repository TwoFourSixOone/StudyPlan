package com.jiang.client;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.config.AgentScopeConfig;
import com.jiang.dto.VisionImage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AgentScope LLM 调用客户端
 * 使用 AgentScope Java SDK（DashScopeChatModel）封装与 LLM API 的交互
 *
 * <p>一个 client、两个 model 实例（design D2 / §3.1）：</p>
 * <ul>
 *   <li>{@code model}：文本调用（现状，行为零改动）</li>
 *   <li>{@code visionModel}：图文调用，复用同一份 apiKey/baseUrl/modelName/EndpointType，
 *       额外挂 {@link DashScopeChatFormatter}（DashScope 多模态接口的硬要求）</li>
 * </ul>
 *
 * <p>两条调用路径都经过 {@link LlmConcurrencyLimiter} 全局闸门（design S1）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Component
@Slf4j
public class AgentScopeClient {

    @Autowired
    private AgentScopeConfig agentScopeConfig;

    @Autowired
    private LlmConcurrencyLimiter llmConcurrencyLimiter;

    private DashScopeChatModel model;

    /**
     * 图文模型（design D2：同 client、同配置、同模型名，仅额外挂 DashScopeChatFormatter）
     */
    private DashScopeChatModel visionModel;

    @PostConstruct
    public void init() {
        String apiKey = agentScopeConfig.getApiKey();
        String baseUrl = agentScopeConfig.getEndpoint();
        String modelName = agentScopeConfig.getModel();
        Duration timeout = agentScopeConfig.getTimeout();
        int maxRetries = agentScopeConfig.getMaxRetries();

        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED,
                    "AgentScope API Key 未配置，请检查 agent.agentscope.api-key 配置或环境变量");
        }

        ExecutionConfig executionConfig = ExecutionConfig.builder()
                .timeout(timeout)
                .maxAttempts(maxRetries + 1)
                .build();

        GenerateOptions generateOptions = GenerateOptions.builder()
                .executionConfig(executionConfig)
                .build();

        model = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .endpointType(EndpointType.MULTIMODAL)//要根据模型来选择
                .stream(false)
                .defaultOptions(generateOptions)
                .build();

        // 图文模型：不新增模型名配置（design D2，仍读 agent.agentscope.model）；
        // DashScope 视觉调用要求 formatter 为 DashScopeChatFormatter（多模态 content parts 由它组装）
        visionModel = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .endpointType(EndpointType.MULTIMODAL)
                .stream(false)
                .defaultOptions(generateOptions)
                .formatter(new DashScopeChatFormatter())
                .build();

        log.info("AgentScope SDK 初始化完成: model={}, baseUrl={}, timeout={}s, maxRetries={}",
                modelName, baseUrl, timeout.getSeconds(), maxRetries);
        //在这里进行一次测试调用，确保 SDK 配置正确
        try {
            String testResult = doCall("调用测试，你只要回复调用成功就代表配置正确");
            log.info("AgentScope SDK 测试调用成功: response={}", testResult);
        } catch (Exception e) {
            log.warn("AgentScope SDK 测试调用失败，请检查 model 名称和 endpoint 配置: {}", e.getMessage());
        }
    }

    /**
     * 调用 LLM API 生成内容（文本，行为与改造前完全一致，仅外层加全局并发闸门）
     *
     * @param prompt 发送给 LLM 的提示词
     * @return LLM 返回的文本内容
     * @throws BusinessException 调用失败时抛出
     */
    public String call(String prompt) {
        try {
            return llmConcurrencyLimiter.call(() -> doCall(prompt));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Agent API 调用异常: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED,
                    "Agent API 调用失败: " + e.getMessage());
        }
    }

    /**
     * 调用视觉模型（图文）
     *
     * <p>约定：每次调用只带 1 张图（design §3.1），避免多图 base64 同时驻留；
     * base64 在 {@link #buildVisionMessage} 内部按图生成，随方法返回即可被回收，
     * <b>不得</b>写入日志、Redis 或数据库字段（design §11.3 禁止项）。</p>
     *
     * @param prompt 提示词
     * @param images 图片（媒体类型 + 原始字节）
     * @return 模型返回的文本内容
     */
    public String callVision(String prompt, List<VisionImage> images) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "视觉调用 Prompt 不能为空");
        }
        if (images == null || images.isEmpty()) {
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "视觉调用缺少图片");
        }
        try {
            return llmConcurrencyLimiter.call(() -> doVisionCall(prompt, images));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Agent 视觉调用异常: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED,
                    "Agent 视觉调用失败: " + e.getMessage());
        }
    }

    private String doCall(String prompt) {
        Msg userMsg = buildTextMessage(prompt);

        log.debug("Agent API 调用开始: promptLength={}", prompt.length());
        log.debug("Agent API 完整 Prompt:\n{}", prompt);

        Flux<ChatResponse> responseFlux = model.stream(List.of(userMsg), null, null);
        return extractText(responseFlux.collectList().block());
    }

    private String doVisionCall(String prompt, List<VisionImage> images) {
        Msg userMsg = buildVisionMessage(prompt, images);

        // 日志只记录长度与张数：base64/图片内容一律不入日志（design §11.3）
        log.debug("Agent 视觉调用开始: promptLength={}, imageCount={}", prompt.length(), images.size());

        Flux<ChatResponse> responseFlux = visionModel.stream(List.of(userMsg), null, null);
        return extractText(responseFlux.collectList().block());
    }

    /**
     * 构造文本消息（保持改造前结构：单个 TextBlock）
     */
    static Msg buildTextMessage(String prompt) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(new TextBlock.Builder().text(prompt).build())
                .name("user")
                .build();
    }

    /**
     * 构造图文消息：1 个 TextBlock + N 个 ImageBlock（Base64Source，落成 data URL）
     *
     * <p>DashScope 多模态接口的 content parts 形如
     * {@code [{"text": "..."}, {"image": "data:image/png;base64,..."}]}，
     * 由 {@code DashScopeChatFormatter} 根据 {@link Base64Source} 自动拼出。</p>
     */
    static Msg buildVisionMessage(String prompt, List<VisionImage> images) {
        if (images == null || images.isEmpty()) {
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "视觉调用缺少图片");
        }
        List<ContentBlock> blocks = new ArrayList<>(images.size() + 1);
        blocks.add(new TextBlock.Builder().text(prompt).build());
        for (VisionImage image : images) {
            if (image == null || image.getBytes() == null || image.getBytes().length == 0) {
                throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "视觉调用图片内容为空");
            }
            // 逐图生成 base64（峰值 = 1 张图），局部变量用完即弃，不进入任何集合/日志
            String base64 = toPureBase64(image.getBytes());
            blocks.add(ImageBlock.builder()
                    .source(Base64Source.builder()
                            .mediaType(image.getMediaType())
                            .data(base64)
                            .build())
                    .build());
            base64 = null; // 及时释放引用（design §11.3：按图生成、及时释放）
        }
        return Msg.builder()
                .role(MsgRole.USER)
                .content(blocks)
                .name("user")
                .build();
    }

    /**
     * 原始字节 → 纯 base64（不带 data URL 前缀；若入参本身带 {@code data:...;base64,} 前缀则先剥离）
     */
    static String toPureBase64(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "视觉调用图片内容为空");
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 从响应块中提取文本（容错：多块拼接；忽略非文本块）
     */
    private String extractText(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "Agent API 返回空响应");
        }

        // 拼接所有响应块的文本内容
        String content = responses.stream()
                .flatMap(r -> r.getContent().stream())
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());

        if (content.isBlank()) {
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "Agent API 返回内容为空");
        }

        log.debug("Agent API 调用完成: responseLength={}", content.length());
        return content;
    }
}
