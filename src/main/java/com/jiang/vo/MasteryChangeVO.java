package com.jiang.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 掌握度变更 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class MasteryChangeVO {

    /** 变更 ID */
    private Long changeId;

    /** 知识点 ID */
    private String knowledgePointId;

    /** 知识点名称 */
    private String knowledgePointName;

    /** 变更类型 */
    private String changeType;

    /** 来源 ID */
    private Long sourceId;

    /** 变化量 */
    private BigDecimal delta;

    /** 变更前 */
    private BigDecimal masteryBefore;

    /** 变更后 */
    private BigDecimal masteryAfter;

    /** 变更时间 */
    private LocalDateTime changeTime;
}
