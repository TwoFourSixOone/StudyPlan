package com.jiang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jiang.agent.AgentDegradationLogger;
import com.jiang.agent.HomeworkDegradationHandler;
import com.jiang.agent.QuestionGenAgent;
import com.jiang.client.AgentScopeClient;
import com.jiang.common.util.DailyPlanContentParser;
import com.jiang.common.util.ExamQuestionTextCodec;
import com.jiang.dto.DailyPlanQuestionRef;
import com.jiang.dto.SubmitAnswerRequest;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.ErrorQuestionMapper;
import com.jiang.mapper.ExamQuestionMapper;
import com.jiang.mapper.GeneratedQuestionMapper;
import com.jiang.mapper.HomeworkSubmissionDetailMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.ExamQuestion;
import com.jiang.model.entity.HomeworkSubmissionDetail;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.entity.Student;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.PlanStatus;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.BankQuestionPicker;
import com.jiang.service.impl.HomeworkServiceImpl;
import com.jiang.vo.DueErrorQuestionVO;
import com.jiang.vo.GoalVO;
import com.jiang.vo.HomeworkVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 试卷错题回流每日作业（Change 2026-11-11 <b>v3</b> / tasks D8）——设计 §7.2.1 四条必测。
 *
 * <p>覆盖：① 试卷错题次日进原题重做（ref 形态 + 题面还原）；② 举一反三可用 exam 源题生成
 * （缺答案跳过 + 降级日志）；③ 5+ 试卷错题不导致作业生成失败；④ exam redo 答题后 stage 推进
 * （答对 advance / 答错 reset；批改不调用 analysis_gen，解析取试卷解析原文）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExamRedoIntegrationTest 试卷错题回流作业")
class ExamRedoIntegrationTest {

    @Mock
    private GoalService goalService;
    @Mock
    private MasteryService masteryService;
    @Mock
    private DailyPlanMapper dailyPlanMapper;
    @Mock
    private HomeworkSubmissionDetailMapper submissionMapper;
    @Mock
    private GeneratedQuestionMapper generatedQuestionMapper;
    @Mock
    private QuestionBankMapper questionBankMapper;
    @Mock
    private LearningGoalMapper learningGoalMapper;
    @Mock
    private SubjectMapper subjectMapper;
    @Mock
    private KnowledgePointMapper knowledgePointMapper;
    @Mock
    private StudentKnowledgeStateMapper stateMapper;
    @Mock
    private StudentMapper studentMapper;
    @Mock
    private QuestionGenAgent questionGenAgent;
    @Mock
    private HomeworkDegradationHandler degradationHandler;
    @Mock
    private ErrorQuestionService errorQuestionService;
    @Mock
    private SimilarQuestionService similarQuestionService;
    @Mock
    private BankQuestionPicker bankQuestionPicker;
    @Mock
    private ErrorQuestionMapper errorQuestionMapper;
    @Mock
    private ExamQuestionMapper examQuestionMapper;
    @Mock
    private AgentDegradationLogger degradationLogger;
    @Mock
    private AnswerGradingService answerGradingService;
    @Mock
    private AgentScopeClient agentScopeClient;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private HomeworkServiceImpl homeworkService;

    private MockedStatic<StudentContext> mockedStudentContext;
    private ThreadPoolExecutor aiExecutor;

