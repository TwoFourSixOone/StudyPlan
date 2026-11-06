package com.jiang.client;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.config.AgentScopeConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AgentScope LLM 调用客户端
 * 使用 AgentScope Java SDK（DashScopeChatModel）封装与 LLM API 的交互
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Component
@Slf4j
public class AgentScopeClient {

    @Autowired
    private AgentScopeConfig agentScopeConfig;

    private DashScopeChatModel model;

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
     * 调用 LLM API 生成内容
     *
     * @param prompt 发送给 LLM 的提示词
     * @return LLM 返回的文本内容
     * @throws BusinessException 调用失败时抛出
     */
    public String call(String prompt) {
        try {
            return doCall(prompt);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Agent API 调用异常: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED,
                    "Agent API 调用失败: " + e.getMessage());
        }
    }

    private String doCall(String prompt) {
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .content(new TextBlock.Builder().text(prompt).build())
                .name("user")
                .build();

        log.debug("Agent API 调用开始: promptLength={}", prompt.length());

        Flux<ChatResponse> responseFlux = model.stream(List.of(userMsg), null, null);
        List<ChatResponse> responses = responseFlux.collectList().block();

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
