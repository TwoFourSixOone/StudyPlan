package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 掌握度变更日志实体（只读不删）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("mastery_change_log")
public class MasteryChangeLog extends BaseEntity {

    /**
     * 日志 ID
     */
    @TableId("log_id")
    private Long logId;

    /**
     * 学生 ID
     */
    private Long studentId;

    /**
     * 知识点 ID
     */
    private String knowledgePointId;

    /**
     * 变更类型：homework / assessment / exam
     */
    private String changeType;

    /**
     * 来源 ID（对 homework 存 submission_id，对 assessment 存 assessment_id，对 exam 存 analysis_id）
     */
    private Long sourceId;

    /**
     * 变动量
     */
    private BigDecimal delta;

    /**
     * 变更前掌握度
     */
    private BigDecimal masteryBefore;

    /**
     * 变更后掌握度
     */
    private BigDecimal masteryAfter;
}
