package com.jiang.model.enums;

import lombok.Getter;

/**
 * 知识点状态枚举
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Getter
public enum KnowledgePointStatus {

    /**
     * 待校对
     */
    PENDING_REVIEW("pending_review", "待校对"),

    /**
     * 已启用
     */
    ACTIVE("active", "已启用"),

    /**
     * 已停用
     */
    DISABLED("disabled", "已停用");

    private final String code;
    private final String description;

    KnowledgePointStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static KnowledgePointStatus fromCode(String code) {
        for (KnowledgePointStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown KnowledgePointStatus code: " + code);
    }
}
