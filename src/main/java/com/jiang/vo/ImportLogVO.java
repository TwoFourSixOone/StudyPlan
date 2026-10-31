package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 导入日志 VO
 * 字段映射自 DataImportLog 实体
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportLogVO {

    /**
     * 导入 ID
     */
    private Long id;

    /**
     * 数据类型：topic / question / dependency / standard
     */
    private String dataType;

    /**
     * 文件来源
     */
    private String fileSource;

    /**
     * 成功数
     */
    private Integer successCount;

    /**
     * 失败数
     */
    private Integer failCount;

    /**
     * 跳过数
     */
    private Integer skipCount;

    /**
     * 导入时间
     */
    private LocalDateTime importedAt;
}
