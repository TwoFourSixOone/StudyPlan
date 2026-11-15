package com.jiang.service;

import com.jiang.agent.AgentDegradationLogger;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.mapper.ErrorQuestionMapper;
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
import com.jiang.vo.ExamDiagnosisVO;
import com.jiang.vo.ExamDiagnosisWeakKpVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 试卷诊断聚合测试（新用户引导方案 A：GET /papers/{paperId}/diagnosis）
 *
 * <p>覆盖验收口径：正确率计算（judged 分母、未判定不折算）、judged=0 → accuracy null、
 * 薄弱点排序（wrongCount desc → masteryScore asc，null 掌握度按最弱）与截断 10、
 * 错题数取自该卷来源（source_type=exam + source_id）、越权/不存在统一 1136、
 * 0 题返回全 0 计数、固定 4 条 SQL（禁止 N+1）、租户隔离。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-15
 */
@DisplayName("ExamPaperServiceImpl 试卷诊断聚合（新用户引导方案 A）")
class ExamDiagnosisServiceTest {

    private static final String TENANT = "t1";
    private static final Long STUDENT_ID = 100L;
    private static final Long PAPER_ID = 10L;
    private static final Long SUBJECT_ID = 5L;
    private static final String CONFIDENCE_NOTE =
            "基于 1 份试卷的诊断，仅供参考；建议持续完成每日作业后复看掌握度";

    private ExamPaperMapper examPaperMapper;
    private ExamQuestionMapper examQuestionMapper;
    private KnowledgePointMapper knowledgePointMapper;
    private ErrorQuestionMapper errorQuestionMapper;
    private ExamPaperServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        examPaperMapper = mock(ExamPaperMapper.class);
        examQuestionMapper = mock(ExamQuestionMapper.class);
        knowledgePointMapper = mock(KnowledgePointMapper.class);
        errorQuestionMapper = mock(ErrorQuestionMapper.class);

        service = new ExamPaperServiceImpl();
        ReflectionTestUtils.setField(service, "examPaperMapper", examPaperMapper);
        ReflectionTestUtils.setField(service, "examQuestionMapper", examQuestionMapper);
        ReflectionTestUtils.setField(service, "examAnalysisTaskMapper", mock(ExamAnalysisTaskMapper.class));
        ReflectionTestUtils.setField(service, "examAnalysisResultMapper", mock(ExamAnalysisResultMapper.class));
        ReflectionTestUtils.setField(service, "subjectMapper", mock(SubjectMapper.class));
        ReflectionTestUtils.setField(service, "knowledgePointMapper", knowledgePointMapper);
        ReflectionTestUtils.setField(service, "errorQuestionMapper", errorQuestionMapper);
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

    // ==================== 测试数据构造 ====================

