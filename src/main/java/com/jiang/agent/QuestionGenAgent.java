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
import com.jiang.model.entity.QuestionBank;
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
     * 针对指定错题知识点生成举一反三新题
     * 一次 Agent 调用生成 targetCount 道，难度按 progressive 策略由 Agent 自行分配（IMP-3）
     * 重试机制不在 Agent 内部，由 Service 外层实现（IMP-5）
     *
     * @param kpId               知识点 ID
     * @param masteryScore       掌握度
     * @param sourceQuestion     原错题内容（QuestionBank）
     * @param targetCount        目标生成数量
     * @param difficultyStrategy 难度策略（"progressive"）
     * @return 生成的新题列表（不含 ref）
     */
    public List<GeneratedQuestion> generateSimilar(
            String kpId, BigDecimal masteryScore,
            QuestionBank sourceQuestion, int targetCount, String difficultyStrategy) {
        // 1. 构造专项 Prompt
        String prompt = buildSimilarPrompt(kpId, masteryScore, sourceQuestion, targetCount, difficultyStrategy);

        // 2. 调用 Agent API
        String agentOutput = agentScopeClient.call(prompt);

        // 3. 校验 + 解析
        return validateAndParseSimilar(agentOutput, kpId);
    }

    private String buildSimilarPrompt(String kpId, BigDecimal masteryScore,
                                       QuestionBank sourceQuestion, int targetCount,
                                       String difficultyStrategy) {
        String promptTemplate = loadSimilarPromptTemplate();
        return promptTemplate
                .replace("{knowledgePointId}", kpId)
                .replace("{masteryScore}", masteryScore != null ? masteryScore.toString() : "N/A")
                .replace("{sourceStem}", sourceQuestion.getContent() != null ? sourceQuestion.getContent() : "")
                .replace("{sourceAnswer}", sourceQuestion.getAnswer() != null ? sourceQuestion.getAnswer() : "")
                .replace("{sourceAnalysis}", sourceQuestion.getAnalysis() != null ? sourceQuestion.getAnalysis() : "")
                .replace("{sourceDifficulty}", sourceQuestion.getDifficulty() != null ? sourceQuestion.getDifficulty() : "standard")
                .replace("{targetCount}", String.valueOf(targetCount))
                .replace("{difficultyStrategy}", difficultyStrategy != null ? difficultyStrategy : "progressive");
    }

    private String loadSimilarPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/versions/similar_question_gen_v1.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        } catch (IOException e) {
            log.error("加载举一反三 Prompt 模板失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "举一反三 Prompt 模板加载失败");
        }
    }

    private List<GeneratedQuestion> validateAndParseSimilar(String agentOutput, String kpId) {
        // 校验（复用现有 Schema 校验器）
        AgentOutputValidator.ValidationResult result = validator.validate(agentOutput);
        if (!result.isSuccess()) {
            throw new BusinessException(ErrorCodes.AGENT_OUTPUT_INVALID, result.getMessage());
        }

        // 解析为 GeneratedQuestion 列表（不含 ref）
        try {
            List<Map<String, Object>> items = OBJECT_MAPPER.readValue(agentOutput,
                    new TypeReference<List<Map<String, Object>>>() {});

            List<GeneratedQuestion> generatedQuestions = new ArrayList<>();
            for (Map<String, Object> item : items) {
                GeneratedQuestion gq = new GeneratedQuestion();
                gq.setKnowledgePointId(kpId);
                gq.setContent(OBJECT_MAPPER.writeValueAsString(item.get("content")));
                gq.setDifficulty(extractDifficulty(item));
                gq.setSimilarityChecked(0);
                generatedQuestions.add(gq);
            }

            return generatedQuestions;
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.AGENT_OUTPUT_INVALID,
                    "举一反三 Agent 输出解析失败: " + e.getMessage());
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
