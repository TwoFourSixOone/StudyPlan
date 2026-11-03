package com.jiang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.dto.AnswerItem;
import com.jiang.dto.CreateAssessmentRequest;
import com.jiang.dto.SubmitAssessmentRequest;
import com.jiang.security.JwtUtil;
import com.jiang.service.AssessmentService;
import com.jiang.vo.AssessmentDetailVO;
import com.jiang.vo.AssessmentHistoryVO;
import com.jiang.vo.AssessmentQuestionVO;
import com.jiang.vo.AssessmentResultVO;
import com.jiang.vo.AssessmentVO;
import com.jiang.vo.KpAccuracyVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AssessmentController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private AssessmentService assessmentService;

    private static final String AUTH_HEADER = "Bearer valid_token";

    private void stubJwtAuth() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn("default");
        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    private AssessmentVO buildAssessmentVO() {
        return AssessmentVO.builder()
                .assessmentId(100L)
                .subjectId(1L)
                .subjectName("数学")
                .knowledgePointIds(Arrays.asList("kp_001", "kp_002"))
                .questionCount(6)
                .status("pending")
                .warning(null)
                .createdAt(LocalDateTime.of(2026, 11, 3, 10, 0))
                .build();
    }

    private AssessmentDetailVO buildDetailVO() {
        AssessmentQuestionVO q1 = AssessmentQuestionVO.builder()
                .questionId(1L).knowledgePointId("kp_001").knowledgePointName("二次函数")
                .content("题目1").choices(Arrays.asList("A. 1", "B. 2", "C. 3"))
                .difficulty("basic").type("choice").build();
        return AssessmentDetailVO.builder()
                .assessmentId(100L).subjectId(1L).subjectName("数学")
                .status("pending").questions(Collections.singletonList(q1))
                .totalQuestions(1).createdAt(LocalDateTime.of(2026, 11, 3, 10, 0))
                .build();
    }

    private AssessmentResultVO buildResultVO() {
        return AssessmentResultVO.builder()
                .assessmentId(100L).score(new BigDecimal("2")).totalScore(new BigDecimal("3"))
                .status("graded")
                .knowledgePointResults(Collections.singletonList(
                        KpAccuracyVO.builder().knowledgePointId("kp_001").knowledgePointName("二次函数")
                                .totalQuestions(3).correctCount(2).accuracy(new BigDecimal("0.6667")).build()))
                .questionsWithAnswers(Collections.emptyList())
                .assessedAt(LocalDateTime.of(2026, 11, 3, 10, 5))
                .build();
    }

    // ==================== 创建评测 ====================

    @Test
    void should_createAssessment_when_validRequest() throws Exception {
        stubJwtAuth();
        when(assessmentService.createAssessment(eq(1L), eq("default"), eq(1L))).thenReturn(buildAssessmentVO());

        CreateAssessmentRequest request = new CreateAssessmentRequest();
        request.setSubjectId(1L);

        mockMvc.perform(post("/api/student/assessment")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.assessmentId").value(100))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.subjectName").value("数学"));
    }

    @Test
    void should_return400_when_subjectIdNull() throws Exception {
        stubJwtAuth();

        CreateAssessmentRequest request = new CreateAssessmentRequest();
        // subjectId is null → @NotNull violation

        mockMvc.perform(post("/api/student/assessment")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ==================== 查看评测详情 ====================

    @Test
    void should_getDetail_when_assessmentExists() throws Exception {
        stubJwtAuth();
        when(assessmentService.getAssessmentDetail(eq(1L), eq("default"), eq(100L))).thenReturn(buildDetailVO());

        mockMvc.perform(get("/api/student/assessment/100")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.assessmentId").value(100))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.questions").isArray());
    }

    @Test
    void should_return404_when_assessmentNotFound() throws Exception {
        stubJwtAuth();
        when(assessmentService.getAssessmentDetail(eq(1L), eq("default"), eq(999L)))
                .thenThrow(new BusinessException(ErrorCodes.ASSESSMENT_NOT_FOUND));

        mockMvc.perform(get("/api/student/assessment/999")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ASSESSMENT_NOT_FOUND.getCode()));
    }

    // ==================== 提交评测 ====================

    @Test
    void should_submitAndGrade_when_validAnswers() throws Exception {
        stubJwtAuth();
        when(assessmentService.submitAndGrade(eq(1L), eq("default"), eq(100L), anyList()))
                .thenReturn(buildResultVO());

        SubmitAssessmentRequest request = new SubmitAssessmentRequest();
        AnswerItem a1 = new AnswerItem();
        a1.setQuestionId(1L);
        a1.setAnswer("A");
        AnswerItem a2 = new AnswerItem();
        a2.setQuestionId(2L);
        a2.setAnswer("B");
        AnswerItem a3 = new AnswerItem();
        a3.setQuestionId(3L);
        a3.setAnswer("3.14");
        request.setAnswers(Arrays.asList(a1, a2, a3));

        mockMvc.perform(post("/api/student/assessment/100/submit")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("graded"))
                .andExpect(jsonPath("$.data.score").value(2));
    }

    @Test
    void should_return400_when_answersEmpty() throws Exception {
        stubJwtAuth();

        SubmitAssessmentRequest request = new SubmitAssessmentRequest();
        request.setAnswers(Collections.emptyList());

        mockMvc.perform(post("/api/student/assessment/100/submit")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ==================== 历史列表 ====================

    @Test
    void should_getHistory_when_hasAssessments() throws Exception {
        stubJwtAuth();
        AssessmentHistoryVO h1 = AssessmentHistoryVO.builder()
                .assessmentId(100L).subjectId(1L).subjectName("数学")
                .score(new BigDecimal("8")).totalScore(new BigDecimal("10"))
                .status("graded").questionCount(10)
                .assessedAt(LocalDateTime.of(2026, 11, 3, 10, 5))
                .createdAt(LocalDateTime.of(2026, 11, 3, 10, 0))
                .build();
        PageResult<AssessmentHistoryVO> pageResult = PageResult.of(1L, 1, 10, List.of(h1));
        when(assessmentService.getAssessmentHistory(eq(1L), eq("default"), eq(1), eq(10)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/student/assessment")
                        .header("Authorization", AUTH_HEADER)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].status").value("graded"));
    }

    @Test
    void should_returnDefaultPage_when_noParams() throws Exception {
        stubJwtAuth();
        PageResult<AssessmentHistoryVO> emptyResult = PageResult.of(0L, 1, 10, Collections.emptyList());
        when(assessmentService.getAssessmentHistory(eq(1L), eq("default"), eq(1), eq(10)))
                .thenReturn(emptyResult);

        mockMvc.perform(get("/api/student/assessment")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }
}
