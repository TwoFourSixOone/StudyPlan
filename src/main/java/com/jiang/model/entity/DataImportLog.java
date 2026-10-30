package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据导入日志实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_import_log")
public class DataImportLog extends BaseEntity {

    /**
     * 导入 ID
     */
    @TableId("import_id")
    private Long importId;

    /**
     * 数据类型：knowledge_point / question / dependency / standard
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
