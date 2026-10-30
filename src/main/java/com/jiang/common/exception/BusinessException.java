package com.jiang.common.exception;

import lombok.Getter;

/**
 * 业务异常
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private final Integer code;

    /**
     * 错误信息
     */
    private final String message;

    /**
     * 使用错误码枚举构造
     */
    public BusinessException(ErrorCodes errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }

    /**
     * 使用自定义错误码和信息构造
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    /**
     * 使用错误码枚举和自定义信息构造
     */
    public BusinessException(ErrorCodes errorCode, String customMessage) {
        super(customMessage);
        this.code = errorCode.getCode();
        this.message = customMessage;
    }
}
