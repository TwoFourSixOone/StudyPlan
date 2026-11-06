package com.jiang.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 录入考试成绩请求 DTO（修复 BLOCK-6 新增）
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class ExamGradeRequest {

    /**
     * 总分
     */
    @NotNull(message = "总分不能为空")
    private BigDecimal totalScore;

    /**
     * 逐题成绩明细
     */
    @NotEmpty(message = "题目明细不能为空")
    private List<ExamGradeItemVO> items;
}
