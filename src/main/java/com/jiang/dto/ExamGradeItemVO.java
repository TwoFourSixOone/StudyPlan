package com.jiang.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 单题成绩明细 VO（修复 BLOCK-6 新增）
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class ExamGradeItemVO {

    /**
     * 题目序号
     */
    @NotNull(message = "题目序号不能为空")
    private Integer questionIndex;

    /**
     * 学生答案
     */
    private String studentAnswer;

    /**
     * 是否正确：0-错误，1-正确
     */
    @NotNull(message = "是否正确不能为空")
    private Integer isCorrect;

    /**
     * 得分
     */
    private BigDecimal score;

    /**
     * 满分
     */
    private BigDecimal fullScore;
}
