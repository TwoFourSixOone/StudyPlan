package com.jiang.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 规划 Agent 输出：整段每日计划骨架
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Data
public class PlanDraft {

    /**
     * 每日计划
     */
    private List<PlanDay> days;

    /**
     * 整体规划思路
     */
    private String reason;

    @Data
    public static class PlanDay {
        private LocalDate date;
        private List<String> kpIds;
        private Integer questionCount;
        private String focus;
    }
}
