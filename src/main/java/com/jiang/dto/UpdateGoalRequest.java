package com.jiang.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 修改学习目标请求 DTO
 *
 * <p>null 语义说明：direction 和 targetScore 为 null 时表示不修改该字段（而非清空）。
 * 若需清空 direction，前端传空字符串 ""；targetScore 不支持清空（业务上无意义）。
 * endDate 为 null 时表示不修改结束日期，且结束日期只能延长不能缩短。</p>
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@Data
public class UpdateGoalRequest {

    @Size(max = 500, message = "学习方向描述不能超过 500 字")
    private String direction;        // null=不修改，空字符串=清空

    @Min(value = 0, message = "目标分数不能小于 0")
    @Max(value = 150, message = "目标分数不能大于 150")
    private Integer targetScore;     // null=不修改；不支持清空（业务无意义）

    private LocalDate endDate;       // null=不修改；只能延长，不能缩短
}
