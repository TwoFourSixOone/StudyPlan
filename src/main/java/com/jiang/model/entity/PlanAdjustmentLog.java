package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 计划调整日志实体（只读不删）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "plan_adjustment_log", autoResultMap = true)
public class PlanAdjustmentLog extends BaseEntity {

    /**
     * 调整 ID
     */
    @TableId("adjustment_id")
    private Long adjustmentId;

    /**
     * 目标 ID
     */
    private Long goalId;

    /**
     * 触发信号列表
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String triggerSignals;

    /**
     * 计划 diff 内容
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String diffContent;

    /**
     * 状态：pending / confirmed / rejected
     */
    private String status;

    /**
     * 确认时间
     */
    private LocalDateTime confirmedAt;
}
