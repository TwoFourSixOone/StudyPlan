package com.jiang.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.QuestionCheckAgent;
import com.jiang.agent.QuestionGenAgent;
import com.jiang.common.exception.AgentOutputInvalidException;
import com.jiang.common.exception.AgentTimeoutException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.SimilarityDetector;
import com.jiang.mapper.AgentDegradationLogMapper;
import com.jiang.model.entity.AgentDegradationLog;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.enums.AgentType;
import com.jiang.service.SimilarQuestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 举一反三新题生成服务实现
 * 核心流程：循环 ≤ 5 次，每轮生成 → 相似度检测 → 检查 Agent 校验 → 通过/重试
 * <p>
 * Change: 2026-11-03_similar_question_generation
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Service
@Slf4j
public class SimilarQuestionServiceImpl implements SimilarQuestionService {

    private static final int MAX_ATTEMPTS = 5;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private QuestionGenAgent questionGenAgent;

    @Autowired
    private QuestionCheckAgent questionCheckAgent;

    @Autowired
    private SimilarityDetector similarityDetector;

    @Autowired
    private AgentDegradationLogMapper degradationLogMapper;

    /** 降级日志 inputSummary 最大长度（隐性约定 #12：脱敏截断） */
    private static final int MAX_LOG_INPUT_LENGTH = 500;

    @Override
    public List<GeneratedQuestion> generateWithValidation(
            Long studentId, String tenantId, String kpId,
            BigDecimal masteryScore, QuestionBank sourceQuestion,
            int targetCount, String sourceContent, List<String> existingContents) {

        // IMP-9：防御性复制，避免修改调用方的不可变 List
        List<String> mutableContents = new ArrayList<>(existingContents);
        mutableContents.add(sourceContent); // 加入原错题题干

        // H-6 修复：existingContents 来自 DB 的 content JSON 列（完整 JSON），
        // 相似度检测须以题干（stem）为粒度比较，避免 answer/analysis 干扰
        List<String> stemContents = extractStemsFromJsonList(mutableContents);

        List<GeneratedQuestion> allPassed = new ArrayList<>();
        int currentTarget = targetCount;
        int totalFailedCount = 0;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            List<GeneratedQuestion> roundPassed = new ArrayList<>();
            List<GeneratedQuestion> roundFailed = new ArrayList<>();

            try {
                doSingleAttempt(kpId, masteryScore, sourceQuestion, currentTarget,
                        stemContents, roundPassed, roundFailed);
            } catch (BusinessException e) {
                // 生成 Agent 调用失败 → 本轮全部视为失败
                log.error("举一反三生成 Agent 调用失败 (kpId={}, attempt={}): {}",
                        kpId, attempt + 1, e.getMessage());
                totalFailedCount += currentTarget;
                break;
            }

            allPassed.addAll(roundPassed);
            totalFailedCount += roundFailed.size();

            // 全部通过 → 提前返回
            if (roundFailed.isEmpty()) {
                return allPassed;
            }

            // 计算仍需补充的数量
            int stillNeeded = targetCount - allPassed.size();
            if (stillNeeded <= 0) {
                return allPassed;
            }

            // 更新 stemContents（加入本轮通过的题目题干，供下轮相似度检测）
            for (GeneratedQuestion passed : roundPassed) {
                stemContents.add(extractStem(passed));
            }
            currentTarget = stillNeeded;
        }

        // 循环 MAX_ATTEMPTS 次后仍有不足 → 记录降级日志（BLOCK-3：统一一次）
        if (totalFailedCount > 0) {
            logDegradation(kpId, totalFailedCount, targetCount - allPassed.size(), tenantId);
        }

