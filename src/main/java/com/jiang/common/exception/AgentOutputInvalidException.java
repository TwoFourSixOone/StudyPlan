package com.jiang.common.exception;

/**
 * Agent 输出格式异常
 * 当 Agent 返回的内容无法解析为合法 JSON 或必要字段缺失时抛出
 * <p>
 * Change: 2026-11-03_similar_question_generation（NOTE-1）
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
public class AgentOutputInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgentOutputInvalidException(String message) {
        super(message);
    }

    public AgentOutputInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
