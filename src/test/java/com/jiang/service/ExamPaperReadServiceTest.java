package com.jiang.service;

import com.jiang.agent.AgentDegradationLogger;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.common.util.ExamQuestionTextCodec;
import com.jiang.mapper.ExamAnalysisResultMapper;
import com.jiang.mapper.ExamAnalysisTaskMapper;
import com.jiang.mapper.ExamPaperMapper;
import com.jiang.mapper.ExamQuestionMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.ExamQuestion;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.ExamPaperServiceImpl;
import com.jiang.vo.ExamPaperDetailVO;
import com.jiang.vo.ExamPaperListItemVO;
import com.jiang.vo.ExamQuestionItemVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史试卷读接口测试（Change 2026-11-11 C 组，design §10.1/§10.2）
 *
 * <p>覆盖验收口径：列表分页 clamp + 固定 2 条 SQL + 题数/正确数/已判定数聚合；
 * 详情固定 3 条 SQL（paper + 题号升序题目 + 知识点批量）、他人 paperId → 1136、
 * 空题目仍返回试卷头、租户隔离、图片只回 URL。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamPaperServiceImpl 历史试卷读接口")
class ExamPaperReadServiceTest {

    private static final String TENANT = "t1";
    private static final Long STUDENT_ID = 100L;
    private static final Long PAPER_ID = 10L;
    private static final Long SUBJECT_ID = 5L;

    private ExamPaperMapper examPaperMapper;
    private ExamQuestionMapper examQuestionMapper;
    private KnowledgePointMapper knowledgePointMapper;
    private ExamPaperServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        examPaperMapper = mock(ExamPaperMapper.class);
        examQuestionMapper = mock(ExamQuestionMapper.class);
        knowledgePointMapper = mock(KnowledgePointMapper.class);

        service = new ExamPaperServiceImpl();
        ReflectionTestUtils.setField(service, "examPaperMapper", examPaperMapper);
        ReflectionTestUtils.setField(service, "examQuestionMapper", examQuestionMapper);
        ReflectionTestUtils.setField(service, "examAnalysisTaskMapper", mock(ExamAnalysisTaskMapper.class));
        ReflectionTestUtils.setField(service, "examAnalysisResultMapper", mock(ExamAnalysisResultMapper.class));
        ReflectionTestUtils.setField(service, "subjectMapper", mock(SubjectMapper.class));
        ReflectionTestUtils.setField(service, "knowledgePointMapper", knowledgePointMapper);
        ReflectionTestUtils.setField(service, "studentMapper", mock(StudentMapper.class));
        ReflectionTestUtils.setField(service, "fileStorageService", mock(FileStorageService.class));
        ReflectionTestUtils.setField(service, "masteryService", mock(MasteryService.class));
        ReflectionTestUtils.setField(service, "errorQuestionService", mock(ErrorQuestionService.class));
        ReflectionTestUtils.setField(service, "examParseService", mock(ExamParseService.class));
        ReflectionTestUtils.setField(service, "examUploadValidator", new ExamUploadValidator());
        ReflectionTestUtils.setField(service, "answerGradingService", mock(AnswerGradingService.class));
        ReflectionTestUtils.setField(service, "examSignalAdjustService", mock(ExamSignalAdjustService.class));
        ReflectionTestUtils.setField(service, "degradationLogger", mock(AgentDegradationLogger.class));
        ReflectionTestUtils.setField(service, "transactionTemplate", mock(TransactionTemplate.class));
        ReflectionTestUtils.setField(service, "redisTemplate", mock(RedisTemplate.class));

