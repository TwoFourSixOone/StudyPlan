package com.jiang.agent.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.ExamParseAgent;
import com.jiang.client.AgentScopeClient;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.AgentOutputValidator;
import com.jiang.common.util.ChoiceOptions;
import com.jiang.common.util.LlmTextSanitizer;
import com.jiang.dto.ExamParseContext;
import com.jiang.dto.ExamParseImageResult;
import com.jiang.dto.ParsedQuestion;
import com.jiang.dto.VisionImage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 试卷视觉解析 Agent 实现
 *
 * <p>Prompt 版本：{@code prompts/versions/exam_parse_v1.txt}；
 * 输出 Schema：{@code prompts/schemas/exam_parse_schema.json}（由
 * {@link AgentOutputValidator#validateExamParse(String)} 校验）。</p>
 *
 * <p>重试语义（design §3.4）：单图最多重试 2 次（共 3 次尝试），每次把上次失败原因塞进
 * {@code {retryHint}}；单题非法只丢弃该题，不重试整图。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Component
@Slf4j
public class ExamParseAgentImpl implements ExamParseAgent {

    /** Prompt 模板路径 */
    static final String PROMPT_PATH = "prompts/versions/exam_parse_v1.txt";

    /** 单图最多重试次数（不含首次尝试） */
    static final int MAX_RETRIES = 2;

    /** 重试提示里保留的失败原因长度 */
    private static final int RETRY_HINT_MAX_LENGTH = 120;

    /** 未替换占位符（用于自检告警） */
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\{[a-zA-Z]+}");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private AgentScopeClient agentScopeClient;

    @Autowired
    private AgentOutputValidator agentOutputValidator;

    /** Prompt 模板缓存（文件小，进程内只读一次） */
    private volatile String promptTemplate;

    @Override
    public ExamParseImageResult parseImage(VisionImage image, ExamParseContext context) {
        StringBuilder lastError = new StringBuilder();
        String retryHint = "无";
        int retryCount = 0;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            retryCount = attempt;
            String prompt = buildPrompt(context, retryHint);
            String rawOutput;
            try {
                rawOutput = agentScopeClient.callVision(prompt, List.of(image));
            } catch (BusinessException e) {
                // 技术异常（超时/网络/模型报错）：按单图重试处理，不向上透传（约定 #14）
                lastError = new StringBuilder("视觉模型调用失败: " + e.getMessage());
                log.warn("试卷视觉解析调用失败，准备重试 {}/{}: imageSeq={}, reason={}",
                        attempt + 1, MAX_RETRIES, context.getImageSeq(), e.getMessage());
                retryHint = "上次调用失败（" + truncate(e.getMessage(), RETRY_HINT_MAX_LENGTH) + "），请重新识别本图。";
                continue;
            }

            String cleaned = stripCodeFence(rawOutput);
            AgentOutputValidator.ValidationResult validation = agentOutputValidator.validateExamParse(cleaned);
            if (!validation.isSuccess()) {
                lastError = new StringBuilder("视觉解析输出校验失败: " + validation.getMessage());
                log.warn("试卷视觉解析输出不合格，准备重试 {}/{}: imageSeq={}, reason={}",
                        attempt + 1, MAX_RETRIES, context.getImageSeq(), validation.getMessage());
                retryHint = "上次输出不符合要求（"
                        + truncate(validation.getMessage(), RETRY_HINT_MAX_LENGTH)
                        + "），请严格按输出要求只返回合法 JSON。";
                continue;
            }

            List<ParsedQuestion> questions = extractQuestions(cleaned, context.getImageSeq());
            return ExamParseImageResult.builder()
                    .questions(questions)
                    .retryCount(retryCount)
                    .warning(buildWarning(cleaned, questions))
                    .failureReason(null)
                    .build();
        }

        log.error("试卷视觉解析重试耗尽: imageSeq={}, reason={}", context.getImageSeq(), lastError);
        return ExamParseImageResult.builder()
                .questions(List.of())
                .retryCount(retryCount)
                .warning(null)
                .failureReason(truncate("视觉解析重试 " + MAX_RETRIES + " 次后仍失败: " + lastError,
                        RETRY_HINT_MAX_LENGTH * 2))
                .build();
    }

    /**
     * 拼 Prompt（占位符与模板一一对应，替换后不得残留 {@code {xxx}}）
     *
     * <p>v4：{@code {knowledgePoints}} 注入本卷候选知识点（{@code - ID | 名称} 紧凑格式），
     * 8 图共用同一份快照字符串。</p>
     */
    String buildPrompt(ExamParseContext context, String retryHint) {
        String template = loadPromptTemplate();
        String subject = (context.getSubjectName() == null || context.getSubjectName().isBlank())
                ? "当前学科" : context.getSubjectName().trim();
        String grade = (context.getGradeLabel() == null || context.getGradeLabel().isBlank())
                ? "当前" : context.getGradeLabel().trim();
        String hint = (retryHint == null || retryHint.isBlank()) ? "无" : retryHint.trim();
        String kpBlock = renderKpCandidates(context.getKpCandidates());

        String prompt = template
                .replace("{subject}", subject)
                .replace("{grade}", grade)
                .replace("{imageSeq}", String.valueOf(context.getImageSeq()))
                .replace("{imageTotal}", String.valueOf(context.getImageTotal()))
                .replace("{retryHint}", hint)
                .replace("{knowledgePoints}", kpBlock);

        Matcher matcher = UNRESOLVED_PLACEHOLDER.matcher(prompt);
        if (matcher.find()) {
            log.warn("试卷解析 Prompt 存在未替换占位符: {}", matcher.group());
        }
        return prompt;
    }

    /**
     * 候选知识点渲染（空清单 → "（本卷无候选知识点）"；只记数量，不打印清单，避免日志体积）
     */
    static String renderKpCandidates(java.util.List<com.jiang.dto.KpCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "（本卷无候选知识点）";
        }
        StringBuilder sb = new StringBuilder(candidates.size() * 40);
        for (com.jiang.dto.KpCandidate candidate : candidates) {
            String name = candidate.name() == null ? "" : candidate.name().replaceAll("\\s+", " ").trim();
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(candidate.knowledgePointId()).append(" | ").append(name);
        }
        return sb.toString();
    }

    private String loadPromptTemplate() {
        String cached = promptTemplate;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (promptTemplate != null) {
                return promptTemplate;
            }
            try {
                ClassPathResource resource = new ClassPathResource(PROMPT_PATH);
                try (InputStream is = resource.getInputStream()) {
                    promptTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                log.error("加载试卷视觉解析 Prompt 模板失败: {}", e.getMessage());
                throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED,
                        "试卷视觉解析 Prompt 模板加载失败");
            }
            return promptTemplate;
        }
    }

    /**
     * 去掉模型可能包裹的 Markdown 代码块围栏（只保留 JSON 主体）
     */
    static String stripCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        String body = text.substring(firstLineEnd + 1);
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.trim();
    }

    private List<ParsedQuestion> extractQuestions(String json, int imageSeq) {
        List<ParsedQuestion> questions = new ArrayList<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            JsonNode items = root.get("questions");
            if (items == null || !items.isArray()) {
                return questions;
            }
            for (JsonNode item : items) {
                String error = agentOutputValidator.checkExamParseItem(item);
                if (error != null) {
                    // 单题非法：丢弃该题，不重试整图（design §3.4 步骤 2/3）
                    log.warn("丢弃非法解析题目: imageSeq={}, reason={}", imageSeq, error);
                    continue;
                }
                questions.add(toQuestion(item, imageSeq));
            }
        } catch (Exception e) {
            log.error("试卷解析结果 JSON 反序列化失败: {}", e.getMessage());
        }
        return questions;
    }

    private ParsedQuestion toQuestion(JsonNode item, int imageSeq) {
        String formatType = item.get("formatType").asText();
        String correctAnswer = sanitize(textOf(item, "correctAnswer"));
        List<Map<String, String>> choices = new ArrayList<>();

        if ("choice".equals(formatType)) {
            // 选择题归一（约定 #25）：label 重排 A/B/C…、answer 转选项标签；归不出来 → 答案置 null
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("formatType", formatType);
            content.put("choices", ChoiceOptions.normalize(item.get("choices")));
            content.put("answer", correctAnswer);
            ChoiceOptions.canonicalize(content);
            choices = ChoiceOptions.normalize(content.get("choices"));
            for (Map<String, String> option : choices) {
                option.put("text", sanitize(option.get("text")));
            }
            Object answerAfterCanonicalize = content.get("answer");
            String answer = answerAfterCanonicalize == null ? null : sanitize(String.valueOf(answerAfterCanonicalize));
            correctAnswer = (answer != null && answer.matches("[A-H]")) ? answer : null;
        }

        String studentAnswer = sanitize(textOf(item, "studentAnswer"));
        String answerStatus = item.get("answerStatus").asText();
        if ("answered".equals(answerStatus) && (studentAnswer == null || studentAnswer.isBlank())) {
            // 学生实际未作答（模型可能误标 answered）
            answerStatus = "blank";
            studentAnswer = "";
        }
        if ("blank".equals(answerStatus)) {
            studentAnswer = "";
        }

        String difficulty = textOf(item, "difficulty");
        if (difficulty != null) {
            difficulty = difficulty.trim();
            if (!"basic".equals(difficulty) && !"standard".equals(difficulty) && !"challenge".equals(difficulty)) {
                difficulty = null;
            }
        }

        return ParsedQuestion.builder()
                .sourceImageIndex(imageSeq)
                .questionIndex(item.get("questionIndex").asInt())
                .stem(sanitize(item.get("stem").asText()))
                .formatType(formatType)
                .choices(choices)
                .correctAnswer(correctAnswer)
                .studentAnswer(studentAnswer)
                .answerStatus(answerStatus)
                .difficulty(difficulty)
                .explanation(sanitize(textOf(item, "explanation")))
                .unclearFields(textList(item.get("unclearFields")))
                // v4：解析直出的知识点（白名单校验在 ExamParseService 做；这里只做格式归一）
                .knowledgePointId(normalizeKnowledgePointId(textOf(item, "knowledgePointId"), imageSeq))
                .attributionStatus(null)
                .build();
    }

    /**
     * 知识点 ID 归一：空白 → null；超长/多 ID → null + warn（规则 #8 的格式层防御）
     */
    private String normalizeKnowledgePointId(String raw, int imageSeq) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || "无".equals(value) || "null".equalsIgnoreCase(value)) {
            return null;
        }
        if (value.length() > 64 || value.contains(",") || value.contains("，") || value.contains(";")) {
            log.warn("丢弃非法知识点 ID（超长或多 ID）: imageSeq={}, length={}", imageSeq, value.length());
            return null;
        }
        return value;
    }

    private String buildWarning(String json, List<ParsedQuestion> questions) {
        String warning = null;
        try {
            String modelWarning = OBJECT_MAPPER.readTree(json).path("imageWarning").asText("");
            if (!modelWarning.isBlank()) {
                warning = sanitize(modelWarning);
            }
        } catch (Exception ignored) {
            // 警告提取失败不影响主流程
        }
        if (questions.isEmpty() && warning == null) {
            warning = "本图未识别出题目";
        }
        return warning;
    }

    private String textOf(JsonNode item, String field) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText(null);
    }

    private List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            String value = sanitize(item.asText(null));
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * 纯文本清洗（约定 #24）
     */
    private String sanitize(String raw) {
        return LlmTextSanitizer.sanitize(raw);
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
