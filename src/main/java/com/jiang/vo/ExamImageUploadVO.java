package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 追加单张试卷图片返回（design §2.2：POST /papers/{paperId}/images）
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ExamImageUploadVO {

    /** 试卷 ID */
    private Long paperId;

    /** 已上传张数 */
    private Integer uploadedCount;

    /** 试卷状态：uploading / pending */
    private String status;

    /** 达到张数上限自动触发解析时返回任务 ID（正常路径由 submit 触发，此字段可空） */
    private Long taskId;
}
