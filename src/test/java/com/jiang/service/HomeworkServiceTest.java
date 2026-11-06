package com.jiang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jiang.agent.HomeworkDegradationHandler;
import com.jiang.agent.QuestionGenAgent;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.DailyPlanContentParser;
import com.jiang.dto.DailyPlanQuestionRef;
import com.jiang.dto.KnowledgePointWithMastery;
import com.jiang.dto.SubmitAnswerRequest;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.ErrorQuestionMapper;
import com.jiang.mapper.GeneratedQuestionMapper;
import com.jiang.mapper.HomeworkSubmissionDetailMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.HomeworkSubmissionDetail;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.PlanStatus;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.HomeworkServiceImpl;
import com.jiang.vo.GradeResultVO;
import com.jiang.vo.GoalVO;
import com.jiang.vo.HomeworkVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HomeworkService 单元测试
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@ExtendWith(MockitoExtension.class)
class HomeworkServiceTest {

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
    private QuestionGenAgent questionGenAgent;
    @Mock
    private HomeworkDegradationHandler degradationHandler;
    @Mock
    private DailyPlanContentParser contentParser;
    @Mock
    private ErrorQuestionService errorQuestionService;
    @Mock
    private SimilarQuestionService similarQuestionService;
    @Mock
    private ErrorQuestionMapper errorQuestionMapper;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private HomeworkServiceImpl homeworkService;

    private MockedStatic<StudentContext> mockedStudentContext;

