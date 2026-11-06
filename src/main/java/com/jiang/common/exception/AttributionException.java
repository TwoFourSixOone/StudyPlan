package com.jiang.common.exception;

/**
 * 归因 Agent 异常
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public class AttributionException extends RuntimeException {

    public AttributionException(String message) {
        super(message);
    }

    public AttributionException(String message, Throwable cause) {
        super(message, cause);
    }
}
