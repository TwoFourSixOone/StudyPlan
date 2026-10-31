package com.jiang.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 数据导入请求 DTO
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@Data
public class ImportRequest {

    /**
     * 数据类型：topic / question / dependency / standard
     */
    @NotBlank(message = "dataType 不能为空")
    private String dataType;
}
