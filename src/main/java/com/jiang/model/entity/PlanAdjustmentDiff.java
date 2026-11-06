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
 * 计划调整 diff 实体
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "plan_adjustment_diff", autoResultMap = true)
public class PlanAdjustmentDiff extends BaseEntity {

    @TableId("id")
    private Long id;

    /** 学习目标 ID */
    private Long goalId;

    /** 状态：pending/confirmed/rejected/expired */
    private String status;

    /** 触发信号列表（JSON） */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String triggerSignals;

    /** 调整内容（JSON） */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String diffContent;

    /** 过期时间 */
    private LocalDateTime expireAt;

    /** 确认时间 */
    private LocalDateTime confirmedAt;

    /** 拒绝时间 */
    private LocalDateTime rejectedAt;
}
