package com.jiang.controller;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.LoginRequest;
import com.jiang.mapper.StudentMapper;
import com.jiang.model.entity.Student;
import com.jiang.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtUtil jwtUtil;

    @Autowired
    private StudentMapper studentMapper;

    @Autowired
    private org.springframework.web.client.RestTemplate restTemplate;

    private org.springframework.test.web.client.MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = org.springframework.test.web.client.MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void should_returnToken_when_loginSuccess() throws Exception {
        mockServer.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"openid\":\"test_openid\"}", MediaType.APPLICATION_JSON));

        when(jwtUtil.generateToken(any(), any(), any())).thenReturn("mock_jwt");

        LoginRequest request = new LoginRequest();
        request.setCode("valid_code");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("mock_jwt"));
    }

    @Test
    void should_return400_when_codeIsBlank() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setCode("");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void should_return401_when_noJwtForCurrentEndpoint() throws Exception {
        mockMvc.perform(get("/api/auth/current"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return401_when_invalidJwt() throws Exception {
        when(jwtUtil.parseAndValidateToken("invalid_jwt"))
                .thenThrow(new BusinessException(ErrorCodes.UNAUTHORIZED, "JWT 无效"));

        mockMvc.perform(get("/api/auth/current")
                        .header("Authorization", "Bearer invalid_jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_rollbackTransaction_when_generateTokenThrows() throws Exception {
        // 记录测试前 student 数量
        long countBefore = studentMapper.selectCount(null);

        mockServer.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"openid\":\"rollback_test_openid\"}", MediaType.APPLICATION_JSON));

        when(jwtUtil.generateToken(any(), any(), any()))
                .thenThrow(new RuntimeException("模拟签发失败"));

        LoginRequest request = new LoginRequest();
        request.setCode("valid_code");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is5xxServerError())
                .andReturn();

        // 验证响应包含正确错误码（generateToken 抛异常 → INTERNAL_ERROR）
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(Integer.valueOf(500), com.jayway.jsonpath.JsonPath.read(responseBody, "$.code"),
                "应返回 INTERNAL_ERROR (500)，实际: " + responseBody);

        // 验证事务回滚：student 表数量不变
        long countAfter = studentMapper.selectCount(null);
        assertEquals(countBefore, countAfter, "事务应回滚，student 表数量不应变化");
    }
}
