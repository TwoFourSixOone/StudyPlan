package com.jiang.dto;

import lombok.Data;

/**
 * OCR 识别结果 DTO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class ExamQuestionOcrDTO {

    /**
     * 题目序号
     */
    private Integer questionIndex;

    /**
     * 题目内容（OCR 识别文本）
     */
    private String questionContent;

    /**
     * 题目图片 URL
     */
    private String questionImageUrl;

    /**
     * 归因状态：pending/manual_review/pending_manual_review
     */
    private String attributionStatus;

    /**
     * 题型：choice/numeric/short
     */
    private String questionType;

    /**
     * 难度：basic/standard/challenge
     */
    private String difficulty;
}
