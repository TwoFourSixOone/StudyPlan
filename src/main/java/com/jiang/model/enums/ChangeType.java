package com.jiang.model.enums;

import lombok.Getter;

/**
 * 掌握度变更类型枚举
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Getter
public enum ChangeType {

    /**
     * 每日作业
     */
    HOMEWORK("homework", "每日作业"),

    /**
     * 专项评测
     */
    ASSESSMENT("assessment", "专项评测"),

    /**
     * 考试
     */
    EXAM("exam", "考试");

    private final String code;
    private final String description;

    ChangeType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static ChangeType fromCode(String code) {
        for (ChangeType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown ChangeType code: " + code);
    }
}
