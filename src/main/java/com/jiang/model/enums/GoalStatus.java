package com.jiang.model.enums;

import lombok.Getter;

/**
 * 学习目标状态枚举
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Getter
public enum GoalStatus {

    /**
     * 进行中
     */
    ACTIVE("active", "进行中"),

    /**
     * 已完成
     */
    COMPLETED("completed", "已完成"),

    /**
     * 已过期
     */
    EXPIRED("expired", "已过期");

    private final String code;
    private final String description;

    GoalStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static GoalStatus fromCode(String code) {
        for (GoalStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown GoalStatus code: " + code);
    }
}
