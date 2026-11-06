package com.jiang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.dto.CreateGoalRequest;
import com.jiang.dto.UpdateGoalRequest;
import com.jiang.security.JwtUtil;
import com.jiang.service.GoalService;
import com.jiang.vo.GoalVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * GoalController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    // ==================== 创建目标 ====================

    @Test
    void should_createGoal_when_validRequest() throws Exception {
        stubJwtAuth();
        when(goalService.createGoal(any(CreateGoalRequest.class))).thenReturn(buildGoalVO());

        CreateGoalRequest request = new CreateGoalRequest();
        request.setSubjectId(10L);
        request.setDirection("提高数学成绩");
        request.setTargetScore(120);
        request.setStartDate(LocalDate.now());
        request.setEndDate(LocalDate.now().plusMonths(3));

        mockMvc.perform(post("/api/student/goal")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.goalId").value(1))
                .andExpect(jsonPath("$.data.subjectName").value("数学"))
                .andExpect(jsonPath("$.data.status").value("active"));
    }

    @Test
    void should_return400_when_subjectIdNull() throws Exception {
        stubJwtAuth();

        CreateGoalRequest request = new CreateGoalRequest();
        request.setDirection("提高数学成绩");
        request.setStartDate(LocalDate.now());
        request.setEndDate(LocalDate.now().plusMonths(3));
        // subjectId is null → @NotNull violation

        mockMvc.perform(post("/api/student/goal")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ==================== 查看活跃目标列表 ====================

    @Test
    void should_returnActiveGoals_when_exists() throws Exception {
        stubJwtAuth();
        when(goalService.getActiveGoals(isNull())).thenReturn(java.util.List.of(buildGoalVO()));

        mockMvc.perform(get("/api/student/goal/active")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].goalId").value(1))
                .andExpect(jsonPath("$.data[0].status").value("active"));
    }

    @Test
    void should_returnActiveGoalsBySubject_when_subjectIdProvided() throws Exception {
        stubJwtAuth();
        when(goalService.getActiveGoals(eq(10L))).thenReturn(java.util.List.of(buildGoalVO()));

        mockMvc.perform(get("/api/student/goal/active")
                        .header("Authorization", AUTH_HEADER)
                        .param("subjectId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].subjectId").value(10));
    }

    @Test
    void should_returnEmptyList_when_noActiveGoal() throws Exception {
        stubJwtAuth();
        when(goalService.getActiveGoals(isNull())).thenReturn(java.util.Collections.emptyList());

        mockMvc.perform(get("/api/student/goal/active")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ==================== 查看目标列表 ====================

    @Test
    void should_returnGoalList_when_studentHasGoals() throws Exception {
        stubJwtAuth();
        PageResult<GoalVO> pageResult = PageResult.of(1L, 1, 10, java.util.List.of(buildGoalVO()));
        when(goalService.getGoalList(1, 10)).thenReturn(pageResult);

        mockMvc.perform(get("/api/student/goal")
                        .header("Authorization", AUTH_HEADER)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    // ==================== 查看目标详情 ====================

    @Test
    void should_returnGoalDetail_when_ownerMatches() throws Exception {
        stubJwtAuth();
        when(goalService.getGoalDetail(1L)).thenReturn(buildGoalVO());

        mockMvc.perform(get("/api/student/goal/1")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.goalId").value(1));
    }

    @Test
    void should_returnError_when_otherStudentGoal() throws Exception {
        stubJwtAuth();
        when(goalService.getGoalDetail(999L)).thenThrow(
            new BusinessException(ErrorCodes.GOAL_NOT_FOUND));

        mockMvc.perform(get("/api/student/goal/999")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.GOAL_NOT_FOUND.getCode()));
    }

    // ==================== 修改目标 ====================

    @Test
    void should_updateGoal_when_validRequest() throws Exception {
        stubJwtAuth();
        GoalVO updated = buildGoalVO();
        updated.setDirection("新方向");
        updated.setTargetScore(130);
        when(goalService.updateGoal(eq(1L), any(UpdateGoalRequest.class))).thenReturn(updated);

        UpdateGoalRequest request = new UpdateGoalRequest();
        request.setDirection("新方向");
        request.setTargetScore(130);

        mockMvc.perform(put("/api/student/goal/1")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direction").value("新方向"))
                .andExpect(jsonPath("$.data.targetScore").value(130));
    }

    @Test
    void should_returnDateInvalid_when_endDateShortened() throws Exception {
        // W-04：集成测试覆盖结束日期缩短场景
        stubJwtAuth();
        when(goalService.updateGoal(eq(1L), any(UpdateGoalRequest.class)))
            .thenThrow(new BusinessException(ErrorCodes.DATE_INVALID));

        UpdateGoalRequest request = new UpdateGoalRequest();
        request.setEndDate(LocalDate.now().plusDays(10));

        mockMvc.perform(put("/api/student/goal/1")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.DATE_INVALID.getCode()));
    }

    // ==================== 结束目标 ====================

    @Test
    void should_completeGoal_when_goalIsActive() throws Exception {
        stubJwtAuth();
        doNothing().when(goalService).completeGoal(1L);

        mockMvc.perform(post("/api/student/goal/1/complete")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 未登录场景 ====================

    @Test
    void should_return401_when_noJwt() throws Exception {
        mockMvc.perform(get("/api/student/goal/active"))
                .andExpect(status().isUnauthorized());
    }
}
