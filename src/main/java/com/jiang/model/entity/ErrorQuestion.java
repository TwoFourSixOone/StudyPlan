package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 错题队列实体（艾宾浩斯周期调度）
 * 继承 BaseEntity（含 tenantId, createTime, updateTime）
 *
 * IMP-K 修复：不使用乐观锁 version 字段（状态机校验已提供并发保护）
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("error_question")
public class ErrorQuestion extends BaseEntity {

    /**
     * 错题 ID
     */
    @TableId(type = IdType.AUTO)
    private Long errorQuestionId;

    /**
     * 学生 ID
     */
    private Long studentId;

    /**
     * 关联 question_bank.question_id
     */
    private Long questionId;

    /**
     * 关联知识点 ID
     */
    private String knowledgePointId;

    /**
     * IMP-C 修复：sourceType 决定 sourceId 含义
     * - HOMEWORK → homework_submission_detail.submission_id
     * - ASSESSMENT → （待 Change 4 定义）
     * - EXAM → （待 Change 4 定义）
     */
    private String sourceType;

    /**
     * 来源 ID（语义由 sourceType 决定）
     */
    private Long sourceId;

    /**
     * 首次答错日期（艾宾浩斯起算点）
     * BLOCK-F 修复：只在 INSERT 时写入，UPDATE 不修改，保证统计/排序稳定性
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
     * 完成日期（stage > 5 时记录）
     */
    private LocalDate completedDate;
}
