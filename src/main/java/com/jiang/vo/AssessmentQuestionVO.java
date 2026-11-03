package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单题 VO（不含答案）
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
@Builder
public class AssessmentQuestionVO {

    /**
     * 题目 ID
     */
    private Long questionId;

    /**
     * 知识点 ID
     */
    private String knowledgePointId;

    /**
     * 知识点名称
     */
    private String knowledgePointName;

    /**
     * 题干
     */
    private String content;

    /**
     * 选项（选择题）
     */
    private List<String> choices;

    /**
     * 难度：basic / standard / challenge
     */
    private String difficulty;

    /**
     * 题型：choice / numeric / short / self
     */
    private String type;
}