    private static final Long STUDENT_ID = 1L;
    private static final String TENANT_ID = "default";
    private static final Long SUBJECT_ID = 10L;
    private static final String SUBJECT_NAME = "数学";
    private static final Long GOAL_ID = 100L;
    private static final Long PLAN_ID = 200L;
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 12);
    private static final Long EXAM_QUESTION_ID = 501L;
    private static final Long EXAM_REF_ID = -EXAM_QUESTION_ID;

    @BeforeEach
    void setUp() {
        mockedStudentContext = mockStatic(StudentContext.class);
        mockedStudentContext.when(StudentContext::getStudentId).thenReturn(STUDENT_ID);
        mockedStudentContext.when(StudentContext::getTenantId).thenReturn(TENANT_ID);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        ReflectionTestUtils.setField(homeworkService, "transactionTemplate", txTemplate);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(
                org.mockito.Mockito.mock(org.springframework.transaction.TransactionStatus.class));

        aiExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        ReflectionTestUtils.setField(homeworkService, "aiExecutor", aiExecutor);
        // 真实 content 解析器（content JSON 往返断言）
        ReflectionTestUtils.setField(homeworkService, "contentParser", new DailyPlanContentParser());
    }

    @AfterEach
    void tearDown() {
        mockedStudentContext.close();
        StudentContext.clear();
        aiExecutor.shutdownNow();
    }

    // ==================== 公共 stub ====================

    private void stubActiveGoal() {
        GoalVO goal = GoalVO.builder()
                .goalId(GOAL_ID).studentId(STUDENT_ID).subjectId(SUBJECT_ID)
                .subjectName(SUBJECT_NAME).status("active")
                .startDate(PLAN_DATE.minusDays(10)).endDate(PLAN_DATE.plusMonths(3))
                .build();
        when(goalService.getActiveGoals(SUBJECT_ID)).thenReturn(List.of(goal));
    }

    private void stubSubjectAndKp() {
        Subject subject = new Subject();
        subject.setSubjectId(SUBJECT_ID);
        subject.setName(SUBJECT_NAME);
        when(subjectMapper.selectById(SUBJECT_ID)).thenReturn(subject);

        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of());
        KnowledgePoint kp = new KnowledgePoint();
        kp.setKnowledgePointId("kp_001");
        kp.setName("集合与函数");
        kp.setSubject(SUBJECT_NAME);
        kp.setStatus("active");
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(kp));

        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setGrade("高中");
        lenient().when(studentMapper.selectById(STUDENT_ID)).thenReturn(student);
    }

    private void stubPlanWrite() {
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);
        doAnswer(invocation -> {
            DailyPlan p = invocation.getArgument(0);
            p.setPlanId(PLAN_ID);
            return 1;
        }).when(dailyPlanMapper).insert(any(DailyPlan.class));
        lenient().when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), anyString(), anyString(),
                anyString(), anyString())).thenReturn(1);
    }

    private DueErrorQuestionVO dueExamError(Long examRefId) {
        return DueErrorQuestionVO.builder()
                .errorQuestionId(9L)
                .questionId(examRefId)
                .knowledgePointId("kp_001")
                .currentStage(1)
                .nextDueDate(PLAN_DATE)
                .build();
    }

    private void stubDueErrors(List<DueErrorQuestionVO> items) {
        Map<String, List<DueErrorQuestionVO>> dueMap = new LinkedHashMap<>();
        dueMap.put("kp_001", items);
        when(errorQuestionService.getDueErrorQuestions(STUDENT_ID, TENANT_ID, PLAN_DATE)).thenReturn(dueMap);
    }

    private void stubActiveErrorCount(int rawCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("knowledge_point_id", "kp_001");
        row.put("error_count", rawCount);
        lenient().when(errorQuestionMapper.selectActiveErrorCountGroupByKp(STUDENT_ID, TENANT_ID))
                .thenReturn(List.of(row));
    }

    private ExamQuestion examQuestionWithAnswer() {
        ExamQuestion question = new ExamQuestion();
        question.setId(EXAM_QUESTION_ID);
        question.setKnowledgePointId("kp_001");
        question.setQuestionType("choice");
        question.setDifficulty("standard");
        question.setTenantId(TENANT_ID);
        question.setQuestionContent(ExamQuestionTextCodec.encode(
                "下列各数中，最小的数是（ ）",
                List.of(Map.of("label", "A", "text", "-3"), Map.of("label", "B", "text", "0")),
                "A", "负数最小"));
        return question;
    }

    // ==================== ① 试卷错题次日进原题重做 ====================

    @Test
    @DisplayName("① 试卷错题次日以 questionSource=exam 进入原题重做，题面/选项/答案/解析/题型可还原")
    void should_putExamWrongQuestionIntoRedo_withRestoredContent() {
        stubActiveGoal();
        stubSubjectAndKp();
        stubPlanWrite();
        stubDueErrors(List.of(dueExamError(EXAM_REF_ID)));
        stubActiveErrorCount(1);
        when(bankQuestionPicker.pick(anyString(), anyString(), anyInt(),
                nullable(Integer.class), nullable(String.class), anySet())).thenReturn(List.of());

        // ② 场景同时覆盖：题库无源题 → exam 源题兜底 → 生成 1 道类似题
        when(errorQuestionMapper.selectSourceQuestionForSimilar(STUDENT_ID, TENANT_ID, "kp_001", PLAN_DATE))
                .thenReturn(null);
        when(errorQuestionMapper.selectDueExamSourceQuestion(STUDENT_ID, TENANT_ID, "kp_001", PLAN_DATE))
                .thenReturn(examQuestionWithAnswer());
        when(generatedQuestionMapper.selectCheckedContentsByKp("kp_001", TENANT_ID)).thenReturn(List.of());
        when(similarQuestionService.generateWithValidation(
                eq(STUDENT_ID), eq(TENANT_ID), eq("kp_001"), any(), any(QuestionBank.class),
                anyInt(), anyString(), anyList()))
                .thenAnswer(invocation -> {
                    // 断言源题被适配为临时 QuestionBank（含题干/答案/解析/题型）
                    QuestionBank source = invocation.getArgument(4);
                    assertTrue(source.getContent().contains("最小的数"));
                    assertEquals("A", source.getAnswer());
                    assertEquals("负数最小", source.getAnalysis());
                    assertEquals("choice", source.getType());
                    assertEquals("kp_001", source.getKnowledgePointId());
                    return List.of();
                });

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), contentCaptor.capture(),
                anyString(), anyString(), anyString())).thenReturn(1);

        assertDoesNotThrow(() -> homeworkService.generateDailyHomework(
                STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));

        List<DailyPlanQuestionRef> refs = new DailyPlanContentParser().parse(contentCaptor.getValue());
        DailyPlanQuestionRef examRef = refs.stream()
                .filter(r -> "exam".equals(r.getQuestionSource()))
                .findFirst().orElseThrow();
        assertEquals(EXAM_REF_ID, examRef.getQuestionId(), "引用形态必须是负的 -exam_question.id");
        assertEquals("redo", examRef.getTaskType());
        // 负 ID 不得落到题库查询：bank/gen 的 selectBatchIds 未被调用
        verify(questionBankMapper, never()).selectBatchIds(anyCollection());

        // 题面组装：getHomeworkByDate 还原 stem/choices/correctAnswer/explanation/type/difficulty
        DailyPlan plan = new DailyPlan();
        plan.setPlanId(PLAN_ID);
        plan.setGoalId(GOAL_ID);
        plan.setPlanDate(PLAN_DATE);
        plan.setStatus(PlanStatus.PENDING.getCode());
        plan.setTenantId(TENANT_ID);
        plan.setContent(contentCaptor.getValue());
        when(dailyPlanMapper.selectBySubjectAndDate(SUBJECT_ID, STUDENT_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);
        when(examQuestionMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(examQuestionWithAnswer()));
        when(submissionMapper.selectByPlanId(PLAN_ID, STUDENT_ID, TENANT_ID)).thenReturn(List.of());

        List<HomeworkVO> homework = homeworkService.getHomeworkByDate(
                STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);

        HomeworkVO examItem = homework.stream()
                .filter(vo -> "exam".equals(vo.getQuestionSource()))
                .findFirst().orElseThrow();
        assertEquals(EXAM_REF_ID, examItem.getQuestionId());
        assertEquals("choice", examItem.getQuestionFormatType());
        assertEquals("下列各数中，最小的数是（ ）", examItem.getQuestionContent());
        assertEquals(List.of("-3", "0"), examItem.getChoices());
        assertEquals("standard", examItem.getDifficulty());
        assertEquals("kp_001", examItem.getKnowledgePointId());
    }

    // ==================== ⑤ 混排与去重 ====================

    @Test
    @DisplayName("⑤ 题库题与试卷题混排：来源与题量正确，同一 (source,id) 不重复")
    void should_mixBankAndExamRefs_andDedupe() {
        stubActiveGoal();
        stubSubjectAndKp();
        stubPlanWrite();
        // 同一道试卷错题重复出现（模拟脏数据/重复组装）→ 去重后只保留 1 条
        stubDueErrors(List.of(dueExamError(101L), dueExamError(EXAM_REF_ID), dueExamError(EXAM_REF_ID)));
        stubActiveErrorCount(2);
        lenient().when(bankQuestionPicker.pick(anyString(), anyString(), anyInt(),
                nullable(Integer.class), nullable(String.class), anySet())).thenReturn(List.of());
        QuestionBank bankSource = new QuestionBank();
        bankSource.setQuestionId(101L);
        bankSource.setKnowledgePointId("kp_001");
        bankSource.setContent("题库源题");
        bankSource.setAnswer("A");
        when(errorQuestionMapper.selectSourceQuestionForSimilar(any(), any(), any(), any()))
                .thenReturn(bankSource);
        when(generatedQuestionMapper.selectCheckedContentsByKp("kp_001", TENANT_ID)).thenReturn(List.of());
        when(similarQuestionService.generateWithValidation(any(), any(), any(), any(), any(),
                anyInt(), anyString(), anyList())).thenReturn(List.of());

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), contentCaptor.capture(),
                anyString(), anyString(), anyString())).thenReturn(1);

        assertDoesNotThrow(() -> homeworkService.generateDailyHomework(
                STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));

        List<DailyPlanQuestionRef> refs = new DailyPlanContentParser().parse(contentCaptor.getValue());
        assertEquals(2, refs.size(), "重复的 (exam,-501) 被去重，只剩 bank#101 + exam#-501");
        assertEquals("bank", refs.get(0).getQuestionSource());
        assertEquals(101L, refs.get(0).getQuestionId());
        assertEquals("exam", refs.get(1).getQuestionSource());
        assertEquals(EXAM_REF_ID, refs.get(1).getQuestionId());
        assertEquals("redo", refs.get(1).getTaskType());
    }

    // ==================== ② 举一反三 exam 源题 ====================

    @Test
    @DisplayName("② 试卷源题缺标准答案 → 跳过该 kp 举一反三 + 写降级日志，不阻塞作业生成")
    void should_skipSimilar_when_examSourceMissingAnswer() {
        stubActiveGoal();
        stubSubjectAndKp();
        stubPlanWrite();
        stubDueErrors(List.of(dueExamError(EXAM_REF_ID)));
        stubActiveErrorCount(1);
        when(bankQuestionPicker.pick(anyString(), anyString(), anyInt(),
                nullable(Integer.class), nullable(String.class), anySet())).thenReturn(List.of());
        when(errorQuestionMapper.selectSourceQuestionForSimilar(any(), any(), any(), any()))
                .thenReturn(null);
        ExamQuestion noAnswer = examQuestionWithAnswer();
        noAnswer.setQuestionContent(ExamQuestionTextCodec.encode("只识别到题干", List.of(), null, null));
        when(errorQuestionMapper.selectDueExamSourceQuestion(STUDENT_ID, TENANT_ID, "kp_001", PLAN_DATE))
                .thenReturn(noAnswer);

        assertDoesNotThrow(() -> homeworkService.generateDailyHomework(
                STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));

        verify(similarQuestionService, never()).generateWithValidation(
                any(), any(), any(), any(), any(), anyInt(), any(), any());
        verify(degradationLogger).log(anyString(), anyString(), anyString(),
                eq("skip_similar_question"), eq(TENANT_ID));
    }

    // ==================== ③ 5+ 试卷错题不失败 ====================

    @Test
    @DisplayName("③ 某 kp 5 道试卷错题 → 次日作业生成不失败（KP_MAX 在上游夹取），exam redo ≤3/天")
    void should_notFailGeneration_when_kpHasFiveExamWrongQuestions() {
        stubActiveGoal();
        stubSubjectAndKp();
        stubPlanWrite();
        // 到期查询每 kp LIMIT 3，5 道试卷错题当天只取 3 道进作业，其余保持 active 次日顺延
        stubDueErrors(List.of(dueExamError(-501L), dueExamError(-502L), dueExamError(-503L)));
        stubActiveErrorCount(5);
        lenient().when(bankQuestionPicker.pick(anyString(), anyString(), anyInt(),
                nullable(Integer.class), nullable(String.class), anySet())).thenReturn(List.of());

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), contentCaptor.capture(),
                anyString(), anyString(), anyString())).thenReturn(1);

        assertDoesNotThrow(() -> homeworkService.generateDailyHomework(
                STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));

        List<DailyPlanQuestionRef> refs = new DailyPlanContentParser().parse(contentCaptor.getValue());
        long examRedoCount = refs.stream()
                .filter(r -> "exam".equals(r.getQuestionSource()) && "redo".equals(r.getTaskType()))
                .count();
        assertEquals(3, examRedoCount, "每 kp 每日 redo ≤3（LIMIT 3），其余顺延到次日");
    }

    // ==================== ④ exam redo 答题后 stage 推进 ====================

    private DailyPlan gradedPlan(List<DailyPlanQuestionRef> refs) {
        DailyPlan plan = new DailyPlan();
        plan.setPlanId(PLAN_ID);
        plan.setGoalId(GOAL_ID);
        plan.setPlanDate(PLAN_DATE);
        plan.setStatus(PlanStatus.IN_PROGRESS.getCode());
        plan.setTenantId(TENANT_ID);
        plan.setContent(new DailyPlanContentParser().serialize(refs));
        return plan;
    }

    private HomeworkSubmissionDetail examRedoSubmission(String answer) {
        HomeworkSubmissionDetail submission = new HomeworkSubmissionDetail();
        submission.setSubmissionId(1L);
        submission.setPlanId(PLAN_ID);
        submission.setQuestionId(EXAM_REF_ID);
        submission.setQuestionSource("exam");
        submission.setQuestionType("redo");
        submission.setStudentAnswer(answer);
        submission.setKnowledgePointId("kp_001");
        submission.setStudentId(STUDENT_ID);
        submission.setTenantId(TENANT_ID);
        return submission;
    }

    private void stubGradeContext(DailyPlan plan, HomeworkSubmissionDetail submission) {
        when(goalService.getActiveGoals(SUBJECT_ID)).thenReturn(List.of(GoalVO.builder()
                .goalId(GOAL_ID).studentId(STUDENT_ID).subjectId(SUBJECT_ID)
                .subjectName(SUBJECT_NAME).status("active")
                .startDate(PLAN_DATE.minusDays(10)).endDate(PLAN_DATE.plusMonths(3))
                .build()));
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);
        when(submissionMapper.selectByPlanId(PLAN_ID, STUDENT_ID, TENANT_ID))
                .thenReturn(new ArrayList<>(List.of(submission)));
        when(examQuestionMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(examQuestionWithAnswer()));
        when(submissionMapper.updateById(any(HomeworkSubmissionDetail.class))).thenReturn(1);
        when(dailyPlanMapper.updateStatusWithCheck(PLAN_ID, PlanStatus.COMPLETED.getCode(),
                PlanStatus.IN_PROGRESS.getCode(), TENANT_ID)).thenReturn(1);
        when(subjectMapper.selectById(SUBJECT_ID)).thenReturn(subject());
        // 年级用于判分 Prompt（约定 #23）；未设置时返回 null，故这里显式给一个年级
        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setGrade("高中");
        when(studentMapper.selectById(STUDENT_ID)).thenReturn(student);
    }

    private Subject subject() {
        Subject subject = new Subject();
        subject.setSubjectId(SUBJECT_ID);
        subject.setName(SUBJECT_NAME);
        return subject;
    }

    @Test
    @DisplayName("④ 答对 → advanceStage(-exam_question_id)，解析取试卷解析原文（不调用 analysis_gen）")
    void should_advanceStage_when_examRedoAnsweredCorrectly() {
        DailyPlan plan = gradedPlan(List.of(new DailyPlanQuestionRef(EXAM_REF_ID, "exam", "redo")));
        HomeworkSubmissionDetail submission = examRedoSubmission("A");
        stubGradeContext(plan, submission);
        when(answerGradingService.grade(eq("choice"), anyString(), eq("A"), eq("A"),
                isNull(), anyString(), anyString())).thenReturn(Boolean.TRUE);

        homeworkService.gradeHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);

        verify(errorQuestionService).advanceStage(STUDENT_ID, TENANT_ID, EXAM_REF_ID, PLAN_DATE);
        verify(errorQuestionService, never()).resetStage(any(), any(), any(), any());
        // D7：exam redo 的解析来自试卷解析原文，不调用 analysis_gen（也不调用任何 LLM）
        verify(agentScopeClient, never()).call(anyString());
        ArgumentCaptor<HomeworkSubmissionDetail> captor =
                ArgumentCaptor.forClass(HomeworkSubmissionDetail.class);
        verify(submissionMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getIsCorrect());
        assertEquals("负数最小", captor.getValue().getAnalysis());
        verify(masteryService).updateMasteryFromHomework(eq(STUDENT_ID), eq(TENANT_ID), anyList());
    }

    @Test
    @DisplayName("④ 答错 → resetStage(-exam_question_id)，与题库错题同一状态机")
    void should_resetStage_when_examRedoAnsweredWrong() {
        DailyPlan plan = gradedPlan(List.of(new DailyPlanQuestionRef(EXAM_REF_ID, "exam", "redo")));
        HomeworkSubmissionDetail submission = examRedoSubmission("B");
        stubGradeContext(plan, submission);
        when(answerGradingService.grade(eq("choice"), anyString(), eq("A"), eq("B"),
                isNull(), anyString(), anyString())).thenReturn(Boolean.FALSE);

        homeworkService.gradeHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);

        verify(errorQuestionService).resetStage(STUDENT_ID, TENANT_ID, EXAM_REF_ID, PLAN_DATE);
        verify(errorQuestionService, never()).advanceStage(any(), any(), any(), any());
        // exam 题不重复入错题队列（已在队列中）
        verify(errorQuestionService, never()).recordWrongAnswer(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("提交：questionSource=exam 接受，kp 从 exam_question 取，submission 落 exam")
    void should_acceptExamSource_when_submitAnswer() {
        DailyPlan plan = gradedPlan(List.of(new DailyPlanQuestionRef(EXAM_REF_ID, "exam", "redo")));
        plan.setStatus(PlanStatus.PENDING.getCode());
        when(dailyPlanMapper.selectById(PLAN_ID)).thenReturn(plan);
        when(learningGoalMapper.selectById(GOAL_ID)).thenReturn(goalEntity());
        when(examQuestionMapper.selectById(EXAM_QUESTION_ID)).thenReturn(examQuestionWithAnswer());
        when(dailyPlanMapper.updateStatusWithCheck(PLAN_ID, PlanStatus.IN_PROGRESS.getCode(),
                PlanStatus.PENDING.getCode(), TENANT_ID)).thenReturn(1);

        SubmitAnswerRequest request = new SubmitAnswerRequest();
        request.setPlanId(PLAN_ID);
        request.setQuestionId(EXAM_REF_ID);
        request.setQuestionSource("exam");
        request.setTaskType("redo");
        request.setStudentAnswer("A");

        homeworkService.submitAnswer(STUDENT_ID, TENANT_ID, request);

        ArgumentCaptor<HomeworkSubmissionDetail> captor =
                ArgumentCaptor.forClass(HomeworkSubmissionDetail.class);
        verify(submissionMapper).insertOrUpdate(captor.capture());
        assertEquals(-501L, captor.getValue().getQuestionId());
        assertEquals("exam", captor.getValue().getQuestionSource());
        assertEquals("kp_001", captor.getValue().getKnowledgePointId());
        assertEquals("redo", captor.getValue().getQuestionType());
        verify(dailyPlanMapper).updateStatusWithCheck(PLAN_ID, PlanStatus.IN_PROGRESS.getCode(),
                PlanStatus.PENDING.getCode(), TENANT_ID);
    }

    private com.jiang.model.entity.LearningGoal goalEntity() {
        com.jiang.model.entity.LearningGoal goal = new com.jiang.model.entity.LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setStudentId(STUDENT_ID);
        goal.setTenantId(TENANT_ID);
        return goal;
    }
}
