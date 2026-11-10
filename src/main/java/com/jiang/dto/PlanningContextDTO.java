package com.jiang.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 规划 Agent 输入上下文（由 Service 层查询真实数据组装，产品规则 #8）
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Data
public class PlanningContextDTO {

    /**
     * 目标 ID
     */
    private Long goalId;

    /**
     * 科目名称
     */
    private String subjectName;

    /**
     * 目标周期
     */
    private LocalDate startDate;

    private LocalDate endDate;

    /**
     * 目标分数（0-150）
     */
    private Integer targetScore;

    /**
     * 目标方向描述
     */
    private String direction;

    /**
     * 学生年级（1-12，可为 null）
     */
    private Integer studentGrade;

    /**
     * 可用知识点（已按年级过滤、仅 active）
     */
    private List<KnowledgePointBrief> knowledgePoints;

    /**
     * 知识点简报
     */
    @Data
    public static class KnowledgePointBrief {
        private String kpId;
        private String name;
        private String chapter;
        private String gradeRange;
        /**
         * 当前掌握度（0-1，null 表示未测过）
         */
        private Double mastery;
    }
}
