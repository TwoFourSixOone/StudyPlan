package com.jiang.service;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.PlanAdjustmentDiffMapper;
import com.jiang.mapper.PlanAdjustmentLogMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.PlanAdjustmentDiff;
import com.jiang.model.entity.PlanAdjustmentLog;
import com.jiang.model.enums.PlanStatus;
import com.jiang.service.impl.PlanDiffServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PlanDiffService 单元测试
 *
 * <p>重点覆盖 2026-11-09 的计划状态判定：</p>
 * <ul>
 *   <li>`newContent` 为 `[]` → `pending_generation`（交由出题 Agent 重新生成）</li>
 *   <li>`newContent` 非空 → `pending`（学生可直接作答）</li>
 *   <li>过去日期的修改必须跳过，历史计划不得被重置</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanDiffService 单元测试")
class PlanDiffServiceTest {

    @Mock
    private PlanAdjustmentDiffMapper diffMapper;
    @Mock
    private PlanAdjustmentLogMapper logMapper;
    @Mock
    private DailyPlanMapper planMapper;
    @Mock
    private LearningGoalMapper goalMapper;

    @InjectMocks
    private PlanDiffServiceImpl planDiffService;

    private static final Long DIFF_ID = 1L;
    private static final Long GOAL_ID = 100L;
    private static final String TENANT_ID = "default";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    @Test
    @DisplayName("确认 diff：newContent 为 [] → 计划置 pending_generation")
    void should_markPendingGeneration_when_newContentIsEmptyArray() {
        LocalDate date = LocalDate.now().plusDays(1);
        PlanAdjustmentDiff diff = buildDiff(diffContent(date, "[]"), "pending");
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff);

