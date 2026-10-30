package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 数据导入错误记录实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_import_error_log")
public class DataImportErrorLog extends BaseEntity {

    /**
     * 错误 ID
     */
    @TableId("error_id")
    private Long errorId;

    /**
     * 所属导入批次 ID
     */
    private Long importId;

    /**
     * 记录 ID
     */
    private String recordId;

    /**
     * 错误原因
     */
    private String errorReason;
}
