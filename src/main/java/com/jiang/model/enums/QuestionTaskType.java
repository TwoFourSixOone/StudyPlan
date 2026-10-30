package com.jiang.model.enums;

import lombok.Getter;

/**
 * 作业任务类型枚举
 * 注意：与 QuestionType 不同，QuestionTaskType 表示作业中的任务类型，
 * QuestionType 表示题目的形式（数值/选择）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Getter
public enum QuestionTaskType {

    /**
     * 知识回顾
     */
    REVIEW("review", "知识回顾"),

    /**
     * 错题重做
     */
    REDO("redo", "错题重做"),

    /**
     * 举一反三（Agent 模拟新题）
     */
    SIMILAR("similar", "举一反三"),

    /**
     * 综合提升
     */
    COMPREHENSIVE("comprehensive", "综合提升");

    private final String code;
    private final String description;

    QuestionTaskType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static QuestionTaskType fromCode(String code) {
        for (QuestionTaskType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown QuestionTaskType code: " + code);
    }
}
