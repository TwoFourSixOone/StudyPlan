package com.jiang.service;

import com.jiang.agent.AdjustmentAgent;
import com.jiang.common.exception.AdjustmentException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.AdjustmentContextDTO;
import com.jiang.dto.PlanDiffDTO;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.AgentDegradationLogMapper;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.ErrorQuestionMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.PlanAdjustmentDiffMapper;
import com.jiang.mapper.PlanAdjustmentLogMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.AgentDegradationLog;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.Subject;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.PlanAdjustmentServiceImpl;
import com.jiang.vo.AdjustTriggerVO;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PlanAdjustmentService 单元测试
 *
 * <p>重点覆盖 2026-11-09 新增的上下文组装逻辑：</p>
 * <ul>
 *   <li>触发信号未命中 → 不调用 Agent，直接返回「暂无需调整」</li>
 *   <li>调用 Agent 前必须注入目标详情、未来 7 天计划与未覆盖知识点</li>
 *   <li>未覆盖知识点按 30 条截断，避免 Prompt 膨胀</li>
 *   <li>Agent 异常 → 降级为空调整，不落库</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanAdjustmentService 单元测试")
class PlanAdjustmentServiceTest {

    @Mock
    private PlanAdjustmentDiffMapper diffMapper;
    @Mock
    private PlanAdjustmentLogMapper logMapper;
    @Mock
    private SignalDetector signalDetector;
    @Mock
    private AdjustmentAgent adjustmentAgent;
    @Mock
    private PlanDiffService planDiffService;
    @Mock
    private ErrorQuestionMapper errorQuestionMapper;
    @Mock
    private LearningGoalMapper goalMapper;
    @Mock
    private SubjectMapper subjectMapper;
    @Mock
    private DailyPlanMapper planMapper;
    @Mock
    private StudentKnowledgeStateMapper knowledgeStateMapper;
    @Mock
    private AgentDegradationLogMapper degradationLogMapper;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private PlanAdjustmentServiceImpl planAdjustmentService;

    private MockedStatic<StudentContext> mockedStudentContext;

    private static final Long GOAL_ID = 100L;
    private static final Long STUDENT_ID = 1L;
    private static final Long SUBJECT_ID = 10L;
    private static final Long DIFF_ID = 999L;
    private static final String TENANT_ID = "default";
    private static final int UNCOVERED_KP_LIMIT = 30;

    @BeforeEach
    void setUp() {
        mockedStudentContext = mockStatic(StudentContext.class);
        mockedStudentContext.when(StudentContext::getTenantId).thenReturn(TENANT_ID);

        // @PostConstruct 不会在 Mockito 测试中执行，手动注入 TransactionTemplate
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        ReflectionTestUtils.setField(planAdjustmentService, "transactionTemplate", txTemplate);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @AfterEach
    void tearDown() {
        mockedStudentContext.close();
        StudentContext.clear();
    }

    @Test
    @DisplayName("无触发信号 → 返回「暂无需调整」，不调用 Agent")
    void should_returnNoAdjustment_when_noSignalHit() {
        when(diffMapper.countByGoalIdAndStatus(GOAL_ID, "pending", TENANT_ID)).thenReturn(0);
        when(signalDetector.detectSignals(GOAL_ID, TENANT_ID)).thenReturn(List.of());

        AdjustTriggerVO vo = planAdjustmentService.triggerAdjustment(GOAL_ID);

        assertFalse(vo.isAdjusted());
        assertEquals("暂无需调整", vo.getMessage());
        verifyNoInteractions(adjustmentAgent);
        verifyNoInteractions(goalMapper);
    }

    @Test
    @DisplayName("已有 pending diff → 抛 PLAN_ADJUST_CONFLICT")
    void should_throwConflict_when_pendingDiffExists() {
        when(diffMapper.countByGoalIdAndStatus(GOAL_ID, "pending", TENANT_ID)).thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.triggerAdjustment(GOAL_ID));

        assertEquals(ErrorCodes.PLAN_ADJUST_CONFLICT.getCode(), ex.getCode());
        verifyNoInteractions(adjustmentAgent);
    }

