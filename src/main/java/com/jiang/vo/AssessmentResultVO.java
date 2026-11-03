package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批改结果 VO
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
@Builder
public class AssessmentResultVO {

    /**
     * 评测 ID
     */
    private Long assessmentId;

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
     * 各知识点正确率
     */
    private List<KpAccuracyVO> knowledgePointResults;

    /**
     * 含答案的题目列表（仅 graded 时非空）
     */
    private List<AssessmentQuestionWithAnswerVO> questionsWithAnswers;

    /**
     * 批改完成时间
     */
    private LocalDateTime assessedAt;
}
