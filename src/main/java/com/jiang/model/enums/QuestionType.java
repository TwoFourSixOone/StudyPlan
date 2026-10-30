package com.jiang.model.enums;

import lombok.Getter;

/**
 * 题型枚举（题目维度）
 * 注意：与 QuestionTaskType 不同，QuestionType 表示题目的形式（数值/选择），
 * QuestionTaskType 表示作业中的任务类型（回顾/重做/举一反三/综合）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Getter
public enum QuestionType {

    /**
     * 数值题
     */
    NUMERIC("numeric", "数值题"),

    /**
     * 选择题
     */
    CHOICE("choice", "选择题");

    private final String code;
    private final String description;

    QuestionType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static QuestionType fromCode(String code) {
        for (QuestionType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown QuestionType code: " + code);
    }
}
