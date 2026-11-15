package com.jiang.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AgentScope 配置属性
 * 读取 application.yml 中 agent.agentscope 前缀的配置
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.agentscope")
public class AgentScopeConfig {

    /** 大模型名称 */
    private String model = "deepseek-v4.1-flash";

    /** API 密钥（从环境变量注入，严禁明文） */
    private String apiKey;

    /** API 端点 */
    private String endpoint;

    /** 调用超时时间 */
    private Duration timeout = Duration.ofSeconds(30);

    /** 最大重试次数 */
    private int maxRetries = 2;

    /**
     * 单次输出 token 上限（2026-11-12）
     *
     * <p>整卷单次解析（一次传全部图片）需要输出全卷题目 + 详细解析，服务端默认上限会截断 JSON
     * （实测 max_tokens=4000 时 4 张图就被截断）。默认 8000，作为上限而非目标值。</p>
     */
    private Integer maxTokens = 8000;

    /**
     * 是否启用"深度思考"（DashScope 参数 {@code enable_thinking}）
     *
     * <p>默认 <b>关闭</b>：本项目的大模型调用全部是结构化输出（切题 JSON、出题 JSON、判分、归因映射），
     * 思考内容不会进入结果，只增加输出 token 与延迟。2026-11-12 实测（同一张试卷图、同 prompt、
     * max_tokens=2000）：思考开 13.1s / 2000 输出 token；思考关 6.5s / 1043 输出 token，<b>约 2 倍</b>。</p>
     *
     * <p>置为 {@code true} 可恢复思考（质量优先的场景），置空（yml 里不配）则沿用服务端默认。</p>
     */
    private Boolean enableThinking = Boolean.FALSE;
}
