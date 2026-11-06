package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 试卷解析任务表实体
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("exam_analysis_task")
public class ExamAnalysisTask extends BaseEntity {

    /**
     * 任务 ID
     */
    @TableId("id")
    private Long id;

    /**
     * 试卷 ID
     */
    private Long examPaperId;

    /**
     * 任务类型：ocr_attribution/ocr_only/attribution_only
     */
    private String taskType;

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
     * 错误信息
     */
    private String errorMessage;

    /**
     * 开始处理时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;
}
