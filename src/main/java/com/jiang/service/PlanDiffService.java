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
     * 应用调整 diff（修复 IMP-21：只更新 plan_date >= today 的未来计划）
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
