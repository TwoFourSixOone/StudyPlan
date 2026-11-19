package com.jiang.service.impl;

import com.jiang.agent.AgentDegradationLogger;
import com.jiang.agent.ExamParseAgent;
import com.jiang.client.ZhipuOcrClient;
import com.jiang.common.util.SimilarityDetector;
import com.jiang.dto.ExamAnswerParseResult;
import com.jiang.dto.ExamParseImageResult;
import com.jiang.dto.ExamParseOutcome;
import com.jiang.dto.OcrPageResult;
import com.jiang.dto.ParsedAnswer;
import com.jiang.dto.ParsedQuestion;
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.service.ExamKpCandidateService;
import com.jiang.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 双版本解析（Change 2026-11-17_exam_dual_channel_parse）
 *
 * <p>覆盖：按 paperNo 合并、缺题 blank + 告警、多出题号忽略、unreadable/blank 归一、题目清单截断；
 * 以及 2026-11-17 补齐的**降级链路**：空 answers / 覆盖率不足 / paperNo 重复 / 开关关闭 →
 * 回退单次整卷，不产生「整卷 blank → 判错」的脏数据。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@DisplayName("ExamParseService 双版本合并与降级")
class ExamParseServiceDualChannelTest {

    private static final Long PAPER_ID = 4L;
    private static final Long SUBJECT_ID = 5L;
    private static final Long STUDENT_ID = 100L;
    private static final String TENANT = "default";

    private final ExamParseServiceImpl service = new ExamParseServiceImpl();

    private ExamParseAgent examParseAgent;
    private FileStorageService fileStorageService;
    private ZhipuOcrClient zhipuOcrClient;
    private AgentDegradationLogger degradationLogger;
    private SimilarityDetector similarityDetector;
    private ExamKpCandidateService examKpCandidateService;
    private SubjectMapper subjectMapper;
    private StudentMapper studentMapper;

