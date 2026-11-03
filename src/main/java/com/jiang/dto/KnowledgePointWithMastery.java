package com.jiang.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 带掌握度的知识点 DTO（用于 Agent 输入）
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgePointWithMastery {

    /** 知识点 ID */
    private String knowledgePointId;

    /** 知识点名称 */
    private String name;

    /** 科目 */
    private String subject;

    /** 掌握度分数（0.00-1.00） */
    private BigDecimal masteryScore;

    /** 置信度（0.00-1.00） */
    private BigDecimal confidence;
}
