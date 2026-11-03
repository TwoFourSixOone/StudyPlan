package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评测历史列表 VO
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
@Builder
public class AssessmentHistoryVO {

    /**
     * 评测 ID
     */
    private Long assessmentId;

    /**
     * 科目 ID
     */
    private Long subjectId;

    /**
     * 科目名称
     */
    private String subjectName;

    /**
     * 得分
     */
    private BigDecimal score;

    /**
     * 总分
     */
    private BigDecimal totalScore;

    /**
     * 状态：pending / graded
     */
    private String status;

    /**
     * 题目数量
     */
    private Integer questionCount;

    /**
     * 批改完成时间
     */
    private LocalDateTime assessedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
