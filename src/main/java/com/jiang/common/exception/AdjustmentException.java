package com.jiang.common.exception;

/**
 * 调整 Agent 异常
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public class AdjustmentException extends RuntimeException {

    public AdjustmentException(String message) {
        super(message);
    }

    public AdjustmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
