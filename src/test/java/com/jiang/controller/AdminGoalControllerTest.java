package com.jiang.controller;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.security.AdminApi;
import com.jiang.security.JwtUtil;
import com.jiang.service.GoalService;
import com.jiang.vo.GoalVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminGoalController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminGoalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** 用于「管理端映射必须标注 @AdminApi」的注解覆盖护栏 */
    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private GoalService goalService;

    private static final String AUTH_HEADER = "Bearer valid_token";

    private void stubJwtAuth() {
        stubJwtAuth(JwtUtil.ROLE_ADMIN);
    }

    /**
     * 按角色 stub JWT（P0-1：/api/admin/** 必须 role=admin）
     */
    private void stubJwtAuth(String role) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn("default");
        if (role != null) {
            when(claims.get(JwtUtil.CLAIM_ROLE, String.class)).thenReturn(role);
        }
        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    private GoalVO buildGoalVO() {
        return GoalVO.builder()
            .goalId(1L).studentId(1L).subjectId(10L).subjectName("数学")
            .direction("提高数学成绩").targetScore(120)
            .startDate(LocalDate.now()).endDate(LocalDate.now().plusMonths(3))
            .status("active").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
            .build();
    }

    @Test
    void should_returnGoalList_when_filteredByStudentAndSubject() throws Exception {
        stubJwtAuth();
        PageResult<GoalVO> pageResult = PageResult.of(1L, 1, 10, List.of(buildGoalVO()));
        when(goalService.adminGetGoalList(eq(1L), eq(10L), eq("active"), eq(1), eq(10)))
            .thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/goal")
                        .header("Authorization", AUTH_HEADER)
                        .param("studentId", "1")
                        .param("subjectId", "10")
                        .param("status", "active")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].goalId").value(1));
    }

    @Test
    void should_returnGoalList_when_noFiltersProvided() throws Exception {
        // W-05：无筛选条件查询管理端目标列表
        stubJwtAuth();
        PageResult<GoalVO> pageResult = PageResult.of(2L, 1, 10, List.of(buildGoalVO(), buildGoalVO()));
        when(goalService.adminGetGoalList(isNull(), isNull(), isNull(), eq(1), eq(10)))
            .thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/goal")
                        .header("Authorization", AUTH_HEADER)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    void should_returnBadRequest_when_invalidStatusProvided() throws Exception {
        // W-03：管理端 status 参数枚举校验
        stubJwtAuth();
        when(goalService.adminGetGoalList(isNull(), isNull(), eq("invalid_status"), eq(1), eq(10)))
            .thenThrow(new BusinessException(ErrorCodes.BAD_REQUEST));

        mockMvc.perform(get("/api/admin/goal")
                        .header("Authorization", AUTH_HEADER)
                        .param("status", "invalid_status")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST.getCode()));
    }

    @Test
    void should_returnGoalDetail_when_goalExists() throws Exception {
        stubJwtAuth();
        when(goalService.adminGetGoalDetail(1L)).thenReturn(buildGoalVO());

        mockMvc.perform(get("/api/admin/goal/1")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.goalId").value(1))
                .andExpect(jsonPath("$.data.studentId").value(1));
    }

    @Test
    void should_returnError_when_goalNotFound() throws Exception {
        stubJwtAuth();
        when(goalService.adminGetGoalDetail(999L)).thenThrow(
            new BusinessException(ErrorCodes.GOAL_NOT_FOUND));

        mockMvc.perform(get("/api/admin/goal/999")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.GOAL_NOT_FOUND.getCode()));
    }

    @Test
    void should_return401_when_noJwt() throws Exception {
        mockMvc.perform(get("/api/admin/goal"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== P0-1：管理端角色隔离 ====================

    @Test
    void should_return403_when_studentTokenCallsAdminApi() throws Exception {
        stubJwtAuth(JwtUtil.ROLE_STUDENT);

        mockMvc.perform(get("/api/admin/goal/1")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.FORBIDDEN.getCode()));
        // 403 必须在进入业务层之前返回
        verifyNoInteractions(goalService);
    }

    @Test
    void should_return403_when_legacyTokenWithoutRoleCallsAdminApi() throws Exception {
        // 改造前签发的旧 token 没有 role claim → 按 student 处理（不强制重新登录，但不得进管理端）
        stubJwtAuth(null);

        mockMvc.perform(get("/api/admin/goal")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.FORBIDDEN.getCode()));
        verifyNoInteractions(goalService);
    }

    // ==================== R2-F1 绕过回归（矩阵参数/percent 编码/重复斜杠/点段/尾斜杠） ====================

    @Test
    void should_notExecuteAdminHandler_when_studentTokenUsesNormalizedPathVariants() throws Exception {
        stubJwtAuth(JwtUtil.ROLE_STUDENT);

        List<String> rawUris = List.of(
                "/api/admin;x=1/goal/1",   // matrix 参数：Spring 剥离后命中 /api/admin/goal/1（R2 实测绕过点）
                "/api/%61dmin/goal/1",     // percent 编码：Spring 解码后命中（R2 实测绕过点）
                "//api/admin/goal/1",      // 重复斜杠
                "/api/./admin/goal/1",     // 点段
                "/api/admin/goal/1/"       // 尾斜杠
        );

        for (String rawUri : rawUris) {
            MvcResult result = mockMvc.perform(rawGet(rawUri)).andReturn();
            int status = result.getResponse().getStatus();
            assertTrue(status == 403 || status == 404,
                    rawUri + " 必须被拒绝（403 或 404），实际 " + status);
        }
        // R2 实测可命中 admin handler 的两个变体：必须由「@AdminApi 注解判定」拦下（403）
        for (String routedVariant : List.of("/api/admin;x=1/goal/1", "/api/%61dmin/goal/1")) {
            assertEquals(403, mockMvc.perform(rawGet(routedVariant)).andReturn().getResponse().getStatus(),
                    "该变体已命中管理端 handler，必须 403 由角色门禁拦下: " + routedVariant);
        }
        // 关键：所有变体都不得进入 admin handler 的业务层
        verifyNoInteractions(goalService);
    }

    @Test
    void should_allowAdminToken_when_usingNormalizedPathVariants() throws Exception {
        stubJwtAuth(JwtUtil.ROLE_ADMIN);
        when(goalService.adminGetGoalDetail(1L)).thenReturn(buildGoalVO());

        List<String> rawUris = List.of(
                "/api/admin;x=1/goal/1",
                "/api/%61dmin/goal/1",
                "//api/admin/goal/1",
                "/api/./admin/goal/1",
                "/api/admin/goal/1/"
        );
        for (String rawUri : rawUris) {
            MvcResult result = mockMvc.perform(rawGet(rawUri)).andReturn();
            int status = result.getResponse().getStatus();
            assertNotEquals(403, status, "admin token 不得被角色门禁误伤: " + rawUri);
            if (status == 200) {
                assertTrue(result.getResponse().getContentAsString().contains("\"goalId\":1"),
                        rawUri + " 应正常返回目标详情: " + result.getResponse().getContentAsString());
            }
        }
        // R2 实测可路由的两个等价路径变体：管理端仍能正常工作（200 + 正常响应体）
        for (String routedVariant : List.of("/api/admin;x=1/goal/1", "/api/%61dmin/goal/1")) {
            MvcResult result = mockMvc.perform(rawGet(routedVariant)).andReturn();
            assertEquals(200, result.getResponse().getStatus(),
                    "admin token 使用等价路径必须仍可访问: " + routedVariant);
            assertTrue(result.getResponse().getContentAsString().contains("\"goalId\":1"), routedVariant);
        }
    }

    // ==================== 注解覆盖护栏：管理端 handler 必须标注 @AdminApi ====================

    @Test
    void should_annotateAllAdminMappedHandlers_when_adminApiContract() {
        long adminMappings = requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().stream()
                        .anyMatch(pattern -> pattern.equals("/api/admin") || pattern.startsWith("/api/admin/")))
                .peek(entry -> assertTrue(
                        AnnotatedElementUtils.hasAnnotation(entry.getValue().getBeanType(), AdminApi.class),
                        "管理端映射必须标注 @AdminApi（否则角色门禁失效）: "
                                + entry.getKey().getPatternValues() + " -> "
                                + entry.getValue().getBeanType().getSimpleName()))
                .count();

        assertTrue(adminMappings >= 9,
                "应至少扫描到 9 个管理端映射（防止扫描为空导致假绿）：" + adminMappings);
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造「原始 URI 不被 MockMvc 再编码」的请求：percent 编码/matrix 参数等变体
     * 必须用 {@link MockHttpServletRequest#setRequestURI} 直接设置
     */
    private RequestBuilder rawGet(String rawUri) {
        return servletContext -> {
            MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "GET", rawUri);
            request.setRequestURI(rawUri);
            request.addHeader("Authorization", AUTH_HEADER);
            return request;
        };
    }
}
