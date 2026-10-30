package com.jiang.model.enums;

import lombok.Getter;

/**
 * 题目来源枚举
 * 区分题目是来自题库原题还是 Agent 生成的新题
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Getter
public enum QuestionSource {

    /**
     * 题库原题
     */
    BANK("bank", "题库原题"),

    /**
     * Agent 生成的新题
     */
    GENERATED("generated", "Agent 生成新题");

    private final String code;
    private final String description;

    QuestionSource(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static QuestionSource fromCode(String code) {
        for (QuestionSource source : values()) {
            if (source.code.equals(code)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown QuestionSource code: " + code);
    }
}
