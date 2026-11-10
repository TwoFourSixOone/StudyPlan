package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 错题详情 VO（供 Controller 返回）
 *
 * <p>在列表 VO 基础上补充题目选项、正确答案与解析。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorQuestionDetailVO {

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

    /**
     * 题目类型（JOIN question_bank.type）
     */
    private String questionType;

    /**
     * 题目选项 JSON（JOIN question_bank.choices）
     */
    private String options;

    /**
     * 正确答案（JOIN question_bank.answer）
     */
    private String correctAnswer;

    /**
     * 题目解析（JOIN question_bank.analysis）
     */
    private String analysis;
}
