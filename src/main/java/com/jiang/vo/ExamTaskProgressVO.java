package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 解析任务进度 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@Builder
public class ExamTaskProgressVO {

    /**
     * 任务 ID
     */
    private Long taskId;

    /**
     * 状态：pending/processing/completed/failed
     */
    private String status;

    /**
     * OCR 重试次数
     */
    private Integer ocrRetryCount;

    /**
     * 归因重试次数
     */
    private Integer attributionRetryCount;

    /**
     * 开始处理时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;

    /**
     * 错误信息（仅 failed 时有值）
     */
    private String errorMessage;
}
