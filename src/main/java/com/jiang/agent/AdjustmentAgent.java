package com.jiang.agent;

import com.jiang.dto.AdjustmentContextDTO;
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
     * <p>
     * v2：传入 Service 层组装的真实上下文（目标详情 + 未来计划 + 未覆盖知识点），
     * 替代仅传 goalId，避免 LLM 凭空编造数据（产品规则 #8）
     *
     * @param signals              触发信号列表
     * @param context              调整上下文（由 Service 层查询真实数据组装）
     * @param criticalErrorQuestionIds 超 30 天错题 ID 列表（由 Service 层查询后传入）
     * @return 调整方案
     */
    PlanDiffDTO generateDiff(List<TriggerSignalDTO> signals, AdjustmentContextDTO context,
                              List<String> criticalErrorQuestionIds);
}