    private static final Long STUDENT_ID = 1L;
    private static final String TENANT_ID = "default";
    private static final Long SUBJECT_ID = 10L;
    private static final String SUBJECT_NAME = "数学";
    private static final Long GOAL_ID = 100L;
    private static final Long PLAN_ID = 200L;
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 2);

    @BeforeEach
    void setUp() {
        mockedStudentContext = mockStatic(StudentContext.class);
        mockedStudentContext.when(StudentContext::getStudentId).thenReturn(STUDENT_ID);
        mockedStudentContext.when(StudentContext::getTenantId).thenReturn(TENANT_ID);

        // 初始化 TransactionTemplate（@PostConstruct 不会在 Mockito 测试中自动调用）
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        ReflectionTestUtils.setField(homeworkService, "transactionTemplate", txTemplate);
        // 配置 mock 立即执行回调（模拟事务提交）— lenient 避免非事务测试报 UnnecessaryStubbing
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
    }

    @AfterEach
    void tearDown() {
        mockedStudentContext.close();
        StudentContext.clear();
    }

    // ==================== generateDailyHomework ====================

    @Test
    @DisplayName("生成作业：无 active goal → 抛 LEARNING_GOAL_NOT_FOUND")
    void should_throwLearningGoalNotFound_when_noActiveGoal() {
        when(goalService.getActiveGoals(SUBJECT_ID)).thenReturn(java.util.Collections.emptyList());

        assertThrows(BusinessException.class,
                () -> homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));
    }

    @Test
    @DisplayName("生成作业：已存在 in_progress 计划 → 抛 HOMEWORK_ALREADY_EXISTS")
    void should_throwAlreadyExists_when_planInProgress() {
        stubActiveGoal();
        DailyPlan plan = buildDailyPlan(PlanStatus.IN_PROGRESS);
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));
        assertEquals(ErrorCodes.HOMEWORK_ALREADY_EXISTS.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("生成作业：Agent 成功 → 创建计划并写入题目")
    void should_generateSuccessfully_when_agentSucceeds() {
        stubActiveGoal();
        stubSubject();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);
        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of(buildStudentState("kp_001", new BigDecimal("0.5"))));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buildKnowledgePoint("kp_001")));

        // Agent 返回 1 道题库题
        List<DailyPlanQuestionRef> refs = List.of(new DailyPlanQuestionRef(1L, "bank", "review"));
        QuestionGenAgent.QuestionGenResult agentResult =
                new QuestionGenAgent.QuestionGenResult(refs, Collections.emptyList());
        when(questionGenAgent.generate(anyList(), eq(15))).thenReturn(agentResult);
        when(errorQuestionService.getDueErrorQuestions(eq(STUDENT_ID), eq(TENANT_ID), eq(PLAN_DATE)))
                .thenReturn(Collections.emptyMap());
        lenient().when(errorQuestionMapper.selectActiveErrorCountGroupByKp(eq(STUDENT_ID), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());
        when(contentParser.serialize(refs)).thenReturn("[{\"questionId\":1}]");

        // 使用 doAnswer 捕获 insert 的 plan 来设置自动生成的 planId
        doAnswer(invocation -> {
            DailyPlan p = invocation.getArgument(0);
            p.setPlanId(PLAN_ID);
            return 1;
        }).when(dailyPlanMapper).insert(any(DailyPlan.class));
        when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        assertDoesNotThrow(() -> homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));

        verify(dailyPlanMapper).insert(any(DailyPlan.class));
        verify(questionGenAgent).generate(anyList(), eq(15));
        verify(dailyPlanMapper).updateContentAndStatus(
                eq(PLAN_ID), anyString(), eq(PlanStatus.PENDING.getCode()),
                eq(PlanStatus.PENDING_GENERATION.getCode()), eq(TENANT_ID));
    }

    @Test
    @DisplayName("生成作业：Agent 失败 → 降级成功")
    void should_fallbackToDegradation_when_agentFails() {
        stubActiveGoal();
        stubSubject();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);
        doAnswer(inv -> { inv.<DailyPlan>getArgument(0).setPlanId(PLAN_ID); return 1; })
                .when(dailyPlanMapper).insert(any(DailyPlan.class));
        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of(buildStudentState("kp_001", new BigDecimal("0.3"))));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buildKnowledgePoint("kp_001")));

        // Agent 两次都失败
        when(questionGenAgent.generate(anyList(), eq(15)))
                .thenThrow(new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "LLM error"));
        when(errorQuestionService.getDueErrorQuestions(eq(STUDENT_ID), eq(TENANT_ID), eq(PLAN_DATE)))
                .thenReturn(Collections.emptyMap());
        lenient().when(errorQuestionMapper.selectActiveErrorCountGroupByKp(eq(STUDENT_ID), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());

        // 降级成功
        List<DailyPlanQuestionRef> defaultRefs = List.of(new DailyPlanQuestionRef(5L, "bank", "review"));
        when(degradationHandler.generateDefaultPlan(anyList(), eq(TENANT_ID))).thenReturn(defaultRefs);
        when(contentParser.serialize(defaultRefs)).thenReturn("[{\"questionId\":5}]");
        when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        assertDoesNotThrow(() -> homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));

        verify(degradationHandler).logDegradation(anyString(), anyString(), eq(TENANT_ID));
        verify(degradationHandler).generateDefaultPlan(anyList(), eq(TENANT_ID));
    }

    @Test
    @DisplayName("生成作业：Review Agent + 降级均失败（新计划）→ 优雅降级，仅含空 review")
    void should_gracefulDegradation_when_reviewAgentAndDegradationBothFail_newPlan() {
        stubActiveGoal();
        stubSubject();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);
        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of(buildStudentState("kp_001", new BigDecimal("0.3"))));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buildKnowledgePoint("kp_001")));

        // Agent 失败
        when(questionGenAgent.generate(anyList(), eq(15)))
                .thenThrow(new BusinessException(ErrorCodes.AGENT_CALL_FAILED));
        when(errorQuestionService.getDueErrorQuestions(eq(STUDENT_ID), eq(TENANT_ID), eq(PLAN_DATE)))
                .thenReturn(Collections.emptyMap());
        lenient().when(errorQuestionMapper.selectActiveErrorCountGroupByKp(eq(STUDENT_ID), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());

        // 降级也失败
        when(degradationHandler.generateDefaultPlan(anyList(), eq(TENANT_ID)))
                .thenThrow(new BusinessException(ErrorCodes.AGENT_DEGRADED, "无可用题目"));

        // 优雅降级后仍需的 stub（plan 创建 + content 序列化）
        when(contentParser.serialize(anyList())).thenReturn("[]");
        doAnswer(invocation -> {
            DailyPlan p = invocation.getArgument(0);
            p.setPlanId(PLAN_ID);
            return 1;
        }).when(dailyPlanMapper).insert(any(DailyPlan.class));
        when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        // Change 8: review 为 P2（非关键），降级失败不阻塞整个作业生成
        assertDoesNotThrow(() -> homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));

        // 降级处理器仍被调用
        verify(degradationHandler).generateDefaultPlan(anyList(), eq(TENANT_ID));
        // 计划正常落库
        verify(dailyPlanMapper).insert(any(DailyPlan.class));
    }

    // ==================== getHomeworkByDate ====================

    @Test
    @DisplayName("查询作业：无计划 → 返回空列表")
    void should_returnEmpty_when_noPlan() {
        stubActiveGoal();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);

        List<HomeworkVO> result = homeworkService.getHomeworkByDate(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("查询作业：有计划 + 题库题 → 正确组装 HomeworkVO")
    void should_returnHomeworkList_when_planExists() {
        stubActiveGoal();
        DailyPlan plan = buildDailyPlan(PlanStatus.PENDING);
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);

        List<DailyPlanQuestionRef> refs = List.of(new DailyPlanQuestionRef(1L, "bank", "review"));
        when(contentParser.parse(plan.getContent())).thenReturn(refs);

        QuestionBank qb = new QuestionBank();
        qb.setQuestionId(1L);
        qb.setContent("题干");
        qb.setAnswer("A");
        qb.setType("choice");
        qb.setDifficulty("basic");
        qb.setKnowledgePointId("kp_001");
        when(questionBankMapper.selectBatchIds(anyCollection())).thenReturn(List.of(qb));
        when(submissionMapper.selectByPlanId(PLAN_ID, TENANT_ID)).thenReturn(Collections.emptyList());

        List<HomeworkVO> result = homeworkService.getHomeworkByDate(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getQuestionId());
        assertEquals("bank", result.get(0).getQuestionSource());
        assertEquals("choice", result.get(0).getQuestionFormatType());
        assertNull(result.get(0).getStudentAnswer());
    }

    // ==================== submitAnswer ====================

    @Test
    @DisplayName("提交答案：plan 不存在 → 抛 HOMEWORK_NOT_FOUND")
    void should_throwNotFound_when_planNotExists() {
        when(dailyPlanMapper.selectById(999L)).thenReturn(null);

        SubmitAnswerRequest request = buildSubmitRequest(999L, 1L);
        assertThrows(BusinessException.class,
                () -> homeworkService.submitAnswer(STUDENT_ID, TENANT_ID, request));
    }

    @Test
    @DisplayName("提交答案：归属校验失败 → 抛 STUDENT_IDENTITY_MISMATCH")
    void should_throwMismatch_when_planBelongsToOtherStudent() {
        DailyPlan plan = buildDailyPlan(PlanStatus.PENDING);
        plan.setPlanId(PLAN_ID);
        when(dailyPlanMapper.selectById(PLAN_ID)).thenReturn(plan);

        LearningGoal otherGoal = new LearningGoal();
        otherGoal.setGoalId(999L);
        otherGoal.setStudentId(999L); // 不同学生
        when(learningGoalMapper.selectById(GOAL_ID)).thenReturn(otherGoal);

        SubmitAnswerRequest request = buildSubmitRequest(PLAN_ID, 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> homeworkService.submitAnswer(STUDENT_ID, TENANT_ID, request));
        assertEquals(ErrorCodes.STUDENT_IDENTITY_MISMATCH.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("提交答案：正常提交 → pending → in_progress")
    void should_submitSuccessfully_when_planIsPending() {
        DailyPlan plan = buildDailyPlan(PlanStatus.PENDING);
        plan.setPlanId(PLAN_ID);
        when(dailyPlanMapper.selectById(PLAN_ID)).thenReturn(plan);

        LearningGoal goal = new LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setStudentId(STUDENT_ID);
        when(learningGoalMapper.selectById(GOAL_ID)).thenReturn(goal);

        List<DailyPlanQuestionRef> refs = List.of(new DailyPlanQuestionRef(1L, "bank", "review"));
        when(contentParser.parse(plan.getContent())).thenReturn(refs);
        when(submissionMapper.insertOrUpdate(any(HomeworkSubmissionDetail.class))).thenReturn(1);
        when(dailyPlanMapper.updateStatusWithCheck(PLAN_ID, PlanStatus.IN_PROGRESS.getCode(),
                PlanStatus.PENDING.getCode(), TENANT_ID)).thenReturn(1);

        QuestionBank qb = new QuestionBank();
        qb.setQuestionId(1L);
        qb.setKnowledgePointId("kp_001");
        when(questionBankMapper.selectById(1L)).thenReturn(qb);

        SubmitAnswerRequest request = buildSubmitRequest(PLAN_ID, 1L);
        assertDoesNotThrow(() -> homeworkService.submitAnswer(STUDENT_ID, TENANT_ID, request));

        verify(submissionMapper).insertOrUpdate(any(HomeworkSubmissionDetail.class));
        verify(dailyPlanMapper).updateStatusWithCheck(
                eq(PLAN_ID), eq(PlanStatus.IN_PROGRESS.getCode()),
                eq(PlanStatus.PENDING.getCode()), eq(TENANT_ID));
    }

    // ==================== gradeHomework ====================

    @Test
    @DisplayName("批改作业：无 active goal → 抛 LEARNING_GOAL_NOT_FOUND")
    void should_throwGoalNotFound_when_noActiveGoalForGrade() {
        when(goalService.getActiveGoals(SUBJECT_ID)).thenReturn(java.util.Collections.emptyList());

        assertThrows(BusinessException.class,
                () -> homeworkService.gradeHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));
    }

    @Test
    @DisplayName("批改作业：已完成 → 抛 HOMEWORK_ALREADY_GRADED（幂等）")
    void should_throwAlreadyGraded_when_planCompleted() {
        stubActiveGoal();
        DailyPlan plan = buildDailyPlan(PlanStatus.COMPLETED);
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> homeworkService.gradeHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));
        assertEquals(ErrorCodes.HOMEWORK_ALREADY_GRADED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("批改作业：未提交 → 抛 HOMEWORK_NOT_SUBMITTED")
    void should_throwNotSubmitted_when_planPending() {
        stubActiveGoal();
        DailyPlan plan = buildDailyPlan(PlanStatus.PENDING);
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> homeworkService.gradeHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));
        assertEquals(ErrorCodes.HOMEWORK_NOT_SUBMITTED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("批改作业：正常批改 → 更新掌握度 + 状态完成")
    void should_gradeSuccessfully_when_allValid() {
        stubActiveGoal();
        DailyPlan plan = buildDailyPlan(PlanStatus.IN_PROGRESS);
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);

        // 提交记录
        HomeworkSubmissionDetail sub = new HomeworkSubmissionDetail();
        sub.setSubmissionId(1L);
        sub.setQuestionId(100L);
        sub.setQuestionSource("bank");
        sub.setStudentAnswer("A");
        sub.setKnowledgePointId("kp_001");
        when(submissionMapper.selectByPlanId(PLAN_ID, TENANT_ID)).thenReturn(new ArrayList<>(List.of(sub)));

        // 题目引用
        List<DailyPlanQuestionRef> refs = List.of(new DailyPlanQuestionRef(100L, "bank", "review"));
        when(contentParser.parse(plan.getContent())).thenReturn(refs);

        // 题库答案
        QuestionBank qb = new QuestionBank();
        qb.setQuestionId(100L);
        qb.setAnswer("A");
        qb.setKnowledgePointId("kp_001");
        when(questionBankMapper.selectBatchIds(anyCollection())).thenReturn(List.of(qb));
        when(generatedQuestionMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());

        when(submissionMapper.updateById(any(HomeworkSubmissionDetail.class))).thenReturn(1);
        when(dailyPlanMapper.updateStatusWithCheck(PLAN_ID, PlanStatus.COMPLETED.getCode(),
                PlanStatus.IN_PROGRESS.getCode(), TENANT_ID)).thenReturn(1);

        GradeResultVO result = homeworkService.gradeHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);

        assertNotNull(result);
        assertEquals(1, result.getTotalQuestions());
        assertEquals(1, result.getCorrectCount());
        assertEquals(0, result.getIncorrectCount());
        assertEquals(0, BigDecimal.ONE.compareTo(result.getAccuracy()), "accuracy should be 1.0000");

        verify(masteryService).updateMasteryFromHomework(STUDENT_ID, TENANT_ID, List.of(1L));
        verify(dailyPlanMapper).updateStatusWithCheck(
                eq(PLAN_ID), eq(PlanStatus.COMPLETED.getCode()),
                eq(PlanStatus.IN_PROGRESS.getCode()), eq(TENANT_ID));
    }

    @Test
    @DisplayName("批改作业：并发冲突 → 抛 HOMEWORK_CONCURRENT_CONFLICT")
    void should_throwConcurrentConflict_when_statusUpdateFails() {
        stubActiveGoal();
        DailyPlan plan = buildDailyPlan(PlanStatus.IN_PROGRESS);
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);
        when(submissionMapper.selectByPlanId(PLAN_ID, TENANT_ID)).thenReturn(Collections.emptyList());
        when(contentParser.parse(plan.getContent())).thenReturn(Collections.emptyList());
        when(questionBankMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());
        when(generatedQuestionMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());

        // 状态机校验失败（另一线程已更新）
        when(dailyPlanMapper.updateStatusWithCheck(PLAN_ID, PlanStatus.COMPLETED.getCode(),
                PlanStatus.IN_PROGRESS.getCode(), TENANT_ID)).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> homeworkService.gradeHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));
    }

    // ==================== 辅助方法 ====================

    private void stubActiveGoal() {
        GoalVO goal = GoalVO.builder()
                .goalId(GOAL_ID).studentId(STUDENT_ID).subjectId(SUBJECT_ID)
                .subjectName(SUBJECT_NAME).status("active")
                .startDate(LocalDate.now()).endDate(LocalDate.now().plusMonths(3))
                .build();
        when(goalService.getActiveGoals(SUBJECT_ID)).thenReturn(java.util.List.of(goal));
    }

    private void stubSubject() {
        Subject subject = new Subject();
        subject.setSubjectId(SUBJECT_ID);
        subject.setName(SUBJECT_NAME);
        when(subjectMapper.selectById(SUBJECT_ID)).thenReturn(subject);
    }

    private DailyPlan buildDailyPlan(PlanStatus status) {
        DailyPlan plan = new DailyPlan();
        plan.setPlanId(PLAN_ID);
        plan.setGoalId(GOAL_ID);
        plan.setPlanDate(PLAN_DATE);
        plan.setStatus(status.getCode());
        plan.setTenantId(TENANT_ID);
        plan.setContent("[]");
        plan.setCreateTime(LocalDateTime.now());
        plan.setUpdateTime(LocalDateTime.now());
        return plan;
    }

    private StudentKnowledgeState buildStudentState(String kpId, BigDecimal mastery) {
        StudentKnowledgeState state = new StudentKnowledgeState();
        state.setStudentId(STUDENT_ID);
        state.setKnowledgePointId(kpId);
        state.setMasteryScore(mastery);
        state.setConfidence(new BigDecimal("0.5"));
        state.setKpName("测试知识点");
        state.setKpSubject(SUBJECT_NAME);
        state.setKpStatus("active");
        return state;
    }

    private KnowledgePoint buildKnowledgePoint(String kpId) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setKnowledgePointId(kpId);
        kp.setName("测试知识点");
        kp.setSubject(SUBJECT_NAME);
        kp.setStatus("active");
        return kp;
    }

    private SubmitAnswerRequest buildSubmitRequest(Long planId, Long questionId) {
        SubmitAnswerRequest request = new SubmitAnswerRequest();
        request.setPlanId(planId);
        request.setQuestionId(questionId);
        request.setQuestionSource("bank");
        request.setTaskType("review");
        request.setStudentAnswer("A");
        return request;
    }
}
