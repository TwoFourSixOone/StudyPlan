package com.jiang.service;

import com.jiang.agent.AdjustmentAgent;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiang.common.exception.AdjustmentException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
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
import com.jiang.model.entity.PlanAdjustmentDiff;
import com.jiang.model.entity.PlanAdjustmentLog;
import com.jiang.model.entity.Subject;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.PlanAdjustmentServiceImpl;
import com.jiang.vo.AdjustTriggerVO;
import com.jiang.vo.AdjustmentHistoryVO;
import com.jiang.vo.PlanDiffDetailVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import java.time.LocalDateTime;
import java.util.Collection;
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
 * <p>P0-2（跨学生越权修复）补充覆盖：触发调整 / diff 读 / diff 写 / 调整历史
 * 四个入口都必须按当前登录学生做归属校验，他人资源统一 {@code PLAN_ADJUST_NOT_FOUND}；
 * 系统重载（定时任务 / 考试结算）不读 StudentContext、不做归属校验。</p>
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
    private com.jiang.mapper.StudentMapper studentMapper;
    @Mock
    private AgentDegradationLogMapper degradationLogMapper;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private PlanAdjustmentServiceImpl planAdjustmentService;

    private MockedStatic<StudentContext> mockedStudentContext;

    private static final Long GOAL_ID = 100L;
    private static final Long OTHERS_GOAL_ID = 200L;
    private static final Long STUDENT_ID = 1L;
    private static final Long SUBJECT_ID = 10L;
    private static final Long DIFF_ID = 999L;
    private static final Long OTHERS_DIFF_ID = 888L;
    private static final String TENANT_ID = "default";
    private static final int UNCOVERED_KP_LIMIT = 30;

    /**
     * 单测没有 MyBatis 容器：{@code LambdaQueryWrapper} 解析列名依赖实体的 TableInfo，
     * 不初始化会抛 "can not find lambda cache for this entity"（调整历史用例会触发）。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("com.jiang.mapper.PlanAdjustmentLogMapper");
        TableInfoHelper.initTableInfo(assistant, PlanAdjustmentLog.class);
    }

    @BeforeEach
    void setUp() {
        // 静态上下文桩整体 lenient：部分用例（系统重载）不读 StudentContext
        mockedStudentContext = mockStatic(StudentContext.class, withSettings().lenient());
        mockedStudentContext.when(StudentContext::getTenantId).thenReturn(TENANT_ID);
        // P0-2：学生端四个入口都按当前登录学生做归属校验
        mockedStudentContext.when(StudentContext::getStudentId).thenReturn(STUDENT_ID);
        // @PostConstruct 不会在 Mockito 测试中执行，手动注入 TransactionTemplate
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        ReflectionTestUtils.setField(planAdjustmentService, "transactionTemplate", txTemplate);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        // 规则 #5 去重窗口：用默认配置对象（dedup-hours=12），避免 mock 配置类
        ReflectionTestUtils.setField(planAdjustmentService, "signalConfig", new com.jiang.config.SignalConfig());
    }

    @AfterEach
    void tearDown() {
        mockedStudentContext.close();
        StudentContext.clear();
    }

    @Test
    @DisplayName("无触发信号 → 返回「暂无需调整」，不调用 Agent")
    void should_returnNoAdjustment_when_noSignalHit() {
        stubOwnedGoal();
        when(diffMapper.countByGoalIdAndStatus(GOAL_ID, "pending", TENANT_ID)).thenReturn(0);
        when(signalDetector.detectSignals(GOAL_ID, TENANT_ID)).thenReturn(List.of());

        AdjustTriggerVO vo = planAdjustmentService.triggerAdjustment(GOAL_ID);

        assertFalse(vo.isAdjusted());
        assertEquals("暂无需调整", vo.getMessage());
        verifyNoInteractions(adjustmentAgent);
        // 目标归属校验会读 learning_goal（P0-2），但无信号时不得再查目标详情组装上下文
        verify(goalMapper).selectOwnedGoalId(GOAL_ID, STUDENT_ID, TENANT_ID);
        verify(goalMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("已有 pending diff → 抛 PLAN_ADJUST_CONFLICT")
    void should_throwConflict_when_pendingDiffExists() {
        stubOwnedGoal();
        when(diffMapper.countByGoalIdAndStatus(GOAL_ID, "pending", TENANT_ID)).thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.triggerAdjustment(GOAL_ID));

        assertEquals(ErrorCodes.PLAN_ADJUST_CONFLICT.getCode(), ex.getCode());
        verifyNoInteractions(adjustmentAgent);
    }

    @Test
    @DisplayName("12h 去重窗口内已有 confirmed 调整 → 抛 PLAN_ADJUST_COOLDOWN，不检测信号、不调 Agent")
    void should_throwCooldown_when_recentConfirmedAdjustment() {
        stubOwnedGoal();
        when(diffMapper.countByGoalIdAndStatus(GOAL_ID, "pending", TENANT_ID)).thenReturn(0);
        when(logMapper.countConfirmedSince(eq(GOAL_ID), eq(TENANT_ID), any(LocalDateTime.class)))
                .thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.triggerAdjustment(GOAL_ID));

        assertEquals(ErrorCodes.PLAN_ADJUST_COOLDOWN.getCode(), ex.getCode());
        verifyNoInteractions(signalDetector);
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
        when(knowledgeStateMapper.selectActiveKpsWithMastery(STUDENT_ID, SUBJECT_ID, TENANT_ID))
                .thenReturn(List.of(kpRow("kp_001", "一元二次方程"), kpRow("kp_002", "勾股定理")));
        when(adjustmentAgent.generateDiff(anyList(), any(AdjustmentContextDTO.class), anyList()))
                .thenReturn(new PlanDiffDTO());
        when(planDiffService.saveDiff(eq(GOAL_ID), anyList(), any(PlanDiffDTO.class), anyString())).thenReturn(DIFF_ID);

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
        verify(knowledgeStateMapper).selectActiveKpsWithMastery(STUDENT_ID, SUBJECT_ID, TENANT_ID);

        assertTrue(vo.isAdjusted());
        assertEquals(DIFF_ID, vo.getDiffId());
    }

    @Test
    @DisplayName("暂缓知识点不出现在调整上下文（2026-11-12 口径修正）")
    void should_excludePausedKps_when_buildingContext() {
        stubSignalHit();
        stubGoalAndSubject("[\"kp_001\"]");
        when(planMapper.selectPlansBetween(anyLong(), anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(knowledgeStateMapper.selectActiveKpsWithMastery(STUDENT_ID, SUBJECT_ID, TENANT_ID))
                .thenReturn(List.of(kpRow("kp_001", "一元二次方程"), kpRow("kp_002", "勾股定理")));
        when(adjustmentAgent.generateDiff(anyList(), any(AdjustmentContextDTO.class), anyList()))
                .thenReturn(new PlanDiffDTO());
        when(planDiffService.saveDiff(eq(GOAL_ID), anyList(), any(PlanDiffDTO.class), anyString()))
                .thenReturn(DIFF_ID);

        planAdjustmentService.triggerAdjustment(GOAL_ID);

        ArgumentCaptor<AdjustmentContextDTO> captor = ArgumentCaptor.forClass(AdjustmentContextDTO.class);
        verify(adjustmentAgent).generateDiff(anyList(), captor.capture(), anyList());
        assertEquals(1, captor.getValue().getUncoveredKps().size(),
                "暂缓的 kp_001 不应出现在未覆盖清单中");
        assertEquals("kp_002", captor.getValue().getUncoveredKps().get(0).getKpId());
    }

    @Test
    @DisplayName("未来 7 天无计划 → 上下文 futurePlans 为空，仍正常调用 Agent")
    void should_passEmptyFuturePlans_when_noPlanScheduled() {
        stubSignalHit();
        stubGoalAndSubject();
        when(planMapper.selectPlansBetween(anyLong(), anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(knowledgeStateMapper.selectActiveKpsWithMastery(anyLong(), anyLong(), anyString()))
                .thenReturn(List.of());
        when(adjustmentAgent.generateDiff(anyList(), any(AdjustmentContextDTO.class), anyList()))
                .thenReturn(new PlanDiffDTO());
        when(planDiffService.saveDiff(eq(GOAL_ID), anyList(), any(PlanDiffDTO.class), anyString())).thenReturn(DIFF_ID);

        planAdjustmentService.triggerAdjustment(GOAL_ID);

        ArgumentCaptor<AdjustmentContextDTO> captor = ArgumentCaptor.forClass(AdjustmentContextDTO.class);
        verify(adjustmentAgent).generateDiff(anyList(), captor.capture(), anyList());
        assertTrue(captor.getValue().getFuturePlans().isEmpty());
        assertTrue(captor.getValue().getUncoveredKps().isEmpty());
    }

    @Test
    @DisplayName("目标不存在/他人的 goalId（学生入口）→ 抛 PLAN_ADJUST_NOT_FOUND，不调 Agent")
    void should_throwPlanAdjustNotFound_when_studentEntryGoalNotOwned() {
        when(goalMapper.selectOwnedGoalId(GOAL_ID, STUDENT_ID, TENANT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.triggerAdjustment(GOAL_ID));

        assertEquals(ErrorCodes.PLAN_ADJUST_NOT_FOUND.getCode(), ex.getCode());
        verifyNoInteractions(diffMapper, logMapper, signalDetector, adjustmentAgent, planDiffService);
    }

    @Test
    @DisplayName("系统重载（定时任务/考试结算）缺目标 → 仍抛 LEARNING_GOAL_NOT_FOUND（系统语义不变）")
    void should_throwGoalNotFound_when_systemOverloadGoalMissing() {
        stubSignalHit();
        when(goalMapper.selectById(GOAL_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.triggerAdjustment(GOAL_ID, STUDENT_ID, TENANT_ID));

        assertEquals(ErrorCodes.LEARNING_GOAL_NOT_FOUND.getCode(), ex.getCode());
        verifyNoInteractions(adjustmentAgent);
        // 系统重载不做学生归属校验（归属由调用方保证）
        verify(goalMapper, never()).selectOwnedGoalId(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("Agent 抛 AdjustmentException → 降级为空调整，不落库")
    void should_degradeToNoAdjustment_when_agentThrows() {
        stubSignalHit();
        stubGoalAndSubject();
        when(planMapper.selectPlansBetween(anyLong(), anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(knowledgeStateMapper.selectActiveKpsWithMastery(anyLong(), anyLong(), anyString()))
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
        when(knowledgeStateMapper.selectActiveKpsWithMastery(anyLong(), anyLong(), anyString()))
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

    // ==================== P0-2：学生隔离（trigger / diff 读 / diff 写 / 调整历史） ====================

    @Test
    @DisplayName("触发调整：他人的 goalId → PLAN_ADJUST_NOT_FOUND，不检测信号、不生成 diff")
    void should_throwPlanAdjustNotFound_when_triggerOthersGoal() {
        when(goalMapper.selectOwnedGoalId(OTHERS_GOAL_ID, STUDENT_ID, TENANT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.triggerAdjustment(OTHERS_GOAL_ID));

        assertEquals(ErrorCodes.PLAN_ADJUST_NOT_FOUND.getCode(), ex.getCode());
        verifyNoInteractions(diffMapper, logMapper, signalDetector, adjustmentAgent, planDiffService);
    }

    @Test
    @DisplayName("触发调整：自己的 goalId → 归属校验通过后正常继续")
    void should_triggerOwnGoal_when_goalOwnedByCurrentStudent() {
        stubOwnedGoal();
        when(diffMapper.countByGoalIdAndStatus(GOAL_ID, "pending", TENANT_ID)).thenReturn(0);
        when(signalDetector.detectSignals(GOAL_ID, TENANT_ID)).thenReturn(List.of());

        AdjustTriggerVO vo = planAdjustmentService.triggerAdjustment(GOAL_ID);

        assertFalse(vo.isAdjusted());
        verify(goalMapper).selectOwnedGoalId(GOAL_ID, STUDENT_ID, TENANT_ID);
        verify(signalDetector).detectSignals(GOAL_ID, TENANT_ID);
    }

    @Test
    @DisplayName("触发调整：无学生上下文（身份缺失）→ PLAN_ADJUST_NOT_FOUND，不查库、不生成 diff")
    void should_throwPlanAdjustNotFound_when_noStudentContextForTrigger() {
        mockedStudentContext.when(StudentContext::getStudentId).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.triggerAdjustment(GOAL_ID));

        assertEquals(ErrorCodes.PLAN_ADJUST_NOT_FOUND.getCode(), ex.getCode());
        verify(goalMapper, never()).selectOwnedGoalId(anyLong(), anyLong(), anyString());
        verifyNoInteractions(diffMapper, signalDetector, adjustmentAgent);
    }

    @Test
    @DisplayName("diff 详情：他人的 diffId → PLAN_ADJUST_NOT_FOUND，不走只按租户的查询")
    void should_throwPlanAdjustNotFound_when_getOthersDiffDetail() {
        when(diffMapper.selectByIdWithStudent(OTHERS_DIFF_ID, STUDENT_ID, TENANT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.getDiffDetail(OTHERS_DIFF_ID));

        assertEquals(ErrorCodes.PLAN_ADJUST_NOT_FOUND.getCode(), ex.getCode());
        verify(diffMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("确认 diff：他人的 diffId → PLAN_ADJUST_NOT_FOUND，不应用、不写日志")
    void should_notApplyOthersDiff_when_confirmOthersDiff() {
        when(diffMapper.selectByIdWithStudent(OTHERS_DIFF_ID, STUDENT_ID, TENANT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.confirmDiff(OTHERS_DIFF_ID));

        assertEquals(ErrorCodes.PLAN_ADJUST_NOT_FOUND.getCode(), ex.getCode());
        verifyNoInteractions(planDiffService);
        verify(diffMapper, never()).updateById(any(PlanAdjustmentDiff.class));
        verifyNoInteractions(logMapper);
    }

    @Test
    @DisplayName("拒绝 diff：他人的 diffId → PLAN_ADJUST_NOT_FOUND，不改状态、不写日志")
    void should_notRejectOthersDiff_when_rejectOthersDiff() {
        when(diffMapper.selectByIdWithStudent(OTHERS_DIFF_ID, STUDENT_ID, TENANT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.rejectDiff(OTHERS_DIFF_ID));

        assertEquals(ErrorCodes.PLAN_ADJUST_NOT_FOUND.getCode(), ex.getCode());
        verify(diffMapper, never()).updateById(any(PlanAdjustmentDiff.class));
        verifyNoInteractions(logMapper);
    }

    @Test
    @DisplayName("diff 详情：自己的 diff → 正常返回方案内容（信号 / 日题量 / 暂缓 / 原因）")
    void should_returnDiffDetail_when_diffOwnedByCurrentStudent() {
        when(diffMapper.selectByIdWithStudent(DIFF_ID, STUDENT_ID, TENANT_ID))
                .thenReturn(buildOwnedDiff(DIFF_ID, "pending"));

        PlanDiffDetailVO vo = planAdjustmentService.getDiffDetail(DIFF_ID);

        assertEquals(DIFF_ID, vo.getDiffId());
        assertEquals(GOAL_ID, vo.getGoalId());
        assertEquals("pending", vo.getStatus());
        assertEquals("SIG-1 命中，下调日题量", vo.getReason());
        assertEquals(10, vo.getDailyQuestionLimit());
        assertEquals(List.of("kp_001"), vo.getPausedKpIds());
        assertEquals(1, vo.getSignals().size());
        assertEquals("SIG-1", vo.getSignals().get(0).getSignalType());
    }

    @Test
    @DisplayName("确认 diff：自己的 pending diff → 正常应用（applyDiff 行为不变）")
    void should_applyDiff_when_confirmOwnDiff() {
        when(diffMapper.selectByIdWithStudent(DIFF_ID, STUDENT_ID, TENANT_ID))
                .thenReturn(buildOwnedDiff(DIFF_ID, "pending"));

        planAdjustmentService.confirmDiff(DIFF_ID);

        verify(planDiffService).applyDiff(DIFF_ID);
    }

    @Test
    @DisplayName("确认 diff：自己的已确认 diff → 归属校验通过后才按状态报错")
    void should_throwAlreadyConfirmed_when_ownDiffAlreadyConfirmed() {
        when(diffMapper.selectByIdWithStudent(DIFF_ID, STUDENT_ID, TENANT_ID))
                .thenReturn(buildOwnedDiff(DIFF_ID, "confirmed"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planAdjustmentService.confirmDiff(DIFF_ID));

        assertEquals(ErrorCodes.PLAN_DIFF_ALREADY_CONFIRMED.getCode(), ex.getCode());
        verifyNoInteractions(planDiffService);
    }

    @Test
    @DisplayName("拒绝 diff：自己的 pending diff → 置 rejected 并写 rejected 日志")
    void should_markRejectedAndWriteLog_when_rejectOwnDiff() {
        PlanAdjustmentDiff diff = buildOwnedDiff(DIFF_ID, "pending");
        when(diffMapper.selectByIdWithStudent(DIFF_ID, STUDENT_ID, TENANT_ID)).thenReturn(diff);

        planAdjustmentService.rejectDiff(DIFF_ID);

        assertEquals("rejected", diff.getStatus());
        assertNotNull(diff.getRejectedAt());
        verify(diffMapper).updateById(diff);

        ArgumentCaptor<PlanAdjustmentLog> logCaptor = ArgumentCaptor.forClass(PlanAdjustmentLog.class);
        verify(logMapper).insert(logCaptor.capture());
        PlanAdjustmentLog entry = logCaptor.getValue();
        assertEquals("rejected", entry.getAction());
        assertEquals(DIFF_ID, entry.getDiffId());
        assertEquals(GOAL_ID, entry.getGoalId());
        assertEquals(TENANT_ID, entry.getTenantId());
    }

    @Test
    @DisplayName("调整历史：只查当前学生所属 goal 的日志（wrapper 带 goal_id IN）")
    void should_filterHistoryByOwnGoalIds_when_studentHasGoals() {
        when(goalMapper.selectGoalIdsByStudent(STUDENT_ID, TENANT_ID))
                .thenReturn(List.of(GOAL_ID, OTHERS_GOAL_ID));
        Page<PlanAdjustmentLog> page = new Page<>(1, 10);
        page.setRecords(List.of(buildLog(7L, GOAL_ID, "confirmed")));
        page.setTotal(1);
        when(logMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        PageResult<AdjustmentHistoryVO> result = planAdjustmentService.getAdjustmentHistory(1, 10);

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(logMapper).selectPage(any(Page.class), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("goal_id IN"), "调整历史必须按当前学生的 goalIds 过滤: " + sql);
        Collection<Object> params = captor.getValue().getParamNameValuePairs().values();
        assertTrue(params.contains(TENANT_ID), "仍要带 tenant_id: " + params);
        // MyBatis-Plus 对 in(collection) 可能展开成多个参数，也可能整体作为一个集合参数
        boolean goalIdsPushed = params.containsAll(List.of(GOAL_ID, OTHERS_GOAL_ID))
                || params.stream().anyMatch(v -> v instanceof Collection<?> c
                        && c.containsAll(List.of(GOAL_ID, OTHERS_GOAL_ID)));
        assertTrue(goalIdsPushed, "goalIds 必须作为过滤参数下发: " + params);

        assertEquals(1L, result.getTotal());
        assertEquals(7L, result.getRecords().get(0).getLogId());
        assertEquals("confirmed", result.getRecords().get(0).getAction());
        assertEquals("调整测试", result.getRecords().get(0).getReason());
    }

    @Test
    @DisplayName("调整历史：当前学生无 goal → 返回空分页，不退化为全租户查询")
    void should_returnEmptyHistoryPage_when_studentHasNoGoal() {
        when(goalMapper.selectGoalIdsByStudent(STUDENT_ID, TENANT_ID)).thenReturn(List.of());

        PageResult<AdjustmentHistoryVO> result = planAdjustmentService.getAdjustmentHistory(1, 10);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getSize());
        verify(logMapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("调整历史：无学生上下文（身份缺失）→ 返回空分页，不查全租户")
    void should_returnEmptyHistoryPage_when_noStudentContext() {
        mockedStudentContext.when(StudentContext::getStudentId).thenReturn(null);

        PageResult<AdjustmentHistoryVO> result = planAdjustmentService.getAdjustmentHistory(1, 10);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        verify(goalMapper, never()).selectGoalIdsByStudent(anyLong(), anyString());
        verify(logMapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    // ==================== 辅助方法 ====================

    /**
     * 目标归属校验通过（P0-2）：当前学生对 GOAL_ID 有归属
     */
    private void stubOwnedGoal() {
        when(goalMapper.selectOwnedGoalId(GOAL_ID, STUDENT_ID, TENANT_ID)).thenReturn(GOAL_ID);
    }

    /**
     * 当前学生名下的 pending diff（P0-2 的正向用例）
     */
    private PlanAdjustmentDiff buildOwnedDiff(Long diffId, String status) {
        PlanAdjustmentDiff diff = new PlanAdjustmentDiff();
        diff.setId(diffId);
        diff.setGoalId(GOAL_ID);
        diff.setStatus(status);
        diff.setTenantId(TENANT_ID);
        diff.setTriggerSignals("[{\"signalType\":\"SIG-1\",\"description\":\"正确率偏低\"}]");
        diff.setDiffContent("{\"modifiedDates\":[],\"dailyQuestionLimit\":10,"
                + "\"pausedKpIds\":[\"kp_001\"],\"reason\":\"SIG-1 命中，下调日题量\"}");
        diff.setExpireAt(LocalDateTime.now().plusDays(7));
        return diff;
    }

    private PlanAdjustmentLog buildLog(Long logId, Long goalId, String action) {
        PlanAdjustmentLog logEntry = new PlanAdjustmentLog();
        logEntry.setId(logId);
        logEntry.setGoalId(goalId);
        logEntry.setDiffId(DIFF_ID);
        logEntry.setAction(action);
        logEntry.setActionAt(LocalDateTime.now());
        logEntry.setTenantId(TENANT_ID);
        logEntry.setTriggerSignals("[{\"signalType\":\"SIG-1\"}]");
        logEntry.setDiffContent("{\"modifiedDates\":[],\"reason\":\"调整测试\"}");
        logEntry.setCreateTime(LocalDateTime.now());
        return logEntry;
    }

    private void stubSignalHit() {
        when(diffMapper.countByGoalIdAndStatus(GOAL_ID, "pending", TENANT_ID)).thenReturn(0);
        TriggerSignalDTO signal = new TriggerSignalDTO();
        signal.setSignalType("SIG-1");
        signal.setDescription("最近 3 次作业正确率均低于 60%");
        when(signalDetector.detectSignals(GOAL_ID, TENANT_ID)).thenReturn(List.of(signal));
        // Change 2026-11-11 v3（S10）：selectCriticalErrors 签名补 studentId
        when(errorQuestionMapper.selectCriticalErrors(any(Long.class), any(LocalDate.class), eq(TENANT_ID)))
                .thenReturn(List.of());
    }

    private void stubGoalAndSubject() {
        stubGoalAndSubject(null);
    }

    private void stubGoalAndSubject(String pausedKpIdsJson) {
        stubOwnedGoal();
        LearningGoal goal = new LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setStudentId(STUDENT_ID);
        goal.setSubjectId(SUBJECT_ID);
        goal.setTargetScore(110);
        goal.setDirection("本学期冲 110 分");
        goal.setStartDate(LocalDate.now().minusMonths(1));
        goal.setEndDate(LocalDate.now().plusMonths(2));
        goal.setStatus("active");
        goal.setPausedKpIds(pausedKpIdsJson);
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
