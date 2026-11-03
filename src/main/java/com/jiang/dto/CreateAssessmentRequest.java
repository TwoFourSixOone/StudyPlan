package com.jiang.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建评测请求 DTO
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
public class CreateAssessmentRequest {

    /**
     * 科目 ID
     */
    @NotNull(message = "科目 ID 不能为空")
    private Long subjectId;
}
