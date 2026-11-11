package com.jiang.controller;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.security.JwtUtil;
import com.jiang.service.ExamPaperService;
import com.jiang.vo.ExamPaperDetailVO;
import com.jiang.vo.ExamPaperListItemVO;
import com.jiang.vo.ExamQuestionItemVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ExamPaperController 集成测试（Change 2026-11-11 C 组）
 *
 * <p>覆盖：GET /papers、GET /papers/{paperId}、他人 paperId → 1136；
 * 已删除的手工录入 / 旧结果接口（/result/{id}/grade、/result/{id}、/results）返回 404。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ExamPaperController 历史试卷读接口")
class ExamPaperControllerTest {

    private static final String TOKEN = "Bearer valid_token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private ExamPaperService examPaperService;

    private void stubJwtAuth() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("100");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn("default");
        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    // ==================== 建会话契约（fix t23：JSON body） ====================

    @Test
    @DisplayName("POST /papers：JSON body 建会话成功（前端 createPaper 的 post(body) 契约）")
    void should_createPaper_when_jsonBody() throws Exception {
        stubJwtAuth();
        com.jiang.vo.ExamUploadVO vo = com.jiang.vo.ExamUploadVO.builder()
                .paperId(100L).status("uploading").imageCount(3).build();
        when(examPaperService.createPaper(anyLong(), any(LocalDate.class), anyString(), anyInt()))
                .thenReturn(vo);

        mockMvc.perform(post("/api/student/exam/papers")
                        .header("Authorization", TOKEN)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":5,\"examDate\":\"2026-11-11\","
                                + "\"paperType\":\"midterm\",\"imageCount\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.paperId").value(100));

