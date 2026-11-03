package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 含答案的题目 VO（仅 graded 时返回）
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
@Builder
public class AssessmentQuestionWithAnswerVO {

    /**
     * 题目 ID
     */
    private Long questionId;

    /**
     * 知识点 ID
     */
    private String knowledgePointId;

    /**
     * 题干
     */
    private String content;

    /**
     * 难度：basic / standard / challenge
     */
    private String difficulty;

    /**
     * 题型：choice / numeric / short / self
     */
    private String type;

    /**
     * 学生答案
     */
    private String studentAnswer;

    /**
     * 正确答案
     */
    private String correctAnswer;

    /**
     * 是否正确
     */
    private Boolean isCorrect;

    /**
     * 解析
     */
    private String analysis;
}
