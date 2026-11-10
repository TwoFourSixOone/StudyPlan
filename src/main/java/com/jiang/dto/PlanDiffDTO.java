package com.jiang.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 计划 diff DTO（修复 IMP-22：合并 AdjustmentAgentOutput）
 * Agent 输出直接映射为此 DTO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class PlanDiffDTO {

    /** 修改的日期列表 */
    private List<PlanDateModification> modifiedDates;

    /** 调整原因 */
    /**
     * 日题量上限（1-15；null = 不调整）
     *
     * <p>产品规则 #5：SIG-3 学习懈怠时下调，作用于目标，后续每日生成生效。</p>
     */
    private Integer dailyQuestionLimit;

    /**
     * 本轮暂缓的知识点 ID（null = 不调整；空数组 = 清空暂缓）
     *
     * <p>产品规则 #5：SIG-4 进度风险时腾出课时。</p>
     */
    private List<String> pausedKpIds;
    private String reason;

    /** 超 30 天错题标记 */
    private List<String> criticalErrorQuestionIds;
}
