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
 * <p>Prompt 版本：{@code prompts/versions/exam_parse_v2.txt}（2026-11-12：详细解析 / 多选 /
 * 配图区域 figureRect；v1 保留可回滚）；
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
    static final String PROMPT_PATH = "prompts/versions/exam_parse_v2.txt";

    /** 整卷（多图单次）Prompt 模板路径（2026-11-12） */
    static final String PAPER_PROMPT_PATH = "prompts/versions/exam_parse_paper_v1.txt";

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

    /**
     * 整卷解析：全部图片一次性发给模型（2026-11-12 v5）
     *
     * <p>与单图解析同构：最多重试 {@link #MAX_RETRIES} 次；单题非法只丢弃该题。
     * 输出题号是全卷连续号，{@code imageIndex} 由模型标注（不合法时回退为 1）。</p>
     */
    @Override
    public ExamParseImageResult parsePaper(java.util.List<VisionImage> images,
                                           com.jiang.dto.ExamParsePaperContext context) {
        StringBuilder lastError = new StringBuilder();
        String retryHint = "无";
        int retryCount = 0;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            retryCount = attempt;
            String prompt = buildPaperPrompt(context, retryHint);
            String rawOutput;
            try {
                rawOutput = agentScopeClient.callVision(prompt, images);
            } catch (BusinessException e) {
                lastError = new StringBuilder("视觉模型调用失败: " + e.getMessage());
                log.warn("整卷视觉解析调用失败，准备重试 {}/{}: imageTotal={}, reason={}",
                        attempt + 1, MAX_RETRIES, context.getImageTotal(), e.getMessage());
                retryHint = "上次调用失败（" + truncate(e.getMessage(), RETRY_HINT_MAX_LENGTH)
                        + "），请重新通读全部图片。";
                continue;
            }

            String cleaned = stripCodeFence(rawOutput);
            AgentOutputValidator.ValidationResult validation = agentOutputValidator.validateExamParse(cleaned);
            if (!validation.isSuccess()) {
                lastError = new StringBuilder("整卷解析输出校验失败: " + validation.getMessage());
                log.warn("整卷视觉解析输出不合格，准备重试 {}/{}: reason={}",
                        attempt + 1, MAX_RETRIES, validation.getMessage());
                retryHint = "上次输出不符合要求（"
                        + truncate(validation.getMessage(), RETRY_HINT_MAX_LENGTH)
                        + "），请严格按输出要求只返回合法 JSON。";
                continue;
            }

            List<ParsedQuestion> questions = extractQuestions(cleaned, 0);
            return ExamParseImageResult.builder()
                    .questions(questions)
                    .retryCount(retryCount)
                    .warning(buildWarning(cleaned, questions))
                    .failureReason(null)
                    .build();
        }

        log.error("整卷视觉解析重试耗尽: imageTotal={}, reason={}", context.getImageTotal(), lastError);
        return ExamParseImageResult.builder()
                .questions(List.of())
                .retryCount(retryCount)
                .warning(null)
                .failureReason(truncate("整卷解析重试 " + MAX_RETRIES + " 次后仍失败: " + lastError,
                        RETRY_HINT_MAX_LENGTH * 2))
                .build();
    }

    /**
     * 整卷 Prompt 渲染（占位符：subject/grade/imageTotal/retryHint/knowledgePoints）
     */
    String buildPaperPrompt(com.jiang.dto.ExamParsePaperContext context, String retryHint) {
        String template = loadTemplate(PAPER_PROMPT_PATH);
        String subject = (context.getSubjectName() == null || context.getSubjectName().isBlank())
                ? "当前学科" : context.getSubjectName().trim();
        String grade = (context.getGradeLabel() == null || context.getGradeLabel().isBlank())
                ? "当前" : context.getGradeLabel().trim();
        String hint = (retryHint == null || retryHint.isBlank()) ? "无" : retryHint.trim();
        String ocrText = context.getOcrText() == null || context.getOcrText().isBlank()
                ? "（本卷无 OCR 文本，按图片识别）" : context.getOcrText().trim();
        String prompt = template
                .replace("{subject}", subject)
                .replace("{grade}", grade)
                .replace("{imageTotal}", String.valueOf(context.getImageTotal()))
                .replace("{retryHint}", hint)
                .replace("{ocrText}", ocrText)
                .replace("{knowledgePoints}", renderKpCandidates(context.getKpCandidates()));
        Matcher matcher = UNRESOLVED_PLACEHOLDER.matcher(prompt);
        if (matcher.find()) {
            log.warn("整卷解析 Prompt 存在未替换占位符: {}", matcher.group());
        }
        return prompt;
    }

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        } catch (IOException e) {
            log.error("加载试卷解析 Prompt 模板失败: path={}, reason={}", path, e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "试卷解析 Prompt 模板加载失败");
        }
    }

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
            JsonNode root = OBJECT_MAPPER.readTree(com.jiang.common.util.JsonPayloadExtractor.extract(json));
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
            // 2026-11-12：支持多选题——答案可能是 "AB" / "A、B"；标签必须都能在本卷选项里找到
            List<String> answerLabels = com.jiang.common.util.ChoiceAnswer.labels(answer);
            List<String> optionLabels = choices.stream()
                    .map(option -> option.get("label"))
                    .filter(label -> label != null)
                    .collect(java.util.stream.Collectors.toList());
            correctAnswer = com.jiang.common.util.ChoiceAnswer.withinOptions(answerLabels, optionLabels)
                    ? com.jiang.common.util.ChoiceAnswer.canonical(answerLabels) : null;
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

        String difficulty = textOf(item, "difficulty");        if (difficulty != null) {
            difficulty = difficulty.trim();
            if (!"basic".equals(difficulty) && !"standard".equals(difficulty) && !"challenge".equals(difficulty)) {
                difficulty = null;
            }
        }

        return ParsedQuestion.builder()
                // 整卷模式（imageSeq=0）由模型标注每题所在图片；单图模式沿用图片序号
                .sourceImageIndex(resolveSourceImageIndex(item, imageSeq))
                .questionIndex(item.get("questionIndex").asInt())
                .stem(sanitize(item.get("stem").asText()))
                .formatType(formatType)
                .choiceMode(normalizeChoiceMode(textOf(item, "choiceMode"), formatType, correctAnswer))
                .choices(choices)
                .correctAnswer(correctAnswer)
                .studentAnswer(studentAnswer)
                .answerStatus(answerStatus)
                .difficulty(difficulty)
                .explanation(sanitize(textOf(item, "explanation")))
                .figureRect(parseFigureRect(item, imageSeq))
                .hasPrintedFigure(hasPrintedFigure(item))
                .unclearFields(textList(item.get("unclearFields")))
                // v4：解析直出的知识点（白名单校验在 ExamParseService 做；这里只做格式归一）
                .knowledgePointId(normalizeKnowledgePointId(textOf(item, "knowledgePointId"), imageSeq))
                .attributionStatus(null)
                .build();
    }

    /**
     * 解析配图区域（2026-11-12）：归一化坐标 x/y/width/height ∈ [0,1]
     *
     * <p>防御：非数字、尺寸过小（&lt;5%）或越界的一律丢弃（宁可不裁图，也不要裁出一块空白）。</p>
     */
    /**
     * 来源图片序号：整卷模式（imageSeq=0）读模型给的 imageIndex，单图模式用入参
     */
    private int resolveSourceImageIndex(JsonNode item, int imageSeq) {
        if (imageSeq > 0) {
            return imageSeq;
        }
        JsonNode node = item.get("imageIndex");
        if (node != null && node.isInt() && node.asInt() > 0) {
            return node.asInt();
        }
        return 1;
    }

    /**
     * 选择题模式归一（2026-11-12）：模型显式给 single/multi 优先；
     * 缺省时按正确答案标签数推断（≥2 → multi），非选择题返回 null。
     */
    private String normalizeChoiceMode(String raw, String formatType, String correctAnswer) {
        if (!"choice".equals(formatType)) {
            return null;
        }
        String value = raw == null ? "" : raw.trim().toLowerCase();
        if ("multi".equals(value) || "multiple".equals(value) || "多选".equals(value)) {
            return "multi";
        }
        if ("single".equals(value) || "单选".equals(value)) {
            return "single";
        }
        return com.jiang.common.util.ChoiceAnswer.isMulti(correctAnswer) ? "multi" : "single";
    }

    /**
     * 是否含卷面印刷图形（2026-11-12）：模型 hasFigure 优先；缺省时按是否给了 figureRect 推断
     */
    private Boolean hasPrintedFigure(JsonNode item) {
        JsonNode node = item.get("hasFigure");
        if (node != null && node.isBoolean()) {
            return node.asBoolean();
        }
        return item.has("figureRect") && !item.get("figureRect").isNull();
    }

    private java.util.Map<String, Double> parseFigureRect(JsonNode item, int imageSeq) {
        JsonNode node = item.get("figureRect");
        if (node == null || !node.isObject()) {
            return null;
        }
        Double x = doubleOf(node, "x");
        Double y = doubleOf(node, "y");
        Double width = doubleOf(node, "width");
        Double height = doubleOf(node, "height");
        if (x == null || y == null || width == null || height == null) {
            return null;
        }
        x = clamp01(x);
        y = clamp01(y);
        width = clamp01(width);
        height = clamp01(height);
        if (width < 0.05 || height < 0.05) {
            log.warn("丢弃过小的配图区域: imageSeq={}, width={}, height={}", imageSeq, width, height);
            return null;
        }
        if (x + width > 1d) {
            width = Math.max(0.05, 1d - x);
        }
        if (y + height > 1d) {
            height = Math.max(0.05, 1d - y);
        }
        java.util.Map<String, Double> rect = new java.util.LinkedHashMap<>();
        rect.put("x", x);
        rect.put("y", y);
        rect.put("width", width);
        rect.put("height", height);
        return rect;
    }

    private Double doubleOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.asDouble();
    }

    private double clamp01(double value) {
        if (value < 0d) {
            return 0d;
        }
        return Math.min(value, 1d);
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
