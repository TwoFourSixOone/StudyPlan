package com.jiang.common.exception;

/**
 * OCR 识别异常
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public class OcrException extends RuntimeException {

    public OcrException(String message) {
        super(message);
    }

    public OcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
