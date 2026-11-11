package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 提交解析 / 重试解析返回（design §2.2：POST /papers/{paperId}/submit|retry）
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ExamSubmitVO {

    /** 试卷 ID */
    private Long paperId;

    /** 解析任务 ID */
    private Long taskId;

    /** 试卷状态：pending */
    private String status;

    /** 整卷重试次数（retry 接口返回，0..2） */
    private Integer ocrRetryCount;
}
