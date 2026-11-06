package com.jiang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.result.PageResult;
import com.jiang.security.JwtUtil;
import com.jiang.service.MasteryService;
import com.jiang.vo.MasteryChangeLogVO;
import com.jiang.vo.MasteryDashboardVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MasteryController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MasteryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private MasteryService masteryService;

    private static final String AUTH_HEADER = "Bearer valid_token";

    private void stubJwtAuth() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn("default");
        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    private MasteryDashboardVO buildDashboardVO() {
        return MasteryDashboardVO.builder()
                .knowledgePointId("kp_001")
                .knowledgePointName("二次函数")
                .subject("数学")
                .masteryScore(new BigDecimal("0.6500"))
                .confidence(new BigDecimal("0.3000"))
                .lastChangeDirection("up")
                .lastAssessedAt(LocalDateTime.now())
                .build();
    }

    private MasteryChangeLogVO buildChangeLogVO() {
        return MasteryChangeLogVO.builder()
                .logId(1L)
                .changeType("homework")
                .sourceId(100L)
                .delta(new BigDecimal("0.0500"))
                .masteryBefore(new BigDecimal("0.5000"))
                .masteryAfter(new BigDecimal("0.5500"))
                .knowledgePointName("二次函数")
                .subject("数学")
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ==================== 掌握度看板 ====================

    @Test
    void should_returnDashboard_when_studentHasData() throws Exception {
        stubJwtAuth();
        List<MasteryDashboardVO> dashboardList = Arrays.asList(buildDashboardVO());
        when(masteryService.getStudentDashboard(isNull(), eq(false))).thenReturn(dashboardList);

        mockMvc.perform(get("/api/student/mastery/dashboard")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].knowledgePointId").value("kp_001"))
                .andExpect(jsonPath("$.data[0].subject").value("数学"))
                .andExpect(jsonPath("$.data[0].lastChangeDirection").value("up"));
    }

    @Test
    void should_returnDashboardBySubject_when_subjectProvided() throws Exception {
        stubJwtAuth();
        List<MasteryDashboardVO> dashboardList = Arrays.asList(buildDashboardVO());
        when(masteryService.getStudentDashboard(eq("数学"), eq(false))).thenReturn(dashboardList);

        mockMvc.perform(get("/api/student/mastery/dashboard")
                        .header("Authorization", AUTH_HEADER)
                        .param("subject", "数学"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].subject").value("数学"));
    }

    @Test
    void should_returnEmptyList_when_noActiveKp() throws Exception {
        stubJwtAuth();
        when(masteryService.getStudentDashboard(isNull(), eq(false))).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/student/mastery/dashboard")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ==================== 变更历史 ====================

    @Test
    void should_returnChangeHistory_when_kpIdProvided() throws Exception {
        stubJwtAuth();
        PageResult<MasteryChangeLogVO> pageResult = PageResult.of(1L, 1, 10, Arrays.asList(buildChangeLogVO()));
        when(masteryService.getStudentChangeHistory(eq("kp_001"), eq(1), eq(10))).thenReturn(pageResult);

        mockMvc.perform(get("/api/student/mastery/history/kp_001")
                        .header("Authorization", AUTH_HEADER)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].logId").value(1))
                .andExpect(jsonPath("$.data.records[0].changeType").value("homework"))
                .andExpect(jsonPath("$.data.records[0].delta").value(0.05));
    }

    @Test
    void should_returnEmptyHistory_when_noData() throws Exception {
        stubJwtAuth();
        PageResult<MasteryChangeLogVO> pageResult = PageResult.of(0L, 1, 10, Collections.emptyList());
        when(masteryService.getStudentChangeHistory(eq("kp_001"), eq(1), eq(10)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/student/mastery/history/kp_001")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    // ==================== 未登录场景 ====================

    @Test
    void should_return401_when_noJwt() throws Exception {
        mockMvc.perform(get("/api/student/mastery/dashboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return401_when_noJwt_history() throws Exception {
        mockMvc.perform(get("/api/student/mastery/history/kp_001"))
                .andExpect(status().isUnauthorized());
    }
}
