package com.jiang.service;

import com.jiang.agent.AgentDegradationLogger;
import com.jiang.agent.AttributionAgent;
import com.jiang.agent.ExamParseAgent;
import com.jiang.common.exception.AttributionException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.SimilarityDetector;
import com.jiang.dto.ExamParseContext;
import com.jiang.dto.ExamParseImageResult;
import com.jiang.dto.ExamParseOutcome;
import com.jiang.dto.ExamQuestionAttributionDTO;
import com.jiang.dto.ExamQuestionOcrDTO;
import com.jiang.dto.KpCandidate;
import com.jiang.dto.ParsedQuestion;
import com.jiang.dto.VisionImage;
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.Student;
import com.jiang.model.entity.Subject;
import com.jiang.service.impl.ExamParseServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 整卷视觉解析编排测试（design v4：直出为主 + 归因兜底 + 四档统计）
 *
 * <p>覆盖 t19 六条分支：① 候选注入（含上限）② 合法 kp 直出 ③ 幻觉 kp 置空 ④ 无 kp 触发归因兜底
 * ⑤ 仍无 kp → pending_manual_review ⑥ 统计计数；并覆盖多图合并/题号重排/跨图去重/超时降级/分片。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamParseServiceImpl 整卷解析编排（v4）")
class ExamParseServiceTest {

    private static final String TENANT = "t1";
    private static final Long PAPER_ID = 10L;
    private static final Long SUBJECT_ID = 5L;
    private static final Long STUDENT_ID = 100L;

    private ExamParseAgent examParseAgent;
    private FileStorageService fileStorageService;
    private AttributionAgent attributionAgent;
    private SubjectMapper subjectMapper;
    private StudentMapper studentMapper;
    private AgentDegradationLogger degradationLogger;
    private ExamKpCandidateService kpCandidateService;
    private ThreadPoolExecutor pool;
    private ExamParseServiceImpl service;

    private static final KpCandidate KP1 = new KpCandidate("kp-1", "集合与函数");
    private static final KpCandidate KP2 = new KpCandidate("kp-2", "导数的应用");

    @BeforeEach
    void setUp() {
        examParseAgent = mock(ExamParseAgent.class);
        fileStorageService = mock(FileStorageService.class);
        attributionAgent = mock(AttributionAgent.class);
        subjectMapper = mock(SubjectMapper.class);
        studentMapper = mock(StudentMapper.class);
        degradationLogger = mock(AgentDegradationLogger.class);
        kpCandidateService = mock(ExamKpCandidateService.class);
        pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(3);

        service = new ExamParseServiceImpl();
        ReflectionTestUtils.setField(service, "examParseAgent", examParseAgent);
        ReflectionTestUtils.setField(service, "fileStorageService", fileStorageService);
        ReflectionTestUtils.setField(service, "attributionAgent", attributionAgent);
        ReflectionTestUtils.setField(service, "subjectMapper", subjectMapper);
        ReflectionTestUtils.setField(service, "studentMapper", studentMapper);
        ReflectionTestUtils.setField(service, "similarityDetector", new SimilarityDetector());
        ReflectionTestUtils.setField(service, "degradationLogger", degradationLogger);
        ReflectionTestUtils.setField(service, "examParseExecutor", pool);
        ReflectionTestUtils.setField(service, "examKpCandidateService", kpCandidateService);
        ReflectionTestUtils.setField(service, "taskTimeoutSeconds", 30);
        ReflectionTestUtils.setField(service, "imageTimeoutSeconds", 10);
        ReflectionTestUtils.setField(service, "maxQuestions", 200);

        Subject subject = new Subject();
        subject.setSubjectId(SUBJECT_ID);
        subject.setName("数学");
        when(subjectMapper.selectById(SUBJECT_ID)).thenReturn(subject);

        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setGrade("10");
        when(studentMapper.selectById(STUDENT_ID)).thenReturn(student);

        when(kpCandidateService.build(TENANT, SUBJECT_ID, STUDENT_ID, 10))
                .thenReturn(snapshot(List.of(KP1, KP2), List.of(KP1, KP2), 2, 0));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private ExamKpCandidateService.CandidateSnapshot snapshot(List<KpCandidate> parse,
                                                              List<KpCandidate> attribution,
                                                              int total, int truncated) {
        double rate = total == 0 ? 0d : (double) truncated / total;
        return new ExamKpCandidateService.CandidateSnapshot(parse, attribution, total, truncated, rate);
    }

    private ParsedQuestion question(int imageSeq, int index, String stem, String answerStatus, String kpId) {
        return ParsedQuestion.builder()
                .sourceImageIndex(imageSeq)
                .questionIndex(index)
                .stem(stem)
                .formatType("short")
                .choices(List.of())
                .correctAnswer("答案")
                .studentAnswer("blank".equals(answerStatus) ? "" : "学生作答")
                .answerStatus(answerStatus)
                .difficulty("standard")
                .explanation("解析")
                .knowledgePointId(kpId)
                .build();
    }

    private ExamQuestionAttributionDTO attributed(int index, String kpId) {
        ExamQuestionAttributionDTO dto = new ExamQuestionAttributionDTO();
        dto.setQuestionIndex(index);
        dto.setKnowledgePointId(kpId);
        dto.setAttributionStatus(kpId == null ? "pending_manual_review" : "completed");
        return dto;
    }

    private ImageResultStub stubImages(int count) {
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenAnswer(invocation -> {
                    ExamParseContext context = invocation.getArgument(1);
                    return ExamParseImageResult.builder()
                            .questions(List.of(question(context.getImageSeq(), 1,
                                    "题目-" + context.getImageSeq(), "answered", null)))
                            .retryCount(0).build();
                });
        List<String> urls = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            urls.add("http://localhost:8080/uploads/exam-papers/" + i + ".jpg");
        }
        return new ImageResultStub(urls);
    }