        verify(examPaperService).createPaper(5L, LocalDate.of(2026, 11, 11), "midterm", 3);
    }

    @Test
    @DisplayName("POST /papers：9 张 → 业务码 1182（上限在 Service，保持既有错误码语义）")
    void should_return1182_when_imageCountOver8() throws Exception {
        stubJwtAuth();
        when(examPaperService.createPaper(anyLong(), any(LocalDate.class), anyString(), anyInt()))
                .thenThrow(new BusinessException(ErrorCodes.EXAM_IMAGE_COUNT_EXCEEDED));

        mockMvc.perform(post("/api/student/exam/papers")
                        .header("Authorization", TOKEN)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":5,\"examDate\":\"2026-11-11\","
                                + "\"paperType\":\"midterm\",\"imageCount\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1182));
    }

    @Test
    @DisplayName("POST /papers：非法 paperType → 业务码 1191")
    void should_return1191_when_paperTypeInvalid() throws Exception {
        stubJwtAuth();
        when(examPaperService.createPaper(anyLong(), any(LocalDate.class), anyString(), anyInt()))
                .thenThrow(new BusinessException(ErrorCodes.EXAM_PAPER_TYPE_INVALID));

        mockMvc.perform(post("/api/student/exam/papers")
                        .header("Authorization", TOKEN)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":5,\"examDate\":\"2026-11-11\","
                                + "\"paperType\":\"unknown\",\"imageCount\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1191));
    }

    @Test
    @DisplayName("POST /papers：缺字段 → HTTP 400（@Valid 校验）")
    void should_return400_when_fieldMissing() throws Exception {
        stubJwtAuth();

        mockMvc.perform(post("/api/student/exam/papers")
                        .header("Authorization", TOKEN)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"paperType\":\"midterm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(examPaperService, never()).createPaper(anyLong(), any(LocalDate.class), anyString(), anyInt());
    }

    @Test
    @DisplayName("GET /papers：返回分页列表（含科目名/类型中文/题数/正确数）")
    void should_returnPaperList() throws Exception {
        stubJwtAuth();
        ExamPaperListItemVO item = ExamPaperListItemVO.builder()
                .paperId(10L).subjectId(5L).subjectName("数学")
                .examDate(LocalDate.of(2026, 11, 11))
                .paperType("midterm").paperTypeName("期中考试")
                .status("completed").gradeStatus("graded")
                .questionCount(5).correctCount(3).judgedCount(4)
                .userScore(BigDecimal.valueOf(3)).totalScore(BigDecimal.valueOf(4))
                .createTime(LocalDateTime.of(2026, 11, 11, 10, 0))
                .build();
        when(examPaperService.getPapers(anyInt(), anyInt()))
                .thenReturn(PageResult.of(1L, 1, 10, List.of(item)));

        mockMvc.perform(get("/api/student/exam/papers")
                        .param("page", "1").param("size", "10")
                        .header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.records[0].paperId").value(10))
                .andExpect(jsonPath("$.data.records[0].subjectName").value("数学"))
                .andExpect(jsonPath("$.data.records[0].paperTypeName").value("期中考试"))
                .andExpect(jsonPath("$.data.records[0].questionCount").value(5))
                .andExpect(jsonPath("$.data.records[0].correctCount").value(3));
    }

    @Test
    @DisplayName("GET /papers/{paperId}：返回试卷头 + 题目（不含图片 base64/内容）")
    void should_returnPaperDetail() throws Exception {
        stubJwtAuth();
        ExamQuestionItemVO question = ExamQuestionItemVO.builder()
                .questionId(101L).questionIndex(1).questionType("choice")
                .stem("题干").choices(List.of("-3", "0")).correctAnswer("A")
                .studentAnswer("A").isCorrect(true).score(BigDecimal.ONE).fullScore(BigDecimal.ONE)
                .knowledgePointName("集合与函数").attributionStatus("completed").answerState("judged")
                .build();
        ExamPaperDetailVO detail = ExamPaperDetailVO.builder()
                .paperId(10L).subjectId(5L).subjectName("数学")
                .examDate(LocalDate.of(2026, 11, 11))
                .paperType("midterm").paperTypeName("期中考试")
                .status("completed").gradeStatus("graded")
                .imageUrls(List.of("http://localhost:8080/uploads/exam-papers/100/1.jpg"))
                .imageCount(1).questionCount(1).correctCount(1).judgedCount(1)
                .userScore(BigDecimal.ONE).totalScore(BigDecimal.ONE)
                .questions(List.of(question))
                .build();
        when(examPaperService.getPaperDetail(10L)).thenReturn(detail);

        mockMvc.perform(get("/api/student/exam/papers/10")
                        .header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.paperId").value(10))
                .andExpect(jsonPath("$.data.subjectName").value("数学"))
                .andExpect(jsonPath("$.data.imageCount").value(1))
                .andExpect(jsonPath("$.data.questions[0].questionIndex").value(1))
                .andExpect(jsonPath("$.data.questions[0].isCorrect").value(true))
                .andExpect(jsonPath("$.data.questions[0].choices[0]").value("-3"));
    }

    @Test
    @DisplayName("GET /papers/{paperId}：他人 paperId → 业务码 1136（不泄漏存在性）")
    void should_return1136_when_otherStudentPaper() throws Exception {
        stubJwtAuth();
        when(examPaperService.getPaperDetail(anyLong()))
                .thenThrow(new BusinessException(ErrorCodes.EXAM_RESULT_NOT_FOUND));

        mockMvc.perform(get("/api/student/exam/papers/999")
                        .header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1136));
    }

    @Test
    @DisplayName("已删除接口：/result/{id}/grade、/result/{id}、/results 均返回 404")
    void should_return404_when_legacyEndpointsRemoved() throws Exception {
        stubJwtAuth();

        mockMvc.perform(post("/api/student/exam/result/1/grade").header("Authorization", TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/student/exam/result/1").header("Authorization", TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/student/exam/results").header("Authorization", TOKEN))
                .andExpect(status().isNotFound());

        verify(examPaperService, never()).getPapers(anyInt(), anyInt());
    }

    @Test
    @DisplayName("GET /tasks/{taskId} 仍可用（上传页进度接口保留）")
    void should_keepTaskProgressEndpoint() throws Exception {
        stubJwtAuth();
        when(examPaperService.getTaskProgress(7L))
                .thenReturn(com.jiang.vo.ExamTaskProgressVO.builder()
                        .taskId(7L).paperId(10L).status("processing").ocrRetryCount(0).build());

        mockMvc.perform(get("/api/student/exam/tasks/7").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(7))
                .andExpect(jsonPath("$.data.paperId").value(10))
                .andExpect(jsonPath("$.data.status").value("processing"));
    }
}
