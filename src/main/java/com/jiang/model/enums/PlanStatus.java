package com.jiang.model.enums;

import lombok.Getter;

/**
 * 每日计划状态枚举
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Getter
public enum PlanStatus {

    /**
     * 待执行
     */
    PENDING("pending", "待执行"),

    /**
     * 待生成（出题 Agent 正在生成中）
     */
    PENDING_GENERATION("pending_generation", "待生成"),

    /**
     * 进行中
     */
    IN_PROGRESS("in_progress", "进行中"),

    /**
     * 已完成
     */
    COMPLETED("completed", "已完成"),

    /**
     * 已取消
     */
    CANCELLED("cancelled", "已取消");

    private final String code;
    private final String description;

    PlanStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static PlanStatus fromCode(String code) {
        for (PlanStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown PlanStatus code: " + code);
    }
}
