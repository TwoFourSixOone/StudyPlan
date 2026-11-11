package com.jiang.agent;

import com.jiang.mapper.AgentDegradationLogMapper;
import com.jiang.model.entity.AgentDegradationLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Agent 降级日志写入器（隐性约定 #12）
 *
 * <p>所有降级日志统一在这里脱敏与截断（≤500 字符），并做"写入失败不影响主流程"兜底。
 * 脱敏范围：学生标识/密钥类键值、图片 base64（data URL 与超长 base64 串）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Component
@Slf4j
public class AgentDegradationLogger {

    /** 试卷视觉解析的 Agent 类型（零 DDL：agent_degradation_log.agent_type 为 VARCHAR） */
    public static final String AGENT_TYPE_EXAM_PARSE = "exam_parse";

    /** 降级日志文本上限（约定 #12） */
    public static final int MAX_TEXT_LENGTH = 500;

    private static final String ELLIPSIS = "...[truncated]";

    private static final java.util.regex.Pattern SENSITIVE_KV =
            java.util.regex.Pattern.compile("(studentId|student_id|openid|token|secret|api[-_]?key)=\\w+",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern DATA_URL =
            java.util.regex.Pattern.compile("data:[^,;]*;base64,[A-Za-z0-9+/=\\s]+");

    private static final java.util.regex.Pattern LONG_BASE64 =
            java.util.regex.Pattern.compile("[A-Za-z0-9+/]{200,}={0,2}");

    @Autowired
    private AgentDegradationLogMapper degradationLogMapper;

    /**
     * 写入一条降级日志
     *
     * @param agentType          Agent 类型（{@link #AGENT_TYPE_EXAM_PARSE} 等）
     * @param inputSummary       输入摘要（可为 null）
     * @param failureReason      失败原因（可为 null）
     * @param degradationAction  降级动作（如 skip_image / pending_manual_review）
     * @param tenantId           租户 ID
     */
    public void log(String agentType, String inputSummary, String failureReason,
                    String degradationAction, String tenantId) {
        AgentDegradationLog entry = new AgentDegradationLog();
        entry.setAgentType(agentType);
        entry.setInputSummary(sanitize(inputSummary));
        entry.setFailureReason(sanitize(failureReason));
        entry.setDegradationAction(degradationAction);
        entry.setTenantId(tenantId);
        entry.setCreateTime(LocalDateTime.now());
        entry.setUpdateTime(LocalDateTime.now());
        try {
            degradationLogMapper.insert(entry);
        } catch (Exception e) {
            // 日志写入失败不得影响主流程
            log.error("降级日志写入失败: {}", e.getMessage());
        }
    }

    /**
     * 脱敏 + 截断（≤500 字符）：移除学生标识、密钥类键值与 base64 图片内容
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String text = SENSITIVE_KV.matcher(raw).replaceAll("$1=***");
        text = DATA_URL.matcher(text).replaceAll("[image-base64]");
        text = LONG_BASE64.matcher(text).replaceAll("[base64]");
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH - ELLIPSIS.length()) + ELLIPSIS;
        }
        return text;
    }
}
