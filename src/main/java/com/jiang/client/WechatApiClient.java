package com.jiang.client;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.config.WechatConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 微信 API 客户端
 * 封装微信登录接口调用及重试逻辑
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Component
@Slf4j
public class WechatApiClient {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private WechatConfig wechatConfig;

    /**
     * 调用微信 API 换取 openid
     * 重试策略：仅对网络/超时异常重试（最多 2 次），业务失败（errcode != 0）直接抛出
     *
     * @param code 微信登录临时凭证
     * @return 用户 openid
     * @throws BusinessException 微信业务错误或网络失败
     */
    public String fetchOpenid(String code) {
        String encodedCode = URLEncoder.encode(code, StandardCharsets.UTF_8);
        String url = String.format(
                "%s?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                wechatConfig.getApiUrl(), wechatConfig.getAppid(), wechatConfig.getSecret(), encodedCode
        );

        int maxRetries = 2;
        long retryBackoffMs = 200;

        for (int i = 0; i < maxRetries; i++) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = restTemplate.getForEntity(url, Map.class).getBody();

                if (body == null) {
                    throw new BusinessException(ErrorCodes.OPENID_INVALID, "微信 API 返回为空");
                }

                // 微信业务错误（errcode 存在且不为 0）
                if (body.containsKey("errcode") && !"0".equals(String.valueOf(body.get("errcode")))) {
                    log.warn("微信 API 业务错误: errcode={}", body.get("errcode"));
                    throw new BusinessException(ErrorCodes.OPENID_INVALID,
                            "微信登录失败: " + body.get("errmsg"));
                }

                // 成功返回 openid
                if (body.containsKey("openid")) {
                    return (String) body.get("openid");
                }

                // 无 openid 也无 errcode，异常情况
                throw new BusinessException(ErrorCodes.OPENID_INVALID, "微信 API 响应格式异常");

            } catch (ResourceAccessException e) {
                // 网络/超时异常，可重试
                log.warn("微信 API 网络异常，重试中 ({}/{}): {}", i + 1, maxRetries, e.getMessage());
                if (i == maxRetries - 1) {
                    log.error("调用微信 API 网络失败，已达最大重试次数", e);
                    throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "微信登录失败，请稍后重试");
                }
                try {
                    Thread.sleep(retryBackoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "登录被中断");
                }
            } catch (BusinessException e) {
                // 业务异常不重试，直接抛出
                throw e;
            } catch (Exception e) {
                // 其他未知异常不重试
                log.error("调用微信 API 未知异常", e);
                throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "微信登录失败");
            }
        }

        throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "微信登录失败");
    }
}
