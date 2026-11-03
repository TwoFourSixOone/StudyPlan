package com.jiang.client;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.config.AgentScopeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * AgentScope LLM 调用客户端
 * 封装与 LLM API 的交互，后续可替换为 AgentScope Java SDK
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Component
@Slf4j
public class AgentScopeClient {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private AgentScopeConfig agentScopeConfig;

    /**
     * 调用 LLM API 生成内容
     *
     * @param prompt 发送给 LLM 的提示词
     * @return LLM 返回的文本内容
     * @throws BusinessException 调用失败时抛出
     */
    public String call(String prompt) {
        int maxRetries = agentScopeConfig.getMaxRetries();
        long retryBackoffMs = 500;

        for (int i = 0; i < maxRetries; i++) {
            try {
                return doCall(prompt);
            } catch (ResourceAccessException e) {
                log.warn("Agent API 网络异常，重试中 ({}/{}): {}", i + 1, maxRetries, e.getMessage());
                if (i == maxRetries - 1) {
                    throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED,
                            "Agent API 调用失败，已达最大重试次数");
                }
                try {
                    Thread.sleep(retryBackoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "Agent 调用被中断");
                }
            }
        }

        throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "Agent API 调用失败");
    }

    private String doCall(String prompt) {
        String endpoint = agentScopeConfig.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED,
                    "Agent API endpoint 未配置");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(agentScopeConfig.getApiKey());

        Map<String, Object> body = new HashMap<>();
        body.put("model", agentScopeConfig.getModel());
        body.put("prompt", prompt);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(endpoint, request, Map.class);
        Map responseBody = response.getBody();

        if (responseBody == null || !responseBody.containsKey("content")) {
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED,
                    "Agent API 返回格式异常");
        }

        return (String) responseBody.get("content");
    }
}
