package com.jiang.model.enums;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * 题目难度枚举
 * 对应 D_norm 映射值
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Getter
public enum QuestionDifficulty {

    /**
     * 基础题，D_norm = 0.3
     */
    BASIC("basic", new BigDecimal("0.3"), "基础"),

    /**
     * 标准题，D_norm = 0.6
     */
    STANDARD("standard", new BigDecimal("0.6"), "标准"),

    /**
     * 挑战题，D_norm = 0.9
     */
    CHALLENGE("challenge", new BigDecimal("0.9"), "挑战");

    private final String code;
    private final BigDecimal dNorm;
    private final String description;

    QuestionDifficulty(String code, BigDecimal dNorm, String description) {
        this.code = code;
        this.dNorm = dNorm;
        this.description = description;
    }

    public static QuestionDifficulty fromCode(String code) {
        for (QuestionDifficulty difficulty : values()) {
            if (difficulty.code.equals(code)) {
                return difficulty;
            }
        }
        throw new IllegalArgumentException("Unknown QuestionDifficulty code: " + code);
    }
}
