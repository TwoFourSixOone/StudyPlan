package com.jiang.controller;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.security.JwtUtil;
import com.jiang.service.ErrorQuestionService;
import com.jiang.vo.ErrorQuestionStatisticsVO;
import com.jiang.vo.ErrorQuestionVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ErrorQuestionController 集成测试
 *
 * <p>IMP-F: 覆盖 page/size 边界测试</p>
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorQuestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private ErrorQuestionService errorQuestionService;

    private static final String AUTH_HEADER = "Bearer valid_token";

    private void stubJwtAuth() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn("default");
        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    // ==================== 错题列表分页查询 ====================

    @Test
    @DisplayName("错题列表：正常分页查询")
    void should_returnPagedList_when_validRequest() throws Exception {
        stubJwtAuth();
        ErrorQuestionVO vo = ErrorQuestionVO.builder()
                .errorQuestionId(1L)
                .questionId(100L)
                .knowledgePointId("kp_001")
                .knowledgePointName("测试知识点")
                .sourceType("homework")
                .firstWrongDate(LocalDate.of(2026, 11, 1))
                .lastWrongDate(LocalDate.of(2026, 11, 1))
                .currentStage(1)
                .nextDueDate(LocalDate.of(2026, 11, 2))
                .status("active")
                .questionContent("题干内容")
                .questionDifficulty("basic")
                .build();
        PageResult<ErrorQuestionVO> pageResult = PageResult.of(1L, 1, 10, List.of(vo));
        when(errorQuestionService.getErrorQuestions(anyLong(), anyString(), any(), any(), eq(1), eq(10)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/error-questions")
                        .header("Authorization", AUTH_HEADER)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].errorQuestionId").value(1))
                .andExpect(jsonPath("$.data.records[0].questionContent").value("题干内容"));
    }

    @Test
    @DisplayName("错题列表：按知识点筛选")
    void should_filterByKnowledgePoint_when_kpIdProvided() throws Exception {
        stubJwtAuth();
        PageResult<ErrorQuestionVO> pageResult = PageResult.of(0L, 1, 10, Collections.emptyList());
        when(errorQuestionService.getErrorQuestions(anyLong(), anyString(), eq("kp_001"), any(), eq(1), eq(10)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/error-questions")
                        .header("Authorization", AUTH_HEADER)
                        .param("knowledgePointId", "kp_001")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test
    @DisplayName("错题列表：IMP-F — page=0 置 1")
    void should_normalizePage_when_pageIsZero() throws Exception {
        stubJwtAuth();
        PageResult<ErrorQuestionVO> pageResult = PageResult.of(0L, 1, 10, Collections.emptyList());
        // page < 1 → 置 1，Service 收到 page=1
        when(errorQuestionService.getErrorQuestions(anyLong(), anyString(), any(), any(), eq(1), eq(10)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/error-questions")
                        .header("Authorization", AUTH_HEADER)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("错题列表：IMP-F — page=-1 置 1")
    void should_normalizePage_when_pageIsNegative() throws Exception {
        stubJwtAuth();
        PageResult<ErrorQuestionVO> pageResult = PageResult.of(0L, 1, 10, Collections.emptyList());
        when(errorQuestionService.getErrorQuestions(anyLong(), anyString(), any(), any(), eq(1), eq(10)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/error-questions")
                        .header("Authorization", AUTH_HEADER)
                        .param("page", "-1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("错题列表：IMP-F — size=101 截断至 100")
    void should_truncateSize_when_sizeExceeds100() throws Exception {
        stubJwtAuth();
        PageResult<ErrorQuestionVO> pageResult = PageResult.of(0L, 1, 100, Collections.emptyList());
        when(errorQuestionService.getErrorQuestions(anyLong(), anyString(), any(), any(), eq(1), eq(100)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/error-questions")
                        .header("Authorization", AUTH_HEADER)
                        .param("page", "1")
                        .param("size", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("错题列表：IMP-F — size=0 置 1")
    void should_normalizeSize_when_sizeIsZero() throws Exception {
        stubJwtAuth();
        PageResult<ErrorQuestionVO> pageResult = PageResult.of(0L, 1, 1, Collections.emptyList());
        when(errorQuestionService.getErrorQuestions(anyLong(), anyString(), any(), any(), eq(1), eq(1)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/error-questions")
                        .header("Authorization", AUTH_HEADER)
                        .param("page", "1")
                        .param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("错题列表：未登录 → 401")
    void should_return401_when_noAuth() throws Exception {
        mockMvc.perform(get("/api/error-questions")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 错题统计 ====================

    @Test
    @DisplayName("错题统计：正常查询")
    void should_returnStatistics_when_validRequest() throws Exception {
        stubJwtAuth();
        ErrorQuestionStatisticsVO stats = ErrorQuestionStatisticsVO.builder()
                .activeAndPausedCount(10)
                .dueCount(3)
                .pausedCount(2)
                .build();
        when(errorQuestionService.getStatistics(anyLong(), anyString())).thenReturn(stats);

        mockMvc.perform(get("/api/error-questions/statistics")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.activeAndPausedCount").value(10))
                .andExpect(jsonPath("$.data.dueCount").value(3))
                .andExpect(jsonPath("$.data.pausedCount").value(2));
    }

    @Test
    @DisplayName("错题统计：BLOCK-C — 零错题学生")
    void should_returnZeros_when_noErrorQuestions() throws Exception {
        stubJwtAuth();
        ErrorQuestionStatisticsVO stats = ErrorQuestionStatisticsVO.builder()
                .activeAndPausedCount(0)
                .dueCount(0)
                .pausedCount(0)
                .build();
        when(errorQuestionService.getStatistics(anyLong(), anyString())).thenReturn(stats);

        mockMvc.perform(get("/api/error-questions/statistics")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeAndPausedCount").value(0))
                .andExpect(jsonPath("$.data.dueCount").value(0))
                .andExpect(jsonPath("$.data.pausedCount").value(0));
    }

    @Test
    @DisplayName("错题统计：未登录 → 401")
    void should_return401_when_noAuthForStats() throws Exception {
        mockMvc.perform(get("/api/error-questions/statistics"))
                .andExpect(status().isUnauthorized());
    }
}
