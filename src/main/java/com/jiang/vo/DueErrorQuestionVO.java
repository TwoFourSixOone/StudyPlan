package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * IMP-1 修复：到期错题 VO（供 generateDailyHomework 使用）
 *
 * <p>精简字段，仅包含 Agent 规划所需的错题信息。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DueErrorQuestionVO {

    /**
     * 错题 ID
     */
    private Long errorQuestionId;

    /**
     * 关联 question_bank.question_id
     */
    private Long questionId;

    /**
     * 关联知识点 ID
     */
    private String knowledgePointId;

    /**
     * 当前阶段（1-5）
     */
    private Integer currentStage;

    /**
     * 下次到期日期
     */
    private LocalDate nextDueDate;
}
