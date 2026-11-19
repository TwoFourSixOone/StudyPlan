package com.jiang.service;

import com.jiang.agent.AgentDegradationLogger;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.ExamMasteryEvent;
import com.jiang.dto.ExamParseOutcome;
import com.jiang.dto.GradeRequest;
import com.jiang.dto.ParsedQuestion;
import com.jiang.dto.WrongQuestionRef;
import com.jiang.mapper.ExamAnalysisResultMapper;
import com.jiang.mapper.ExamAnalysisTaskMapper;
import com.jiang.mapper.ExamPaperMapper;
import com.jiang.mapper.ExamQuestionMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.ExamAnalysisResult;
import com.jiang.model.entity.ExamAnalysisTask;
import com.jiang.model.entity.ExamPaper;
import com.jiang.model.entity.ExamQuestion;
import com.jiang.model.entity.Student;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.ErrorQuestionSourceType;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.ExamPaperServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 试卷服务测试（Change 2026-11-11 后端 B：三段上传 / 判分 / 结算 / 错题 / 掌握度 / 信号）
 *
 * <p>覆盖验收口径：9 张 1182、8 张建会话；逐张上传校验与上限；submit 0 张 1187；
 * retry 条件；归属校验 1130/1135；Worker CAS/失败兜底/占位题不落库；判分结果批量回写（CASE WHEN）；
 * 错题负 ID 批量入队；掌握度对错都更新；学习记录一条；信号异步触发。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamPaperServiceImpl 上传/判分/结算")
class ExamPaperServiceTest {

    private static final String TENANT = "t1";
    private static final Long STUDENT_ID = 100L;
    private static final Long TASK_ID = 7L;
    private static final Long PAPER_ID = 10L;
    private static final Long SUBJECT_ID = 5L;
    private static final LocalDate EXAM_DATE = LocalDate.of(2026, 11, 11);

    private ExamPaperMapper examPaperMapper;
    private ExamQuestionMapper examQuestionMapper;
    private ExamAnalysisTaskMapper examAnalysisTaskMapper;
    private ExamAnalysisResultMapper examAnalysisResultMapper;
    private SubjectMapper subjectMapper;
    private StudentMapper studentMapper;
    private FileStorageService fileStorageService;
    private ExamParseService examParseService;
    private AnswerGradingService answerGradingService;
    private MasteryService masteryService;
    private ErrorQuestionService errorQuestionService;
    private ExamSignalAdjustService examSignalAdjustService;
    private AgentDegradationLogger degradationLogger;
    private TransactionTemplate transactionTemplate;
    private RedisTemplate<String, Object> redisTemplate;
    private ListOperations<String, Object> listOperations;
    private final ExamUploadValidator examUploadValidator = new ExamUploadValidator();
    private ExamPaperServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        examPaperMapper = mock(ExamPaperMapper.class);
        examQuestionMapper = mock(ExamQuestionMapper.class);
        examAnalysisTaskMapper = mock(ExamAnalysisTaskMapper.class);
        examAnalysisResultMapper = mock(ExamAnalysisResultMapper.class);
        subjectMapper = mock(SubjectMapper.class);
        studentMapper = mock(StudentMapper.class);
        fileStorageService = mock(FileStorageService.class);
        examParseService = mock(ExamParseService.class);
        answerGradingService = mock(AnswerGradingService.class);
        masteryService = mock(MasteryService.class);
        errorQuestionService = mock(ErrorQuestionService.class);
        examSignalAdjustService = mock(ExamSignalAdjustService.class);
        degradationLogger = mock(AgentDegradationLogger.class);
        transactionTemplate = mock(TransactionTemplate.class);
        redisTemplate = mock(RedisTemplate.class);
        listOperations = mock(ListOperations.class);

