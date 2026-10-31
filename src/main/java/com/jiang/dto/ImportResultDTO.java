package com.jiang.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据导入结果 DTO
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportResultDTO {

    /**
     * 导入日志 ID
     */
    private Long importId;

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
    private int successCount;

    /**
     * 失败数
     */
    private int failCount;

    /**
     * 跳过数（ID 冲突）
     */
    private int skipCount;

    /**
     * 错误摘要（前 5 条，格式："[recordId] 原因"）
     */
    private List<String> errorSamples;

    /**
     * 导入完成时间
     */
    private LocalDateTime importedAt;
}
