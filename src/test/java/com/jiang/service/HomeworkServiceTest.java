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
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.HomeworkSubmissionDetail;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.entity.Student;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.PlanStatus;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.BankQuestionPicker;
import com.jiang.service.impl.HomeworkServiceImpl;
import com.jiang.vo.GradeResultVO;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    private StudentMapper studentMapper;
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
    private BankQuestionPicker bankQuestionPicker;
    @Mock
    private ErrorQuestionMapper errorQuestionMapper;
    @Mock
    private PlatformTransactionManager transactionManager;
    /** 判分公共组件内部的 LLM 客户端（Change 2026-11-11：判分已委托 AnswerGradingService） */
    @Mock
    private com.jiang.client.AgentScopeClient agentScopeClient;

    @InjectMocks
    private HomeworkServiceImpl homeworkService;

    /** 真实判分组件 + mock LLM：保证"判分口径委托公共组件"这条路径被真实覆盖 */
    private com.jiang.service.impl.AnswerGradingServiceImpl answerGradingService;

    private MockedStatic<StudentContext> mockedStudentContext;

    /** 批改流程使用 aiExecutor 并行调用 AI，测试中注入真实线程池以覆盖该分支 */
    private ThreadPoolExecutor aiExecutor;

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

        // 批改流程通过 aiExecutor 提交并行任务，测试注入真实线程池
        aiExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        ReflectionTestUtils.setField(homeworkService, "aiExecutor", aiExecutor);

        // 判分已委托 AnswerGradingService（design D5）：注入真实实现 + mock LLM
        answerGradingService = new com.jiang.service.impl.AnswerGradingServiceImpl();
        ReflectionTestUtils.setField(answerGradingService, "agentScopeClient", agentScopeClient);
        ReflectionTestUtils.setField(answerGradingService, "gradingExecutor", aiExecutor);
        ReflectionTestUtils.setField(homeworkService, "answerGradingService", answerGradingService);
    }

    @AfterEach
    void tearDown() {
        mockedStudentContext.close();
        StudentContext.clear();
        if (aiExecutor != null) {
            aiExecutor.shutdownNow();
        }
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
    @DisplayName("生成作业：科目无已启用知识点 → SUBJECT_NO_ACTIVE_KP，且不写空作业")
    void should_throwNoActiveKp_when_noActiveKnowledgePoint() {
        stubActiveGoal();
        stubSubject();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);
        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of());
        // 知识点全部处于 pending_review（未校对）→ 查询已启用知识点返回空
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));
        assertEquals(ErrorCodes.SUBJECT_NO_ACTIVE_KP.getCode(), ex.getCode());
        verify(dailyPlanMapper, never()).insert(any(DailyPlan.class));
    }

    // ==================== 题库优先（产品规则 #3） ====================
    @Test
    @DisplayName("题库优先：知识回顾命中 1 道题库题，Agent 只补剩余数量")
    void should_preferBankQuestions_when_reviewQuotaPartiallyFilled() {
        stubActiveGoal();
        stubSubject();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);
        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of(buildStudentState("kp_001", new BigDecimal("0.5"))));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buildKnowledgePoint("kp_001")));
        when(errorQuestionService.getDueErrorQuestions(eq(STUDENT_ID), eq(TENANT_ID), eq(PLAN_DATE)))
                .thenReturn(Collections.emptyMap());
        lenient().when(errorQuestionMapper.selectActiveErrorCountGroupByKp(eq(STUDENT_ID), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());

        // 题库命中 1 道
        QuestionBank bankQuestion = new QuestionBank();
        bankQuestion.setQuestionId(99L);
        bankQuestion.setKnowledgePointId("kp_001");
        when(bankQuestionPicker.pick(eq("kp_001"), eq(TENANT_ID), eq(15),
                nullable(Integer.class), nullable(String.class), anySet()))
                .thenReturn(List.of(bankQuestion));

        // Agent 只补 14 道
        List<DailyPlanQuestionRef> agentRefs = List.of(new DailyPlanQuestionRef(2L, "bank", "review"));
        when(questionGenAgent.generate(anyList(), eq(14)))
                .thenReturn(new QuestionGenAgent.QuestionGenResult(agentRefs, Collections.emptyList()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DailyPlanQuestionRef>> refsCaptor = ArgumentCaptor.forClass(List.class);
        when(contentParser.serialize(refsCaptor.capture())).thenReturn("[]");

        doAnswer(invocation -> {
            DailyPlan p = invocation.getArgument(0);
            p.setPlanId(PLAN_ID);
            return 1;
        }).when(dailyPlanMapper).insert(any(DailyPlan.class));
        when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        assertDoesNotThrow(() -> homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));

        verify(questionGenAgent).generate(anyList(), eq(14));
        List<DailyPlanQuestionRef> serialized = refsCaptor.getValue();
        assertTrue(serialized.stream().anyMatch(r -> Long.valueOf(99L).equals(r.getQuestionId())
                        && "bank".equals(r.getQuestionSource())
                        && "review".equals(r.getTaskType())),
                "题库命中的题目应以 bank/review 写入计划");
    }

    @Test
    @DisplayName("生成作业：日期不在目标周期内 → HOMEWORK_DATE_OUT_OF_RANGE，且不写计划")
    void should_throwOutOfRange_when_planDateOutsideGoal() {
        stubActiveGoal();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID,
                        PLAN_DATE.minusDays(30)));
        assertEquals(ErrorCodes.HOMEWORK_DATE_OUT_OF_RANGE.getCode(), ex.getCode());
        verify(dailyPlanMapper, never()).insert(any(DailyPlan.class));
    }

    // ==================== 调整策略（产品规则 #5） ====================

    @Test
    @DisplayName("调整策略：暂缓知识点不参与新题，其余知识点正常出题")
    void should_excludePausedKp_when_goalHasPausedKps() {
        stubActiveGoal();
        stubSubject();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);

        LearningGoal goal = new LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setTenantId(TENANT_ID);
        goal.setPausedKpIds("[\"kp_002\"]");
        when(learningGoalMapper.selectById(GOAL_ID)).thenReturn(goal);

        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of(
                        buildStudentState("kp_001", new BigDecimal("0.5")),
                        buildStudentState("kp_002", new BigDecimal("0.5"))));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buildKnowledgePoint("kp_001"), buildKnowledgePoint("kp_002")));
        when(errorQuestionService.getDueErrorQuestions(eq(STUDENT_ID), eq(TENANT_ID), eq(PLAN_DATE)))
                .thenReturn(Collections.emptyMap());
        lenient().when(errorQuestionMapper.selectActiveErrorCountGroupByKp(eq(STUDENT_ID), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());

        QuestionGenAgent.QuestionGenResult agentResult = new QuestionGenAgent.QuestionGenResult(
                List.of(new DailyPlanQuestionRef(1L, "bank", "review")), Collections.emptyList());
        when(questionGenAgent.generate(anyList(), anyInt())).thenReturn(agentResult);
        when(contentParser.serialize(anyList())).thenReturn("[]");

        doAnswer(invocation -> {
            DailyPlan p = invocation.getArgument(0);
            p.setPlanId(PLAN_ID);
            return 1;
        }).when(dailyPlanMapper).insert(any(DailyPlan.class));
        when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgePointWithMastery>> kpCaptor = ArgumentCaptor.forClass(List.class);
        verify(questionGenAgent).generate(kpCaptor.capture(), anyInt());
        List<KnowledgePointWithMastery> passed = kpCaptor.getValue();
        assertEquals(1, passed.size(), "暂缓知识点不应传给出题 Agent");
        assertEquals("kp_001", passed.get(0).getKnowledgePointId());
    }

    // ==================== 年级过滤（产品规则 #11） ====================

    @Test
    @DisplayName("年级过滤：只把匹配年级的知识点传给 Agent")
    void should_filterKnowledgePoints_when_studentGradePresent() {
        stubActiveGoal();
        stubSubject();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);
        when(studentMapper.selectById(STUDENT_ID)).thenReturn(buildStudent("3"));
        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of(
                        buildStudentState("kp_001", new BigDecimal("0.5")),
                        buildStudentState("kp_002", new BigDecimal("0.4"))));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(
                        buildKnowledgePoint("kp_001", "3"),
                        buildKnowledgePoint("kp_002", "7-9")));

        List<DailyPlanQuestionRef> refs = List.of(new DailyPlanQuestionRef(1L, "bank", "review"));
        stubGenerateCommon(refs);

        homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgePointWithMastery>> captor = ArgumentCaptor.forClass(List.class);
        verify(questionGenAgent).generate(captor.capture(), eq(15));
        assertEquals(1, captor.getValue().size(), "只有三年级知识点应传给 Agent");
        assertEquals("kp_001", captor.getValue().get(0).getKnowledgePointId());
    }

    @Test
    @DisplayName("年级过滤：全部被过滤时整体降级，不把内容池筛空")
    void should_fallbackToUnfiltered_when_allKpsFilteredOut() {
        stubActiveGoal();
        stubSubject();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);
        when(studentMapper.selectById(STUDENT_ID)).thenReturn(buildStudent("3"));
        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of(
                        buildStudentState("kp_001", new BigDecimal("0.5")),
                        buildStudentState("kp_002", new BigDecimal("0.4"))));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(
                        buildKnowledgePoint("kp_001", "7-9"),
                        buildKnowledgePoint("kp_002", "10-12")));

        List<DailyPlanQuestionRef> refs = List.of(new DailyPlanQuestionRef(1L, "bank", "review"));
        stubGenerateCommon(refs);

        homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgePointWithMastery>> captor = ArgumentCaptor.forClass(List.class);
        verify(questionGenAgent).generate(captor.capture(), eq(15));
        assertEquals(2, captor.getValue().size(), "过滤后为空应降级为不过滤");
    }

    @Test
    @DisplayName("年级过滤：学生无年级 → 不过滤，知识点原样传入")
    void should_skipGradeFilter_when_studentGradeAbsent() {
        stubActiveGoal();
        stubSubject();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);
        // 不 stub studentMapper → 学生年级为 null
        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of(
                        buildStudentState("kp_001", new BigDecimal("0.5")),
                        buildStudentState("kp_002", new BigDecimal("0.4"))));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(
                        buildKnowledgePoint("kp_001", "3"),
                        buildKnowledgePoint("kp_002", "7-9")));

        List<DailyPlanQuestionRef> refs = List.of(new DailyPlanQuestionRef(1L, "bank", "review"));
        stubGenerateCommon(refs);

        homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgePointWithMastery>> captor = ArgumentCaptor.forClass(List.class);
        verify(questionGenAgent).generate(captor.capture(), eq(15));
        assertEquals(2, captor.getValue().size());
    }

    /**
     * 生成成功路径的公共 stub（Agent 成功 + 落库）
     */
    private void stubGenerateCommon(List<DailyPlanQuestionRef> refs) {
        QuestionGenAgent.QuestionGenResult agentResult =
                new QuestionGenAgent.QuestionGenResult(refs, Collections.emptyList());
        when(questionGenAgent.generate(anyList(), eq(15))).thenReturn(agentResult);
        when(errorQuestionService.getDueErrorQuestions(eq(STUDENT_ID), eq(TENANT_ID), eq(PLAN_DATE)))
                .thenReturn(Collections.emptyMap());
        lenient().when(errorQuestionMapper.selectActiveErrorCountGroupByKp(eq(STUDENT_ID), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());
        when(contentParser.serialize(refs)).thenReturn("[{\"questionId\":1}]");
        doAnswer(invocation -> {
            DailyPlan p = invocation.getArgument(0);
            p.setPlanId(PLAN_ID);
            return 1;
        }).when(dailyPlanMapper).insert(any(DailyPlan.class));
        when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
    }

    private Student buildStudent(String grade) {
        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setGrade(grade);
        return student;
    }

    private KnowledgePoint buildKnowledgePoint(String kpId, String gradeRange) {
        KnowledgePoint kp = buildKnowledgePoint(kpId);
        kp.setGradeRange(gradeRange);
        return kp;
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
        when(degradationHandler.generateDefaultPlan(anyList(), eq(TENANT_ID), any())).thenReturn(defaultRefs);
        when(contentParser.serialize(defaultRefs)).thenReturn("[{\"questionId\":5}]");
        when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        assertDoesNotThrow(() -> homeworkService.generateDailyHomework(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));

        verify(degradationHandler).logDegradation(anyString(), anyString(), eq(TENANT_ID));
        verify(degradationHandler).generateDefaultPlan(anyList(), eq(TENANT_ID), any());
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
        when(degradationHandler.generateDefaultPlan(anyList(), eq(TENANT_ID), any()))
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
        verify(degradationHandler).generateDefaultPlan(anyList(), eq(TENANT_ID), any());
        // 计划正常落库
        verify(dailyPlanMapper).insert(any(DailyPlan.class));
    }

    // ==================== getHomeworkByDate ====================

    @Test
    @DisplayName("查询作业：无计划 → 返回空列表")
    void should_returnEmpty_when_noPlan() {
        when(dailyPlanMapper.selectBySubjectAndDate(SUBJECT_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);

        List<HomeworkVO> result = homeworkService.getHomeworkByDate(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("查询作业：有计划 + 题库题 → 正确组装 HomeworkVO")
    void should_returnHomeworkList_when_planExists() {
        DailyPlan plan = buildDailyPlan(PlanStatus.PENDING);
        when(dailyPlanMapper.selectBySubjectAndDate(SUBJECT_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);

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

    @Test
    @DisplayName("查询作业：生成题题干与选项中的 LaTeX 被清洗为纯文本（隐性约定 #24）")
    void should_sanitizeGeneratedQuestionText() {
        DailyPlan plan = buildDailyPlan(PlanStatus.PENDING);
        when(dailyPlanMapper.selectBySubjectAndDate(SUBJECT_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);

        List<DailyPlanQuestionRef> refs = List.of(new DailyPlanQuestionRef(7L, "generated", "similar"));
        when(contentParser.parse(plan.getContent())).thenReturn(refs);

        GeneratedQuestion gq = new GeneratedQuestion();
        gq.setGeneratedQuestionId(7L);
        gq.setKnowledgePointId("kp_001");
        gq.setDifficulty("standard");
        gq.setContent("{\"stem\":\"求 $\\\\sqrt{10}$ 的值\","
                + "\"choices\":[{\"label\":\"A\",\"text\":\"$\\\\sqrt{10}$\"},"
                + "{\"label\":\"B\",\"text\":\"$\\\\frac{1}{2}$\"}],"
                + "\"answer\":\"A\",\"formatType\":\"choice\",\"difficulty\":\"standard\"}");
        when(generatedQuestionMapper.selectBatchIds(anyCollection())).thenReturn(List.of(gq));
        when(submissionMapper.selectByPlanId(PLAN_ID, TENANT_ID)).thenReturn(Collections.emptyList());

        List<HomeworkVO> result = homeworkService.getHomeworkByDate(STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE);

        assertEquals(1, result.size());
        assertEquals("求 √(10) 的值", result.get(0).getQuestionContent());
        assertEquals(List.of("√(10)", "(1)/(2)"), result.get(0).getChoices());
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

    // ==================== 试卷错题回流（v3 / tasks D5） ====================

    private com.jiang.vo.DueErrorQuestionVO dueError(Long questionId) {
        return com.jiang.vo.DueErrorQuestionVO.builder()
                .errorQuestionId(Math.abs(questionId))
                .questionId(questionId)
                .knowledgePointId("kp_001")
                .currentStage(1)
                .nextDueDate(PLAN_DATE)
                .build();
    }

    private java.util.Map<String, Object> errorCountRow(String kpId, int count) {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("knowledge_point_id", kpId);
        row.put("error_count", count);
        return row;
    }

    /**
     * 生成作业公共 stub（含到期错题与活跃错题计数）
     */
    private void stubGenerationWithErrors(java.util.List<com.jiang.vo.DueErrorQuestionVO> dueErrors,
                                          int rawActiveCount) {
        stubActiveGoal();
        stubSubject();
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(null);
        when(stateMapper.selectActiveStatesWithKpInfo(STUDENT_ID, TENANT_ID, SUBJECT_NAME, false))
                .thenReturn(List.of(buildStudentState("kp_001", new BigDecimal("0.5"))));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buildKnowledgePoint("kp_001")));

        java.util.Map<String, java.util.List<com.jiang.vo.DueErrorQuestionVO>> dueMap =
                new java.util.LinkedHashMap<>();
        dueMap.put("kp_001", dueErrors);
        when(errorQuestionService.getDueErrorQuestions(eq(STUDENT_ID), eq(TENANT_ID), eq(PLAN_DATE)))
                .thenReturn(dueMap);
        lenient().when(errorQuestionMapper.selectActiveErrorCountGroupByKp(eq(STUDENT_ID), eq(TENANT_ID)))
                .thenReturn(java.util.List.of(errorCountRow("kp_001", rawActiveCount)));
        doAnswer(invocation -> {
            DailyPlan p = invocation.getArgument(0);
            p.setPlanId(PLAN_ID);
            return 1;
        }).when(dailyPlanMapper).insert(any(DailyPlan.class));
        lenient().when(dailyPlanMapper.updateContentAndStatus(eq(PLAN_ID), anyString(), anyString(),
                anyString(), anyString())).thenReturn(1);
    }

    @Test
    @DisplayName("D5-T1：某 kp 5 道试卷错题 → 作业生成不失败，进作业的 redo ≤3（其余顺延）")
    void should_notFailGeneration_when_kpHasFiveExamWrongQuestions() {
        stubGenerationWithErrors(
                List.of(dueError(-501L), dueError(-502L), dueError(-503L))
                , 5);
        lenient().when(bankQuestionPicker.pick(anyString(), anyString(), anyInt(),
                nullable(Integer.class), nullable(String.class), anySet())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DailyPlanQuestionRef>> refsCaptor = ArgumentCaptor.forClass(List.class);
        when(contentParser.serialize(refsCaptor.capture())).thenReturn("[]");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> homeworkService.generateDailyHomework(
                STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));

        List<DailyPlanQuestionRef> refs = refsCaptor.getValue();
        long examRedo = refs.stream()
                .filter(r -> "exam".equals(r.getQuestionSource()) && "redo".equals(r.getTaskType()))
                .count();
        assertEquals(3, examRedo, "每 kp 每日 redo ≤3（LIMIT 3），5 道里其余 2 道次日顺延");
    }

    @Test
    @DisplayName("D5-T2：题库 3 + 试卷 3 = 6 → 传给配额计算器的 map 按 kp 合计夹取到 KP_MAX=4")
    void should_clampKpErrorCountToKpMax_byTotal() {
        stubGenerationWithErrors(
                List.of(dueError(101L), dueError(102L), dueError(103L),
                        dueError(-501L), dueError(-502L), dueError(-503L)),
                6);
        lenient().when(bankQuestionPicker.pick(anyString(), anyString(), anyInt(),
                nullable(Integer.class), nullable(String.class), anySet())).thenReturn(List.of());
        when(contentParser.serialize(anyList())).thenReturn("[]");

        java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Integer>> capturedMap =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (MockedStatic<com.jiang.common.util.HomeworkQuotaCalculator> mocked =
                     mockStatic(com.jiang.common.util.HomeworkQuotaCalculator.class,
                             org.mockito.Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> com.jiang.common.util.HomeworkQuotaCalculator.calculate(
                            anyInt(), anySet(), anyMap(), anyMap(), anyInt()))
                    .thenAnswer(invocation -> {
                        capturedMap.set(invocation.getArgument(3));
                        return invocation.callRealMethod();
                    });

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> homeworkService.generateDailyHomework(
                    STUDENT_ID, TENANT_ID, SUBJECT_ID, PLAN_DATE));
        }

        assertNotNull(capturedMap.get());
        assertEquals(4, capturedMap.get().get("kp_001"),
                "按 kp 合计夹取（3 题库 + 3 试卷 = 6 → 4），不是各算 4");
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
        stubStudentGrade();
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
        stubStudentGrade();
        DailyPlan plan = buildDailyPlan(PlanStatus.IN_PROGRESS);
        when(dailyPlanMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, PLAN_DATE)).thenReturn(plan);
        when(submissionMapper.selectByPlanId(PLAN_ID, TENANT_ID)).thenReturn(Collections.emptyList());
        when(contentParser.parse(plan.getContent())).thenReturn(Collections.emptyList());

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
                .startDate(PLAN_DATE.minusDays(10)).endDate(PLAN_DATE.plusMonths(3))
                .build();
        when(goalService.getActiveGoals(SUBJECT_ID)).thenReturn(java.util.List.of(goal));
    }

    /**
     * 批改流程需要读取学生年级（用于 AI 解析），未 mock 时 studentMapper 为 null 会 NPE
     */
    private void stubStudentGrade() {
        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        student.setGrade("高中");
        when(studentMapper.selectById(STUDENT_ID)).thenReturn(student);
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
