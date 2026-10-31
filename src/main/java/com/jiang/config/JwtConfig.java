package com.jiang.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/**
 * JWT 配置类
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Configuration
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtConfig {

    /**
     * JWT 密钥（从 application.yml 读取，必须 >= 32 字节）
     */
    @ToString.Exclude
    private String secret;

    /**
     * 过期时间（毫秒），默认 7200000（2 小时）
     */
    private long expiration = 7200000L;

    /**
     * 刷新阈值（毫秒），默认 300000（5 分钟）
     */
    private long refreshThreshold = 300000L;

    /**
     * 启动时校验密钥长度及数值字段
     */
    @PostConstruct
    public void validate() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT 密钥长度必须 >= 32 字节（256 位）");
        }
        if (expiration <= 0) {
            throw new IllegalArgumentException("jwt.expiration 必须 > 0");
        }
        if (refreshThreshold <= 0) {
            throw new IllegalArgumentException("jwt.refresh-threshold 必须 > 0");
        }
    }
}
