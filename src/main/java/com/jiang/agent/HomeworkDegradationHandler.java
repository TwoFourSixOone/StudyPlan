package com.jiang.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.DailyPlanQuestionRef;
import com.jiang.dto.KnowledgePointWithMastery;
import com.jiang.mapper.AgentDegradationLogMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.model.entity.AgentDegradationLog;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.enums.AgentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 出题 Agent 降级处理器
 * 当 Agent 调用失败时，按掌握度从低到高生成默认计划（每知识点 1-2 题，总题数 ≤ 15）
 * <p>
 * IMP-7: 降级日志写入前做脱敏处理（截断 + 移除敏感信息）
 * IMP-F: 降级失败时更新 daily_plan.status = cancelled，记录日志，抛异常
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Component
@Slf4j
public class HomeworkDegradationHandler {

    private static final int MAX_QUESTIONS = 15;
    private static final int MAX_LOG_LENGTH = 500;

    @Autowired
    private QuestionBankMapper questionBankMapper;

    @Autowired
    private AgentDegradationLogMapper degradationLogMapper;

    /**
     * 生成默认计划（降级方案）
     *
     * @param knowledgePoints 知识点列表（含掌握度）
     * @param tenantId        租户 ID
     * @return 题目引用列表
     */
    public List<DailyPlanQuestionRef> generateDefaultPlan(
            List<KnowledgePointWithMastery> knowledgePoints, String tenantId) {

        // 1. 按掌握度从低到高排序（优先练习薄弱知识点）
        List<KnowledgePointWithMastery> sorted = knowledgePoints.stream()
                .sorted(Comparator.comparing(
                        kp -> kp.getMasteryScore() != null ? kp.getMasteryScore() : java.math.BigDecimal.ZERO))
                .collect(Collectors.toList());

        List<DailyPlanQuestionRef> refs = new ArrayList<>();
        int totalQuestions = 0;

        // 2. 逐知识点分配 1-2 题
        for (KnowledgePointWithMastery kp : sorted) {
            if (totalQuestions >= MAX_QUESTIONS) {
                break;
            }

            // 查询该知识点下的 basic 难度题库题目（最多 2 题）
            List<QuestionBank> questions = questionBankMapper.selectList(
                    new LambdaQueryWrapper<QuestionBank>()
                            .eq(QuestionBank::getKnowledgePointId, kp.getKnowledgePointId())
                            .eq(QuestionBank::getTenantId, tenantId)
                            .eq(QuestionBank::getDifficulty, "basic")
                            .last("LIMIT 2")
            );

            if (questions.isEmpty()) {
                log.warn("降级：知识点 {} 下无 basic 难度题目，跳过", kp.getKnowledgePointId());
                continue;
            }

            for (QuestionBank q : questions) {
                if (totalQuestions >= MAX_QUESTIONS) {
                    break;
                }
                refs.add(new DailyPlanQuestionRef(q.getQuestionId(), "bank", "review"));
                totalQuestions++;
            }
        }

        // IMP-F: 降级失败（无题目可生成）
        if (refs.isEmpty()) {
            throw new BusinessException(ErrorCodes.AGENT_DEGRADED,
                    "降级处理失败：无可用的基础题目");
        }

        return refs;
    }

    /**
     * 记录降级日志
     * IMP-7: 写入前做脱敏处理（截断 + 移除敏感信息）
     *
     * @param inputSummary  输入摘要
     * @param failureReason 失败原因
     * @param tenantId      租户 ID
     */
    public void logDegradation(String inputSummary, String failureReason, String tenantId) {
        AgentDegradationLog logEntry = new AgentDegradationLog();
        logEntry.setAgentType(AgentType.QUESTION_GEN.getCode());
        logEntry.setInputSummary(sanitize(inputSummary));
        logEntry.setFailureReason(sanitize(failureReason));
        logEntry.setDegradationAction("return_default");
        logEntry.setTenantId(tenantId);
        logEntry.setCreateTime(LocalDateTime.now());
        logEntry.setUpdateTime(LocalDateTime.now());

        try {
            degradationLogMapper.insert(logEntry);
        } catch (Exception e) {
            // 日志写入失败不影响主流程，仅记录错误
            log.error("降级日志写入失败: {}", e.getMessage());
        }
    }

    /**
     * 脱敏处理：截断至最大 500 字符，移除敏感信息
     */
    private String sanitize(String input) {
        if (input == null) {
            return "";
        }
        // 移除可能的敏感信息（API Key、studentId 等）
        String sanitized = input
                .replaceAll("(?i)(api[-_]?key|token|secret)[=:]\\s*\\S+", "$1=***")
                .replaceAll("studentId[=:]\\s*\\d+", "studentId=***");
        // 截断至最大长度
        if (sanitized.length() > MAX_LOG_LENGTH) {
            sanitized = sanitized.substring(0, MAX_LOG_LENGTH) + "...[truncated]";
        }
        return sanitized;
    }
}
