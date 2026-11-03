package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评测概要 VO（创建后返回）
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
@Builder
public class AssessmentVO {

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
     * 涉及的知识点 ID 列表
     */
    private List<String> knowledgePointIds;

    /**
     * 题目数量
     */
    private Integer questionCount;

    /**
     * 状态：pending / graded
     */
    private String status;

    /**
     * 提示信息（如：当前知识点掌握良好，评测可能偏简单）
     */
    private String warning;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
