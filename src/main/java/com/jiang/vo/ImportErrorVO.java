package com.jiang.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入错误记录 VO
 * 注意：字段名与实际实体 DataImportErrorLog 对齐
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class ImportErrorVO {

    /** 错误 ID */
    private Long id;

    /** 所属导入批次 ID */
    private Long importId;

    /** 记录 ID */
    private String recordId;

    /** 错误原因 */
    private String errorReason;

    /** 创建时间 */
    private LocalDateTime createTime;
}
