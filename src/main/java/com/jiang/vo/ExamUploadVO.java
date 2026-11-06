package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 试卷上传返回 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@Builder
public class ExamUploadVO {

    /**
     * 试卷 ID
     */
    private Long examPaperId;

    /**
     * 解析任务 ID
     */
    private Long taskId;

    /**
     * 状态：pending
     */
    private String status;
}
