package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jiang.common.handler.JsonStringTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 每日计划实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "daily_plan", autoResultMap = true)
public class DailyPlan extends BaseEntity {

    /**
     * 计划 ID
     */
    @TableId("plan_id")
    private Long planId;

    /**
     * 目标 ID
     */
    private Long goalId;

    /**
     * 计划日期
     */
    private LocalDate planDate;

    /**
     * 计划内容（仅存计划元信息，题目明细以 question_bank/generated_question 为准）
     */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String content;

    /**
     * 状态：pending / in_progress / completed / cancelled
     */
    private String status;

    /**
     * 规划 Agent 指定的知识点 ID（JSON 数组；NULL = 未规划，按掌握度现算）
     *
     * <p>产品规则 #1：创建目标时生成整段计划骨架，02:00 出题按骨架执行。</p>
     */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String plannedKpIds;

    /**
     * 规划 Agent 指定的当日题量（NULL = 按配额计算）
     */
    private Integer plannedQuestionCount;
}
