package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调整历史 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@Builder
public class AdjustmentHistoryVO {

    /** 日志 ID */
    private Long logId;

    /** 目标 ID */
    private Long goalId;

    /** diff ID */
    private Long diffId;

    /** 操作：confirmed/rejected/expired */
    private String action;

    /** 操作时间 */
    private LocalDateTime actionAt;

    /** 调整原因（摘要） */
    private String reason;

    /** 创建时间 */
    private LocalDateTime createTime;
}
