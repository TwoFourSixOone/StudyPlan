package com.jiang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.SubmitAnswerRequest;
import com.jiang.security.JwtUtil;
import com.jiang.service.HomeworkService;
import com.jiang.vo.GradeResultVO;
import com.jiang.vo.HomeworkVO;
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
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HomeworkController 集成测试
 * <p>
 * IMP-11: Agent 调用通过 Mock HomeworkService 模拟，不触达真实 LLM
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HomeworkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private HomeworkService homeworkService;

    private static final String AUTH_HEADER = "Bearer valid_token";

    private void stubJwtAuth() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn("default");
        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    private HomeworkVO buildHomeworkVO(Long questionId, String source) {
        return HomeworkVO.builder()
                .questionId(questionId)
                .questionSource(source)
                .questionFormatType("choice")
                .taskType("review")
                .questionContent("题干内容")
                .difficulty("basic")
                .knowledgePointId("kp_001")
                .knowledgePointName("测试知识点")
                .studentAnswer(null)
                .isCorrect(null)
                .build();
    }

    private GradeResultVO buildGradeResultVO() {
        return GradeResultVO.builder()
                .totalQuestions(3)
                .correctCount(2)
                .incorrectCount(1)
                .accuracy(new BigDecimal("0.6667"))
                .affectedKnowledgePoints(List.of("kp_001", "kp_002"))
                .build();
    }

    // ==================== 生成作业 ====================

    @Test
    void should_generateHomework_when_validRequest() throws Exception {
        stubJwtAuth();
        doNothing().when(homeworkService)
                .generateDailyHomework(anyLong(), anyString(), anyLong(), any(LocalDate.class));

        mockMvc.perform(post("/api/homework/generate")
                        .header("Authorization", AUTH_HEADER)
                        .param("subjectId", "1")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void should_return1057_when_noActiveGoal() throws Exception {
        stubJwtAuth();
        doThrow(new BusinessException(ErrorCodes.LEARNING_GOAL_NOT_FOUND))
                .when(homeworkService)
                .generateDailyHomework(anyLong(), anyString(), anyLong(), any(LocalDate.class));

        mockMvc.perform(post("/api/homework/generate")
                        .header("Authorization", AUTH_HEADER)
                        .param("subjectId", "1")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.LEARNING_GOAL_NOT_FOUND.getCode()));
    }

    @Test
    void should_return1052_when_homeworkAlreadyExists() throws Exception {
        stubJwtAuth();
        doThrow(new BusinessException(ErrorCodes.HOMEWORK_ALREADY_EXISTS))
                .when(homeworkService)
                .generateDailyHomework(anyLong(), anyString(), anyLong(), any(LocalDate.class));

        mockMvc.perform(post("/api/homework/generate")
                        .header("Authorization", AUTH_HEADER)
                        .param("subjectId", "1")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.HOMEWORK_ALREADY_EXISTS.getCode()));
    }

    // ==================== 查询作业 ====================

    @Test
    void should_returnHomeworkList_when_planExists() throws Exception {
        stubJwtAuth();
        List<HomeworkVO> homeworkList = List.of(
                buildHomeworkVO(1L, "bank"),
                buildHomeworkVO(2L, "generated")
        );
        when(homeworkService.getHomeworkByDate(anyLong(), anyString(), anyLong(), any(LocalDate.class)))
                .thenReturn(homeworkList);

        mockMvc.perform(get("/api/homework/by-date")
                        .header("Authorization", AUTH_HEADER)
                        .param("subjectId", "1")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].questionId").value(1))
                .andExpect(jsonPath("$.data[0].questionSource").value("bank"))
                .andExpect(jsonPath("$.data[1].questionSource").value("generated"));
    }

    @Test
    void should_returnEmptyList_when_noPlan() throws Exception {
        stubJwtAuth();
        when(homeworkService.getHomeworkByDate(anyLong(), anyString(), anyLong(), any(LocalDate.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/homework/by-date")
                        .header("Authorization", AUTH_HEADER)
                        .param("subjectId", "11")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ==================== 提交答案 ====================

    @Test
    void should_submitAnswer_when_validRequest() throws Exception {
        stubJwtAuth();
        doNothing().when(homeworkService)
                .submitAnswer(anyLong(), anyString(), any(SubmitAnswerRequest.class));

        SubmitAnswerRequest request = new SubmitAnswerRequest();
        request.setPlanId(1L);
        request.setQuestionId(100L);
        request.setQuestionSource("bank");
        request.setTaskType("review");
        request.setStudentAnswer("A");

        mockMvc.perform(post("/api/homework/submit")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void should_return400_when_submitMissingFields() throws Exception {
        stubJwtAuth();

        // planId 和 questionId 缺失 → @Valid 校验失败
        SubmitAnswerRequest request = new SubmitAnswerRequest();
        request.setQuestionSource("bank");
        request.setTaskType("review");

        mockMvc.perform(post("/api/homework/submit")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return1053_when_submitToOtherPlan() throws Exception {
        stubJwtAuth();
        doThrow(new BusinessException(ErrorCodes.STUDENT_IDENTITY_MISMATCH))
                .when(homeworkService)
                .submitAnswer(anyLong(), anyString(), any(SubmitAnswerRequest.class));

        SubmitAnswerRequest request = new SubmitAnswerRequest();
        request.setPlanId(999L);
        request.setQuestionId(100L);
        request.setQuestionSource("bank");
        request.setTaskType("review");
        request.setStudentAnswer("A");

        mockMvc.perform(post("/api/homework/submit")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.STUDENT_IDENTITY_MISMATCH.getCode()));
    }

    // ==================== 批改作业 ====================

    @Test
    void should_gradeHomework_when_validRequest() throws Exception {
        stubJwtAuth();
        when(homeworkService.gradeHomework(anyLong(), anyString(), anyLong(), any(LocalDate.class)))
                .thenReturn(buildGradeResultVO());

        mockMvc.perform(post("/api/homework/grade")
                        .header("Authorization", AUTH_HEADER)
                        .param("subjectId", "1")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalQuestions").value(3))
                .andExpect(jsonPath("$.data.correctCount").value(2))
                .andExpect(jsonPath("$.data.accuracy").value(0.6667))
                .andExpect(jsonPath("$.data.affectedKnowledgePoints").isArray());
    }

    @Test
    void should_return1054_when_homeworkNotSubmitted() throws Exception {
        stubJwtAuth();
        when(homeworkService.gradeHomework(anyLong(), anyString(), anyLong(), any(LocalDate.class)))
                .thenThrow(new BusinessException(ErrorCodes.HOMEWORK_NOT_SUBMITTED));

        mockMvc.perform(post("/api/homework/grade")
                        .header("Authorization", AUTH_HEADER)
                        .param("subjectId", "1")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.HOMEWORK_NOT_SUBMITTED.getCode()));
    }

    @Test
    void should_return1055_when_homeworkAlreadyGraded() throws Exception {
        stubJwtAuth();
        when(homeworkService.gradeHomework(anyLong(), anyString(), anyLong(), any(LocalDate.class)))
                .thenThrow(new BusinessException(ErrorCodes.HOMEWORK_ALREADY_GRADED));

        mockMvc.perform(post("/api/homework/grade")
                        .header("Authorization", AUTH_HEADER)
                        .param("subjectId", "1")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCodes.HOMEWORK_ALREADY_GRADED.getCode()));
    }

    // ==================== 未登录场景 ====================

    @Test
    void should_return401_when_noJwt_generate() throws Exception {
        mockMvc.perform(post("/api/homework/generate")
                        .param("subjectId", "1")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return401_when_noJwt_byDate() throws Exception {
        mockMvc.perform(get("/api/homework/by-date")
                        .param("subjectId", "1")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return401_when_noJwt_submit() throws Exception {
        SubmitAnswerRequest request = new SubmitAnswerRequest();
        request.setPlanId(1L);
        request.setQuestionId(100L);
        request.setQuestionSource("bank");
        request.setTaskType("review");

        mockMvc.perform(post("/api/homework/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return401_when_noJwt_grade() throws Exception {
        mockMvc.perform(post("/api/homework/grade")
                        .param("subjectId", "1")
                        .param("planDate", "2026-11-02"))
                .andExpect(status().isUnauthorized());
    }
}
