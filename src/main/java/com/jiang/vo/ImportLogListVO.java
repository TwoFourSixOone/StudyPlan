package com.jiang.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入日志列表 VO
 * 注意：字段名与实际实体 DataImportLog 对齐
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class ImportLogListVO {

    /** 导入 ID */
    private Long id;

    /** 数据类型 */
    private String dataType;

    /** 文件来源 */
    private String fileSource;

    /** 成功数 */
    private Integer successCount;

    /** 失败数 */
    private Integer failCount;

    /** 跳过数 */
    private Integer skipCount;

    /** 导入时间 */
    private LocalDateTime importedAt;
}
