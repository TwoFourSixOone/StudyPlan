package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IMP-12 修复：错题统计 VO
 *
 * <p>BLOCK-C 修复：所有字段由 COALESCE 保证非 null。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorQuestionStatisticsVO {

    /**
     * 活跃 + 暂停总数（不含 completed）
     */
    private Integer activeAndPausedCount;

    /**
     * 今日到期数（status=active AND next_due_date <= today）
     */
    private Integer dueCount;

    /**
     * 暂停数（status=paused）
     */
    private Integer pausedCount;
}
