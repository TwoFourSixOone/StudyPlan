package com.jiang.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 触发调整请求 DTO（修复 IMP-23）
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class AdjustRequestDTO {

    @NotNull(message = "目标 ID 不能为空")
    private Long goalId;
}