        StudentContext.setStudentId(STUDENT_ID);
        StudentContext.setTenantId(TENANT);
    }

    @AfterEach
    void tearDown() {
        StudentContext.clear();
    }

    private Map<String, Object> listRow(long paperId, String subjectName, String paperType,
                                        int questionCount, int correctCount, int judgedCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("paperId", paperId);
        row.put("subjectId", SUBJECT_ID);
        row.put("subjectName", subjectName);
        row.put("examDate", LocalDate.of(2026, 11, 11));
        row.put("paperType", paperType);
        row.put("status", "completed");
        row.put("gradeStatus", "graded");
        row.put("questionCount", questionCount);
        row.put("correctCount", correctCount);
        row.put("judgedCount", judgedCount);
        row.put("createTime", LocalDateTime.of(2026, 11, 11, 10, 0));
        return row;
    }

    private Map<String, Object> paperRow(String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("paperId", PAPER_ID);
        row.put("studentId", STUDENT_ID);
        row.put("subjectId", SUBJECT_ID);
        row.put("subjectName", "数学");
        row.put("examDate", LocalDate.of(2026, 11, 11));
        row.put("paperType", "midterm");
        row.put("imageUrls", "[\"http://localhost:8080/uploads/exam-papers/100/1.jpg\","
                + "\"http://localhost:8080/uploads/exam-papers/100/2.jpg\"]");
        row.put("status", status);
        row.put("gradeStatus", "graded");
        row.put("remark", "第 2 张图片解析失败：超时");
        row.put("createTime", LocalDateTime.of(2026, 11, 11, 10, 0));
        return row;
    }

    private ExamQuestion dbQuestion(Long id, int index, String type, String answer,
                                    Integer isCorrect, String kpId, String answerStatus) {
        ExamQuestion question = new ExamQuestion();
        question.setId(id);
        question.setExamPaperId(PAPER_ID);
        question.setQuestionIndex(index);
        question.setQuestionType(type);
        question.setStudentAnswer(answer);
        question.setIsCorrect(isCorrect);
        question.setKnowledgePointId(kpId);
        question.setAttributionStatus(kpId == null ? "pending_manual_review" : "completed");
        question.setDifficulty("standard");
        question.setScore(isCorrect == null ? null : (isCorrect == 1 ? BigDecimal.ONE : BigDecimal.ZERO));
        question.setFullScore(isCorrect == null ? null : BigDecimal.ONE);
        question.setTenantId(TENANT);
        question.setQuestionContent(ExamQuestionTextCodec.encode(
                "题干" + index,
                "choice".equals(type) ? List.of(Map.of("label", "A", "text", "-3"),
                        Map.of("label", "B", "text", "0")) : List.of(),
                "A", "解析" + index, answerStatus));
        return question;
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("列表：size 超 50 夹到 50、page<1 置 1，固定 2 条 SQL，字段映射完整")
    void should_listPapers_withClampAndTwoQueries() {
        when(examPaperMapper.countByStudent(STUDENT_ID, TENANT)).thenReturn(3L);
        when(examPaperMapper.selectPageWithStats(STUDENT_ID, TENANT, 0, 50))
                .thenReturn(List.of(listRow(PAPER_ID, "数学", "midterm", 5, 3, 4)));

        PageResult<ExamPaperListItemVO> result = service.getPapers(0, 100);

        assertEquals(3L, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(50, result.getSize());
        assertEquals(1, result.getRecords().size());

        ExamPaperListItemVO item = result.getRecords().get(0);
        assertEquals(PAPER_ID, item.getPaperId());
        assertEquals(SUBJECT_ID, item.getSubjectId());
        assertEquals("数学", item.getSubjectName());
        assertEquals(LocalDate.of(2026, 11, 11), item.getExamDate());
        assertEquals("midterm", item.getPaperType());
        assertEquals("期中考试", item.getPaperTypeName(), "前端不直接展示 code（约定 #21/S13）");
        assertEquals("completed", item.getStatus());
        assertEquals("graded", item.getGradeStatus());
        assertEquals(5, item.getQuestionCount());
        assertEquals(3, item.getCorrectCount());
        assertEquals(4, item.getJudgedCount(), "未判定题不计入分母");
        assertEquals(0, BigDecimal.valueOf(3).compareTo(item.getUserScore()));
        assertEquals(0, BigDecimal.valueOf(4).compareTo(item.getTotalScore()));

        verify(examPaperMapper, times(1)).countByStudent(STUDENT_ID, TENANT);
        verify(examPaperMapper, times(1)).selectPageWithStats(STUDENT_ID, TENANT, 0, 50);
        verify(examPaperMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("列表：第 2 页 offset=(page-1)*size；空结果返回空数组而不是 null")
    void should_computeOffset_andReturnEmptyList() {
        when(examPaperMapper.countByStudent(STUDENT_ID, TENANT)).thenReturn(0L);
        when(examPaperMapper.selectPageWithStats(STUDENT_ID, TENANT, 10, 10)).thenReturn(List.of());

        PageResult<ExamPaperListItemVO> result = service.getPapers(2, 10);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        verify(examPaperMapper).selectPageWithStats(STUDENT_ID, TENANT, 10, 10);
    }

    // ==================== 详情 ====================

    @Test
    @DisplayName("详情：固定 3 条 SQL、题目按题号升序、codec 解码题干/选项/答案/解析、只回图片 URL")
    void should_returnDetail_withSortedQuestionsAndKpNames() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT))
                .thenReturn(paperRow("completed"));
        // 故意乱序返回，服务层需按题号升序稳定
        List<ExamQuestion> questions = new ArrayList<>(List.of(
                dbQuestion(102L, 2, "short", "", 0, "kp-1", "blank"),
                dbQuestion(101L, 1, "choice", "A", 1, "kp-1", null),
                dbQuestion(103L, 3, "short", "涂改", null, "kp-2", "unreadable"),
                dbQuestion(104L, 4, "short", "x", 0, null, null)));
        when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(questions);
        Map<String, Object> kpRow = new HashMap<>();
        kpRow.put("knowledge_point_id", "kp-1");
        kpRow.put("name", "集合与函数");
        when(knowledgePointMapper.selectNamesByIds(anyList(), eq(TENANT))).thenReturn(List.of(kpRow));

        ExamPaperDetailVO detail = service.getPaperDetail(PAPER_ID);

        assertEquals(PAPER_ID, detail.getPaperId());
        assertEquals("数学", detail.getSubjectName());
        assertEquals("期中考试", detail.getPaperTypeName());
        assertEquals("completed", detail.getStatus());
        assertEquals("graded", detail.getGradeStatus());
        assertEquals(2, detail.getImageCount(), "只返回图片 URL 列表");
        assertEquals(2, detail.getImageUrls().size());
        assertTrue(detail.getImageUrls().get(0).startsWith("http"), "不得返回 base64/图片内容");
        assertEquals("第 2 张图片解析失败：超时", detail.getImageWarning(), "部分图片未识别提示来自 remark");
        assertEquals(4, detail.getQuestionCount());
        assertEquals(1, detail.getCorrectCount());
        assertEquals(3, detail.getJudgedCount());
        assertEquals(0, BigDecimal.ONE.compareTo(detail.getUserScore()));
        assertEquals(0, BigDecimal.valueOf(3).compareTo(detail.getTotalScore()));

        List<ExamQuestionItemVO> items = detail.getQuestions();
        assertEquals(List.of(1, 2, 3, 4), items.stream().map(ExamQuestionItemVO::getQuestionIndex).toList());

        ExamQuestionItemVO q1 = items.get(0);
        assertEquals("choice", q1.getQuestionType());
        assertEquals("题干1", q1.getStem());
        assertEquals("题干1", q1.getQuestionContent());
        assertEquals(List.of("-3", "0"), q1.getChoices());
        assertEquals("A", q1.getCorrectAnswer());
        assertEquals("解析1", q1.getExplanation());
        assertEquals(Boolean.TRUE, q1.getIsCorrect());
        assertEquals("judged", q1.getAnswerState());
        assertEquals("集合与函数", q1.getKnowledgePointName(), "知识点名称批量映射");

        assertEquals("blank", items.get(1).getAnswerState());
        assertEquals(Boolean.FALSE, items.get(1).getIsCorrect());

        ExamQuestionItemVO q3 = items.get(2);
        assertNull(q3.getIsCorrect(), "无法判定 → null（前端显示待人工判定）");
        assertNull(q3.getScore());
        assertEquals("unreadable", q3.getAnswerState());

        ExamQuestionItemVO q4 = items.get(3);
        assertNull(q4.getKnowledgePointId());
        assertNull(q4.getKnowledgePointName());
        assertEquals("pending_manual_review", q4.getAttributionStatus());

        verify(examPaperMapper, times(1)).selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT);
        verify(examQuestionMapper, times(1)).selectByPaperOrderByIndex(PAPER_ID, TENANT);
        verify(knowledgePointMapper, times(1)).selectNamesByIds(anyList(), eq(TENANT));
    }

    @Test
    @DisplayName("2026-11-17：详情题干带 HTML 表格 → stem 为纯文本、stemHtml 为受控 HTML（rich-text 渲染）")
    void should_exposeStemHtml_forTableInStem() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT))
                .thenReturn(paperRow("completed"));
        ExamQuestion question = dbQuestion(201L, 19, "short", "(1) p=0.9", 1, "kp-1", "answered");
        question.setQuestionContent(ExamQuestionTextCodec.encode(
                "列联表：<table><tr><td>合计</td><td>960</td></tr></table>(1) 求 p；",
                List.of(), "0.9", "解析：代入得 0.9", "answered"));
        when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of(question));
        when(knowledgePointMapper.selectNamesByIds(anyList(), eq(TENANT))).thenReturn(List.of());

        ExamQuestionItemVO item = service.getPaperDetail(PAPER_ID).getQuestions().get(0);

        // 纯文本字段：表格被转成行式文本（判分/兼容用）
        assertFalse(item.getStem().toLowerCase().contains("<table"), item.getStem());
        assertTrue(item.getStem().contains("合计 | 960"), item.getStem());
        // 富文本字段：保留表格结构并注入内联样式
        assertNotNull(item.getStemHtml());
        assertTrue(item.getStemHtml().contains("<table style="), item.getStemHtml());
        assertTrue(item.getStemHtml().contains("960"), item.getStemHtml());
        // 分块（demo：宽表格横向滚动）：表格单独成块并带 min-width
        assertNotNull(item.getStemBlocks());
        assertTrue(item.getStemBlocks().stream().anyMatch(block -> "table".equals(block.getType())
                        && block.getMinWidthPx() > 0),
                "表格块需带 minWidthPx 供前端 scroll-view 横向滚动: " + item.getStemBlocks());
    }

    @Test
    @DisplayName("详情：解析中/失败且无题目 → 仍返回试卷头（questions 为空数组，不查知识点）")
    void should_returnHeaderOnly_when_noQuestions() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT))
                .thenReturn(paperRow("processing"));
        when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of());

        ExamPaperDetailVO detail = service.getPaperDetail(PAPER_ID);

        assertEquals("processing", detail.getStatus());
        assertNotNull(detail.getQuestions());
        assertTrue(detail.getQuestions().isEmpty());
        assertEquals(0, detail.getQuestionCount());
        assertEquals(0, detail.getCorrectCount(), "空题目时计数为 0 而不是 null");
        assertEquals(0, detail.getJudgedCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(detail.getUserScore()));
        assertEquals(0, BigDecimal.ZERO.compareTo(detail.getTotalScore()));
        assertFalse(detail.getImageUrls().isEmpty(), "头部信息（图片 URL）照常返回");
        verify(knowledgePointMapper, never()).selectNamesByIds(anyList(), anyString());
    }

    @Test
    @DisplayName("详情：他人 paperId / 不存在 / 租户不符 → 1136，且不再查题目（不泄漏存在性）")
    void should_throw1136_when_notOwned() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getPaperDetail(PAPER_ID));

        assertEquals(ErrorCodes.EXAM_RESULT_NOT_FOUND.getCode(), exception.getCode());
        verify(examQuestionMapper, never()).selectByPaperOrderByIndex(any(), any());
    }

    @Test
    @DisplayName("租户隔离：所有读查询都显式带 tenant_id（列表/详情/知识点批量）")
    void should_passTenantId_toAllQueries() {
        when(examPaperMapper.countByStudent(STUDENT_ID, TENANT)).thenReturn(1L);
        when(examPaperMapper.selectPageWithStats(STUDENT_ID, TENANT, 0, 10))
                .thenReturn(List.of(listRow(PAPER_ID, "数学", "final", 1, 1, 1)));
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT)).thenReturn(paperRow("completed"));
        when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of(
                dbQuestion(101L, 1, "choice", "A", 1, "kp-1", null)));
        when(knowledgePointMapper.selectNamesByIds(anyList(), eq(TENANT))).thenReturn(List.of());

        service.getPapers(1, 10);
        service.getPaperDetail(PAPER_ID);

        verify(examPaperMapper).countByStudent(STUDENT_ID, TENANT);
        verify(examPaperMapper).selectPageWithStats(STUDENT_ID, TENANT, 0, 10);
        verify(examPaperMapper).selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT);
        verify(examQuestionMapper).selectByPaperOrderByIndex(PAPER_ID, TENANT);
        verify(knowledgePointMapper).selectNamesByIds(anyList(), eq(TENANT));
    }

    @Test
    @DisplayName("详情：单题图片字段为 URL 时不受影响；题目无 choices 时返回空数组")
    void should_returnEmptyChoices_when_nonChoice() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT)).thenReturn(paperRow("completed"));
        when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of(
                dbQuestion(101L, 1, "short", "2", 1, null, null)));

        ExamPaperDetailVO detail = service.getPaperDetail(PAPER_ID);

        ExamQuestionItemVO item = detail.getQuestions().get(0);
        assertTrue(item.getChoices().isEmpty());
        assertEquals("2", item.getStudentAnswer());
        verify(knowledgePointMapper, never()).selectNamesByIds(anyList(), anyString());
    }
}
