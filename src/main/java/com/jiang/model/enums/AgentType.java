package com.jiang.model.enums;

import lombok.Getter;

/**
 * Agent 类型枚举
 * 避免魔法字符串，统一标识不同类型的 Agent
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Getter
public enum AgentType {

    /** 出题 Agent */
    QUESTION_GEN("question_gen", "出题 Agent"),

    /** 归因 Agent */
    ATTRIBUTION("attribution", "归因 Agent"),

    /** 计划 Agent */
    PLANNING("planning", "计划 Agent"),

    /** 调整 Agent */
    ADJUSTMENT("adjustment", "调整 Agent"),

    /** 检查 Agent（Change 8: 举一反三新题校验） */
    QUESTION_CHECK("question_check", "检查 Agent");

    private final String code;
    private final String description;

    AgentType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static AgentType fromCode(String code) {
        for (AgentType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown AgentType code: " + code);
    }
}
