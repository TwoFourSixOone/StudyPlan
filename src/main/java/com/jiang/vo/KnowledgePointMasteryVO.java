package com.jiang.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识点掌握度 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class KnowledgePointMasteryVO {

    /** 知识点 ID */
    private String knowledgePointId;

    /** 知识点名称 */
    private String knowledgePointName;

    /** 掌握度分数 */
    private BigDecimal masteryScore;

    /** 置信度 */
    private BigDecimal confidence;

    /** 知识点状态 */
    private String status;

    /** 最后评估时间 */
    private LocalDateTime lastAssessedAt;
}
