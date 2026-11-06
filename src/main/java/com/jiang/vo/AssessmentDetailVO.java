package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评测详情 VO（含题目列表，不含答案）
 * 用于 AssessmentService.getAssessmentDetail
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
@Builder
public class AssessmentDetailVO {

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
     * 状态：pending / graded
     */
    private String status;

    /**
     * 题目列表（不含答案）
     */
    private List<AssessmentQuestionVO> questions;

    /**
     * 题目总数
     */
    private Integer totalQuestions;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
