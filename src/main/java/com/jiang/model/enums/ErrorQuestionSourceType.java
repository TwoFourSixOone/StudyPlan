package com.jiang.model.enums;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import lombok.Getter;

/**
 * BLOCK-3 修复：错题来源类型枚举
 * IMP-C 修复：ASSESSMENT/EXAM 暂不写入，sourceId 语义待后续 Change 定义
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Getter
public enum ErrorQuestionSourceType {

    /**
     * 每日作业
     */
    HOMEWORK("homework", "每日作业"),

    /**
     * 专项评测（暂不写入，待 Change 4）
     */
    ASSESSMENT("assessment", "专项评测"),

    /**
     * 考试（暂不写入，待 Change 4）
     */
    EXAM("exam", "考试");

    private final String code;
    private final String description;

    ErrorQuestionSourceType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据 code 获取枚举
     *
     * @param code 来源码
     * @return 对应枚举
     * @throws BusinessException 未知来源码
     */
    public static ErrorQuestionSourceType fromCode(String code) {
        for (ErrorQuestionSourceType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new BusinessException(ErrorCodes.BAD_REQUEST, "Unknown ErrorQuestionSourceType: " + code);
    }
}
