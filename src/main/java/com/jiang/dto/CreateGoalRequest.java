package com.jiang.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 创建学习目标请求 DTO
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@Data
public class CreateGoalRequest {

    @NotNull(message = "科目 ID 不能为空")
    private Long subjectId;

    @Size(max = 500, message = "学习方向描述不能超过 500 字")
    private String direction;

    @Min(value = 0, message = "目标分数不能小于 0")
    @Max(value = 150, message = "目标分数不能大于 150")
    private Integer targetScore;

    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;

    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;
}