        return allPassed; // 可能不足 targetCount
    }

    /**
     * 执行单轮生成 + 校验
     * 将候选题目分别放入 roundPassed 和 roundFailed
     */
    private void doSingleAttempt(String kpId, BigDecimal masteryScore,
                                  QuestionBank sourceQuestion, int targetCount,
                                  List<String> existingContents,
                                  List<GeneratedQuestion> roundPassed,
                                  List<GeneratedQuestion> roundFailed) {
        // 1. 出题 Agent 生成候选
        List<GeneratedQuestion> candidates = questionGenAgent.generateSimilar(
                kpId, masteryScore, sourceQuestion, targetCount, "progressive");

        // 2. 逐个校验
        for (GeneratedQuestion candidate : candidates) {
            // 2a. 相似度检测
            String stem = extractStem(candidate);
            double similarity = similarityDetector.detectSimilarity(stem, existingContents);
            if (similarity > SimilarityDetector.SIMILARITY_THRESHOLD) {
                roundFailed.add(candidate);
                log.warn("新题相似度过高: {} (similarity={})", kpId, similarity);
                continue;
            }

            // 2b. 检查 Agent 校验（BLOCK-6 保守策略 + BLOCK-7 降级 + IMP-16 格式异常）
            try {
                QuestionCheckAgent.CheckResult checkResult = questionCheckAgent.check(candidate, sourceQuestion);
                if (!checkResult.isPassed()) {
                    roundFailed.add(candidate);
                    log.warn("新题校验失败: {} (reason={})", kpId, checkResult.getReason());
                    continue;
                }
            } catch (AgentTimeoutException e) {
                // BLOCK-7：Agent 超时 → 降级为仅规则校验（项 1-4）
                boolean rulesPassed = questionCheckAgent.validateRulesOnly(candidate, sourceQuestion);
                if (rulesPassed) {
                    log.warn("检查 Agent 超时，降级为仅规则校验通过: {}", kpId);
                    // 放行：不 add 到 failed
                } else {
                    roundFailed.add(candidate);
                    log.warn("检查 Agent 超时，降级规则校验失败: {}", kpId);
                    continue;
                }
            } catch (AgentOutputInvalidException e) {
                // IMP-16：Agent 输出格式异常 → 视为校验失败，进入重试
                roundFailed.add(candidate);
                log.warn("检查 Agent 输出格式异常: {} (error={})", kpId, e.getMessage());
                continue;
            }

            // 3. 通过 → 标记已校验 + 计算 contentHash
            candidate.setSimilarityChecked(1);
            candidate.setContentHash(similarityDetector.computeContentHash(stem));
            roundPassed.add(candidate);
        }
    }

    /**
     * 从 GeneratedQuestion 的 content JSON 中提取题干（stem）
     */
    private String extractStem(GeneratedQuestion question) {
        try {
            Map<String, Object> content = OBJECT_MAPPER.readValue(question.getContent(),
                    new TypeReference<Map<String, Object>>() {});
            String stem = (String) content.get("stem");
            return stem != null ? stem : "";
        } catch (Exception e) {
            log.warn("提取题干失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 记录降级日志（统一一次，BLOCK-3）
     * H-1 修复：inputSummary 脱敏截断，移除学生标识等敏感信息
     */
    private void logDegradation(String kpId, int failedCount, int stillNeeded, String tenantId) {
        AgentDegradationLog logEntry = new AgentDegradationLog();
        logEntry.setAgentType(AgentType.QUESTION_CHECK.getCode());
        // 脱敏：仅包含知识点 ID 和数量统计，不含学生 ID / 租户原始值
        String rawSummary = String.format("kpId=%s, failedCount=%d, stillNeeded=%d", kpId, failedCount, stillNeeded);
        logEntry.setInputSummary(sanitize(rawSummary));
        logEntry.setFailureReason(String.format("举一反三 %d 次重试后仍有 %d 题未通过校验", MAX_ATTEMPTS, failedCount));
        logEntry.setDegradationAction("skip_similar_question");
        logEntry.setTenantId(tenantId);
        logEntry.setCreateTime(LocalDateTime.now());
        logEntry.setUpdateTime(LocalDateTime.now());

        try {
            degradationLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("降级日志写入失败: {}", e.getMessage());
        }
    }

    /**
     * 脱敏处理：截断至 MAX_LOG_INPUT_LENGTH，移除可能的敏感信息
     */
    private String sanitize(String input) {
        if (input == null) return "";
        // 移除 studentId / openid / token 等敏感字段模式
        String sanitized = input.replaceAll("(studentId|openid|token|secret)=\\w+", "$1=***");
        return sanitized.length() > MAX_LOG_INPUT_LENGTH
                ? sanitized.substring(0, MAX_LOG_INPUT_LENGTH) + "...[truncated]"
                : sanitized;
    }

    /**
     * 从 JSON 字符串列表中提取题干（stem）
     * H-6 修复：DB 存储的 content 列为完整 JSON（含 stem/answer/analysis），
     * 相似度检测须以 stem 为粒度，避免 answer/analysis 干扰判断
     */
    private List<String> extractStemsFromJsonList(List<String> jsonContents) {
        List<String> stems = new ArrayList<>(jsonContents.size());
        for (String json : jsonContents) {
            stems.add(extractStemFromJson(json));
        }
        return stems;
    }

    /**
     * 从单条 content JSON 中提取题干
     */
    private String extractStemFromJson(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            Map<String, Object> content = OBJECT_MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            String stem = (String) content.get("stem");
            return stem != null ? stem : json; // 解析失败时回退使用原始字符串
        } catch (Exception e) {
            // 非 JSON 格式（如纯文本题干），直接返回
            return json;
        }
    }
}
