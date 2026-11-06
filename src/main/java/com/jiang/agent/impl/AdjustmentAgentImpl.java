package com.jiang.agent.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.AdjustmentAgent;
import com.jiang.client.AgentScopeClient;
import com.jiang.common.exception.AdjustmentException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.AgentOutputValidator;
import com.jiang.dto.PlanDateModification;
import com.jiang.dto.PlanDiffDTO;
import com.jiang.dto.TriggerSignalDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 调整 Agent 实现
 * 使用 AgentScope SDK 调用 LLM，根据触发信号生成计划调整方案
 * 修复 Issue-3：移除 Mapper 注入，Agent 只封装 LLM 调用
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Component
@Slf4j
public class AdjustmentAgentImpl implements AdjustmentAgent {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_RETRY = 2;

    @Autowired
    private AgentScopeClient agentScopeClient;

    @Autowired
    private AgentOutputValidator validator;

    @Override
    public PlanDiffDTO generateDiff(List<TriggerSignalDTO> signals, Long goalId,
                                     List<String> criticalErrorQuestionIds) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount <= MAX_RETRY) {
            try {
                return doGenerate(signals, goalId, criticalErrorQuestionIds);
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                log.warn("调整 Agent 调用失败，重试 {}/{}: goalId={}, error={}",
                        retryCount, MAX_RETRY, goalId, e.getMessage());
            }
        }

        // 重试耗尽，降级处理：抛出异常，由 Service 层处理
        log.error("调整 Agent 重试耗尽: goalId={}", goalId);
        throw new AdjustmentException("调整 Agent 调用失败: " + lastException.getMessage());
    }

    private PlanDiffDTO doGenerate(List<TriggerSignalDTO> signals, Long goalId,
                                     List<String> criticalErrorQuestionIds) {
        // 1. 构造 Prompt
        String prompt = buildAdjustmentPrompt(signals, goalId, criticalErrorQuestionIds);

        // 2. 调用 AgentScope LLM
        String agentOutput;
        try {
            agentOutput = agentScopeClient.call(prompt);
        } catch (BusinessException e) {
            log.error("调整 Agent LLM 调用失败: {}", e.getMessage(), e);
            throw new AdjustmentException("调整 Agent LLM 调用失败: " + e.getMessage(), e);
        }

        // 3. 校验输出
        AgentOutputValidator.ValidationResult validationResult = validator.validateAdjustment(agentOutput);
        if (!validationResult.isSuccess()) {
            log.warn("调整 Agent 输出校验失败: {}", validationResult.getMessage());
            throw new AdjustmentException("调整 Agent 输出格式非法: " + validationResult.getMessage());
        }

        // 4. 解析结果
        return parseAdjustmentResult(agentOutput, criticalErrorQuestionIds);
    }

    private String buildAdjustmentPrompt(List<TriggerSignalDTO> signals, Long goalId,
                                          List<String> criticalErrorQuestionIds) {
        // 构造信号文本
        String signalsText = signals.stream()
                .map(s -> String.format("- 类型: %s\n  描述: %s\n  详情: %s",
                        s.getSignalType(), s.getDescription(),
                        s.getDetail() != null ? s.getDetail() : "无"))
                .collect(Collectors.joining("\n"));

        // 构造超期错题文本
        String errorQuestionsText;
        if (criticalErrorQuestionIds == null || criticalErrorQuestionIds.isEmpty()) {
            errorQuestionsText = "无超期错题";
        } else {
            errorQuestionsText = criticalErrorQuestionIds.stream()
                    .map(id -> "- 错题 ID: " + id)
                    .collect(Collectors.joining("\n"));
        }

        String promptTemplate = loadPromptTemplate();
        return promptTemplate
                .replace("{signals}", signalsText)
                .replace("{goalId}", String.valueOf(goalId))
                .replace("{criticalErrorQuestions}", errorQuestionsText);
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/versions/adjustment_v1.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        } catch (IOException e) {
            log.error("加载调整 Agent Prompt 模板失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "调整 Agent Prompt 模板加载失败");
        }
    }

    private PlanDiffDTO parseAdjustmentResult(String agentOutput, List<String> criticalErrorQuestionIds) {
        try {
            JsonNode json = OBJECT_MAPPER.readTree(agentOutput);

            PlanDiffDTO diff = new PlanDiffDTO();
            diff.setReason(json.has("reason") ? json.get("reason").asText("") : "Agent 调整建议");
            diff.setCriticalErrorQuestionIds(criticalErrorQuestionIds);

            // 解析 modifiedDates
            List<PlanDateModification> modifiedDates = new ArrayList<>();
            if (json.has("modifiedDates") && json.get("modifiedDates").isArray()) {
                for (JsonNode item : json.get("modifiedDates")) {
                    PlanDateModification mod = new PlanDateModification();

                    String dateStr = item.has("date") ? item.get("date").asText() : null;
                    if (dateStr != null) {
                        try {
                            mod.setDate(LocalDate.parse(dateStr));
                        } catch (Exception e) {
                            log.warn("日期解析失败: {}", dateStr);
                            continue;
                        }
                    }

                    mod.setNewContent(item.has("newContent") ? item.get("newContent").asText("") : "");
                    mod.setReason(item.has("reason") ? item.get("reason").asText("") : "");
                    modifiedDates.add(mod);
                }
            }

            diff.setModifiedDates(modifiedDates);

            log.info("调整 Agent 解析完成: reason={}, modifiedDates={}", diff.getReason(), modifiedDates.size());
            return diff;
        } catch (Exception e) {
            log.error("调整 Agent 输出解析失败: {}", e.getMessage(), e);
            throw new AdjustmentException("调整 Agent 输出解析失败: " + e.getMessage());
        }
    }
}