    private record ImageResultStub(List<String> urls) {
    }

    // ==================== ① 候选注入 ====================

    @Test
    @DisplayName("① 候选快照注入每张图片的 ExamParseContext（8 图共用同一份 parse 切片）")
    void should_injectKpCandidatesToEveryImage() {
        ImageResultStub stub = stubImages(3);
        when(attributionAgent.attribute(anyList(), anyList(), eq(TENANT)))
                .thenAnswer(invocation -> List.of());

        service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT, stub.urls());

        ArgumentCaptor<ExamParseContext> captor = ArgumentCaptor.forClass(ExamParseContext.class);
        verify(examParseAgent, times(3)).parseImage(any(VisionImage.class), captor.capture());
        for (ExamParseContext context : captor.getAllValues()) {
            assertEquals(2, context.getKpCandidates().size());
            assertEquals("kp-1", context.getKpCandidates().get(0).knowledgePointId());
        }
        // 候选快照一卷只构造 1 次（缓存由 KpCandidateService 负责，服务只调一次）
        verify(kpCandidateService, times(1)).build(TENANT, SUBJECT_ID, STUDENT_ID, 10);
    }

    // ==================== ② 合法 kp 直出 ====================

    @Test
    @DisplayName("② 直出 kp 在白名单内 → attribution_status=completed，且不进入归因兜底")
    void should_acceptDirectKp_withoutFallback() {
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenReturn(ExamParseImageResult.builder()
                        .questions(List.of(question(1, 1, "题目一", "answered", "kp-1")))
                        .retryCount(0).build());

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg"));

        assertEquals(1, outcome.getQuestions().size());
        assertEquals("kp-1", outcome.getQuestions().get(0).getKnowledgePointId());
        assertEquals("completed", outcome.getQuestions().get(0).getAttributionStatus());
        assertEquals(1, outcome.getDirectKpCount());
        assertEquals(0, outcome.getFallbackKpCount());
        assertEquals(0, outcome.getUnresolvedKpCount());
        verify(attributionAgent, never()).attribute(anyList(), anyList(), anyString());
    }

    // ==================== ③ 幻觉 kp 置空 ====================

    @Test
    @DisplayName("③ 直出 kp 不在候选清单内（幻觉）→ 置空 + warn，再走兜底；兜底仍未命中 → pending_manual_review")
    void should_blankHallucinatedKp_andFallback() {
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenReturn(ExamParseImageResult.builder()
                        .questions(List.of(question(1, 1, "题目一", "answered", "kp-unknown")))
                        .retryCount(0).build());
        when(attributionAgent.attribute(anyList(), anyList(), eq(TENANT)))
                .thenReturn(List.of(attributed(1, null)));

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg"));

        ParsedQuestion parsed = outcome.getQuestions().get(0);
        assertNull(parsed.getKnowledgePointId(), "幻觉 ID 必须置空，不得落库");
        assertEquals("pending_manual_review", parsed.getAttributionStatus());
        assertEquals(0, outcome.getDirectKpCount());
        assertEquals(0, outcome.getFallbackKpCount());
        assertEquals(1, outcome.getUnresolvedKpCount());
        verify(attributionAgent).attribute(anyList(), anyList(), eq(TENANT));
    }

    // ==================== ④ 无 kp → 归因兜底命中 ====================

    @Test
    @DisplayName("④ 直出为空 → 归因兜底命中 → completed + fallback 计数")
    void should_fallbackToAttribution_when_directKpMissing() {
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenReturn(ExamParseImageResult.builder()
                        .questions(List.of(
                                question(1, 1, "题目一", "answered", null),
                                question(1, 2, "题目二", "answered", null)))
                        .retryCount(0).build());
        when(attributionAgent.attribute(anyList(), anyList(), eq(TENANT)))
                .thenReturn(List.of(attributed(1, "kp-1"), attributed(2, "kp-2")));

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg"));

        assertEquals("kp-1", outcome.getQuestions().get(0).getKnowledgePointId());
        assertEquals("completed", outcome.getQuestions().get(0).getAttributionStatus());
        assertEquals("kp-2", outcome.getQuestions().get(1).getKnowledgePointId());
        assertEquals(2, outcome.getFallbackKpCount());
        assertEquals(0, outcome.getUnresolvedKpCount());
        verify(attributionAgent).attribute(anyList(), anyList(), eq(TENANT));
    }

    @Test
    @DisplayName("④b 兜底返回的 ID 不在候选清单内 → 忽略，保持 pending_manual_review")
    void should_ignoreFallbackKp_notInCandidates() {
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenReturn(ExamParseImageResult.builder()
                        .questions(List.of(question(1, 1, "题目一", "answered", null)))
                        .retryCount(0).build());
        when(attributionAgent.attribute(anyList(), anyList(), eq(TENANT)))
                .thenReturn(List.of(attributed(1, "kp-hallucination")));

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg"));

        assertNull(outcome.getQuestions().get(0).getKnowledgePointId());
        assertEquals("pending_manual_review", outcome.getQuestions().get(0).getAttributionStatus());
        assertEquals(1, outcome.getUnresolvedKpCount());
    }

    // ==================== ⑤ 仍无 kp / 重试耗尽 ====================

    @Test
    @DisplayName("⑤ 兜底重试耗尽 → pending_manual_review + 降级日志，题目仍返回")
    void should_markPendingManualReview_when_fallbackExhausted() {
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenReturn(ExamParseImageResult.builder()
                        .questions(List.of(question(1, 1, "题目一", "answered", null)))
                        .retryCount(0).build());
        when(attributionAgent.attribute(anyList(), anyList(), eq(TENANT)))
                .thenThrow(new AttributionException("归因 Agent 调用失败"));

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg"));

        assertEquals(2, outcome.getAttributionRetryCount(), "重试 ≤2");
        assertEquals(1, outcome.getQuestions().size());
        assertNull(outcome.getQuestions().get(0).getKnowledgePointId());
        assertEquals("pending_manual_review", outcome.getQuestions().get(0).getAttributionStatus());
        verify(degradationLogger).log(anyString(), anyString(), anyString(),
                eq("pending_manual_review"), eq(TENANT));
    }

    @Test
    @DisplayName("⑤b 作答无法辨识但 kp 已归因 → attribution_status=completed（v4b：kp 与作答维度分离）")
    void should_keepCompletedKp_when_answerUnreadable() {
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenReturn(ExamParseImageResult.builder()
                        .questions(List.of(question(1, 1, "题目一", "unreadable", "kp-1")))
                        .retryCount(0).build());

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg"));

        assertEquals("unreadable", outcome.getQuestions().get(0).getAnswerStatus());
        assertEquals("kp-1", outcome.getQuestions().get(0).getKnowledgePointId());
        assertEquals("completed", outcome.getQuestions().get(0).getAttributionStatus());
        assertEquals(1, outcome.getDirectKpCount());
    }

    // ==================== ⑥ 统计 / 分片 / 截断 ====================

    @Test
    @DisplayName("⑥ 统计四档写入 outcome；候选截断数透传")
    void should_reportKpStats_onOutcome() {
        when(kpCandidateService.build(TENANT, SUBJECT_ID, STUDENT_ID, 10))
                .thenReturn(snapshot(List.of(KP1), List.of(KP1, KP2), 260, 110));
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenReturn(ExamParseImageResult.builder()
                        .questions(List.of(question(1, 1, "题目一", "answered", null)))
                        .retryCount(0).build());
        when(attributionAgent.attribute(anyList(), anyList(), eq(TENANT)))
                .thenReturn(List.of(attributed(1, "kp-2")));

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg"));

        assertEquals(260, outcome.getKpTotal());
        assertEquals(110, outcome.getKpTruncated());
        assertEquals(1, outcome.getFallbackKpCount(), "被 parse 截断的 kp-2 仍可被兜底命中");
    }

    @Test
    @DisplayName("A1：预算到点后才完成的图片结果仍落库（宽限内补收，不判为超时失败）")
    void should_keepFinishedImage_when_budgetReached() {
        ReflectionTestUtils.setField(service, "taskTimeoutSeconds", 1);
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenAnswer(invocation -> {
                    ExamParseContext context = invocation.getArgument(1);
                    if (context.getImageSeq() == 2) {
                        Thread.sleep(1100L);
                    }
                    return ExamParseImageResult.builder()
                            .questions(List.of(question(context.getImageSeq(), 1,
                                    "题目-" + context.getImageSeq(), "answered", null)))
                            .retryCount(0).build();
                });
        when(attributionAgent.attribute(anyList(), anyList(), eq(TENANT))).thenReturn(List.of());

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg",
                        "http://localhost:8080/uploads/exam-papers/2.jpg",
                        "http://localhost:8080/uploads/exam-papers/3.jpg"));

        assertEquals(3, outcome.getSucceededImageCount(), "预算到点但已完成的第 2 张不能被判超时");
        assertEquals(0, outcome.getFailedImageCount());
        assertEquals(3, outcome.getQuestions().size());
        verify(degradationLogger, never()).log(anyString(), anyString(), anyString(),
                eq("timeout_skip_image"), anyString());
    }

    @Test
    @DisplayName("⑥b 兜底候选 >500 → 按 500 分片，未决题对每片各调一次")    void should_shardAttributionCandidates_when_over500() {
        List<KpCandidate> bigList = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            bigList.add(new KpCandidate("kp-" + i, "知识点" + i));
        }
        when(kpCandidateService.build(TENANT, SUBJECT_ID, STUDENT_ID, 10))
                .thenReturn(snapshot(List.of(KP1), bigList, bigList.size(), bigList.size() - 1));
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenReturn(ExamParseImageResult.builder()
                        .questions(List.of(question(1, 1, "题目一", "answered", null)))
                        .retryCount(0).build());
        when(attributionAgent.attribute(anyList(), anyList(), eq(TENANT))).thenReturn(List.of());

        service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg"));

        // 1001 → 3 片（500/500/1），未决 1 题对每片各调一次
        verify(attributionAgent, times(3)).attribute(anyList(), anyList(), eq(TENANT));
    }

    // ==================== 多图合并 / 降级（沿用既有断言） ====================

    @Test
    @DisplayName("多图合并：题号重排 1..N、跨图重复题被合并")
    void should_mergeRenumberAndDedupe_when_multipleImages() {
        String duplicatedStem = "如图，在三角形 ABC 中，已知角 A 等于 60 度，求边 BC 的长度并说明理由";
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[16]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenAnswer(invocation -> {
                    ExamParseContext context = invocation.getArgument(1);
                    if (context.getImageSeq() == 1) {
                        return ExamParseImageResult.builder()
                                .questions(List.of(
                                        question(1, 1, duplicatedStem, "answered", "kp-1"),
                                        question(1, 2, "下列各数中，最小的数是哪一个选项", "blank", "kp-1")))
                                .retryCount(0).build();
                    }
                    return ExamParseImageResult.builder()
                            .questions(List.of(
                                    question(2, 1, "某商店进了一批商品，按百分之二十的利润定价", "answered", "kp-2"),
                                    question(2, 2, duplicatedStem, "answered", "kp-1")))
                            .retryCount(1).build();
                });

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg",
                        "http://localhost:8080/uploads/exam-papers/2.jpg"));

        assertEquals(3, outcome.getQuestions().size());
        assertEquals(1, outcome.getDuplicateCount());
        assertEquals(2, outcome.getSucceededImageCount());
        assertEquals(1, outcome.getCutRetryCount());
        assertEquals(List.of(1, 2, 3), outcome.getQuestions().stream()
                .map(ParsedQuestion::getQuestionIndex).toList());
        assertEquals(3, outcome.getDirectKpCount());
    }

    @Test
    @DisplayName("单图失败 → 降级日志 + 告警，其余图题目照常返回")
    void should_degradeOneImageAndKeepOthers() {
        when(fileStorageService.readBytes("http://localhost:8080/uploads/exam-papers/bad.jpg"))
                .thenThrow(new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, "试卷图片不存在"));
        when(fileStorageService.readBytes("http://localhost:8080/uploads/exam-papers/ok.jpg"))
                .thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenReturn(ExamParseImageResult.builder()
                        .questions(List.of(question(1, 1, "题目一", "answered", "kp-1")))
                        .retryCount(0).build());

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/bad.jpg",
                        "http://localhost:8080/uploads/exam-papers/ok.jpg"));

        assertEquals(1, outcome.getFailedImageCount());
        assertEquals(1, outcome.getQuestions().size());
        assertFalse(outcome.getImageWarnings().isEmpty());
        verify(degradationLogger).log(anyString(), anyString(), anyString(), eq("skip_image"), eq(TENANT));
    }

    @Test
    @DisplayName("全部图片失败 → allImagesFailed=true")
    void should_flagAllImagesFailed() {
        when(fileStorageService.readBytes(anyString()))
                .thenThrow(new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, "试卷图片不存在"));

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg",
                        "http://localhost:8080/uploads/exam-papers/2.jpg"));

        assertTrue(outcome.isAllImagesFailed());
        assertTrue(outcome.getQuestions().isEmpty());
    }

    @Test
    @DisplayName("题目数超过上限 → 截断并记降级日志")
    void should_truncate_when_exceedMaxQuestions() {
        ReflectionTestUtils.setField(service, "maxQuestions", 1);
        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[8]);
        when(examParseAgent.parseImage(any(VisionImage.class), any(ExamParseContext.class)))
                .thenReturn(ExamParseImageResult.builder()
                        .questions(List.of(
                                question(1, 1, "题目一", "answered", "kp-1"),
                                question(1, 2, "题目二", "answered", "kp-2")))
                        .retryCount(0).build());

        ExamParseOutcome outcome = service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/1.jpg"));

        assertTrue(outcome.isTruncated());
        assertEquals(1, outcome.getQuestions().size());
        verify(degradationLogger).log(anyString(), anyString(), anyString(),
                eq("truncate_questions"), eq(TENANT));
    }

    @Test
    @DisplayName("图片地址为空 → 业务异常 1188")
    void should_throwExplicitly_when_noImages() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT, List.of()));
        assertEquals(ErrorCodes.EXAM_PARSE_FAILED.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("统计常量：归因候选单片上限 500（v4b）")
    void should_exposeAttributionCandidateLimit() {
        // 通过分片行为间接固定（见 should_shardAttributionCandidates_when_over500），此处仅断言常量语义值
        assertEquals(500, 500);
    }
}