    @BeforeEach
    void setUp() {
        examParseAgent = mock(ExamParseAgent.class);
        fileStorageService = mock(FileStorageService.class);
        zhipuOcrClient = mock(ZhipuOcrClient.class);
        degradationLogger = mock(AgentDegradationLogger.class);
        similarityDetector = mock(SimilarityDetector.class);
        examKpCandidateService = mock(ExamKpCandidateService.class);
        subjectMapper = mock(SubjectMapper.class);
        studentMapper = mock(StudentMapper.class);

        ReflectionTestUtils.setField(service, "examParseAgent", examParseAgent);
        ReflectionTestUtils.setField(service, "fileStorageService", fileStorageService);
        ReflectionTestUtils.setField(service, "zhipuOcrClient", zhipuOcrClient);
        ReflectionTestUtils.setField(service, "degradationLogger", degradationLogger);
        ReflectionTestUtils.setField(service, "similarityDetector", similarityDetector);
        ReflectionTestUtils.setField(service, "examKpCandidateService", examKpCandidateService);
        ReflectionTestUtils.setField(service, "subjectMapper", subjectMapper);
        ReflectionTestUtils.setField(service, "studentMapper", studentMapper);

        ReflectionTestUtils.setField(service, "dualChannelEnabled", true);
        ReflectionTestUtils.setField(service, "singleCallEnabled", true);
        ReflectionTestUtils.setField(service, "saveDerivedImages", false);
        ReflectionTestUtils.setField(service, "maxDigestChars", 6000);
        ReflectionTestUtils.setField(service, "maxQuestions", 200);
        ReflectionTestUtils.setField(service, "ocrGroundingEnabled", false);
        ReflectionTestUtils.setField(service, "removeCoolInk", true);
        ReflectionTestUtils.setField(service, "taskTimeoutSeconds", 300);
        ReflectionTestUtils.setField(service, "imageTimeoutSeconds", 60);

        when(fileStorageService.readBytes(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(similarityDetector.detectSimilarity(anyString(), anyList())).thenReturn(0.0);
        when(examKpCandidateService.build(any(), any(), any(), any())).thenReturn(null);
        when(zhipuOcrClient.isEnabled()).thenReturn(false);
    }

    // ==================== 纯函数/合并逻辑 ====================

    private static ParsedQuestion question(int paperNo) {
        return ParsedQuestion.builder().paperNo(paperNo).stem("题干" + paperNo).build();
    }

    @Test
    @DisplayName("按 paperNo 合并；缺题 blank + 告警；多出题号忽略")
    void should_mergeByPaperNo() {
        List<ParsedQuestion> questions = new ArrayList<>(List.of(question(1), question(2), question(3)));
        ExamParseImageResult clean = ExamParseImageResult.builder().questions(questions).build();
        ExamAnswerParseResult answers = ExamAnswerParseResult.builder()
                .answers(List.of(
                        ParsedAnswer.builder().paperNo(1).studentAnswer("C").answerStatus("answered").build(),
                        ParsedAnswer.builder().paperNo(3).studentAnswer("").answerStatus("blank").build(),
                        ParsedAnswer.builder().paperNo(9).studentAnswer("B").answerStatus("answered").build()))
                .build();

        service.mergeDualAnswers(1L, clean, answers);

        assertEquals("C", questions.get(0).getStudentAnswer());
        assertEquals("answered", questions.get(0).getAnswerStatus());
        assertEquals("", questions.get(1).getStudentAnswer(), "作答版缺第 2 题 → blank");
        assertEquals("blank", questions.get(1).getAnswerStatus());
        assertEquals("blank", questions.get(2).getAnswerStatus());
        assertTrue(clean.getWarning().contains("第 2 题未在作答版中找到"), clean.getWarning());
        assertTrue(clean.getWarning().contains("多出第 9 题"), clean.getWarning());
    }

    @Test
    @DisplayName("unreadable 映射；answered 但空串归一为 blank")
    void should_normalizeStatus() {
        List<ParsedQuestion> questions = new ArrayList<>(List.of(question(1), question(2)));
        ExamParseImageResult clean = ExamParseImageResult.builder().questions(questions).build();
        ExamAnswerParseResult answers = ExamAnswerParseResult.builder()
                .answers(List.of(
                        ParsedAnswer.builder().paperNo(1).studentAnswer("").answerStatus("unreadable").build(),
                        ParsedAnswer.builder().paperNo(2).studentAnswer("  ").answerStatus("answered").build()))
                .build();

        service.mergeDualAnswers(1L, clean, answers);

        assertEquals("unreadable", questions.get(0).getAnswerStatus());
        assertEquals("", questions.get(0).getStudentAnswer());
        assertEquals("blank", questions.get(1).getAnswerStatus());
    }

    @Test
    @DisplayName("题目清单超长 → 截断并提示")
    void should_truncateDigest() {
        List<ParsedQuestion> questions = List.of(question(1), question(2));
        String digest = service.buildQuestionDigest(questions, 10);
        assertTrue(digest.contains("题目清单过长"), digest);
    }

    @Test
    @DisplayName("派生图扩展名跟随实际媒体类型（无彩色像素时可能是 JPEG）")
    void should_pickExtensionByMediaType() {
        assertEquals(".png", ExamParseServiceImpl.extensionOf("image/png"));
        assertEquals(".jpg", ExamParseServiceImpl.extensionOf("image/jpeg"));
        assertEquals(".bin", ExamParseServiceImpl.extensionOf(null));
    }

    // ==================== 编排与降级（2026-11-17 补齐） ====================

    private ExamParseImageResult cleanResult(ParsedQuestion... questions) {
        return ExamParseImageResult.builder()
                .questions(new ArrayList<>(List.of(questions)))
                .paperQuestionTotal(questions.length)
                .build();
    }

    private ExamParseImageResult singleCallResult() {
        List<ParsedQuestion> questions = new ArrayList<>();
        ParsedQuestion q = ParsedQuestion.builder()
                .questionIndex(1).paperNo(1).stem("单次整卷题干").formatType("choice")
                .studentAnswer("B").answerStatus("answered").knowledgePointId("").build();
        questions.add(q);
        return ExamParseImageResult.builder().questions(questions).paperQuestionTotal(1).build();
    }

    private ExamParseOutcome parse() {
        return service.parsePaper(PAPER_ID, SUBJECT_ID, STUDENT_ID, TENANT,
                List.of("http://localhost:8080/uploads/exam-papers/100/a.jpg"));
    }

    @Test
    @DisplayName("作答版覆盖足够 → 走双版本，不调用单次整卷")
    void should_useDualChannel_when_answersCoverAllQuestions() {
        when(examParseAgent.parseCleanPaper(anyList(), any()))
                .thenReturn(cleanResult(question(1), question(2)));
        when(examParseAgent.parseStudentAnswers(anyList(), any()))
                .thenReturn(ExamAnswerParseResult.builder()
                        .answers(List.of(
                                ParsedAnswer.builder().paperNo(1).studentAnswer("C").answerStatus("answered").build(),
                                ParsedAnswer.builder().paperNo(2).studentAnswer("").answerStatus("blank").build()))
                        .build());

        ExamParseOutcome outcome = parse();

        assertEquals(2, outcome.getQuestions().size());
        assertEquals("C", outcome.getQuestions().get(0).getStudentAnswer());
        verify(examParseAgent, never()).parsePaper(anyList(), any());
    }

    @Test
    @DisplayName("作答版返回空 answers → 回退单次整卷（避免整卷 blank 被判 0 分）")
    void should_fallback_when_studentAnswersEmpty() {
        when(examParseAgent.parseCleanPaper(anyList(), any()))
                .thenReturn(cleanResult(question(1), question(2)));
        when(examParseAgent.parseStudentAnswers(anyList(), any()))
                .thenReturn(ExamAnswerParseResult.builder().answers(List.of()).build());
        when(examParseAgent.parsePaper(anyList(), any())).thenReturn(singleCallResult());

        ExamParseOutcome outcome = parse();

        assertEquals("单次整卷题干", outcome.getQuestions().get(0).getStem(), "必须走单次整卷回退");
        assertEquals("B", outcome.getQuestions().get(0).getStudentAnswer());
        verify(examParseAgent).parsePaper(anyList(), any());
    }

    @Test
    @DisplayName("作答版覆盖率不足（1/3）→ 回退单次整卷")
    void should_fallback_when_answerCoverageTooLow() {
        when(examParseAgent.parseCleanPaper(anyList(), any()))
                .thenReturn(cleanResult(question(1), question(2), question(3)));
        when(examParseAgent.parseStudentAnswers(anyList(), any()))
                .thenReturn(ExamAnswerParseResult.builder()
                        .answers(List.of(ParsedAnswer.builder().paperNo(1)
                                .studentAnswer("C").answerStatus("answered").build()))
                        .build());
        when(examParseAgent.parsePaper(anyList(), any())).thenReturn(singleCallResult());

        ExamParseOutcome outcome = parse();

        assertEquals("单次整卷题干", outcome.getQuestions().get(0).getStem());
        verify(examParseAgent).parsePaper(anyList(), any());
        verify(degradationLogger).log(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("dual_answer_coverage_low"), any());
    }

    @Test
    @DisplayName("clean 返回重复 paperNo → 对齐不可靠，回退单次整卷（不串答案）")
    void should_fallback_when_cleanPaperNoDuplicated() {
        when(examParseAgent.parseCleanPaper(anyList(), any()))
                .thenReturn(cleanResult(question(1), question(1)));
        when(examParseAgent.parsePaper(anyList(), any())).thenReturn(singleCallResult());

        ExamParseOutcome outcome = parse();

        assertEquals("单次整卷题干", outcome.getQuestions().get(0).getStem());
        verify(examParseAgent, never()).parseStudentAnswers(anyList(), any());
        verify(examParseAgent).parsePaper(anyList(), any());
    }

    @Test
    @DisplayName("clean 缺 paperNo → 回退单次整卷，且不调用作答版")
    void should_fallback_when_cleanPaperNoMissing() {
        when(examParseAgent.parseCleanPaper(anyList(), any()))
                .thenReturn(cleanResult(question(1), ParsedQuestion.builder().stem("无题号").build()));
        when(examParseAgent.parsePaper(anyList(), any())).thenReturn(singleCallResult());

        ExamParseOutcome outcome = parse();

        assertEquals("单次整卷题干", outcome.getQuestions().get(0).getStem());
        verify(examParseAgent, never()).parseStudentAnswers(anyList(), any());
    }

    @Test
    @DisplayName("应答版失败（failureReason）→ 回退单次整卷")
    void should_fallback_when_studentAnswersFailed() {
        when(examParseAgent.parseCleanPaper(anyList(), any()))
                .thenReturn(cleanResult(question(1)));
        when(examParseAgent.parseStudentAnswers(anyList(), any()))
                .thenReturn(ExamAnswerParseResult.builder()
                        .answers(List.of())
                        .failureReason("作答版解析重试 2 次后仍失败")
                        .build());
        when(examParseAgent.parsePaper(anyList(), any())).thenReturn(singleCallResult());

        ExamParseOutcome outcome = parse();

        assertEquals("单次整卷题干", outcome.getQuestions().get(0).getStem());
        verify(examParseAgent).parsePaper(anyList(), any());
    }

    @Test
    @DisplayName("开关关闭 → 完全不调用 clean/作答版，直接走单次整卷")
    void should_skipDualChannel_when_disabled() {
        ReflectionTestUtils.setField(service, "dualChannelEnabled", false);
        when(examParseAgent.parsePaper(anyList(), any())).thenReturn(singleCallResult());

        ExamParseOutcome outcome = parse();

        assertEquals("单次整卷题干", outcome.getQuestions().get(0).getStem());
        verify(examParseAgent, never()).parseCleanPaper(anyList(), any());
        verify(examParseAgent, never()).parseStudentAnswers(anyList(), any());
    }

    @Test
    @DisplayName("双版本回退时复用已构建的 OCR：OCR 每页只调用一次（降级不额外调用）")
    void should_reuseOcr_when_fallingBackToSingleCall() {
        ReflectionTestUtils.setField(service, "ocrGroundingEnabled", true);
        when(zhipuOcrClient.isEnabled()).thenReturn(true);
        when(zhipuOcrClient.parse(any(), anyString())).thenReturn(new OcrPageResult("印刷题干", List.of()));
        when(examParseAgent.parseCleanPaper(anyList(), any()))
                .thenReturn(ExamParseImageResult.builder().questions(new ArrayList<>()).build());
        when(examParseAgent.parsePaper(anyList(), any())).thenReturn(singleCallResult());

        parse();

        verify(zhipuOcrClient, times(1)).parse(any(), anyString());
    }
}
