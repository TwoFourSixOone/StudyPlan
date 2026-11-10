package com.jiang.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 学生设置年级请求（产品规则 #11）
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Data
public class UpdateGradeRequest {

    /**
     * 年级：1–12（1–6 小学、7–9 初中、10–12 高中）
     */
    @NotNull(message = "年级不能为空")
    @Min(value = 1, message = "年级最小为 1")
    @Max(value = 12, message = "年级最大为 12")
    private Integer grade;
}
