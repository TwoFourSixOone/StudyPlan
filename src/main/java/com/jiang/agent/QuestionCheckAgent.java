package com.jiang.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.client.AgentScopeClient;
import com.jiang.common.exception.AgentOutputInvalidException;
import com.jiang.common.exception.AgentTimeoutException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.AgentOutputValidator;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.QuestionBank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * 检查 Agent
 * 校验举一反三新题的质量：内容完整性、难度枚举合法性、知识点关联、答案合理性、解题逻辑相似性
 * <p>
 * Change: 2026-11-03_similar_question_generation
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Component
@Slf4j
public class QuestionCheckAgent {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> VALID_DIFFICULTIES = Set.of("basic", "standard", "challenge");

    @Autowired
    private AgentScopeClient agentScopeClient;

    @Autowired
    private AgentOutputValidator validator;

    /**
     * 校验新题质量（完整流程：规则校验 + Agent 语义校验）
     * <p>
     * 检查项：
     * 1. 内容完整性（stem/answer/formatType 非空）
     * 2. 难度枚举合法性（difficulty ∈ {basic, standard, challenge}）
     * 3. 知识点关联（kpId 匹配）
     * 4. 答案合理性（非空、长度 > 0）
     * 5. 解题逻辑相似性（Agent 语义判断，保守策略：宁可漏放不可误杀）
     *
     * @param question       待校验的新题
     * @param sourceQuestion 原错题
     * @return 校验结果
     * @throws AgentTimeoutException      Agent 调用超时
     * @throws AgentOutputInvalidException Agent 输出格式异常
     */
    public CheckResult check(GeneratedQuestion question, QuestionBank sourceQuestion) {
        // 项 1-4：规则校验
        String ruleFailReason = validateRules(question, sourceQuestion);
        if (ruleFailReason != null) {
            return CheckResult.fail(ruleFailReason);
        }

        // 项 5：Agent 语义校验（解题逻辑相似性）
        try {
            return checkWithAgent(question, sourceQuestion);
        } catch (BusinessException e) {
            if (e.getCode() == ErrorCodes.AGENT_CALL_FAILED.getCode()) {
                throw new AgentTimeoutException("检查 Agent 调用超时: " + e.getMessage(), e);
            }
            throw e;
        }
    }

    /**
     * 仅执行规则校验（项 1-4），不调用 Agent
     * 用于检查 Agent 超时/不可用时的降级场景（BLOCK-7）
     *
     * @param question       待校验的新题
     * @param sourceQuestion 原错题
     * @return 规则校验是否全部通过
     */
    public boolean validateRulesOnly(GeneratedQuestion question, QuestionBank sourceQuestion) {
        return validateRules(question, sourceQuestion) == null;
    }

    /**
     * 规则校验（项 1-4）
     *
     * @return 失败原因，null 表示全部通过
     */
    private String validateRules(GeneratedQuestion question, QuestionBank sourceQuestion) {
        // 解析 content JSON
        Map<String, Object> content;
        try {
            content = OBJECT_MAPPER.readValue(question.getContent(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return "内容 JSON 解析失败: " + e.getMessage();
        }

        // 项 1：内容完整性
        String stem = (String) content.get("stem");
        String answer = (String) content.get("answer");
        String formatType = (String) content.get("formatType");
        if (stem == null || stem.isBlank()) {
            return "题干为空";
        }
        if (answer == null || answer.isBlank()) {
            return "答案为空";
        }
        if (formatType == null || formatType.isBlank()) {
            return "题型为空";
        }

        // 项 2：难度枚举合法性（NOTE-2 修正：仅校验枚举合法性，不做档位偏离比较）
        String difficulty = question.getDifficulty();
        if (difficulty == null || !VALID_DIFFICULTIES.contains(difficulty)) {
            return "invalid difficulty value: " + difficulty;
        }

        // 项 3：知识点关联
        if (sourceQuestion != null && question.getKnowledgePointId() != null
                && !question.getKnowledgePointId().equals(sourceQuestion.getKnowledgePointId())) {
            return "知识点不匹配: expected=" + sourceQuestion.getKnowledgePointId()
                    + ", actual=" + question.getKnowledgePointId();
        }

        // 项 4：答案合理性（长度 > 0，已在项 1 中覆盖 isBlank 检查）
        return null; // 全部通过
    }

    /**
     * Agent 语义校验（项 5：解题逻辑相似性）
     */
    private CheckResult checkWithAgent(GeneratedQuestion question, QuestionBank sourceQuestion) {
        String prompt = buildCheckPrompt(question, sourceQuestion);
        String agentOutput = agentScopeClient.call(prompt);

        // 解析 Agent 输出
        try {
            JsonNode jsonNode = OBJECT_MAPPER.readTree(agentOutput);

            // IMP-16：passed 字段缺失 → 视为 false
            if (!jsonNode.has("passed")) {
                throw new AgentOutputInvalidException("Agent 输出缺少 passed 字段");
            }

            boolean passed = jsonNode.get("passed").asBoolean(false);
            String reason = jsonNode.has("reason") ? jsonNode.get("reason").asText("") : "";

            // DETAIL-3：passed=true 时忽略 reason 字段
            if (passed) {
                return CheckResult.pass();
            } else {
                return CheckResult.fail(reason.isEmpty() ? "Agent 判断实质相同" : reason);
            }
        } catch (AgentOutputInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentOutputInvalidException("Agent 输出解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造检查 Agent Prompt
     * 输入包含原题和新题的题干 + 答案 + 解析（BLOCK-6）
     */
    private String buildCheckPrompt(GeneratedQuestion question, QuestionBank sourceQuestion) {
        String promptTemplate = loadPromptTemplate();

        String newContent = question.getContent();
        String sourceContent = sourceQuestion != null ? sourceQuestion.getContent() : "";
        String sourceAnswer = sourceQuestion != null ? sourceQuestion.getAnswer() : "";
        String sourceAnalysis = sourceQuestion != null ? sourceQuestion.getAnalysis() : "";

        return promptTemplate
                .replace("{newQuestionContent}", newContent != null ? newContent : "")
                .replace("{sourceQuestionContent}", sourceContent)
                .replace("{sourceAnswer}", sourceAnswer != null ? sourceAnswer : "")
                .replace("{sourceAnalysis}", sourceAnalysis != null ? sourceAnalysis : "");
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/versions/question_check_v1.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        } catch (IOException e) {
            log.error("加载检查 Agent Prompt 模板失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "检查 Agent Prompt 模板加载失败");
        }
    }

    /**
     * 校验结果
     */
    public static class CheckResult {
        private final boolean passed;
        private final String reason;

        private CheckResult(boolean passed, String reason) {
            this.passed = passed;
            this.reason = reason;
        }

        public static CheckResult pass() {
            return new CheckResult(true, null);
        }

        public static CheckResult fail(String reason) {
            return new CheckResult(false, reason);
        }

        public boolean isPassed() { return passed; }
        public String getReason() { return reason; }
    }
}