        service = new ExamPaperServiceImpl();
        ReflectionTestUtils.setField(service, "examPaperMapper", examPaperMapper);
        ReflectionTestUtils.setField(service, "examQuestionMapper", examQuestionMapper);
        ReflectionTestUtils.setField(service, "examAnalysisTaskMapper", examAnalysisTaskMapper);
        ReflectionTestUtils.setField(service, "examAnalysisResultMapper", examAnalysisResultMapper);
        ReflectionTestUtils.setField(service, "subjectMapper", subjectMapper);
        ReflectionTestUtils.setField(service, "knowledgePointMapper", mock(KnowledgePointMapper.class));
        ReflectionTestUtils.setField(service, "studentMapper", studentMapper);
        ReflectionTestUtils.setField(service, "fileStorageService", fileStorageService);
        ReflectionTestUtils.setField(service, "masteryService", masteryService);
        ReflectionTestUtils.setField(service, "errorQuestionService", errorQuestionService);
        ReflectionTestUtils.setField(service, "examParseService", examParseService);
        ReflectionTestUtils.setField(service, "examUploadValidator", examUploadValidator);
        ReflectionTestUtils.setField(service, "answerGradingService", answerGradingService);
        ReflectionTestUtils.setField(service, "examSignalAdjustService", examSignalAdjustService);
        ReflectionTestUtils.setField(service, "degradationLogger", degradationLogger);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "gradingTimeoutSeconds", 60);
        ReflectionTestUtils.setField(service, "gradingConcurrency", 3);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        Subject subject = new Subject();
        subject.setSubjectId(SUBJECT_ID);
        subject.setName("数学");
        when(subjectMapper.selectByIdAndTenant(SUBJECT_ID, TENANT)).thenReturn(subject);

        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setGrade("10");
        when(studentMapper.selectById(STUDENT_ID)).thenReturn(student);

        StudentContext.setStudentId(STUDENT_ID);
        StudentContext.setTenantId(TENANT);
    }

    @AfterEach
    void tearDown() {
        StudentContext.clear();
    }

    private static MockMultipartFile jpeg(String name, int size) {
        // A5：带 JPEG 魔数（FF D8 FF），否则会被文件头校验拒绝
        byte[] bytes = new byte[Math.max(size, 3)];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return new MockMultipartFile("file", name, "image/jpeg", bytes);
    }

    private ExamPaper paper(String status, int imageCount) {
        ExamPaper paper = new ExamPaper();
        paper.setId(PAPER_ID);
        paper.setStudentId(STUDENT_ID);
        paper.setSubjectId(SUBJECT_ID);
        paper.setTenantId(TENANT);
        paper.setExamDate(EXAM_DATE);
        paper.setPaperType("midterm");
        paper.setStatus(status);
        paper.setGradeStatus("ungraded");
        paper.setCreateTime(LocalDateTime.now().minusMinutes(5));
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < imageCount; i++) {
            urls.add("http://localhost:8080/uploads/exam-papers/100/" + i + ".jpg");
        }
        try {
            paper.setImageUrls(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(urls));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return paper;
    }

    private ExamAnalysisTask task(String status) {
        ExamAnalysisTask task = new ExamAnalysisTask();
        task.setId(TASK_ID);
        task.setExamPaperId(PAPER_ID);
        task.setTenantId(TENANT);
        task.setStatus(status);
        task.setOcrRetryCount(0);
        return task;
    }

    private static ParsedQuestion parsedQuestion(int index, String stem, String type,
                                                 String answer, String answerStatus,
                                                 String kpId, String attributionStatus) {
        return ParsedQuestion.builder()
                .questionIndex(index)
                .stem(stem)
                .formatType(type)
                .choices(List.of(Map.of("label", "A", "text", "-3"), Map.of("label", "B", "text", "0")))
                .correctAnswer("A")
                .studentAnswer(answer)
                .answerStatus(answerStatus)
                .difficulty("basic")
                .explanation("负数最小")
                .knowledgePointId(kpId)
                .attributionStatus(attributionStatus)
                .build();
    }

    private ExamQuestion dbQuestion(Long id, int index, String type, String answer,
                                    String answerStatus, String kpId) {
        ExamQuestion question = new ExamQuestion();
        question.setId(id);
        question.setExamPaperId(PAPER_ID);
        question.setQuestionIndex(index);
        question.setQuestionType(type);
        question.setStudentAnswer(answer);
        question.setKnowledgePointId(kpId);
        question.setDifficulty("basic");
        question.setAttributionStatus(kpId == null ? "pending_manual_review" : "completed");
        question.setTenantId(TENANT);
        question.setQuestionContent(com.jiang.common.util.ExamQuestionTextCodec.encode(
                "题干" + index,
                "choice".equals(type) ? List.of(Map.of("label", "A", "text", "-3"),
                        Map.of("label", "B", "text", "0")) : List.of(),
                "A", "解析" + index, answerStatus));
        return question;
    }

    // ==================== 上传会话 ====================

    @Nested
    @DisplayName("上传会话：创建 / 追加 / 提交 / 重试")
    class UploadSessionTests {

        @Test
        @DisplayName("创建会话：8 张正常创建；9 张 → 1182")
        void should_validateImageCount() {
            when(examPaperMapper.selectByStudentSubjectDate(any(), any(), any(), any())).thenReturn(null);
            when(examPaperMapper.insert(any(ExamPaper.class))).thenAnswer(invocation -> {
                ExamPaper p = invocation.getArgument(0);
                p.setId(PAPER_ID);
                return 1;
            });

            var created = service.createPaper(SUBJECT_ID, EXAM_DATE, "midterm", 8);
            assertEquals(PAPER_ID, created.getPaperId());
            assertEquals("uploading", created.getStatus());
            assertFalse(created.getReused());

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.createPaper(SUBJECT_ID, EXAM_DATE, "midterm", 9));
            assertEquals(ErrorCodes.EXAM_IMAGE_COUNT_EXCEEDED.getCode(), exception.getCode());
        }

        @Test
        @DisplayName("创建会话：paperType 非法 → 1191；空白按 midterm")
        void should_validatePaperType() {
            when(examPaperMapper.selectByStudentSubjectDate(any(), any(), any(), any())).thenReturn(null);
            when(examPaperMapper.insert(any(ExamPaper.class))).thenAnswer(invocation -> {
                ExamPaper p = invocation.getArgument(0);
                p.setId(PAPER_ID);
                return 1;
            });

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.createPaper(SUBJECT_ID, EXAM_DATE, "abc", 1));
            assertEquals(ErrorCodes.EXAM_PAPER_TYPE_INVALID.getCode(), exception.getCode());

            service.createPaper(SUBJECT_ID, EXAM_DATE, null, 1);
            ArgumentCaptor<ExamPaper> captor = ArgumentCaptor.forClass(ExamPaper.class);
            verify(examPaperMapper, atLeastOnce()).insert(captor.capture());
            assertEquals("midterm", captor.getAllValues().get(0).getPaperType());
        }

        @Test
        @DisplayName("创建会话：同学生+科目+日期已存在非 uploading 记录 → 1131")
        void should_throwDuplicate_when_existingNonUploading() {
            when(examPaperMapper.selectByStudentSubjectDate(STUDENT_ID, SUBJECT_ID, EXAM_DATE, TENANT))
                    .thenReturn(paper("completed", 1));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.createPaper(SUBJECT_ID, EXAM_DATE, "midterm", 1));
            assertEquals(ErrorCodes.EXAM_PAPER_DUPLICATE.getCode(), exception.getCode());
        }

        @Test
        @DisplayName("创建会话：解析失败且 0 题 → 重置为 uploading 复用（允许当天重新上传）")
        void should_resetFailedPaperForReupload_when_noQuestionPersisted() {
            when(examPaperMapper.selectByStudentSubjectDate(STUDENT_ID, SUBJECT_ID, EXAM_DATE, TENANT))
                    .thenReturn(paper("failed", 4));
            when(examQuestionMapper.countByPaper(PAPER_ID, TENANT)).thenReturn(0L);
            when(examPaperMapper.resetFailedPaperForReupload(PAPER_ID, TENANT)).thenReturn(1);

            var created = service.createPaper(SUBJECT_ID, EXAM_DATE, "midterm", 4);

            assertEquals(PAPER_ID, created.getPaperId());
            assertTrue(created.getReused());
            assertEquals("uploading", created.getStatus());
            verify(examPaperMapper).resetFailedPaperForReupload(PAPER_ID, TENANT);
            verify(examPaperMapper, never()).insert(any(ExamPaper.class));
        }

        @Test
        @DisplayName("创建会话：解析失败但已有题目 → 仍 1131（防止重复落题/重复结算）")
        void should_throwDuplicate_when_failedPaperHasQuestions() {
            when(examPaperMapper.selectByStudentSubjectDate(STUDENT_ID, SUBJECT_ID, EXAM_DATE, TENANT))
                    .thenReturn(paper("failed", 4));
            when(examQuestionMapper.countByPaper(PAPER_ID, TENANT)).thenReturn(2L);

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.createPaper(SUBJECT_ID, EXAM_DATE, "midterm", 4));
            assertEquals(ErrorCodes.EXAM_PAPER_DUPLICATE.getCode(), exception.getCode());
            verify(examPaperMapper, never()).resetFailedPaperForReupload(any(), any());
        }

        @Test
        @DisplayName("创建会话：30 分钟内 uploading 会话复用（不重置图片）")
        void should_reuseRecentUploadingSession() {
            when(examPaperMapper.selectByStudentSubjectDate(STUDENT_ID, SUBJECT_ID, EXAM_DATE, TENANT))
                    .thenReturn(paper("uploading", 2));

            var created = service.createPaper(SUBJECT_ID, EXAM_DATE, "midterm", 2);

            assertEquals(PAPER_ID, created.getPaperId());
            assertTrue(created.getReused());
            verify(examPaperMapper, never()).resetImageUrls(any(), any());
            verify(examPaperMapper, never()).insert(any(ExamPaper.class));
        }

        @Test
        @DisplayName("创建会话：超 30 分钟的 uploading 会话清空后复用")
        void should_resetExpiredUploadingSession() {
            ExamPaper expired = paper("uploading", 2);
            expired.setCreateTime(LocalDateTime.now().minusMinutes(31));
            when(examPaperMapper.selectByStudentSubjectDate(STUDENT_ID, SUBJECT_ID, EXAM_DATE, TENANT))
                    .thenReturn(expired);

            var created = service.createPaper(SUBJECT_ID, EXAM_DATE, "midterm", 2);

            assertTrue(created.getReused());
            verify(examPaperMapper).resetImageUrls(PAPER_ID, TENANT);
        }

        @Test
        @DisplayName("创建会话：并发插入撞唯一键 → 1131")
        void should_mapDuplicateKeyTo1131() {
            when(examPaperMapper.selectByStudentSubjectDate(any(), any(), any(), any())).thenReturn(null);
            when(examPaperMapper.insert(any(ExamPaper.class))).thenThrow(new DuplicateKeyException("uk_student_exam"));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.createPaper(SUBJECT_ID, EXAM_DATE, "midterm", 1));
            assertEquals(ErrorCodes.EXAM_PAPER_DUPLICATE.getCode(), exception.getCode());
        }

        @Test
        @DisplayName("追加图片：他人试卷 → 1130；状态非 uploading → 1186")
        void should_checkOwnershipAndSessionStatus_when_uploadImage() {
            ExamPaper other = paper("uploading", 1);
            other.setStudentId(999L);
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(other);

            assertEquals(ErrorCodes.EXAM_PAPER_NOT_FOUND.getCode(),
                    assertThrows(BusinessException.class,
                            () -> service.uploadImage(PAPER_ID, jpeg("a.jpg", 1024))).getCode());

            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("completed", 1));
            assertEquals(ErrorCodes.EXAM_UPLOAD_SESSION_NOT_FOUND.getCode(),
                    assertThrows(BusinessException.class,
                            () -> service.uploadImage(PAPER_ID, jpeg("a.jpg", 1024))).getCode());
        }

        @Test
        @DisplayName("追加图片：已达 8 张 → 1189；单张超 9.5MB → 1184；累计超 30MB → 1185")
        void should_validateSizeAndCount_when_uploadImage() {
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("uploading", 8));
            assertEquals(ErrorCodes.EXAM_PAPER_IMAGE_LIMIT_REACHED.getCode(),
                    assertThrows(BusinessException.class,
                            () -> service.uploadImage(PAPER_ID, jpeg("a.jpg", 1024))).getCode());

            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("uploading", 1));
            assertEquals(ErrorCodes.EXAM_IMAGE_TOO_LARGE.getCode(),
                    assertThrows(BusinessException.class,
                            () -> service.uploadImage(PAPER_ID, jpeg("a.jpg", (int) (9.6 * 1024 * 1024)))).getCode());

            when(fileStorageService.size(anyString())).thenReturn(28L * 1024 * 1024);
            assertEquals(ErrorCodes.EXAM_IMAGE_TOTAL_TOO_LARGE.getCode(),
                    assertThrows(BusinessException.class,
                            () -> service.uploadImage(PAPER_ID, jpeg("a.jpg", 3 * 1024 * 1024))).getCode());
        }

        @Test
        @DisplayName("追加图片：成功 → 落盘 + 原子追加，返回已上传张数")
        void should_appendImageUrl_when_valid() {
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("uploading", 2));
            when(fileStorageService.size(anyString())).thenReturn(1024L);
            when(fileStorageService.upload(any(MultipartFile.class), anyString()))
                    .thenReturn("http://localhost:8080/uploads/exam-papers/100/new.jpg");
            when(examPaperMapper.appendImageUrl(eq(PAPER_ID), eq(TENANT),
                    eq("http://localhost:8080/uploads/exam-papers/100/new.jpg"), anyInt(), anyInt())).thenReturn(1);

            var vo = service.uploadImage(PAPER_ID, jpeg("new.jpg", 1024));

            assertEquals(3, vo.getUploadedCount());
            assertEquals("uploading", vo.getStatus());
            assertNull(vo.getTaskId());
            verify(examPaperMapper).appendImageUrl(eq(PAPER_ID), eq(TENANT), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("2026-11-17：带 pageNo 上传 → 页序原样落库（并行上传保序）")
        void should_passPageNo_when_provided() {
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("uploading", 2));
            when(fileStorageService.size(anyString())).thenReturn(1024L);
            when(fileStorageService.upload(any(MultipartFile.class), anyString()))
                    .thenReturn("http://localhost:8080/uploads/exam-papers/100/p3.jpg");
            when(examPaperMapper.appendImageUrl(eq(PAPER_ID), eq(TENANT), anyString(), eq(3), anyInt()))
                    .thenReturn(1);

            service.uploadImage(PAPER_ID, jpeg("p3.jpg", 1024), 3);

            verify(examPaperMapper).appendImageUrl(eq(PAPER_ID), eq(TENANT), anyString(), eq(3), anyInt());
        }

        @Test
        @DisplayName("2026-11-17：pageNo 缺失 → 按「当前张数+1」补齐（旧客户端等价到达顺序）")
        void should_fallbackPageNo_when_absent() {
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("uploading", 2));
            when(fileStorageService.size(anyString())).thenReturn(1024L);
            when(fileStorageService.upload(any(MultipartFile.class), anyString()))
                    .thenReturn("http://localhost:8080/uploads/exam-papers/100/legacy.jpg");
            when(examPaperMapper.appendImageUrl(eq(PAPER_ID), eq(TENANT), anyString(), anyInt(), anyInt()))
                    .thenReturn(1);

            service.uploadImage(PAPER_ID, jpeg("legacy.jpg", 1024));

            verify(examPaperMapper).appendImageUrl(eq(PAPER_ID), eq(TENANT), anyString(), eq(3), anyInt());
        }

        @Test
        @DisplayName("2026-11-17：pageNo 越界（0 / 9）→ 归一为「当前张数+1」，不阻断上传")
        void should_fallbackPageNo_when_outOfRange() {
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("uploading", 1));
            when(fileStorageService.size(anyString())).thenReturn(1024L);
            when(fileStorageService.upload(any(MultipartFile.class), anyString()))
                    .thenReturn("http://localhost:8080/uploads/exam-papers/100/bad.jpg");
            when(examPaperMapper.appendImageUrl(eq(PAPER_ID), eq(TENANT), anyString(), anyInt(), anyInt()))
                    .thenReturn(1);

            service.uploadImage(PAPER_ID, jpeg("bad0.jpg", 1024), 0);
            service.uploadImage(PAPER_ID, jpeg("bad9.jpg", 1024), 9);

            verify(examPaperMapper, times(2))
                    .appendImageUrl(eq(PAPER_ID), eq(TENANT), anyString(), eq(2), anyInt());
        }

        @Test
        @DisplayName("追加第 8 张 → 自动建 task 并入队（兜底路径）")
        void should_triggerAnalysis_when_reachMaxImages() {
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("uploading", 7));
            when(fileStorageService.size(anyString())).thenReturn(1024L);
            when(fileStorageService.upload(any(MultipartFile.class), anyString()))
                    .thenReturn("http://localhost:8080/uploads/exam-papers/100/8.jpg");
            when(examPaperMapper.appendImageUrl(eq(PAPER_ID), eq(TENANT), anyString(), anyInt(), anyInt())).thenReturn(1);
            when(examPaperMapper.casToPending(PAPER_ID, TENANT)).thenReturn(1);
            when(examAnalysisTaskMapper.insert(any(ExamAnalysisTask.class))).thenAnswer(invocation -> {
                ExamAnalysisTask t = invocation.getArgument(0);
                t.setId(TASK_ID);
                return 1;
            });

            var vo = service.uploadImage(PAPER_ID, jpeg("8.jpg", 1024));

            assertEquals("pending", vo.getStatus());
            assertEquals(TASK_ID, vo.getTaskId());
            verify(listOperations).leftPush(anyString(), eq(TASK_ID));
        }

        @Test
        @DisplayName("A7：科目校验带 tenant_id（其它租户的同 ID 科目 → 1135，不再裸 selectById）")
        void should_rejectCrossTenantSubject_when_createPaper() {
            when(subjectMapper.selectByIdAndTenant(eq(SUBJECT_ID), eq(TENANT))).thenReturn(null);

            assertEquals(ErrorCodes.SUBJECT_NOT_FOUND.getCode(),
                    assertThrows(BusinessException.class,
                            () -> service.createPaper(SUBJECT_ID, EXAM_DATE, "midterm", 1)).getCode());
            verify(subjectMapper).selectByIdAndTenant(eq(SUBJECT_ID), eq(TENANT));
        }

        @Test
        @DisplayName("A7：建会话科目只走 selectByIdAndTenant（不调用裸 selectById）")
        void should_querySubject_withTenant() {
            when(examPaperMapper.selectByStudentSubjectDate(any(), any(), any(), any())).thenReturn(null);
            when(examPaperMapper.insert(any(ExamPaper.class))).thenAnswer(invocation -> {
                ExamPaper p = invocation.getArgument(0);
                p.setId(PAPER_ID);
                return 1;
            });

            service.createPaper(SUBJECT_ID, EXAM_DATE, "midterm", 1);

            verify(subjectMapper).selectByIdAndTenant(eq(SUBJECT_ID), eq(TENANT));
            verify(subjectMapper, never()).selectById(any());
        }

        @Test
        @DisplayName("A3：并发把张数打满 → 条件追加 0 行 → 1189（不再静默突破 8 张）")
        void should_throw1189_when_appendGuardRejects() {
            // 快速校验/复核读到 7 张，条件追加被并发打满后返回 0，重读看到 8 张
            when(examPaperMapper.selectById(PAPER_ID))
                    .thenReturn(paper("uploading", 7), paper("uploading", 7), paper("uploading", 8));
            when(fileStorageService.size(anyString())).thenReturn(1024L);
            when(fileStorageService.upload(any(MultipartFile.class), anyString()))
                    .thenReturn("http://localhost:8080/uploads/exam-papers/100/8.jpg");
            when(examPaperMapper.appendImageUrl(eq(PAPER_ID), eq(TENANT), anyString(), anyInt(), anyInt()))
                    .thenReturn(0);

            assertEquals(ErrorCodes.EXAM_PAPER_IMAGE_LIMIT_REACHED.getCode(),
                    assertThrows(BusinessException.class,
                            () -> service.uploadImage(PAPER_ID, jpeg("8.jpg", 1024))).getCode());
        }

        @Test
        @DisplayName("A3：条件追加 0 行且会话已非 uploading → 1186")
        void should_throw1186_when_appendGuardRejectsAndSessionChanged() {
            when(examPaperMapper.selectById(PAPER_ID))
                    .thenReturn(paper("uploading", 2), paper("uploading", 2), paper("pending", 2));
            when(fileStorageService.size(anyString())).thenReturn(1024L);
            when(fileStorageService.upload(any(MultipartFile.class), anyString()))
                    .thenReturn("http://localhost:8080/uploads/exam-papers/100/3.jpg");
            when(examPaperMapper.appendImageUrl(eq(PAPER_ID), eq(TENANT), anyString(), anyInt(), anyInt()))
                    .thenReturn(0);

            assertEquals(ErrorCodes.EXAM_UPLOAD_SESSION_NOT_FOUND.getCode(),
                    assertThrows(BusinessException.class,
                            () -> service.uploadImage(PAPER_ID, jpeg("3.jpg", 1024))).getCode());
        }

        @Test
        @DisplayName("提交解析：0 张 → 1187；成功 → CAS + 建 task；重复提交 → 1186")
        void should_submitPaper() {
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("uploading", 0));
            assertEquals(ErrorCodes.EXAM_UPLOAD_INCOMPLETE.getCode(),
                    assertThrows(BusinessException.class, () -> service.submitPaper(PAPER_ID)).getCode());

            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("uploading", 2));
            when(examPaperMapper.casToPending(PAPER_ID, TENANT)).thenReturn(1);
            when(examAnalysisTaskMapper.insert(any(ExamAnalysisTask.class))).thenAnswer(invocation -> {
                ExamAnalysisTask t = invocation.getArgument(0);
                t.setId(TASK_ID);
                return 1;
            });

            var vo = service.submitPaper(PAPER_ID);
            assertEquals(TASK_ID, vo.getTaskId());
            assertEquals("pending", vo.getStatus());
            verify(listOperations).leftPush(anyString(), eq(TASK_ID));

            when(examPaperMapper.casToPending(PAPER_ID, TENANT)).thenReturn(0);
            assertEquals(ErrorCodes.EXAM_UPLOAD_SESSION_NOT_FOUND.getCode(),
                    assertThrows(BusinessException.class, () -> service.submitPaper(PAPER_ID)).getCode());
        }

        @Test
        @DisplayName("重试：非 failed → 1190；failed 但重试次数用尽 → 1190；成功 → 入队")
        void should_retryPaper() {
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("completed", 1));
            assertEquals(ErrorCodes.EXAM_RETRY_NOT_ALLOWED.getCode(),
                    assertThrows(BusinessException.class, () -> service.retryPaper(PAPER_ID)).getCode());

            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("failed", 1));
            when(examAnalysisTaskMapper.selectLatestByPaper(PAPER_ID, TENANT)).thenReturn(task("failed"));
            when(examAnalysisTaskMapper.casFailedToPendingForRetry(TASK_ID, TENANT)).thenReturn(0);
            assertEquals(ErrorCodes.EXAM_RETRY_NOT_ALLOWED.getCode(),
                    assertThrows(BusinessException.class, () -> service.retryPaper(PAPER_ID)).getCode());

            when(examAnalysisTaskMapper.casFailedToPendingForRetry(TASK_ID, TENANT)).thenReturn(1);
            when(examPaperMapper.casFailedToPending(PAPER_ID, TENANT)).thenReturn(1);

            var vo = service.retryPaper(PAPER_ID);
            assertEquals(TASK_ID, vo.getTaskId());
            assertEquals(1, vo.getOcrRetryCount());
            verify(listOperations).leftPush(anyString(), eq(TASK_ID));
        }

        @Test
        @DisplayName("任务进度：他人 task → 1135（不区分存在性）")
        void should_checkTaskOwnership() {
            when(examAnalysisTaskMapper.selectById(TASK_ID)).thenReturn(task("processing"));
            ExamPaper other = paper("processing", 1);
            other.setStudentId(999L);
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(other);

            assertEquals(ErrorCodes.EXAM_TASK_NOT_FOUND.getCode(),
                    assertThrows(BusinessException.class, () -> service.getTaskProgress(TASK_ID)).getCode());

            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("processing", 1));
            var vo = service.getTaskProgress(TASK_ID);
            assertEquals(PAPER_ID, vo.getPaperId());
            assertEquals("processing", vo.getStatus());
        }
    }

    // ==================== Worker：解析 → 判分 → 结算 ====================

    @Nested
    @DisplayName("Worker：解析 → 判分 → TX-B 结算")
    class WorkerTests {

        @Test
        @DisplayName("CAS 失败（任务已被处理）→ 直接返回，不重复解析/判分")
        void should_returnEarly_when_casFailed() {
            when(examAnalysisTaskMapper.selectById(TASK_ID)).thenReturn(task("pending"));
            when(examAnalysisTaskMapper.casToProcessing(eq(TASK_ID), any())).thenReturn(0);

            service.processExamAnalysis(TASK_ID);

            verify(examPaperMapper, never()).selectById(any());
            verify(examParseService, never()).parsePaper(any(), any(), any(), any(), anyList());
            verify(examQuestionMapper, never()).batchUpdateJudgeResult(any(), any(), anyList());
        }

        @Test
        @DisplayName("A6：判分结果 key 与题目 id 不一致 → 记降级日志 + 缺失题按 NULL，不整体失败")
        void should_logAndKeepNull_when_judgeKeysMismatch() {
            when(examAnalysisTaskMapper.selectById(TASK_ID)).thenReturn(task("pending"));
            when(examAnalysisTaskMapper.casToProcessing(eq(TASK_ID), any())).thenReturn(1);
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("pending", 1));
            when(examQuestionMapper.countByPaper(PAPER_ID, TENANT)).thenReturn(2L);
            when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of(
                    dbQuestion(101L, 1, "choice", "A", null, "kp-1"),
                    dbQuestion(102L, 2, "short", "x", null, "kp-1")));
            // 只返回一个 key（模拟 key 集合错配：条数相同也可能错位）
            when(answerGradingService.gradeBatch(anyList(), anyString(), anyString(), any(), anyInt()))
                    .thenReturn(new LinkedHashMap<>(Map.of(0, Boolean.TRUE)));
            when(examPaperMapper.casToGraded(eq(PAPER_ID), eq(TENANT), any())).thenReturn(1);
            when(examAnalysisResultMapper.countCompletedByExamName(eq(STUDENT_ID), eq(TENANT), anyString()))
                    .thenReturn(0);

            service.processExamAnalysis(TASK_ID);

            verify(degradationLogger).log(anyString(), anyString(), anyString(),
                    eq("judge_key_mismatch"), eq(TENANT));
            verify(examQuestionMapper).batchUpdateJudgeResult(eq(PAPER_ID), eq(TENANT), anyList());
        }

        @Test
        @DisplayName("全流程：解析落库（题号升序）→ 判分回写 → 错题负 ID → 掌握度对错都更新 → 学习记录 → 信号")
        void should_parseJudgeAndSettle() {
            when(examAnalysisTaskMapper.selectById(TASK_ID)).thenReturn(task("pending"));
            when(examAnalysisTaskMapper.casToProcessing(eq(TASK_ID), any())).thenReturn(1);
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("pending", 1));
            when(examQuestionMapper.countByPaper(PAPER_ID, TENANT)).thenReturn(0L);

            ExamParseOutcome outcome = ExamParseOutcome.builder()
                    .questions(List.of(
                            parsedQuestion(2, "题目二（未作答）", "short", "", "blank", "kp-1", "completed"),
                            parsedQuestion(1, "题目一（答对）", "choice", "A", "answered", "kp-1", "completed"),
                            parsedQuestion(3, "题目三（无法辨识）", "short", "涂改", "unreadable", "kp-2", "pending_manual_review"),
                            parsedQuestion(4, "题目四（待人工归因）", "short", "x", "answered", null, "pending_manual_review")))
                    .succeededImageCount(1)
                    .imageWarnings(List.of())
                    .build();
            when(examParseService.parsePaper(eq(PAPER_ID), eq(SUBJECT_ID), eq(STUDENT_ID), eq(TENANT), anyList()))
                    .thenReturn(outcome);

            // 判分输入来自库中行（TX-A 之后）：q1 对、q2 错、q3 无法判定、q4 错（但无 kp，不记账）
            when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of(
                    dbQuestion(101L, 1, "choice", "A", null, "kp-1"),
                    dbQuestion(102L, 2, "short", "", "blank", "kp-1"),
                    dbQuestion(103L, 3, "short", "涂改", "unreadable", "kp-2"),
                    dbQuestion(104L, 4, "short", "x", null, null)));
            when(answerGradingService.gradeBatch(anyList(), anyString(), anyString(), any(), anyInt()))
                    .thenAnswer(invocation -> {
                        List<GradeRequest> requests = invocation.getArgument(0);
                        Map<Integer, Boolean> result = new LinkedHashMap<>();
                        for (GradeRequest request : requests) {
                            result.put(request.getKey(), switch (request.getKey()) {
                                case 0 -> Boolean.TRUE;
                                case 2 -> null;
                                default -> Boolean.FALSE;
                            });
                        }
                        return result;
                    });
            when(examPaperMapper.casToGraded(eq(PAPER_ID), eq(TENANT), any())).thenReturn(1);
            when(examAnalysisResultMapper.countCompletedByExamName(eq(STUDENT_ID), eq(TENANT), anyString()))
                    .thenReturn(0);

            service.processExamAnalysis(TASK_ID);

            // 1) TX-A 批量落库（题号升序）+ 禁逐题 insert
            ArgumentCaptor<List<ExamQuestion>> insertCaptor = ArgumentCaptor.forClass(List.class);
            verify(examQuestionMapper).batchInsert(insertCaptor.capture());
            assertEquals(List.of(1, 2, 3, 4), insertCaptor.getValue().stream()
                    .map(ExamQuestion::getQuestionIndex).toList());
            verify(examQuestionMapper, never()).insert(any(ExamQuestion.class));

            // 2) 判分结果批量回写（CASE WHEN，含 NULL 的三列）
            ArgumentCaptor<List<ExamQuestion>> judgeCaptor = ArgumentCaptor.forClass(List.class);
            verify(examQuestionMapper).batchUpdateJudgeResult(eq(PAPER_ID), eq(TENANT), judgeCaptor.capture());
            List<ExamQuestion> judged = judgeCaptor.getValue();
            assertEquals(4, judged.size());
            assertEquals(1, judged.get(0).getIsCorrect());
            assertEquals(BigDecimal.ONE, judged.get(0).getScore());
            assertEquals(BigDecimal.ONE, judged.get(0).getFullScore());
            assertEquals(0, judged.get(1).getIsCorrect());
            assertEquals(BigDecimal.ZERO, judged.get(1).getScore());
            assertNull(judged.get(2).getIsCorrect(), "无法判定的题不参与记账");
            assertNull(judged.get(2).getScore());
            assertEquals(0, judged.get(3).getIsCorrect(), "无 kp 的题仍正常判分（只是不记账）");
            verify(examQuestionMapper, never()).updateById(any(ExamQuestion.class));

            // 3) 错题批量入队：只有答错且归因成功的题，question_id 为负
            ArgumentCaptor<List<WrongQuestionRef>> wrongCaptor = ArgumentCaptor.forClass(List.class);
            verify(errorQuestionService).recordWrongAnswers(eq(STUDENT_ID), eq(TENANT),
                    wrongCaptor.capture(), eq(ErrorQuestionSourceType.EXAM), eq(PAPER_ID), any());
            assertEquals(1, wrongCaptor.getValue().size(), "无 kp / 无法判定的题不入错题库");
            assertEquals(-102L, wrongCaptor.getValue().get(0).questionId());
            assertEquals("kp-1", wrongCaptor.getValue().get(0).knowledgePointId());

            // 4) 掌握度批量更新：答对 + 答错都更新（kp-1 两题），无法判定 / 无 kp 的题跳过
            ArgumentCaptor<List<ExamMasteryEvent>> masteryCaptor = ArgumentCaptor.forClass(List.class);
            verify(masteryService).updateMasteryFromExam(eq(STUDENT_ID), eq(TENANT), eq(PAPER_ID),
                    masteryCaptor.capture());
            assertEquals(2, masteryCaptor.getValue().size());
            assertTrue(masteryCaptor.getValue().get(0).isCorrect());
            assertFalse(masteryCaptor.getValue().get(1).isCorrect());

            // 5) 学习记录一条（1 题 1 分制：judged=3（含无 kp 的 q4）、correct=1）
            ArgumentCaptor<ExamAnalysisResult> recordCaptor = ArgumentCaptor.forClass(ExamAnalysisResult.class);
            verify(examAnalysisResultMapper).insert(recordCaptor.capture());
            assertEquals("completed", recordCaptor.getValue().getStatus());
            assertEquals(0, new BigDecimal("3").compareTo(recordCaptor.getValue().getTotalScore()));
            assertEquals(0, BigDecimal.ONE.compareTo(recordCaptor.getValue().getUserScore()));
            assertNotNull(recordCaptor.getValue().getAnalyzedAt());
            assertTrue(recordCaptor.getValue().getExamName().contains("数学"));
            assertTrue(recordCaptor.getValue().getQuestions().contains("\"paperId\":10"));
            assertTrue(recordCaptor.getValue().getQuestions().contains("\"questionIndex\":4"));

            // 6) 结算 CAS + task 完成 + 异步信号
            verify(examPaperMapper).casToGraded(eq(PAPER_ID), eq(TENANT), any());
            verify(examAnalysisTaskMapper).casToCompleted(eq(TASK_ID), any());
            verify(examSignalAdjustService).triggerAfterSettlement(PAPER_ID, STUDENT_ID, SUBJECT_ID, TENANT);
            verify(degradationLogger, never()).log(any(), any(), any(), eq("task_failed"), any());
        }

        @Test
        @DisplayName("幂等：grade_status CAS 0 行（已结算）→ 不重复入错题/掌握度/学习记录，只收尾 task")
        void should_notRepeatAccounting_when_alreadySettled() {
            when(examAnalysisTaskMapper.selectById(TASK_ID)).thenReturn(task("processing"));
            when(examAnalysisTaskMapper.casToProcessing(eq(TASK_ID), any())).thenReturn(1);
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("processing", 1));
            when(examQuestionMapper.countByPaper(PAPER_ID, TENANT)).thenReturn(2L);
            when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of(
                    dbQuestion(101L, 1, "choice", "A", null, "kp-1")));
            when(answerGradingService.gradeBatch(anyList(), any(), any(), any(), anyInt()))
                    .thenReturn(Map.of(0, true));
            when(examPaperMapper.casToGraded(eq(PAPER_ID), eq(TENANT), any())).thenReturn(0);

            service.processExamAnalysis(TASK_ID);

            verify(examParseService, never()).parsePaper(any(), any(), any(), any(), anyList());
            verify(errorQuestionService, never()).recordWrongAnswers(any(), any(), anyList(), any(), any(), any());
            verify(masteryService, never()).updateMasteryFromExam(any(), any(), any(), anyList());
            verify(examAnalysisResultMapper, never()).insert(any(ExamAnalysisResult.class));
            verify(examAnalysisTaskMapper).casToCompleted(eq(TASK_ID), any());
        }

        @Test
        @DisplayName("B7 重试：库中已有题目 → 跳过视觉解析与归因，直接用库中行判分")
        void should_skipParse_when_questionsAlreadyPersisted() {
            when(examAnalysisTaskMapper.selectById(TASK_ID)).thenReturn(task("pending"));
            when(examAnalysisTaskMapper.casToProcessing(eq(TASK_ID), any())).thenReturn(1);
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("pending", 1));
            when(examQuestionMapper.countByPaper(PAPER_ID, TENANT)).thenReturn(1L);
            when(examQuestionMapper.selectByPaperOrderByIndex(PAPER_ID, TENANT)).thenReturn(List.of(
                    dbQuestion(101L, 1, "choice", "A", null, "kp-1")));
            when(answerGradingService.gradeBatch(anyList(), any(), any(), any(), anyInt()))
                    .thenReturn(Map.of(0, true));
            when(examPaperMapper.casToGraded(eq(PAPER_ID), eq(TENANT), any())).thenReturn(1);

            service.processExamAnalysis(TASK_ID);

            verify(examParseService, never()).parsePaper(any(), any(), any(), any(), anyList());
            verify(examQuestionMapper, never()).batchInsert(anyList());
            verify(examQuestionMapper).batchUpdateJudgeResult(eq(PAPER_ID), eq(TENANT), anyList());
        }

        @Test
        @DisplayName("解析失败 → task/paper=failed + errorMessage≤500 + 降级日志；占位题不落库")
        void should_failTaskAndPaper_when_parseFailed() {
            when(examAnalysisTaskMapper.selectById(TASK_ID)).thenReturn(task("pending"));
            when(examAnalysisTaskMapper.casToProcessing(eq(TASK_ID), any())).thenReturn(1);
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("pending", 1));
            when(examQuestionMapper.countByPaper(PAPER_ID, TENANT)).thenReturn(0L);
            when(examParseService.parsePaper(any(), any(), any(), any(), anyList()))
                    .thenThrow(new BusinessException(ErrorCodes.EXAM_PARSE_FAILED,
                            "试卷解析失败：所有图片均未识别出有效题目"));

            service.processExamAnalysis(TASK_ID);

            ArgumentCaptor<ExamAnalysisTask> taskCaptor = ArgumentCaptor.forClass(ExamAnalysisTask.class);
            verify(examAnalysisTaskMapper).updateById(taskCaptor.capture());
            assertEquals("failed", taskCaptor.getValue().getStatus());
            assertNotNull(taskCaptor.getValue().getCompletedAt());
            assertTrue(taskCaptor.getValue().getErrorMessage().length() <= 500);

            ArgumentCaptor<ExamPaper> paperCaptor = ArgumentCaptor.forClass(ExamPaper.class);
            verify(examPaperMapper, atLeastOnce()).updateById(paperCaptor.capture());
            assertTrue(paperCaptor.getAllValues().stream().anyMatch(p -> "failed".equals(p.getStatus())));

            verify(degradationLogger).log(anyString(), anyString(), anyString(), eq("task_failed"), anyString());
            verify(examQuestionMapper, never()).batchInsert(anyList());
            verify(examQuestionMapper, never()).batchUpdateJudgeResult(any(), any(), anyList());
            verify(examSignalAdjustService, never()).triggerAfterSettlement(any(), any(), any(), any());
        }

        @Test
        @DisplayName("任务不存在 → 直接返回")
        void should_return_when_taskMissing() {
            when(examAnalysisTaskMapper.selectById(999L)).thenReturn(null);

            service.processExamAnalysis(999L);

            verify(examPaperMapper, never()).selectById(any());
        }

        @Test
        @DisplayName("Worker 不使用 StudentContext（design §9.1：身份显式传参，失败也不污染线程）")
        void should_notUseStudentContext_when_workerRuns() {
            when(examAnalysisTaskMapper.selectById(TASK_ID)).thenReturn(task("pending"));
            when(examAnalysisTaskMapper.casToProcessing(eq(TASK_ID), any())).thenReturn(1);
            when(examPaperMapper.selectById(PAPER_ID)).thenReturn(paper("pending", 1));
            when(examQuestionMapper.countByPaper(PAPER_ID, TENANT)).thenReturn(0L);
            when(examParseService.parsePaper(any(), any(), any(), any(), anyList()))
                    .thenThrow(new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, "失败"));

            // 前置：线程上下文为空（Worker 线程的真实状态）
            StudentContext.clear();
            service.processExamAnalysis(TASK_ID);

            assertNull(StudentContext.getStudentId(), "Worker 不得写 StudentContext（显式传参）");
            assertNull(StudentContext.getTenantId());
            verify(examAnalysisTaskMapper).updateById(any(com.jiang.model.entity.ExamAnalysisTask.class));
        }
    }
}
