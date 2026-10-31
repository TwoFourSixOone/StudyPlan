package com.jiang.controller;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.security.JwtUtil;
import com.jiang.service.GoalService;
import com.jiang.vo.GoalVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private GoalService goalService;

    private static final String AUTH_HEADER = "Bearer valid_token";

    private void stubJwtAuth() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn("default");
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
}
