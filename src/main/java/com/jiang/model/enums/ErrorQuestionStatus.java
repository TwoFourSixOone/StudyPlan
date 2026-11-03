package com.jiang.model.enums;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import lombok.Getter;

/**
 * 错题状态枚举
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Getter
public enum ErrorQuestionStatus {

    /**
     * 活跃（需重做）
     */
    ACTIVE("active", "活跃"),

    /**
     * 暂停（掌握度达标，暂不需重做）
     */
    PAUSED("paused", "暂停"),

    /**
     * 已完成（stage > 5，移出队列）
     */
    COMPLETED("completed", "已完成");

    private final String code;
    private final String description;

    ErrorQuestionStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 状态码
     * @return 对应枚举
     * @throws BusinessException 未知状态码
     */
    public static ErrorQuestionStatus fromCode(String code) {
        for (ErrorQuestionStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new BusinessException(ErrorCodes.BAD_REQUEST, "Unknown ErrorQuestionStatus: " + code);
    }
}
