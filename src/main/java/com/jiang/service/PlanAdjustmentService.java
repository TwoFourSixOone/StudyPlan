package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.vo.AdjustTriggerVO;
import com.jiang.vo.AdjustmentHistoryVO;
import com.jiang.vo.PlanDiffDetailVO;

/**
 * 计划调整服务接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface PlanAdjustmentService {

    /**
     * 触发调整（BLOCK-B：应用层防重）
     *
     * @param goalId 目标 ID
     * @return 触发结果
     */
    AdjustTriggerVO triggerAdjustment(Long goalId);

    /**
     * 确认 diff
     *
     * @param diffId diff ID
     */
    void confirmDiff(Long diffId);

    /**
     * 拒绝 diff
     *
     * @param diffId diff ID
     */
    void rejectDiff(Long diffId);

    /**
     * 获取 diff 详情
     *
     * @param diffId diff ID
     * @return diff 详情
     */
    PlanDiffDetailVO getDiffDetail(Long diffId);

    /**
     * 获取调整历史
     *
     * @param page 页码
     * @param size 每页大小
     * @return 分页结果
     */
    PageResult<AdjustmentHistoryVO> getAdjustmentHistory(Integer page, Integer size);
}
