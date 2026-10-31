package com.jiang.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 微信配置类（含 fail-fast 校验）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Configuration
@ConfigurationProperties(prefix = "wechat")
@Data
public class WechatConfig {

    @ToString.Exclude
    private String appid;

    @ToString.Exclude
    private String secret;

    /**
     * 微信登录 API 地址（默认：https://api.weixin.qq.com/sns/jscode2session）
     */
    private String apiUrl = "https://api.weixin.qq.com/sns/jscode2session";

    @PostConstruct
    public void validate() {
        if (appid == null || appid.isBlank()) {
            throw new IllegalArgumentException("wechat.appid 不能为空，请在配置文件中设置");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("wechat.secret 不能为空，请在配置文件中设置");
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalArgumentException("wechat.api-url 不能为空，请在配置文件中设置");
        }
    }
}
