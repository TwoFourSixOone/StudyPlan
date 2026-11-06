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
    private String reason;

    /** 超 30 天错题标记 */
    private List<String> criticalErrorQuestionIds;
}
