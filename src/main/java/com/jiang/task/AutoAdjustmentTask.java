package com.jiang.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.enums.GoalStatus;
import com.jiang.security.StudentContext;
import com.jiang.service.PlanAdjustmentService;
import com.jiang.service.PlanDiffService;
import com.jiang.service.SignalDetector;
import com.jiang.vo.AdjustTriggerVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jiang.common.util.BusinessDate;
import java.time.LocalDate;
import java.util.List;

/**
 * 计划自动调整任务（产品规则 #5）
 *
 * <p>每天 01:30（在 02:00 作业生成之前）检查所有进行中目标的触发信号：
 * 命中信号则自动生成调整方案并**直接应用**，无需学生手动确认。
 * 调整只影响未来且尚未开始的计划，已作答的历史不受影响。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Component
@Slf4j
public class AutoAdjustmentTask {

    @Autowired
    private LearningGoalMapper learningGoalMapper;

    @Autowired
    private SignalDetector signalDetector;

    @Autowired
    private PlanAdjustmentService planAdjustmentService;

    @Autowired
    private PlanDiffService planDiffService;

    /**
     * 每天凌晨 1:30 自动检测信号并调整（早于 02:00 出题，避免调整覆盖刚生成的作业）
     */
    @Scheduled(cron = "0 30 1 * * ?", zone = "Asia/Shanghai")
    public void autoAdjust() {
        LocalDate today = BusinessDate.today();
        log.info("[AutoAdjust] 开始检测 {} 的计划调整信号", today);

        List<LearningGoal> goals;
        try {
            goals = learningGoalMapper.selectList(
                    new LambdaQueryWrapper<LearningGoal>()
                            .eq(LearningGoal::getStatus, GoalStatus.ACTIVE.getCode())
                            .le(LearningGoal::getStartDate, today)
                            .ge(LearningGoal::getEndDate, today)
            );
        } catch (Exception e) {
            log.error("[AutoAdjust] 查询进行中目标失败，本次任务中止", e);
            return;
        }

        if (goals == null || goals.isEmpty()) {
            log.info("[AutoAdjust] 无进行中的学习目标，跳过");
            return;
        }

        int adjusted = 0;
        int skipped = 0;
        int failed = 0;

        for (LearningGoal goal : goals) {
            String tenantId = goal.getTenantId() == null ? "default" : goal.getTenantId();
            try {
                StudentContext.setStudentId(goal.getStudentId());
                StudentContext.setTenantId(tenantId);

                List<TriggerSignalDTO> signals = signalDetector.detectSignals(goal.getGoalId(), tenantId);
                if (signals == null || signals.isEmpty()) {
                    skipped++;
                    continue;
                }

                AdjustTriggerVO vo = planAdjustmentService.triggerAdjustment(goal.getGoalId());
                if (vo != null && vo.isAdjusted() && vo.getDiffId() != null) {
                    // 自动模式：不等学生确认，直接应用
                    planDiffService.applyDiff(vo.getDiffId());
                    adjusted++;
                    log.info("[AutoAdjust] 已自动调整: studentId={}, goalId={}, signals={}, diffId={}",
                            goal.getStudentId(), goal.getGoalId(),
                            signals.stream().map(TriggerSignalDTO::getSignalType).toList(),
                            vo.getDiffId());
                } else {
                    skipped++;
                }
            } catch (BusinessException e) {
                // 规则 #5 去重（12h 内已确认 / 已有 pending diff）：属于正常跳过，不计入失败
                if (ErrorCodes.PLAN_ADJUST_CONFLICT.getCode().equals(e.getCode())
                        || ErrorCodes.PLAN_ADJUST_COOLDOWN.getCode().equals(e.getCode())) {
                    skipped++;
                    log.info("[AutoAdjust] 去重命中，跳过: studentId={}, goalId={}, code={}, message={}",
                            goal.getStudentId(), goal.getGoalId(), e.getCode(), e.getMessage());
                } else {
                    failed++;
                    log.error("[AutoAdjust] 调整失败: studentId={}, goalId={}, error={}",
                            goal.getStudentId(), goal.getGoalId(), e.getMessage());
                }
            } catch (Exception e) {
                failed++;
                log.error("[AutoAdjust] 调整失败: studentId={}, goalId={}, error={}",
                        goal.getStudentId(), goal.getGoalId(), e.getMessage());
            } finally {
                StudentContext.clear();
            }
        }

        log.info("[AutoAdjust] 完成: 目标总数={}, 已调整={}, 无需调整={}, 失败={}",
                goals.size(), adjusted, skipped, failed);
    }
}
