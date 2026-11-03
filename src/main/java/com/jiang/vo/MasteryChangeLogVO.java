package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 掌握度变更日志视图对象
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasteryChangeLogVO {

    /**
     * 日志 ID
     */
    private Long logId;

    /**
     * 变更类型
     */
    private String changeType;

    /**
     * 来源 ID
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

    /**
     * 知识点名称
     */
    private String knowledgePointName;

    /**
     * 科目
     */
    private String subject;

    /**
     * 变更时间
     */
    private LocalDateTime createdAt;
}
