package com.jiang.model.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import lombok.Getter;

/**
 * 学习记录类型枚举
 * 修复 BLOCK-11：recordType 枚举校验
 *
 * <p>JSON 序列化输出小写 code（如 "homework"），与前端筛选参数、展示文案保持一致；
 * MyBatis 映射时数据库/SQL 需提供枚举名（如 "HOMEWORK"）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Getter
public enum RecordType {
    HOMEWORK("homework"),
    ASSESSMENT("assessment"),
    EXAM("exam");

    /**
     * @JsonValue：序列化输出小写 code，前端展示与回传均使用该值
     */
    @JsonValue
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
