package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 错题列表 VO（供 Controller 返回）
 *
 * <p>IMP-G 修复：questionContent 由 SQL COALESCE 兜底，不会为 null。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorQuestionVO {

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
     * 知识点名称（JOIN knowledge_point.name）
     */
    private String knowledgePointName;

    /**
     * 来源类型：homework/assessment/exam
     */
    private String sourceType;

    /**
     * 首次答错日期（艾宾浩斯起算点）
     */
    private LocalDate firstWrongDate;

    /**
     * 最近一次答错日期（周期重置基准）
     */
    private LocalDate lastWrongDate;

    /**
     * 当前阶段（1-5）
     */
    private Integer currentStage;

    /**
     * 下次到期日期
     */
    private LocalDate nextDueDate;

    /**
     * 状态：active / paused / completed
     */
    private String status;

    /**
     * 题目内容（JOIN question_bank.content，COALESCE 兜底）
     */
    private String questionContent;

    /**
     * 题目难度（JOIN question_bank.difficulty，COALESCE 兜底）
     */
    private String questionDifficulty;
}
