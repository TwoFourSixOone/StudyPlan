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

    /** unclearFields 最大项数（与 prompts/schemas 的 maxItems 保持一致） */
    public static final int UNCLEAR_FIELDS_MAX = 6;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private JsonNode questionGenSchema;
    private JsonNode attributionSchema;
    private JsonNode adjustmentSchema;
    private JsonNode planningSchema;
    private JsonNode similarQuestionSchema;
    private JsonNode examParseSchema;

    @PostConstruct
    public void init() {
        try {
            questionGenSchema = loadSchema("prompts/schemas/question_gen_schema.json");
            attributionSchema = loadSchema("prompts/schemas/attribution_schema.json");
            adjustmentSchema = loadSchema("prompts/schemas/adjustment_schema.json");
            planningSchema = loadSchema("prompts/schemas/planning_schema.json");
            similarQuestionSchema = loadSchema("prompts/schemas/similar_question_schema.json");
            examParseSchema = loadSchema("prompts/schemas/exam_parse_schema.json");
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
            jsonNode = OBJECT_MAPPER.readTree(JsonPayloadExtractor.extract(agentOutput));
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
                } else if ("choice".equals(ft)
                        && "generated".equals(item.path("questionSource").asText())) {
                    // 生成的选择题必须有可作答选项（隐性约定 #25）；题库题由 question_bank 提供选项
                    List<Map<String, String>> options = ChoiceOptions.normalize(content.get("choices"));
                    if (!ChoiceOptions.isUsable(options)) {
                        errors.add(prefix + ".content: 选择题 content.choices 至少需要 "
                                + ChoiceOptions.MIN_OPTIONS + " 个有效选项");
                    }
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
     * 校验视觉解析 Agent 输出（试卷上传 change，design §3.3/§3.4）
     *
     * <p>判定语义：</p>
     * <ul>
     *   <li>JSON 解析失败 / 顶层不是对象 / {@code questions} 非数组 → <b>整图判失败</b>（调用方可重试）</li>
     *   <li>单题结构非法（缺必填字段、枚举越界、length 越界、choice 选项不足）→ <b>只丢弃该题</b>，
     *       由 {@link #checkExamParseItem(JsonNode)} 暴露给调用方，不重试整图</li>
     *   <li>有效题数为 0 且原数组非空 → 整图判失败</li>
     *   <li>{@code questions} 为空数组 → 合法（整页无题，如封面页），不重试</li>
     * </ul>
     *
     * @param agentOutput 视觉模型返回的原始文本
     * @return 整图级别的校验结果
     */
    public ValidationResult validateExamParse(String agentOutput) {
        JsonNode jsonNode;
        try {
            jsonNode = OBJECT_MAPPER.readTree(JsonPayloadExtractor.extract(agentOutput));
        } catch (Exception e) {
            return ValidationResult.fail("JSON 解析失败: " + e.getMessage());
        }

        if (!jsonNode.isObject()) {
            return ValidationResult.fail("输出必须是 JSON 对象");
        }

        JsonNode questions = jsonNode.get("questions");
        if (questions == null || !questions.isArray()) {
            return ValidationResult.fail("缺少或非法字段 questions（必须是 JSON 数组）");
        }

        if (questions.isEmpty()) {
            // 整页无题（封面/答题卡页）是合法结果，但必须有 imageWarning 说明原因
            String warning = jsonNode.path("imageWarning").asText("");
            if (warning.isBlank()) {
                log.warn("视觉解析返回空题目且缺少 imageWarning（按 0 题接受）");
            }
            return ValidationResult.success();
        }

        int validCount = 0;
        List<String> invalidItems = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            String error = checkExamParseItem(questions.get(i));
            if (error == null) {
                validCount++;
            } else {
                invalidItems.add("题目[" + i + "]: " + error);
            }
        }

        if (validCount == 0) {
            return ValidationResult.fail("questions 中没有任何合法题目: " + String.join("; ", invalidItems));
        }
        if (!invalidItems.isEmpty()) {
            log.warn("视觉解析部分题目被丢弃: {}", String.join("; ", invalidItems));
        }
        return ValidationResult.success();
    }

    /**
     * 校验单道试卷解析题（约定 #24 纯文本字段不做长度外校验，长度按 schema §3.3）
     *
     * @param item questions 数组中的一个元素
     * @return 错误信息；合法返回 null
     */
    public String checkExamParseItem(JsonNode item) {
        if (item == null || !item.isObject()) {
            return "题目必须是 JSON 对象";
        }

        // 必填字段（v4：knowledgePointId 由解析直出，允许空串但字段必须存在）
        // 2026-11-17：questionIndex 移出必填——服务端已用 paperNo 重新编号，模型漏给 questionIndex
        // 不应整题丢弃（否则又会构成一条"静默丢题"路径）；题号存在性在下方单独校验
        String[] requiredFields = {"stem", "formatType", "studentAnswer", "answerStatus",
                "knowledgePointId"};
        for (String field : requiredFields) {
            if (!item.has(field) || item.get(field).isNull()) {
                return "缺少必填字段 " + field;
            }
        }

        // knowledgePointId 必须是字符串（空串合法 = 模型无法判断；超长/多 ID 由解析层置空 + warn，
        // 服务端白名单再兜一层，规则 #8）
        JsonNode knowledgePointId = item.get("knowledgePointId");
        if (!knowledgePointId.isTextual()) {
            return "knowledgePointId 必须是字符串";
        }

        // 题号：questionIndex（1..60）或 paperNo（1..60）至少给一个整数
        JsonNode questionIndex = item.get("questionIndex");
        boolean hasIndex = questionIndex != null && !questionIndex.isNull();
        if (hasIndex) {
            if (!questionIndex.isNumber()) {
                return "questionIndex 必须是整数";
            }
            int index = questionIndex.asInt();
            if (index < 1 || index > 60) {
                return "questionIndex 必须在 1-60 之间，实际 " + index;
            }
        } else {
            JsonNode paperNo = item.get("paperNo");
            if (paperNo == null || paperNo.isNull() || !paperNo.isNumber()) {
                return "缺少题号（questionIndex 与 paperNo 至少提供一个整数）";
            }
        }

        // 题干：1..2000
        JsonNode stem = item.get("stem");
        if (!stem.isTextual() || stem.asText().isBlank()) {
            return "stem 不能为空";
        }
        if (stem.asText().length() > 2000) {
            return "stem 超过 2000 字";
        }

        // 题型枚举
        JsonNode formatType = item.get("formatType");
        if (!formatType.isTextual()
                || !Set.of("choice", "numeric", "short", "self").contains(formatType.asText())) {
            return "formatType 值非法: " + formatType.asText();
        }

        // 选择题：选项必须可用（约定 #25：至少 2 个有效选项，最多 8 个）
        if ("choice".equals(formatType.asText())) {
            JsonNode choices = item.get("choices");
            if (choices != null && !choices.isNull()) {
                if (!choices.isArray()) {
                    return "选择题 choices 必须是数组";
                }
                if (choices.size() > ChoiceOptions.MAX_OPTIONS) {
                    return "选择题 choices 不能超过 " + ChoiceOptions.MAX_OPTIONS + " 个";
                }
            }
            List<Map<String, String>> options = ChoiceOptions.normalize(choices);
            if (!ChoiceOptions.isUsable(options)) {
                return "选择题 choices 至少需要 " + ChoiceOptions.MIN_OPTIONS + " 个有效选项";
            }
        }

        // 作答状态枚举
        JsonNode answerStatus = item.get("answerStatus");
        if (!answerStatus.isTextual()
                || !Set.of("answered", "blank", "unreadable").contains(answerStatus.asText())) {
            return "answerStatus 值非法: " + answerStatus.asText();
        }

        // 学生作答长度
        JsonNode studentAnswer = item.get("studentAnswer");
        if (!studentAnswer.isTextual()) {
            return "studentAnswer 必须是字符串";
        }
        if (studentAnswer.asText().length() > 1000) {
            return "studentAnswer 超过 1000 字";
        }

        // 可选字段：难度枚举
        if (item.has("difficulty") && !item.get("difficulty").isNull()) {
            JsonNode difficulty = item.get("difficulty");
            if (!difficulty.isTextual()
                    || !Set.of("basic", "standard", "challenge").contains(difficulty.asText())) {
                return "difficulty 值非法: " + difficulty.asText();
            }
        }

        // 可选字段：正确答案/解析长度
        if (item.has("correctAnswer") && item.get("correctAnswer").isTextual()
                && item.get("correctAnswer").asText().length() > 500) {
            return "correctAnswer 超过 500 字";
        }
        if (item.has("explanation") && item.get("explanation").isTextual()
                && item.get("explanation").asText().length() > 1500) {
            // 2026-11-17：与 schema 的 maxLength=1500 对齐；此前卡 1000 会静默丢整题
            return "explanation 超过 1500 字";
        }

        return null;
    }

    /**
     * 校验学生作答版输出（2026-11-17 双版本）
     *
     * <p>语义：JSON 解析失败/顶层非对象/answers 非数组/<b>answers 为空</b> → 整图失败（可重试）；
     * 单条非法只丢弃该条，由 {@link #checkExamAnswerItem(JsonNode)} 暴露。</p>
     *
     * <p><b>为什么空数组也算失败</b>：作答版 Prompt 要求「题目清单里的每一道题都必须输出一条（没有作答也输出
     * blank）」，因此空数组说明模型没有按清单作答 → 若当成功处理，会让整卷所有题落 {@code blank}，
     * 而 {@code AnswerGradingServiceImpl} 对「学生空 + 标准答案有」判错 → 整卷 0 分并污染掌握度/错题
     * （这正是双版本引入回退的原因）。与整卷题目校验里「空页合法」的语义不同，不要复用。</p>
     */
    public ValidationResult validateExamAnswers(String agentOutput) {
        JsonNode jsonNode;
        try {
            jsonNode = OBJECT_MAPPER.readTree(JsonPayloadExtractor.extract(agentOutput));
        } catch (Exception e) {
            return ValidationResult.fail("JSON 解析失败: " + e.getMessage());
        }
        if (!jsonNode.isObject()) {
            return ValidationResult.fail("输出必须是 JSON 对象");
        }
        JsonNode answers = jsonNode.get("answers");
        if (answers == null || !answers.isArray()) {
            return ValidationResult.fail("缺少或非法字段 answers（必须是 JSON 数组）");
        }
        if (answers.isEmpty()) {
            return ValidationResult.fail("answers 为空：题目清单中的每道题都必须输出一条（无作答也要输出 blank）");
        }
        int validCount = 0;
        List<String> invalidItems = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            String error = checkExamAnswerItem(answers.get(i));
            if (error == null) {
                validCount++;
            } else {
                invalidItems.add("作答[" + i + "]: " + error);
            }
        }
        if (validCount == 0) {
            return ValidationResult.fail("answers 中没有任何合法作答: " + String.join("; ", invalidItems));
        }
        if (!invalidItems.isEmpty()) {
            log.warn("作答版部分条目被丢弃: {}", String.join("; ", invalidItems));
        }
        return ValidationResult.success();
    }

    /**
     * 校验单条学生作答
     *
     * @return 错误信息；合法返回 null
     */
    public String checkExamAnswerItem(JsonNode item) {
        if (item == null || !item.isObject()) {
            return "作答必须是 JSON 对象";
        }
        String[] requiredFields = {"paperNo", "studentAnswer", "answerStatus"};
        for (String field : requiredFields) {
            if (!item.has(field) || item.get(field).isNull()) {
                return "缺少必填字段 " + field;
            }
        }
        JsonNode paperNo = item.get("paperNo");
        if (!paperNo.isNumber()) {
            return "paperNo 必须是整数";
        }
        int value = paperNo.asInt();
        if (value < 1 || value > 60) {
            return "paperNo 必须在 1-60 之间，实际 " + value;
        }
        JsonNode studentAnswer = item.get("studentAnswer");
        if (!studentAnswer.isTextual()) {
            return "studentAnswer 必须是字符串";
        }
        if (studentAnswer.asText().length() > 1000) {
            return "studentAnswer 超过 1000 字";
        }
        JsonNode answerStatus = item.get("answerStatus");
        if (!answerStatus.isTextual()
                || !Set.of("answered", "blank", "unreadable").contains(answerStatus.asText())) {
            return "answerStatus 值非法: " + answerStatus.asText();
        }
        // 可选字段：unclearFields 与 schema 对齐（数组、≤6 项、字符串），避免模型回传超大数组
        String unclearError = checkUnclearFields(item);
        if (unclearError != null) {
            return unclearError;
        }
        return null;
    }

    /**
     * 可选字段 {@code unclearFields} 的结构/长度校验（schema：array，maxItems=6，items string）
     *
     * @return 错误信息；合法或缺省返回 null
     */
    String checkUnclearFields(JsonNode item) {
        if (!item.has("unclearFields") || item.get("unclearFields").isNull()) {
            return null;
        }
        JsonNode fields = item.get("unclearFields");
        if (!fields.isArray()) {
            return "unclearFields 必须是数组";
        }
        if (fields.size() > UNCLEAR_FIELDS_MAX) {
            return "unclearFields 不能超过 " + UNCLEAR_FIELDS_MAX + " 项，实际 " + fields.size();
        }
        for (JsonNode field : fields) {
            if (!field.isTextual()) {
                return "unclearFields 的元素必须是字符串";
            }
        }
        return null;
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
            jsonNode = OBJECT_MAPPER.readTree(JsonPayloadExtractor.extract(agentOutput));
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
    /**
     * 校验举一反三 Agent 输出（产品规则 #3）
     *
     * <p>结构与出题 Agent 不同：{@code [{knowledgePointId, content{stem, answer, formatType}}]}，
     * 不含 questionId/questionSource/taskType（这些由系统落库时分配）。</p>
     */
    public ValidationResult validateSimilar(String agentOutput) {
        JsonNode jsonNode;
        try {
            jsonNode = OBJECT_MAPPER.readTree(JsonPayloadExtractor.extract(agentOutput));
        } catch (Exception e) {
            return ValidationResult.fail("JSON 解析失败: " + e.getMessage());
        }

        if (!jsonNode.isArray() || jsonNode.isEmpty()) {
            return ValidationResult.fail("输出必须是非空 JSON 数组");
        }

        List<String> errors = new ArrayList<>();
        for (int i = 0; i < jsonNode.size(); i++) {
            JsonNode item = jsonNode.get(i);
            String prefix = "题目[" + i + "]";

            if (!item.has("knowledgePointId") || !item.get("knowledgePointId").isTextual()
                    || item.get("knowledgePointId").asText().isBlank()) {
                errors.add(prefix + ": 缺少必填字段 knowledgePointId");
            }

            JsonNode content = item.get("content");
            if (content == null || !content.isObject()) {
                errors.add(prefix + ": 缺少必填字段 content");
                continue;
            }
            if (!content.has("stem") || !content.get("stem").isTextual()
                    || content.get("stem").asText().isBlank()) {
                errors.add(prefix + ": content.stem 不能为空");
            }
            if (!content.has("answer") || !content.get("answer").isTextual()
                    || content.get("answer").asText().isBlank()) {
                errors.add(prefix + ": content.answer 不能为空");
            }
            if (!content.has("formatType") || !content.get("formatType").isTextual()) {
                errors.add(prefix + ": 缺少 content.formatType");
            } else {
                String formatType = content.get("formatType").asText();
                if (!java.util.Set.of("choice", "numeric", "short", "self").contains(formatType)) {
                    errors.add(prefix + ": content.formatType 非法（" + formatType + "）");
                } else if ("choice".equals(formatType)) {
                    // 选择题必须有可作答的选项（隐性约定 #25），否则前端只能渲染出空选项区
                    List<Map<String, String>> options = ChoiceOptions.normalize(content.get("choices"));
                    if (!ChoiceOptions.isUsable(options)) {
                        errors.add(prefix + ": 选择题 content.choices 至少需要 "
                                + ChoiceOptions.MIN_OPTIONS + " 个有效选项");
                    }
                }
            }
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.fail(String.join("; ", errors));
    }

    /**
     * 校验规划 Agent 输出（产品规则 #6）
     *
     * <p>结构：{@code days[{date, kpIds[], questionCount, focus?}], reason}</p>
     */
    public ValidationResult validatePlanning(String agentOutput) {
        JsonNode jsonNode;
        try {
            jsonNode = OBJECT_MAPPER.readTree(JsonPayloadExtractor.extract(agentOutput));
        } catch (Exception e) {
            return ValidationResult.fail("JSON 解析失败: " + e.getMessage());
        }

        if (!jsonNode.isObject()) {
            return ValidationResult.fail("输出必须是 JSON 对象");
        }

        List<String> errors = new ArrayList<>();

        if (!jsonNode.has("days") || !jsonNode.get("days").isArray()
                || jsonNode.get("days").isEmpty()) {
            errors.add("缺少或非法字段 days（必须为非空数组）");
        } else {
            JsonNode days = jsonNode.get("days");
            for (int i = 0; i < days.size(); i++) {
                JsonNode day = days.get(i);
                String prefix = "days[" + i + "]";

                if (!day.has("date") || !day.get("date").isTextual()
                        || !day.get("date").asText().matches("\\d{4}-\\d{2}-\\d{2}")) {
                    errors.add(prefix + ": 缺少或非法字段 date（YYYY-MM-DD）");
                }

                if (!day.has("kpIds") || !day.get("kpIds").isArray() || day.get("kpIds").isEmpty()) {
                    errors.add(prefix + ": 缺少或非法字段 kpIds（必须为非空数组）");
                } else if (day.get("kpIds").size() > 4) {
                    errors.add(prefix + ": 单日知识点不得超过 4 个");
                }

                if (!day.has("questionCount") || !day.get("questionCount").isInt()) {
                    errors.add(prefix + ": 缺少或非法字段 questionCount");
                } else {
                    int count = day.get("questionCount").asInt();
                    if (count < 1 || count > 15) {
                        errors.add(prefix + ": questionCount 必须在 1-15 之间，实际 " + count);
                    }
                }
            }
        }

        if (!jsonNode.has("reason") || !jsonNode.get("reason").isTextual()) {
            errors.add("缺少或非法字段 reason");
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.fail(String.join("; ", errors));
    }

    public ValidationResult validateAdjustment(String agentOutput) {
        JsonNode jsonNode;
        try {
            jsonNode = OBJECT_MAPPER.readTree(JsonPayloadExtractor.extract(agentOutput));
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
            jsonNode = OBJECT_MAPPER.readTree(JsonPayloadExtractor.extract(agentOutput));
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
