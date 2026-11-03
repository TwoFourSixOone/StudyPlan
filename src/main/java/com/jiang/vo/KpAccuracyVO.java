package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 知识点正确率 VO
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
@Builder
public class KpAccuracyVO {

    /**
     * 知识点 ID
     */
    private String knowledgePointId;

    /**
     * 知识点名称
     */
    private String knowledgePointName;

    /**
     * 题目总数
     */
    private Integer totalQuestions;

    /**
     * 正确题数
     */
    private Integer correctCount;

    /**
     * 正确率 0.00~1.00
     */
    private BigDecimal accuracy;
}
