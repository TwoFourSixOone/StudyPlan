package com.jiang.dto;

import lombok.Data;

/**
 * 归因 Agent 结果 DTO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class ExamQuestionAttributionDTO {

    /**
     * 题目序号
     */
    private Integer questionIndex;

    /**
     * 题目内容
     */
    private String questionContent;

    /**
     * 归因的知识点 ID（待人工归因时为 NULL）
     */
    private String knowledgePointId;

    /**
     * 归因状态：pending/manual_review/completed/pending_manual_review
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
