package com.jiang.model.enums;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 掌握度变更类型枚举
 * <p>
 * 用于标识掌握度更新的来源类型，不同来源具有不同的权重。
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Getter
public enum ChangeType {

    /**
     * 每日作业
     */
    HOMEWORK("homework", "每日作业", new BigDecimal("0.4")),

    /**
     * 专项评测
     */
    ASSESSMENT("assessment", "专项评测", new BigDecimal("0.7")),

    /**
     * 考试
     */
    EXAM("exam", "考试", BigDecimal.ONE);

    private final String code;
    private final String description;
    private final BigDecimal sourceWeight;

    ChangeType(String code, String description, BigDecimal sourceWeight) {
        this.code = code;
        this.description = description;
        this.sourceWeight = sourceWeight;
    }

    /**
     * 根据 code 获取枚举实例
     *
     * @param code 变更类型代码
     * @return 对应的枚举实例
     * @throws BusinessException 无效的 code
     */
    public static ChangeType fromCode(String code) {
        for (ChangeType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new BusinessException(ErrorCodes.BAD_REQUEST, "Unknown ChangeType code: " + code);
    }
}
