package com.jiang.agent;

import com.jiang.dto.PlanDiffDTO;
import com.jiang.dto.TriggerSignalDTO;

import java.util.List;

/**
 * 调整 Agent 接口
 * 修复 Issue-3：Agent 只封装 LLM 调用，数据查询由 Service 层完成
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface AdjustmentAgent {

    /**
     * 根据触发信号生成调整方案
     *
     * @param signals              触发信号列表
     * @param goalId               学习目标 ID
     * @param criticalErrorQuestionIds 超 30 天错题 ID 列表（由 Service 层查询后传入）
     * @return 调整方案
     */
    PlanDiffDTO generateDiff(List<TriggerSignalDTO> signals, Long goalId,
                              List<String> criticalErrorQuestionIds);
}
