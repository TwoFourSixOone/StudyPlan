package com.jiang.client;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.config.WechatConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WechatApiClient 单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@ExtendWith(MockitoExtension.class)
class WechatApiClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private WechatConfig wechatConfig;

    @InjectMocks
    private WechatApiClient wechatApiClient;

    private static final String TEST_CODE = "test_code";
    private static final String TEST_OPENID = "test_openid";
    private static final String TEST_APPID = "test_appid";
    private static final String TEST_SECRET = "test_secret";
    private static final String TEST_API_URL = "https://api.weixin.qq.com/sns/jscode2session";

    @BeforeEach
    void setUp() {
        lenient().when(wechatConfig.getAppid()).thenReturn(TEST_APPID);
        lenient().when(wechatConfig.getSecret()).thenReturn(TEST_SECRET);
        lenient().when(wechatConfig.getApiUrl()).thenReturn(TEST_API_URL);
    }

    @Test
    void should_returnOpenid_when_wechatApiSuccess() {
        Map<String, Object> wxResponse = new HashMap<>();
        wxResponse.put("openid", TEST_OPENID);
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(wxResponse, HttpStatus.OK));

        String result = wechatApiClient.fetchOpenid(TEST_CODE);

        assertEquals(TEST_OPENID, result);
        verify(restTemplate, times(1)).getForEntity(anyString(), eq(Map.class));
    }

    @Test
    void should_retryAndThrow_when_networkExceptionTwice() {
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenThrow(new ResourceAccessException("timeout"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> wechatApiClient.fetchOpenid(TEST_CODE));
        assertEquals(ErrorCodes.INTERNAL_ERROR.getCode(), ex.getCode());
        verify(restTemplate, times(2)).getForEntity(anyString(), eq(Map.class));
    }

    @Test
    void should_notRetry_when_businessError() {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("errcode", 40029);
        errorResponse.put("errmsg", "invalid code");
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(errorResponse, HttpStatus.OK));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> wechatApiClient.fetchOpenid(TEST_CODE));
        assertEquals(ErrorCodes.OPENID_INVALID.getCode(), ex.getCode());
        verify(restTemplate, times(1)).getForEntity(anyString(), eq(Map.class));
    }

    @Test
    void should_throw_when_responseBodyNull() {
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> wechatApiClient.fetchOpenid(TEST_CODE));
        assertEquals(ErrorCodes.OPENID_INVALID.getCode(), ex.getCode());
    }

    @Test
    void should_throw_when_noOpenidAndNoErrcode() {
        Map<String, Object> response = new HashMap<>();
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> wechatApiClient.fetchOpenid(TEST_CODE));
        assertEquals(ErrorCodes.OPENID_INVALID.getCode(), ex.getCode());
    }

    @Test
    void should_retryOnce_thenSucceed() {
        // 第一次网络失败，第二次成功返回 openid
        Map<String, Object> wxResponse = new HashMap<>();
        wxResponse.put("openid", TEST_OPENID);
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(new ResponseEntity<>(wxResponse, HttpStatus.OK));

        String result = wechatApiClient.fetchOpenid(TEST_CODE);

        assertEquals(TEST_OPENID, result);
        verify(restTemplate, times(2)).getForEntity(anyString(), eq(Map.class));
    }
}
