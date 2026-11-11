package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 创建试卷上传会话返回（design §2.2：POST /papers）
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ExamUploadVO {

    /** 试卷 ID */
    private Long paperId;

    /** 状态：uploading（新建/续传会话） */
    private String status;

    /** 声明图片张数（[1,8]） */
    private Integer imageCount;

    /** 是否复用已有 uploading 会话（续传） */
    private Boolean reused;
}
