package com.jiang.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单题答案 DTO
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
public class AnswerItem {

    /**
     * 题目 ID
     */
    @NotNull(message = "题目 ID 不能为空")
    private Long questionId;

    /**
     * 学生答案（允许为空，表示未作答）
     */
    private String answer;
}
