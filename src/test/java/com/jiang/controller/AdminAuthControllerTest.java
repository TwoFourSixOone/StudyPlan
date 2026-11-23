package com.jiang.controller;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.security.AdminApi;
import com.jiang.security.JwtUtil;
import com.jiang.service.AdminAuthService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminAuthController 改密接口测试（Change: 2026-11-19_admin_password_change）
 *
 * <p>覆盖：admin token 调改密成功、adminId 取自服务端上下文（不接受客户端传入）、
 * 学生 token 403（@AdminApi 角色隔离）、原密码错返回业务码 1194、参数缺失走校验。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthControllerTest {

    private static final String AUTH_HEADER = "Bearer valid_token";
    private static final String CHANGE_PASSWORD_URL = "/api/admin/auth/change-password";
    private static final Long ADMIN_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private AdminAuthService adminAuthService;

    /**
     * 按角色 stub JWT（role=admin → 放行管理端；role=student → 403）
     */
    private void stubJwtAuth(String role) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(ADMIN_ID));
        when(claims.get("openid", String.class)).thenReturn("admin_test");
        when(claims.get("tenantId", String.class)).thenReturn("default");
        if (role != null) {
            when(claims.get(JwtUtil.CLAIM_ROLE, String.class)).thenReturn(role);
        }
        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    private String body(String oldPassword, String newPassword) {
        return "{\"oldPassword\":\"" + oldPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    @Test
    void should_changePassword_when_adminTokenProvided() throws Exception {
        stubJwtAuth(JwtUtil.ROLE_ADMIN);

        mockMvc.perform(post(CHANGE_PASSWORD_URL)
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("OldPass-123", "NewPass-456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // adminId 必须来自服务端上下文（JWT sub），请求体不携带
        verify(adminAuthService).changePassword(eq(ADMIN_ID), eq("OldPass-123"), eq("NewPass-456"));
    }

    @Test
    void should_forbid_when_studentTokenProvided() throws Exception {
        stubJwtAuth(JwtUtil.ROLE_STUDENT);

        mockMvc.perform(post(CHANGE_PASSWORD_URL)
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("OldPass-123", "NewPass-456")))
                .andExpect(status().isForbidden());

        verify(adminAuthService, never()).changePassword(any(), anyString(), anyString());
    }

    @Test
    void should_returnPasswordMismatchCode_when_oldPasswordWrong() throws Exception {
        stubJwtAuth(JwtUtil.ROLE_ADMIN);
        doThrow(new BusinessException(ErrorCodes.ADMIN_PASSWORD_MISMATCH))
                .when(adminAuthService).changePassword(any(), anyString(), anyString());

        mockMvc.perform(post(CHANGE_PASSWORD_URL)
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("wrong", "NewPass-456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ADMIN_PASSWORD_MISMATCH.getCode()));
    }

    @Test
    void should_reject_when_newPasswordTooShort() throws Exception {
        stubJwtAuth(JwtUtil.ROLE_ADMIN);

        mockMvc.perform(post(CHANGE_PASSWORD_URL)
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("OldPass-123", "123")))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));

        verify(adminAuthService, never()).changePassword(any(), anyString(), anyString());
    }

    @Test
    void should_annotateAdminApi_when_declared() {
        assertTrue(AdminAuthController.class.isAnnotationPresent(AdminApi.class),
                "管理端 controller 必须标注 @AdminApi，否则改密接口不会被角色隔离覆盖");
    }
}
