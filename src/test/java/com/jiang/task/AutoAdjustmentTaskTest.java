package com.jiang.task;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.model.entity.LearningGoal;
import com.jiang.service.PlanAdjustmentService;
import com.jiang.service.PlanDiffService;
import com.jiang.service.SignalDetector;
import com.jiang.vo.AdjustTriggerVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AutoAdjustmentTask 单元测试（产品规则 #5：自动调整）
 *
 * <p>2026-11-12 补充：12h 去重（`PLAN_ADJUST_COOLDOWN`）与已有 pending diff 命中时按「跳过」处理，
 * 不得计入失败、不得 applyDiff。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("计划自动调整任务")
class AutoAdjustmentTaskTest {

    @Mock
    private LearningGoalMapper learningGoalMapper;
    @Mock
    private SignalDetector signalDetector;
    @Mock
    private PlanAdjustmentService planAdjustmentService;
    @Mock
    private PlanDiffService planDiffService;

    @InjectMocks
    private AutoAdjustmentTask autoAdjustmentTask;

    private static final Long GOAL_ID = 100L;
    private static final Long STUDENT_ID = 1L;
    private static final String TENANT_ID = "default";

    private LearningGoal buildGoal() {
        LearningGoal goal = new LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setStudentId(STUDENT_ID);
        goal.setSubjectId(10L);
        goal.setStatus("active");
        goal.setTenantId(TENANT_ID);
        return goal;
    }

    private TriggerSignalDTO sig4Signal() {
        TriggerSignalDTO signal = new TriggerSignalDTO();
        signal.setSignalType("SIG-4");
        signal.setDescription("剩余 4.0 天 × 容量 3.0 × 效率 0.80 = 9.6 < 未覆盖知识点 174");
        return signal;
    }

    @Test
    @DisplayName("无进行中目标 → 不检测信号")
    void should_doNothing_when_noActiveGoal() {
        when(learningGoalMapper.selectList(any())).thenReturn(List.of());

        autoAdjustmentTask.autoAdjust();

        verifyNoInteractions(signalDetector, planAdjustmentService, planDiffService);
    }

    @Test
    @DisplayName("无信号 → 不触发调整")
    void should_skip_when_noSignals() {
        when(learningGoalMapper.selectList(any())).thenReturn(List.of(buildGoal()));
        when(signalDetector.detectSignals(GOAL_ID, TENANT_ID)).thenReturn(List.of());

        autoAdjustmentTask.autoAdjust();

        verifyNoInteractions(planAdjustmentService, planDiffService);
    }

    @Test
    @DisplayName("命中信号 → 自动生成并直接应用（无需学生确认）")
    void should_applyAutomatically_when_signalsDetected() {
        when(learningGoalMapper.selectList(any())).thenReturn(List.of(buildGoal()));
        TriggerSignalDTO signal = new TriggerSignalDTO();
        signal.setSignalType("SIG-3");
        when(signalDetector.detectSignals(GOAL_ID, TENANT_ID)).thenReturn(List.of(signal));

        AdjustTriggerVO vo = AdjustTriggerVO.of(9L, List.of(signal));
        when(planAdjustmentService.triggerAdjustment(GOAL_ID)).thenReturn(vo);

        autoAdjustmentTask.autoAdjust();

        verify(planDiffService).applyDiff(9L);
    }

    @Test
    @DisplayName("12h 去重窗口内已确认调整 → 按跳过处理（不计失败、不应用 diff）")
    void should_skip_when_dedupWindowHit() {
        when(learningGoalMapper.selectList(any())).thenReturn(List.of(buildGoal()));
        when(signalDetector.detectSignals(GOAL_ID, TENANT_ID)).thenReturn(List.of(sig4Signal()));
        when(planAdjustmentService.triggerAdjustment(GOAL_ID))
                .thenThrow(new BusinessException(ErrorCodes.PLAN_ADJUST_COOLDOWN));

        assertDoesNotThrow(() -> autoAdjustmentTask.autoAdjust());

        verifyNoInteractions(planDiffService);
    }

    @Test
    @DisplayName("已有 pending diff → 按跳过处理（不计失败、不应用 diff）")
    void should_skip_when_pendingDiffExists() {
        when(learningGoalMapper.selectList(any())).thenReturn(List.of(buildGoal()));
        when(signalDetector.detectSignals(GOAL_ID, TENANT_ID)).thenReturn(List.of(sig4Signal()));
        when(planAdjustmentService.triggerAdjustment(GOAL_ID))
                .thenThrow(new BusinessException(ErrorCodes.PLAN_ADJUST_CONFLICT));

        assertDoesNotThrow(() -> autoAdjustmentTask.autoAdjust());

        verifyNoInteractions(planDiffService);
    }

    @Test
    @DisplayName("单个目标失败 → 不影响其他目标")
    void should_continue_when_oneGoalFails() {
        LearningGoal second = buildGoal();
        second.setGoalId(101L);
        second.setStudentId(2L);
        when(learningGoalMapper.selectList(any())).thenReturn(List.of(buildGoal(), second));

        when(signalDetector.detectSignals(GOAL_ID, TENANT_ID))
                .thenThrow(new RuntimeException("db down"));
        when(signalDetector.detectSignals(101L, TENANT_ID)).thenReturn(List.of());

        autoAdjustmentTask.autoAdjust();

        verify(signalDetector).detectSignals(101L, TENANT_ID);
        verifyNoInteractions(planDiffService);
    }
}
