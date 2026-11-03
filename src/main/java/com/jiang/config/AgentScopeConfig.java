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
}