    @Test
    @DisplayName("信号命中 → 组装真实上下文（目标 + 未来计划 + 未覆盖知识点）后调用 Agent")
    void should_passRealContextToAgent_when_signalHit() {
        LocalDate today = LocalDate.now();
        stubSignalHit();
        stubGoalAndSubject();
        when(planMapper.selectPlansBetween(eq(GOAL_ID), eq(TENANT_ID), eq(today), eq(today.plusDays(6))))
                .thenReturn(List.of(
                        buildPlan(today, "pending", "[]"),
                        buildPlan(today.plusDays(1), "pending", "[{\"questionId\":1}]")));
        when(knowledgeStateMapper.selectUncoveredKpList(STUDENT_ID, SUBJECT_ID, TENANT_ID, UNCOVERED_KP_LIMIT))
                .thenReturn(List.of(kpRow("kp_001", "一元二次方程"), kpRow("kp_002", "勾股定理")));
        when(adjustmentAgent.generateDiff(anyList(), any(AdjustmentContextDTO.class), anyList()))
                .thenReturn(new PlanDiffDTO());
        when(planDiffService.saveDiff(eq(GOAL_ID), anyList(), any(PlanDiffDTO.class))).thenReturn(DIFF_ID);

        AdjustTriggerVO vo = planAdjustmentService.triggerAdjustment(GOAL_ID);

        ArgumentCaptor<AdjustmentContextDTO> captor = ArgumentCaptor.forClass(AdjustmentContextDTO.class);
        verify(adjustmentAgent).generateDiff(anyList(), captor.capture(), anyList());
        AdjustmentContextDTO context = captor.getValue();

        assertEquals(STUDENT_ID, context.getStudentId());
        assertEquals(SUBJECT_ID, context.getSubjectId());
        assertEquals("数学", context.getSubjectName());
        assertEquals(110, context.getTargetScore());
        assertEquals("本学期冲 110 分", context.getDirection());

        assertEquals(2, context.getFuturePlans().size());
        assertEquals(today, context.getFuturePlans().get(0).getDate());
        assertEquals("[]", context.getFuturePlans().get(0).getContent());

        assertEquals(2, context.getUncoveredKps().size());
        assertEquals("kp_001", context.getUncoveredKps().get(0).getKpId());
        assertEquals("一元二次方程", context.getUncoveredKps().get(0).getName());

        // 截断上限必须按常量 30 下发，防止 Prompt 膨胀
        verify(knowledgeStateMapper).selectUncoveredKpList(STUDENT_ID, SUBJECT_ID, TENANT_ID, UNCOVERED_KP_LIMIT);

        assertTrue(vo.isAdjusted());
        assertEquals(DIFF_ID, vo.getDiffId());
    }

