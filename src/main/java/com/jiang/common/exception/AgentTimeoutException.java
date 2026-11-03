package com.jiang.common.exception;

/**
 * Agent 调用超时异常
 * 当 AgentScopeClient 调用超过配置的超时时间（默认 30s）时抛出
 * <p>
 * Change: 2026-11-03_similar_question_generation（NOTE-1）
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
public class AgentTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgentTimeoutException(String message) {
        super(message);
    }

    public AgentTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