    /** selectDetailWithSubject 的 Map 行（诊断只消费 paperId/subjectId/subjectName/examDate/paperType） */
    private Map<String, Object> paperRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("paperId", PAPER_ID);
        row.put("studentId", STUDENT_ID);
        row.put("subjectId", SUBJECT_ID);
        row.put("subjectName", "数学");
        row.put("examDate", LocalDate.of(2026, 11, 14));
        row.put("paperType", "midterm");
        row.put("imageUrls", "[]");
        row.put("status", "completed");
        row.put("gradeStatus", "graded");
        row.put("remark", null);
        row.put("createTime", LocalDateTime.of(2026, 11, 14, 10, 0));
        return row;
    }

    /** 试卷题目（诊断只读 isCorrect / knowledgePointId） */
    private ExamQuestion question(int index, Integer isCorrect, String kpId) {
        ExamQuestion question = new ExamQuestion();
        question.setId(1000L + index);
        question.setExamPaperId(PAPER_ID);
        question.setQuestionIndex(index);
        question.setIsCorrect(isCorrect);
        question.setKnowledgePointId(kpId);
        question.setTenantId(TENANT);
        return question;
    }

    /** selectNamesWithMasteryByIds 的 Map 行 */
    private Map<String, Object> kpRow(String kpId, String name, BigDecimal mastery) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("knowledgePointId", kpId);
        row.put("name", name);
        row.put("masteryScore", mastery);
        return row;
    }

    // ==================== 正确率与计数 ====================

    @Test
    @DisplayName("正确率与计数：judged 为分母、未判定不算答错、无/空白 kpId 的错题不计薄弱点、错题数取自该卷来源")
    void should_computeAccuracyAndCounts_when_judgedQuestionsExist() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT)).thenReturn(paperRow());
        when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of(
                question(1, 1, "kp-1"),
                question(2, 1, "kp-1"),
                question(3, 1, "kp-2"),
                question(4, 0, "kp-1"),
                question(5, null, "kp-2"),
                question(6, 0, null),
                question(7, 0, "  ")));
        when(errorQuestionMapper.countActiveExamErrorsByPaper(STUDENT_ID, TENANT, PAPER_ID)).thenReturn(2);
        when(knowledgePointMapper.selectNamesWithMasteryByIds(anyList(), eq(TENANT), eq(STUDENT_ID)))
                .thenReturn(List.of(kpRow("kp-1", "集合与函数", new BigDecimal("0.42"))));

        ExamDiagnosisVO vo = service.getDiagnosis(PAPER_ID);

        assertEquals(PAPER_ID, vo.getPaperId());
        assertEquals(SUBJECT_ID, vo.getSubjectId());
        assertEquals("数学", vo.getSubjectName());
        assertEquals(LocalDate.of(2026, 11, 14), vo.getExamDate());
        assertEquals("midterm", vo.getPaperType());
        assertEquals(7, vo.getTotalQuestions(), "题数含未判定题");
        assertEquals(6, vo.getJudgedCount(), "未判定题不计入分母（7 题中 1 题不可判定）");
        assertEquals(3, vo.getCorrectCount());
        assertEquals(0, BigDecimal.valueOf(3).compareTo(vo.getUserScore()), "1 题 1 分制：得分 = 答对数");
        assertEquals(0, BigDecimal.valueOf(6).compareTo(vo.getTotalScore()), "总分 = 已判定数");
        assertEquals(0, new BigDecimal("0.5").compareTo(vo.getAccuracy()), "正确率 = 3/6");
        assertEquals(2, vo.getErrorQuestionCount(), "错题数取自该卷来源（status=active）");
        assertEquals(CONFIDENCE_NOTE, vo.getConfidenceNote());

        // 薄弱点：仅 kp-1（答错 1）；kp-2 未判定、null/空白 kpId 的错题均不计入
        assertEquals(1, vo.getWeakKnowledgePoints().size());
        ExamDiagnosisWeakKpVO weak = vo.getWeakKnowledgePoints().get(0);
        assertEquals("kp-1", weak.getKnowledgePointId());
        assertEquals("集合与函数", weak.getKnowledgePointName());
        assertEquals(1, weak.getWrongCount());
        assertEquals(0, new BigDecimal("0.42").compareTo(weak.getMasteryScore()));

        // 固定 4 条 SQL（禁止 N+1）：paper+科目 / 题目按卷 / 错题数 / 知识点批量，各恰好 1 次
        verify(examPaperMapper, times(1)).selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT);
        verify(examQuestionMapper, times(1)).selectByPaperOrderByIndex(PAPER_ID, TENANT);
        verify(errorQuestionMapper, times(1)).countActiveExamErrorsByPaper(STUDENT_ID, TENANT, PAPER_ID);
        verify(knowledgePointMapper, times(1)).selectNamesWithMasteryByIds(anyList(), eq(TENANT), eq(STUDENT_ID));

        // 知识点批量只查「答错的知识点」，而不是全卷知识点
        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(knowledgePointMapper).selectNamesWithMasteryByIds(idsCaptor.capture(), eq(TENANT), eq(STUDENT_ID));
        assertEquals(List.of("kp-1"), idsCaptor.getValue());
    }

    @Test
    @DisplayName("judged=0（全部待人工判定）→ accuracy 为 null，薄弱点为空数组，不查知识点批量")
    void should_returnNullAccuracy_when_noJudgedQuestions() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT)).thenReturn(paperRow());
        when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of(
                question(1, null, "kp-1"),
                question(2, null, null)));
        when(errorQuestionMapper.countActiveExamErrorsByPaper(STUDENT_ID, TENANT, PAPER_ID)).thenReturn(0);

        ExamDiagnosisVO vo = service.getDiagnosis(PAPER_ID);

        assertNull(vo.getAccuracy(), "judged=0 时正确率为 null（不折算成 0）");
        assertEquals(2, vo.getTotalQuestions());
        assertEquals(0, vo.getJudgedCount());
        assertEquals(0, vo.getCorrectCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getUserScore()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getTotalScore()));
        assertNotNull(vo.getWeakKnowledgePoints());
        assertTrue(vo.getWeakKnowledgePoints().isEmpty(), "未判定不算答错 → 无薄弱点");
        assertEquals(0, vo.getErrorQuestionCount());
        verify(knowledgePointMapper, never()).selectNamesWithMasteryByIds(anyList(), anyString(), any());
    }

    @Test
    @DisplayName("试卷未解析完成 / 0 题 → 各计数 0 + accuracy null + 头信息照常返回，不抛异常")
    void should_returnZeroCounts_when_paperHasNoQuestions() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT)).thenReturn(paperRow());
        when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of());
        when(errorQuestionMapper.countActiveExamErrorsByPaper(STUDENT_ID, TENANT, PAPER_ID)).thenReturn(0);

        ExamDiagnosisVO vo = service.getDiagnosis(PAPER_ID);

        assertEquals("数学", vo.getSubjectName(), "试卷头信息照常返回");
        assertEquals("midterm", vo.getPaperType());
        assertEquals(0, vo.getTotalQuestions());
        assertEquals(0, vo.getJudgedCount());
        assertEquals(0, vo.getCorrectCount());
        assertNull(vo.getAccuracy());
        assertNotNull(vo.getWeakKnowledgePoints());
        assertTrue(vo.getWeakKnowledgePoints().isEmpty());
        assertEquals(0, vo.getErrorQuestionCount());
        assertEquals(CONFIDENCE_NOTE, vo.getConfidenceNote());
        verify(knowledgePointMapper, never()).selectNamesWithMasteryByIds(anyList(), anyString(), any());
    }

    // ==================== 薄弱知识点排序与截断 ====================

    @Test
    @DisplayName("薄弱点排序：wrongCount desc → masteryScore asc → kpId asc 稳定兜底，超过 10 截断")
    void should_sortWeakPointsAndTruncateTo10_when_moreThan10WeakKps() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT)).thenReturn(paperRow());

        // 12 个薄弱知识点：kp-03 错 5、kp-02/kp-01 各错 3、kp-12 与 kp-04..kp-11 各错 1
        List<ExamQuestion> questions = new ArrayList<>();
        int index = 1;
        for (int i = 0; i < 5; i++) {
            questions.add(question(index++, 0, "kp-03"));
        }
        for (int i = 0; i < 3; i++) {
            questions.add(question(index++, 0, "kp-02"));
        }
        for (int i = 0; i < 3; i++) {
            questions.add(question(index++, 0, "kp-01"));
        }
        questions.add(question(index++, 0, "kp-12"));
        for (int i = 4; i <= 11; i++) {
            questions.add(question(index++, 0, i < 10 ? "kp-0" + i : "kp-" + i));
        }
        when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(questions);
        when(errorQuestionMapper.countActiveExamErrorsByPaper(STUDENT_ID, TENANT, PAPER_ID)).thenReturn(20);

        List<Map<String, Object>> kpRows = new ArrayList<>();
        kpRows.add(kpRow("kp-01", "函数", new BigDecimal("0.9")));
        kpRows.add(kpRow("kp-02", "集合", new BigDecimal("0.2")));
        kpRows.add(kpRow("kp-03", "复数", new BigDecimal("0.8")));
        for (int i = 4; i <= 11; i++) {
            String kpId = i < 10 ? "kp-0" + i : "kp-" + i;
            kpRows.add(kpRow(kpId, "知识点" + kpId, new BigDecimal("0.5")));
        }
        kpRows.add(kpRow("kp-12", "数列", new BigDecimal("0.1")));
        when(knowledgePointMapper.selectNamesWithMasteryByIds(anyList(), eq(TENANT), eq(STUDENT_ID)))
                .thenReturn(kpRows);

        ExamDiagnosisVO vo = service.getDiagnosis(PAPER_ID);

        List<ExamDiagnosisWeakKpVO> weak = vo.getWeakKnowledgePoints();
        assertEquals(10, weak.size(), "12 个薄弱知识点只取前 10");
        assertEquals(List.of("kp-03", "kp-02", "kp-01", "kp-12",
                        "kp-04", "kp-05", "kp-06", "kp-07", "kp-08", "kp-09"),
                weak.stream().map(ExamDiagnosisWeakKpVO::getKnowledgePointId).toList(),
                "kp-10/kp-11（同为 1 错 0.5 掌握度）被截断；同错数时低掌握度在前");
        assertEquals(20, vo.getErrorQuestionCount());

        ExamDiagnosisWeakKpVO top = weak.get(0);
        assertEquals(5, top.getWrongCount(), "同知识点多题答错累计 wrongCount");
        assertEquals("复数", top.getKnowledgePointName());
        assertEquals(0, new BigDecimal("0.8").compareTo(top.getMasteryScore()));
    }

    @Test
    @DisplayName("掌握度缺失按最弱排序（null 视为 0）；知识点行缺失时名称/掌握度为 null 但条目保留")
    void should_treatNullMasteryAsWeakest_when_sortingWeakPoints() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT)).thenReturn(paperRow());

        // kp-a/kp-b/kp-c 各错 2，kp-d 错 1（知识点行缺失）；另有两题答对 → accuracy = 2/9
        List<ExamQuestion> questions = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            questions.add(question(i + 1, 0, "kp-a"));
            questions.add(question(i + 10, 0, "kp-b"));
            questions.add(question(i + 20, 0, "kp-c"));
        }
        questions.add(question(30, 0, "kp-d"));
        questions.add(question(31, 1, "kp-a"));
        questions.add(question(32, 1, "kp-c"));
        when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(questions);
        when(errorQuestionMapper.countActiveExamErrorsByPaper(STUDENT_ID, TENANT, PAPER_ID)).thenReturn(7);
        // kp-b 有知识点行但无掌握度（LEFT JOIN 未命中）；kp-d 连知识点行都没有
        when(knowledgePointMapper.selectNamesWithMasteryByIds(anyList(), eq(TENANT), eq(STUDENT_ID)))
                .thenReturn(List.of(
                        kpRow("kp-a", "函数", new BigDecimal("0.3")),
                        kpRow("kp-b", "集合", null),
                        kpRow("kp-c", "数列", new BigDecimal("0.9"))));

        ExamDiagnosisVO vo = service.getDiagnosis(PAPER_ID);

        List<ExamDiagnosisWeakKpVO> weak = vo.getWeakKnowledgePoints();
        assertEquals(4, weak.size());
        assertEquals(List.of("kp-b", "kp-a", "kp-c", "kp-d"),
                weak.stream().map(ExamDiagnosisWeakKpVO::getKnowledgePointId).toList(),
                "同 wrongCount 时 masteryScore 升序、null 视为 0 排最前；更少错题的 kp-d 最后");
        assertNull(weak.get(0).getMasteryScore(), "未评测知识点掌握度为 null（不虚构 0）");
        assertEquals("集合", weak.get(0).getKnowledgePointName());
        assertNull(weak.get(3).getKnowledgePointName(), "知识点行缺失时名称为 null，条目仍保留");
        assertNull(weak.get(3).getMasteryScore());
        assertEquals(0, new BigDecimal("0.2222").compareTo(vo.getAccuracy()),
                "正确率 2/9 保留 4 位 HALF_UP");
    }

    // ==================== 越权 / 不存在 ====================

    @Test
    @DisplayName("越权 / 不存在 / 租户不符 → 统一 1136，且不再执行后续查询（不泄漏存在性）")
    void should_throw1136_when_paperNotOwnedOrMissing() {
        when(examPaperMapper.selectDetailWithSubject(PAPER_ID, STUDENT_ID, TENANT)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getDiagnosis(PAPER_ID));

        assertEquals(ErrorCodes.EXAM_RESULT_NOT_FOUND.getCode(), exception.getCode());
        verify(examQuestionMapper, never()).selectByPaperOrderByIndex(any(), any());
        verify(errorQuestionMapper, never()).countActiveExamErrorsByPaper(any(), any(), any());
        verify(knowledgePointMapper, never()).selectNamesWithMasteryByIds(anyList(), anyString(), any());
    }
}
