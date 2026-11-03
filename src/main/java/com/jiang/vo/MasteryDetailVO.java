package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端掌握度看板视图对象
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasteryDetailVO {

    /**
     * 学生 ID
     */
    private Long studentId;

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
     * 知识点状态：active / disabled
     */
    private String kpStatus;

    /**
     * 掌握度（0.00–1.00）
     */
    private BigDecimal masteryScore;

    /**
     * 置信度（0.00–1.00）
     */
    private BigDecimal confidence;

    /**
     * 最近来源
     */
    private String source;

    /**
     * 最近评测时间
     */
    private LocalDateTime lastAssessedAt;
}
