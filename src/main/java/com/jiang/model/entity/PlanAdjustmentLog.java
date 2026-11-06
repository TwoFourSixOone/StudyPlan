package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jiang.common.handler.JsonStringTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 计划调整日志实体
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "plan_adjustment_log", autoResultMap = true)
public class PlanAdjustmentLog extends BaseEntity {

    @TableId("id")
    private Long id;

    /** 学习目标 ID */
    private Long goalId;

    /** 关联 diff ID */
    private Long diffId;

    /** 触发信号列表（冗余） */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String triggerSignals;

    /** 调整内容（冗余） */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String diffContent;

    /** 操作：confirmed/rejected/expired */
    private String action;

    /** 操作时间 */
    private LocalDateTime actionAt;
}
