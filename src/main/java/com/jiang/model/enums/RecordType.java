package com.jiang.model.enums;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import lombok.Getter;

/**
 * 学习记录类型枚举
 * 修复 BLOCK-11：recordType 枚举校验
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Getter
public enum RecordType {
    HOMEWORK("homework"),
    ASSESSMENT("assessment"),
    EXAM("exam");

    private final String code;

    RecordType(String code) {
        this.code = code;
    }

    /**
     * 修复 BLOCK-11：非法值直接抛 400
     * 支持 trim + 大小写不敏感
     */
    public static RecordType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_RECORD_TYPE);
        }
        String trimmed = code.trim().toLowerCase();
        for (RecordType type : values()) {
            if (type.code.equals(trimmed)) {
                return type;
            }
        }
        throw new BusinessException(ErrorCodes.INVALID_RECORD_TYPE);
    }
}
