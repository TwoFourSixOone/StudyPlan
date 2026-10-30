package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 校对超期提醒实体（只读不删）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("pending_review_alert")
public class PendingReviewAlert extends BaseEntity {

    /**
     * 提醒 ID
     */
    @TableId("alert_id")
    private Long alertId;

    /**
     * 知识点 ID
     */
    private String knowledgePointId;

    /**
     * 提醒类型：overdue / manual
     */
    private String alertType;

    /**
     * 提醒消息
     */
    private String message;
}
