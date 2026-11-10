package com.jiang.agent.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.AdjustmentAgent;
import com.jiang.client.AgentScopeClient;
import com.jiang.common.exception.AdjustmentException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.AgentOutputValidator;
import com.jiang.dto.AdjustmentContextDTO;
import com.jiang.dto.DailyPlanQuestionRef;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    public PlanDiffDTO generateDiff(List<TriggerSignalDTO> signals, AdjustmentContextDTO context,
                                     List<String> criticalErrorQuestionIds) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount <= MAX_RETRY) {
            try {
                return doGenerate(signals, context, criticalErrorQuestionIds);
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                log.warn("调整 Agent 调用失败，重试 {}/{}: goalId={}, error={}",
                        retryCount, MAX_RETRY, context.getGoalId(), e.getMessage());
            }
        }

        // 重试耗尽，降级处理：抛出异常，由 Service 层处理
        log.error("调整 Agent 重试耗尽: goalId={}", context.getGoalId());
        throw new AdjustmentException("调整 Agent 调用失败: " + lastException.getMessage());
    }

    private PlanDiffDTO doGenerate(List<TriggerSignalDTO> signals, AdjustmentContextDTO context,
                                     List<String> criticalErrorQuestionIds) {
        // 1. 构造 Prompt（v2：注入真实数据上下文）
        String prompt = buildAdjustmentPrompt(signals, context, criticalErrorQuestionIds);

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

        // 4. 解析结果（白名单防御：newContent 题目引用必须来自未来计划原有内容，防止 LLM 编造题目 ID）
        return parseAdjustmentResult(agentOutput, criticalErrorQuestionIds,
                buildQuestionWhitelist(context));
    }

    /**
     * 从未来计划原内容构建题目引用白名单（questionSource:questionId）
     * LLM 输出的 newContent 只能复用白名单内的题目引用，防止编造题目 ID 落库
     */
    private Set<String> buildQuestionWhitelist(AdjustmentContextDTO context) {
        Set<String> whitelist = new HashSet<>();
        if (context.getFuturePlans() == null) {
            return whitelist;
        }
        for (AdjustmentContextDTO.FuturePlanItem plan : context.getFuturePlans()) {
            if (plan.getContent() == null || plan.getContent().isBlank()) {
                continue;
            }
            try {
                List<DailyPlanQuestionRef> refs = OBJECT_MAPPER.readValue(
                        plan.getContent(), new TypeReference<List<DailyPlanQuestionRef>>() {});
                for (DailyPlanQuestionRef ref : refs) {
                    whitelist.add(ref.getQuestionSource() + ":" + ref.getQuestionId());
                }
            } catch (Exception e) {
                log.warn("未来计划 content 解析失败，跳过白名单收集: date={}, error={}",
                        plan.getDate(), e.getMessage());
            }
        }
        return whitelist;
    }

    private String buildAdjustmentPrompt(List<TriggerSignalDTO> signals, AdjustmentContextDTO context,
                                          List<String> criticalErrorQuestionIds) {
        // 构造信号文本
        String signalsText = signals.stream()
                .map(s -> String.format("- 类型: %s\n  描述: %s\n  详情: %s",
                        s.getSignalType(), s.getDescription(),
                        s.getDetail() != null ? s.getDetail() : "无"))
                .collect(Collectors.joining("\n"));

        // 构造目标信息文本（v2：注入真实目标详情，替代 v1 仅传 goalId）
        String goalInfoText = String.format(
                "- 目标 ID：%d\n- 科目：%s\n- 目标分数：%s\n- 方向：%s\n- 周期：%s ~ %s",
                context.getGoalId(),
                context.getSubjectName() != null ? context.getSubjectName() : "未知",
                context.getTargetScore() != null ? String.valueOf(context.getTargetScore()) : "未设置",
                context.getDirection() != null ? context.getDirection() : "无",
                context.getStartDate(), context.getEndDate());

        // 构造未来计划文本（v2：提供原计划内容，LLM 只能基于其调整）
        String futurePlansText;
        if (context.getFuturePlans() == null || context.getFuturePlans().isEmpty()) {
            futurePlansText = "暂无未来计划";
        } else {
            futurePlansText = context.getFuturePlans().stream()
                    .map(p -> String.format("- %s（状态: %s，原内容: %s）",
                            p.getDate(), p.getStatus(),
                            p.getContent() != null && !p.getContent().isBlank()
                                    ? p.getContent() : "无（待生成）"))
                    .collect(Collectors.joining("\n"));
        }

        // 构造未覆盖知识点文本（v2：真实清单，Service 层已截断）
        String uncoveredKpsText;
        if (context.getUncoveredKps() == null || context.getUncoveredKps().isEmpty()) {
            uncoveredKpsText = "无未覆盖知识点";
        } else {
            uncoveredKpsText = context.getUncoveredKps().stream()
                    .map(kp -> String.format("- %s: %s", kp.getKpId(), kp.getName()))
                    .collect(Collectors.joining("\n"));
        }

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
                .replace("{goalInfo}", goalInfoText)
                .replace("{futurePlans}", futurePlansText)
                .replace("{uncoveredKps}", uncoveredKpsText)
                .replace("{criticalErrorQuestions}", errorQuestionsText);
    }

    private String loadPromptTemplate() {
        try {
            // v2：新增 {goalInfo}/{futurePlans}/{uncoveredKps} 占位符，注入真实数据
            ClassPathResource resource = new ClassPathResource("prompts/versions/adjustment_v2.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        } catch (IOException e) {
            log.error("加载调整 Agent Prompt 模板失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "调整 Agent Prompt 模板加载失败");
        }
    }

    private PlanDiffDTO parseAdjustmentResult(String agentOutput, List<String> criticalErrorQuestionIds,
                                              Set<String> questionWhitelist) {
        try {
            JsonNode json = OBJECT_MAPPER.readTree(agentOutput);

            PlanDiffDTO diff = new PlanDiffDTO();
            diff.setReason(json.has("reason") ? json.get("reason").asText("") : "Agent 调整建议");
            diff.setCriticalErrorQuestionIds(criticalErrorQuestionIds);

            // 解析 modifiedDates（含白名单防御：newContent 题目引用不在白名单内时拒绝该条修改）
            List<PlanDateModification> modifiedDates = new ArrayList<>();
            if (json.has("modifiedDates") && json.get("modifiedDates").isArray()) {
                for (JsonNode item : json.get("modifiedDates")) {
                    PlanDateModification mod = new PlanDateModification();

                    String dateStr = item.has("date") ? item.get("date").asText() : null;
                    if (dateStr != null) {
                        try {
                            mod.setDate(LocalDate.parse(dateStr));
                        } catch (Exception e) {
                            log.warn("日期解析失败，拒绝该条修改: {}", dateStr);
                            continue;
                        }
                    }

                    String newContent = item.has("newContent") ? item.get("newContent").asText("") : "";
                    String violation = validateNewContent(newContent, questionWhitelist);
                    if (violation != null) {
                        // 产品规则 #8：拒绝引用非真实数据的修改，防止幻觉内容落库
                        log.warn("newContent 白名单校验失败，拒绝该条修改: date={}, reason={}",
                                dateStr, violation);
                        continue;
                    }
                    mod.setNewContent(newContent);

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

    /**
     * 校验 newContent：必须是题目引用数组，且每个引用（questionSource:questionId）在白名单内
     *
     * @return null 表示通过；否则返回拒绝原因
     */
    private String validateNewContent(String newContent, Set<String> questionWhitelist) {
        if (newContent == null || newContent.isBlank()) {
            return "newContent 为空";
        }
        JsonNode refs;
        try {
            refs = OBJECT_MAPPER.readTree(newContent);
        } catch (Exception e) {
            return "newContent 不是合法 JSON";
        }
        if (!refs.isArray()) {
            return "newContent 必须是题目引用数组";
        }
        for (JsonNode ref : refs) {
            String source = ref.has("questionSource") ? ref.get("questionSource").asText() : null;
            String taskType = ref.has("taskType") ? ref.get("taskType").asText() : null;
            if (!"bank".equals(source) && !"generated".equals(source)) {
                return "questionSource 非法: " + source;
            }
            if (!Set.of("review", "redo", "similar", "comprehensive").contains(taskType)) {
                return "taskType 非法: " + taskType;
            }
            if (!ref.has("questionId") || !ref.get("questionId").isNumber()) {
                return "questionId 缺失或非法";
            }
            String key = source + ":" + ref.get("questionId").asLong();
            if (!questionWhitelist.contains(key)) {
                return "题目引用不在未来计划原内容中（疑似编造）: " + key;
            }
        }
        return null;
    }
}