    @Test
    @DisplayName("未来 7 天无计划 → 上下文 futurePlans 为空，仍正常调用 Agent")
    void should_passEmptyFuturePlans_when_noPlanScheduled() {
        stubSignalHit();
        stubGoalAndSubject();
        when(planMapper.selectPlansBetween(anyLong(), anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(knowledgeStateMapper.selectUncoveredKpList(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(adjustmentAgent.generateDiff(anyList(), any(AdjustmentContextDTO.class), anyList()))
                .thenReturn(new PlanDiffDTO());
        when(planDiffService.saveDiff(eq(GOAL_ID), anyList(), any(PlanDiffDTO.class))).thenReturn(DIFF_ID);

        planAdjustmentService.triggerAdjustment(GOAL_ID);

        ArgumentCaptor<AdjustmentContextDTO> captor = ArgumentCaptor.forClass(AdjustmentContextDTO.class);
        verify(adjustmentAgent).generateDiff(anyList(), captor.capture(), anyList());
        assertTrue(captor.getValue().getFuturePlans().isEmpty());
        assertTrue(captor.getValue().getUncoveredKps().isEmpty());
    }

    @Test
    @DisplayName("目标不存在 → 抛 LEARNING_GOAL_NOT_FOUND，不调用 Agent")
    void should_throwGoalNotFound_when_goalMissing() {
        stubSignalHit();
        when(goalMapper.selectById(GOAL_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.triggerAdjustment(GOAL_ID));

        assertEquals(ErrorCodes.LEARNING_GOAL_NOT_FOUND.getCode(), ex.getCode());
        verifyNoInteractions(adjustmentAgent);
    }

    @Test
    @DisplayName("Agent 抛 AdjustmentException → 降级为空调整，不落库")
    void should_degradeToNoAdjustment_when_agentThrows() {
        stubSignalHit();
        stubGoalAndSubject();
        when(planMapper.selectPlansBetween(anyLong(), anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(knowledgeStateMapper.selectUncoveredKpList(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(adjustmentAgent.generateDiff(anyList(), any(AdjustmentContextDTO.class), anyList()))
                .thenThrow(new AdjustmentException("agent timeout"));

        AdjustTriggerVO vo = planAdjustmentService.triggerAdjustment(GOAL_ID);

        assertFalse(vo.isAdjusted());
        assertEquals("暂无需调整", vo.getMessage());
        verifyNoInteractions(planDiffService);

        // 隐性约定 #12：降级事件必须落 agent_degradation_log
        ArgumentCaptor<AgentDegradationLog> logCaptor = ArgumentCaptor.forClass(AgentDegradationLog.class);
        verify(degradationLogMapper).insert(logCaptor.capture());
        AgentDegradationLog entry = logCaptor.getValue();
        assertEquals("adjustment", entry.getAgentType());
        assertEquals("return_empty_diff", entry.getDegradationAction());
        assertEquals(TENANT_ID, entry.getTenantId());
        assertTrue(entry.getInputSummary().contains("SIG-1"));
        assertTrue(entry.getFailureReason().contains("agent timeout"));
    }

    @Test
    @DisplayName("降级日志：敏感信息脱敏 + 超长截断至 500 字符")
    void should_sanitizeAndTruncate_when_degradationLogIsWritten() {
        stubSignalHit();
        stubGoalAndSubject();
        when(planMapper.selectPlansBetween(anyLong(), anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(knowledgeStateMapper.selectUncoveredKpList(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(List.of());
        String longMessage = "apiKey=sk-secret-123 " + "x".repeat(600);
        when(adjustmentAgent.generateDiff(anyList(), any(AdjustmentContextDTO.class), anyList()))
                .thenThrow(new AdjustmentException(longMessage));

        planAdjustmentService.triggerAdjustment(GOAL_ID);

        ArgumentCaptor<AgentDegradationLog> logCaptor = ArgumentCaptor.forClass(AgentDegradationLog.class);
        verify(degradationLogMapper).insert(logCaptor.capture());
        String failureReason = logCaptor.getValue().getFailureReason();

        assertFalse(failureReason.contains("sk-secret-123"), "API Key 必须被脱敏");
        assertTrue(failureReason.contains("apiKey=***"));
        assertTrue(failureReason.endsWith("...[truncated]"));
        assertTrue(failureReason.length() <= 520, "500 字符 + 省略标记");
    }

    // ==================== 辅助方法 ====================

    private void stubSignalHit() {
        when(diffMapper.countByGoalIdAndStatus(GOAL_ID, "pending", TENANT_ID)).thenReturn(0);
        TriggerSignalDTO signal = new TriggerSignalDTO();
        signal.setSignalType("SIG-1");
        signal.setDescription("最近 3 次作业正确率均低于 60%");
        when(signalDetector.detectSignals(GOAL_ID, TENANT_ID)).thenReturn(List.of(signal));
        when(errorQuestionMapper.selectCriticalErrors(any(LocalDate.class), eq(TENANT_ID))).thenReturn(List.of());
    }

    private void stubGoalAndSubject() {
        LearningGoal goal = new LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setStudentId(STUDENT_ID);
        goal.setSubjectId(SUBJECT_ID);
        goal.setTargetScore(110);
        goal.setDirection("本学期冲 110 分");
        goal.setStartDate(LocalDate.now().minusMonths(1));
        goal.setEndDate(LocalDate.now().plusMonths(2));
        goal.setStatus("active");
        when(goalMapper.selectById(GOAL_ID)).thenReturn(goal);

        Subject subject = new Subject();
        subject.setSubjectId(SUBJECT_ID);
        subject.setName("数学");
        when(subjectMapper.selectById(SUBJECT_ID)).thenReturn(subject);
    }

    private DailyPlan buildPlan(LocalDate date, String status, String content) {
        DailyPlan plan = new DailyPlan();
        plan.setGoalId(GOAL_ID);
        plan.setPlanDate(date);
        plan.setStatus(status);
        plan.setContent(content);
        plan.setTenantId(TENANT_ID);
        return plan;
    }

    private Map<String, Object> kpRow(String kpId, String kpName) {
        Map<String, Object> row = new HashMap<>();
        row.put("kpId", kpId);
        row.put("kpName", kpName);
        return row;
    }
}