        DailyPlan plan = buildPlan(date, PlanStatus.PENDING.getCode(), "[]");
        when(planMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, date)).thenReturn(plan);

        planDiffService.applyDiff(DIFF_ID);

        assertEquals(PlanStatus.PENDING_GENERATION.getCode(), plan.getStatus(),
                "空内容计划日应标记为待生成，避免学生看到空作业");
        verify(planMapper).updateById(plan);
    }

    @Test
    @DisplayName("确认 diff：newContent 非空 → 计划置 pending")
    void should_markPending_when_newContentIsNotEmpty() {
        LocalDate date = LocalDate.now().plusDays(1);
        String content = "[{\"questionId\":1,\"questionSource\":\"bank\",\"taskType\":\"review\"}]";
        PlanAdjustmentDiff diff = buildDiff(diffContent(date, content), "pending");
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff);

        DailyPlan plan = buildPlan(date, PlanStatus.PENDING_GENERATION.getCode(), "[]");
        when(planMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, date)).thenReturn(plan);

        planDiffService.applyDiff(DIFF_ID);

        assertEquals(PlanStatus.PENDING.getCode(), plan.getStatus());
        assertEquals(content, plan.getContent());
    }

    @Test
    @DisplayName("确认 diff：过去日期 → 跳过，不查询也不更新计划")
    void should_skipPastDate_when_dateBeforeToday() {
        LocalDate past = LocalDate.now().minusDays(1);
        PlanAdjustmentDiff diff = buildDiff(diffContent(past, "[]"), "pending");
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff);

        planDiffService.applyDiff(DIFF_ID);

        verifyNoInteractions(planMapper);
        // diff 状态本身仍应推进为 confirmed
        assertEquals("confirmed", diff.getStatus());
    }

    @Test
    @DisplayName("确认 diff：非 pending 状态 → 抛 PLAN_DIFF_NOT_FOUND_OR_INVALID")
    void should_throw_when_diffIsNotPending() {
        PlanAdjustmentDiff diff = buildDiff("{}", "confirmed");
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planDiffService.applyDiff(DIFF_ID));

        assertEquals(ErrorCodes.PLAN_DIFF_NOT_FOUND_OR_INVALID.getCode(), ex.getCode());
        verifyNoInteractions(planMapper);
    }

    @Test
    @DisplayName("确认 diff：成功后写 confirmed 状态并追加调整日志")
    void should_confirmAndAppendLog_when_applied() {
        LocalDate date = LocalDate.now().plusDays(1);
        PlanAdjustmentDiff diff = buildDiff(diffContent(date, "[]"), "pending");
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff);
        when(planMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, date))
                .thenReturn(buildPlan(date, PlanStatus.PENDING.getCode(), "[]"));

        planDiffService.applyDiff(DIFF_ID);

        assertEquals("confirmed", diff.getStatus());
        assertNotNull(diff.getConfirmedAt());
        verify(diffMapper).updateById(diff);

        ArgumentCaptor<PlanAdjustmentLog> logCaptor = ArgumentCaptor.forClass(PlanAdjustmentLog.class);
        verify(logMapper).insert(logCaptor.capture());
        PlanAdjustmentLog log = logCaptor.getValue();
        assertEquals("confirmed", log.getAction());
        assertEquals(DIFF_ID, log.getDiffId());
        assertEquals(GOAL_ID, log.getGoalId());
        assertEquals(TENANT_ID, log.getTenantId());
    }

    @Test
    @DisplayName("确认 diff：策略字段写入目标（日题量 + 暂缓知识点）")
    void should_applyGoalStrategy_when_strategyProvided() {
        // 产品规则 #5：未来日期尚无计划，策略必须落到目标上，由每日生成承接
        PlanAdjustmentDiff diff = buildDiff(
                "{\"modifiedDates\":[],\"dailyQuestionLimit\":10,"
                        + "\"pausedKpIds\":[\"kp_001\"],\"reason\":\"SIG-3/SIG-4\"}",
                "pending");
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff);

        LearningGoal goal = new LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setTenantId(TENANT_ID);
        when(goalMapper.selectById(GOAL_ID)).thenReturn(goal);

        planDiffService.applyDiff(DIFF_ID);

        assertEquals(10, goal.getDailyQuestionLimit());
        assertEquals("[\"kp_001\"]", goal.getPausedKpIds());
        verify(goalMapper).updateById(goal);
    }

    @Test
    @DisplayName("确认 diff：日期尚无计划 → 显式跳过，diff 仍然 confirmed")
    void should_skipMissingPlan_andStillConfirm() {
        LocalDate date = LocalDate.now().plusDays(1);
        PlanAdjustmentDiff diff = buildDiff(diffContent(date, "[]"), "pending");
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff);
        when(planMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, date)).thenReturn(null);

        planDiffService.applyDiff(DIFF_ID);

        assertEquals("confirmed", diff.getStatus());
        verify(planMapper, never()).updateById(any(DailyPlan.class));
    }

    @Test
    @DisplayName("确认 diff：已开始/已完成的计划不动")
    void should_skipStartedPlan() {
        LocalDate date = LocalDate.now().plusDays(1);
        PlanAdjustmentDiff diff = buildDiff(diffContent(date, "[]"), "pending");
        when(diffMapper.selectById(DIFF_ID)).thenReturn(diff);
        when(planMapper.selectByGoalIdAndDate(GOAL_ID, TENANT_ID, date))
                .thenReturn(buildPlan(date, PlanStatus.IN_PROGRESS.getCode(), "[]"));

        planDiffService.applyDiff(DIFF_ID);

        verify(planMapper, never()).updateById(any(DailyPlan.class));
    }

    // ==================== 辅助方法 ====================

    private PlanAdjustmentDiff buildDiff(String diffContent, String status) {
        PlanAdjustmentDiff diff = new PlanAdjustmentDiff();
        diff.setId(DIFF_ID);
        diff.setGoalId(GOAL_ID);
        diff.setStatus(status);
        diff.setTenantId(TENANT_ID);
        diff.setTriggerSignals("[{\"signalType\":\"SIG-1\"}]");
        diff.setDiffContent(diffContent);
        diff.setExpireAt(LocalDateTime.now().plusDays(7));
        return diff;
    }

    private DailyPlan buildPlan(LocalDate date, String status, String content) {
        DailyPlan plan = new DailyPlan();
        plan.setPlanId(200L);
        plan.setGoalId(GOAL_ID);
        plan.setPlanDate(date);
        plan.setStatus(status);
        plan.setContent(content);
        plan.setTenantId(TENANT_ID);
        return plan;
    }

    /**
     * 构造 diff_content JSON（与 PlanDiffServiceImpl 的 JavaTimeModule 反序列化契约一致）
     */
    private String diffContent(LocalDate date, String newContent) {
        return "{\"modifiedDates\":[{\"date\":\"" + date.format(DATE_FMT)
                + "\",\"newContent\":" + quote(newContent) + ",\"reason\":\"test\"}],"
                + "\"reason\":\"调整测试\"}";
    }

    private String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
