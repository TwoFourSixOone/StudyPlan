package com.jiang.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.client.AgentScopeClient;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.AgentOutputValidator;
import com.jiang.dto.DailyPlanQuestionRef;
import com.jiang.dto.KnowledgePointWithMastery;
import com.jiang.model.entity.GeneratedQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 出题 Agent
 * 封装出题 LLM 调用逻辑：构造 Prompt → 调用 API → 校验输出 → 解析结果
 * 重试机制不在 Agent 内部，由 Service 外层实现（IMP-5）
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Component
@Slf4j
public class QuestionGenAgent {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private AgentScopeClient agentScopeClient;

    @Autowired
    private AgentOutputValidator validator;

    /**
     * 生成每日作业题目
     * 内部完成 validate → parse 原子流程（IMP-8）
     *
     * @param knowledgePoints 知识点列表（含掌握度）
     * @param questionCount   题目数量
     * @return 题目引用列表 + 生成题实体列表
     */
    public QuestionGenResult generate(List<KnowledgePointWithMastery> knowledgePoints, int questionCount) {
        // 1. 构造 Prompt
        String prompt = buildPrompt(knowledgePoints, questionCount);

        // 2. 调用 Agent API
        String agentOutput = agentScopeClient.call(prompt);

        // 3. 校验 + 解析（原子流程）
        return validateAndParse(agentOutput);
    }

    private String buildPrompt(List<KnowledgePointWithMastery> knowledgePoints, int questionCount) {
        StringBuilder kpBuilder = new StringBuilder();
        for (KnowledgePointWithMastery kp : knowledgePoints) {
            kpBuilder.append(String.format("- 知识点: %s (ID: %s), 掌握度: %s, 置信度: %s%n",
                    kp.getName(), kp.getKnowledgePointId(),
                    kp.getMasteryScore() != null ? kp.getMasteryScore() : "N/A",
                    kp.getConfidence() != null ? kp.getConfidence() : "N/A"));
        }

        String promptTemplate = loadPromptTemplate();
        return promptTemplate
                .replace("{knowledgePoints}", kpBuilder.toString())
                .replace("{questionCount}", String.valueOf(questionCount));
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/versions/question_gen_v1.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        } catch (IOException e) {
            log.error("加载 Prompt 模板失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "Prompt 模板加载失败");
        }
    }

    private QuestionGenResult validateAndParse(String agentOutput) {
        // 校验
        AgentOutputValidator.ValidationResult result = validator.validate(agentOutput);
        if (!result.isSuccess()) {
            throw new BusinessException(ErrorCodes.AGENT_OUTPUT_INVALID, result.getMessage());
        }

        // 解析
        try {
            List<Map<String, Object>> items = OBJECT_MAPPER.readValue(agentOutput,
                    new TypeReference<List<Map<String, Object>>>() {});

            List<DailyPlanQuestionRef> refs = new ArrayList<>();
            List<GeneratedQuestion> generatedQuestions = new ArrayList<>();

            for (Map<String, Object> item : items) {
                String questionSource = (String) item.get("questionSource");
                Long questionId = ((Number) item.get("questionId")).longValue();
                String taskType = (String) item.get("taskType");
                String knowledgePointId = (String) item.get("knowledgePointId");

                if ("generated".equals(questionSource)) {
                    // 生成题：创建 GeneratedQuestion 实体（planId 后续回填）
                    GeneratedQuestion gq = new GeneratedQuestion();
                    gq.setKnowledgePointId(knowledgePointId);
                    gq.setContent(OBJECT_MAPPER.writeValueAsString(item.get("content")));
                    gq.setDifficulty(extractDifficulty(item));
                    gq.setSimilarityChecked(0);
                    generatedQuestions.add(gq);

                    // ref 中的 questionId 暂时为 0，落表后回填
                    refs.add(new DailyPlanQuestionRef(0L, "generated", taskType));
                } else {
                    // 题库原题：直接引用 questionId
                    refs.add(new DailyPlanQuestionRef(questionId, "bank", taskType));
                }
            }

            return new QuestionGenResult(refs, generatedQuestions);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.AGENT_OUTPUT_INVALID,
                    "Agent 输出解析失败: " + e.getMessage());
        }
    }

    private String extractDifficulty(Map<String, Object> item) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) item.get("content");
            return (String) content.getOrDefault("difficulty", "standard");
        } catch (Exception e) {
            return "standard";
        }
    }

    /**
     * Agent 生成结果
     */
    public static class QuestionGenResult {
        private final List<DailyPlanQuestionRef> refs;
        private final List<GeneratedQuestion> generatedQuestions;

        public QuestionGenResult(List<DailyPlanQuestionRef> refs, List<GeneratedQuestion> generatedQuestions) {
            this.refs = refs;
            this.generatedQuestions = generatedQuestions;
        }

        public List<DailyPlanQuestionRef> getRefs() {
            return refs;
        }

        public List<GeneratedQuestion> getGeneratedQuestions() {
            return generatedQuestions;
        }
    }
}
