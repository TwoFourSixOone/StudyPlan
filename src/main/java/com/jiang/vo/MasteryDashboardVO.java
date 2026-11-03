package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学生掌握度看板视图对象
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasteryDashboardVO {

    /**
     * 知识点 ID
     */
    private String knowledgePointId;

    /**
     * 知识点名称
     */
    private String knowledgePointName;

    /**
     * 科目
     */
    private String subject;

    /**
     * 掌握度（0.00–1.00）
     */
    private BigDecimal masteryScore;

    /**
     * 置信度（0.00–1.00）
     */
    private BigDecimal confidence;

    /**
     * 最近变更方向：up / down / stable / null
     */
    private String lastChangeDirection;

    /**
     * 最近评测时间
     */
    private LocalDateTime lastAssessedAt;
}
