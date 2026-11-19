package com.jiang.service;

import com.jiang.dto.PlanDiffDTO;
import com.jiang.dto.TriggerSignalDTO;

import java.util.List;

/**
 * 计划 diff 服务接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface PlanDiffService {

    /**
     * 保存调整 diff
     *
     * @param goalId  目标 ID
     * @param signals 触发信号
     * @param diff    调整内容
     * @return diff ID
     */
    Long saveDiff(Long goalId, List<TriggerSignalDTO> signals, PlanDiffDTO diff);

    /**
     * 保存调整 diff（显式租户，design §8.2：Worker/异步线程不读 {@code StudentContext}）
     *
     * @param tenantId 租户 ID；非空时直接使用，为空则回退 {@code StudentContext}（HTTP 线程兼容）
     */
    Long saveDiff(Long goalId, List<TriggerSignalDTO> signals, PlanDiffDTO diff, String tenantId);

    /**
     * 应用调整 diff（修复 IMP-21：只更新 plan_date >= today 的未来计划）
     *
     * <p><b>系统入口，不含学生归属校验（P0-2）</b>：调用方包括学生确认
     * （{@code PlanAdjustmentService#confirmDiff}）、定时任务（{@code AutoAdjustmentTask}）与
     * 考试结算（{@code ExamSignalAdjustServiceImpl}）。后两者无 HTTP 上下文/学生身份，
     * 因此归属校验由**调用方**负责——学生端入口必须先校验 diff 属于当前登录学生，
     * 系统路径无需（也没有）学生身份。租户取自 diff 自身，不读 {@code StudentContext}。</p>
     *
     * @param diffId diff ID
     */
    void applyDiff(Long diffId);

    /**
     * 清理过期 diff（修复 BLOCK-13：批量 UPDATE + 批量 INSERT）
     *
     * @return 过期数量
     */
    int cleanExpiredDiffs();
}
