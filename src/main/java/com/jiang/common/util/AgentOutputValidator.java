package com.jiang.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 输出校验器
 * 校验 Agent 返回的 JSON 是否符合预定义 Schema
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Component
@Slf4j
public class AgentOutputValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private JsonNode questionGenSchema;
    private JsonNode attributionSchema;
    private JsonNode adjustmentSchema;

    @PostConstruct
    public void init() {
        try {
            questionGenSchema = loadSchema("prompts/schemas/question_gen_schema.json");
            attributionSchema = loadSchema("prompts/schemas/attribution_schema.json");
            adjustmentSchema = loadSchema("prompts/schemas/adjustment_schema.json");
        } catch (IOException e) {
            log.error("加载 JSON Schema 失败: {}", e.getMessage());
        }
    }

    /**
     * 校验 Agent 输出是否符合 Schema
     *
     * @param agentOutput Agent 返回的 JSON 字符串
     * @return 校验结果
     */
    public ValidationResult validate(String agentOutput) {
        // 1. 解析 JSON
        JsonNode jsonNode;
        try {
            jsonNode = OBJECT_MAPPER.readTree(agentOutput);
        } catch (Exception e) {
            return ValidationResult.fail("JSON 解析失败: " + e.getMessage());
        }

        // 2. 基础结构校验（必须是数组）
        if (!jsonNode.isArray()) {
            return ValidationResult.fail("输出必须是 JSON 数组");
        }

        // 3. 逐元素校验
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < jsonNode.size(); i++) {
            JsonNode item = jsonNode.get(i);
            validateQuestionItem(item, i, errors);
        }

        if (errors.isEmpty()) {
            return ValidationResult.success();
        }
        return ValidationResult.fail(String.join("; ", errors));
    }

    private void validateQuestionItem(JsonNode item, int index, List<String> errors) {
        String prefix = "题目[" + index + "]";

        // 必填字段校验
        String[] requiredFields = {"questionId", "questionSource", "taskType", "knowledgePointId", "content"};
        for (String field : requiredFields) {
            if (!item.has(field) || item.get(field).isNull()) {
                errors.add(prefix + ": 缺少必填字段 " + field);
            }
        }

        // questionSource 枚举校验
        if (item.has("questionSource")) {
            String source = item.get("questionSource").asText();
            if (!Set.of("bank", "generated").contains(source)) {
                errors.add(prefix + ": questionSource 必须为 bank 或 generated");
            }
        }

        // taskType 枚举校验
        if (item.has("taskType")) {
            String taskType = item.get("taskType").asText();
            if (!Set.of("review", "redo", "similar", "comprehensive").contains(taskType)) {
                errors.add(prefix + ": taskType 值非法: " + taskType);
            }
        }

        // content 子对象校验
        if (item.has("content") && item.get("content").isObject()) {
            JsonNode content = item.get("content");
            String[] contentRequired = {"stem", "answer", "formatType", "difficulty"};
            for (String field : contentRequired) {
                if (!content.has(field) || content.get(field).isNull()) {
                    errors.add(prefix + ".content: 缺少必填字段 " + field);
                }
            }

            if (content.has("formatType")) {
                String ft = content.get("formatType").asText();
                if (!Set.of("choice", "numeric", "short", "self").contains(ft)) {
                    errors.add(prefix + ".content: formatType 值非法: " + ft);
                }
            }

            if (content.has("difficulty")) {
                String diff = content.get("difficulty").asText();
                if (!Set.of("basic", "standard", "challenge").contains(diff)) {
                    errors.add(prefix + ".content: difficulty 值非法: " + diff);
                }
            }
        }
    }

    private JsonNode loadSchema(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream is = resource.getInputStream()) {
            return OBJECT_MAPPER.readTree(is);
        }
    }

    /**
     * 校验归因 Agent 输出是否符合 Schema
     * 期望格式：JSON 数组，每个元素包含 questionIndex、knowledgePointId、confidence
     *
     * @param agentOutput Agent 返回的 JSON 字符串
     * @return 校验结果
     */
    public ValidationResult validateAttribution(String agentOutput) {
        JsonNode jsonNode;
        try {
            jsonNode = OBJECT_MAPPER.readTree(agentOutput);
        } catch (Exception e) {
            return ValidationResult.fail("JSON 解析失败: " + e.getMessage());
        }

        if (!jsonNode.isArray()) {
            return ValidationResult.fail("输出必须是 JSON 数组");
        }

        List<String> errors = new ArrayList<>();
        for (int i = 0; i < jsonNode.size(); i++) {
            JsonNode item = jsonNode.get(i);
            String prefix = "题目[" + i + "]";

            // questionIndex 必填且为整数
            if (!item.has("questionIndex") || !item.get("questionIndex").isNumber()) {
                errors.add(prefix + ": 缺少或非法字段 questionIndex");
            }

            // knowledgePointId 必填且为字符串
            if (!item.has("knowledgePointId") || !item.get("knowledgePointId").isTextual()) {
                errors.add(prefix + ": 缺少或非法字段 knowledgePointId");
            }

            // confidence 必填且为枚举值
            if (!item.has("confidence")) {
                errors.add(prefix + ": 缺少字段 confidence");
            } else {
                String confidence = item.get("confidence").asText();
                if (!Set.of("high", "medium", "low").contains(confidence)) {
                    errors.add(prefix + ": confidence 值非法: " + confidence);
                }
            }
        }

        if (errors.isEmpty()) {
            return ValidationResult.success();
        }
        return ValidationResult.fail(String.join("; ", errors));
    }

    /**
     * 校验调整 Agent 输出是否符合 Schema
     * 期望格式：JSON 对象，包含 modifiedDates、reason
     *
     * @param agentOutput Agent 返回的 JSON 字符串
     * @return 校验结果
     */
    public ValidationResult validateAdjustment(String agentOutput) {
        JsonNode jsonNode;
        try {
            jsonNode = OBJECT_MAPPER.readTree(agentOutput);
        } catch (Exception e) {
            return ValidationResult.fail("JSON 解析失败: " + e.getMessage());
        }

        if (!jsonNode.isObject()) {
            return ValidationResult.fail("输出必须是 JSON 对象");
        }

        List<String> errors = new ArrayList<>();

        // modifiedDates 必填且为数组
        if (!jsonNode.has("modifiedDates") || !jsonNode.get("modifiedDates").isArray()) {
            errors.add("缺少或非法字段 modifiedDates（必须为数组）");
        } else {
            JsonNode dates = jsonNode.get("modifiedDates");
            for (int i = 0; i < dates.size(); i++) {
                JsonNode item = dates.get(i);
                String prefix = "modifiedDates[" + i + "]";

                if (!item.has("date") || !item.get("date").isTextual()) {
                    errors.add(prefix + ": 缺少或非法字段 date");
                } else {
                    String dateStr = item.get("date").asText();
                    if (!dateStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        errors.add(prefix + ": date 格式非法（应为 YYYY-MM-DD）: " + dateStr);
                    }
                }

                if (!item.has("newContent") || !item.get("newContent").isTextual()) {
                    errors.add(prefix + ": 缺少或非法字段 newContent");
                }

                if (!item.has("reason") || !item.get("reason").isTextual()) {
                    errors.add(prefix + ": 缺少或非法字段 reason");
                }
            }
        }

        // reason 必填
        if (!jsonNode.has("reason") || !jsonNode.get("reason").isTextual()) {
            errors.add("缺少或非法字段 reason");
        }

        if (errors.isEmpty()) {
            return ValidationResult.success();
        }
        return ValidationResult.fail(String.join("; ", errors));
    }

    /**
     * 校验检查 Agent 输出是否符合 Schema（Change 8: 举一反三新题校验）
     * 期望格式：{"passed": true/false, "reason": "..."}
     *
     * @param agentOutput Agent 返回的 JSON 字符串
     * @return 校验结果
     */
    public ValidationResult validateCheckResult(String agentOutput) {
        JsonNode jsonNode;
        try {
            jsonNode = OBJECT_MAPPER.readTree(agentOutput);
        } catch (Exception e) {
            return ValidationResult.fail("JSON 解析失败: " + e.getMessage());
        }

        if (!jsonNode.isObject()) {
            return ValidationResult.fail("输出必须是 JSON 对象");
        }

        List<String> errors = new ArrayList<>();

        // passed 字段必须存在且为布尔值
        if (!jsonNode.has("passed")) {
            errors.add("缺少必填字段 passed");
        } else if (!jsonNode.get("passed").isBoolean()) {
            errors.add("passed 必须为布尔值");
        }

        // reason 字段可选，但若存在必须为字符串
        if (jsonNode.has("reason") && !jsonNode.get("reason").isTextual()) {
            errors.add("reason 必须为字符串");
        }

        if (errors.isEmpty()) {
            return ValidationResult.success();
        }
        return ValidationResult.fail(String.join("; ", errors));
    }

    /**
     * 校验结果
     */
    public static class ValidationResult {
        private final boolean success;
        private final String message;

        private ValidationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }
}
