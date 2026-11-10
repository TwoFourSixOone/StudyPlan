package com.jiang.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.client.AgentScopeClient;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.AgentOutputValidator;
import com.jiang.dto.PlanDraft;
import com.jiang.dto.PlanningContextDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 规划 Agent：把学习目标拆成每日计划骨架（产品规则 #1）
 *
 * <p>输出只包含"每天学哪些知识点、做多少题"，**不含题目内容**；
 * 题目由每日出题环节按骨架生成。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Component
@Slf4j
public class PlanningAgent {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_AGENT_RETRIES = 2;

    @Autowired
    private AgentScopeClient agentScopeClient;

    @Autowired
    private AgentOutputValidator validator;

    /**
     * 生成整段计划
     *
     * @param context 由 Service 层查询真实数据组装
     * @return 计划草稿
     */
    public PlanDraft generate(PlanningContextDTO context) {
        String prompt = buildPrompt(context);
        String template = loadPromptTemplate();

        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_AGENT_RETRIES; attempt++) {
            try {
                String agentOutput = agentScopeClient.call(prompt);
                AgentOutputValidator.ValidationResult result = validator.validatePlanning(agentOutput);
                if (!result.isSuccess()) {
                    log.warn("规划 Agent 输出校验失败 (attempt {}/{}): {}",
                            attempt + 1, MAX_AGENT_RETRIES, result.getMessage());
                    lastException = new IllegalStateException(result.getMessage());
                    continue;
                }
                return parse(agentOutput);
            } catch (Exception e) {
                log.warn("规划 Agent 调用失败 (attempt {}/{}): {}", attempt + 1, MAX_AGENT_RETRIES, e.getMessage());
                lastException = e;
            }
        }
        throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED,
                lastException == null ? "规划 Agent 失败" : lastException.getMessage());
    }

    private String buildPrompt(PlanningContextDTO ctx) {
        String kpList = ctx.getKnowledgePoints() == null ? "" : ctx.getKnowledgePoints().stream()
                .map(kp -> {
                    String mastery = kp.getMastery() == null ? "未测" : String.format("%.2f", kp.getMastery());
                    return String.format("- %s | %s | 章节=%s | 年级=%s | 掌握度=%s",
                            kp.getKpId(), kp.getName(),
                            kp.getChapter() == null ? "-" : kp.getChapter(),
                            kp.getGradeRange() == null ? "全年级" : kp.getGradeRange(),
                            mastery);
                })
                .collect(Collectors.joining("\n"));

        String goalInfo = String.format(
                "科目：%s；周期：%s ~ %s；目标分数：%s；方向：%s；学生年级：%s",
                ctx.getSubjectName(), ctx.getStartDate(), ctx.getEndDate(),
                ctx.getTargetScore() == null ? "未设置" : ctx.getTargetScore(),
                ctx.getDirection() == null ? "未设置" : ctx.getDirection(),
                ctx.getStudentGrade() == null ? "未设置" : ctx.getStudentGrade());

        return loadPromptTemplate()
                .replace("{goalInfo}", goalInfo)
                .replace("{kpList}", kpList)
                .replace("{masteryInfo}", "见上方知识点清单中的掌握度列")
                .replace("{startDate}", String.valueOf(ctx.getStartDate()))
                .replace("{endDate}", String.valueOf(ctx.getEndDate()));
    }

    private PlanDraft parse(String agentOutput) throws Exception {
        JsonNode json = OBJECT_MAPPER.readTree(agentOutput);
        PlanDraft draft = new PlanDraft();
        draft.setReason(json.has("reason") ? json.get("reason").asText("") : "");

        List<PlanDraft.PlanDay> days = new ArrayList<>();
        for (JsonNode day : json.get("days")) {
            PlanDraft.PlanDay d = new PlanDraft.PlanDay();
            d.setDate(LocalDate.parse(day.get("date").asText()));
            List<String> kpIds = new ArrayList<>();
            for (JsonNode kp : day.get("kpIds")) {
                kpIds.add(kp.asText());
            }
            d.setKpIds(kpIds);
            d.setQuestionCount(day.get("questionCount").asInt());
            d.setFocus(day.has("focus") ? day.get("focus").asText("") : "");
            days.add(d);
        }
        draft.setDays(days);
        return draft;
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/versions/planning_v1.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        } catch (Exception e) {
            log.error("加载规划 Agent Prompt 模板失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "规划 Agent Prompt 模板加载失败");
        }
    }
}
