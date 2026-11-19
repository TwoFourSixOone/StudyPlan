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

    /** 印刷题目版 Prompt（2026-11-17 双版本） */
    static final String CLEAN_PROMPT_PATH = "prompts/versions/exam_parse_clean_v1.txt";

    /** 学生作答版 Prompt（2026-11-17 双版本） */
    static final String ANSWERS_PROMPT_PATH = "prompts/versions/exam_parse_answers_v1.txt";

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
        return parseWholePaper(images, context, PAPER_PROMPT_PATH);
    }

    /**
     * 印刷题目版（2026-11-17 双版本）：同一套重试/校验链路，只换 Prompt
     *
     * <p>结果强制 `studentAnswer=""`、`answerStatus="blank"`——作答由 student 版单独提取，
     * 防止 clean 调用从卷面手写里"顺带"读出学生答案。</p>
     */
    @Override
    public ExamParseImageResult parseCleanPaper(java.util.List<VisionImage> images,
                                                com.jiang.dto.ExamParsePaperContext context) {
        ExamParseImageResult result = parseWholePaper(images, context, CLEAN_PROMPT_PATH);
        if (result.getQuestions() != null) {
            for (ParsedQuestion question : result.getQuestions()) {
                question.setStudentAnswer("");
                question.setAnswerStatus("blank");
            }
        }
        return result;
    }

    private ExamParseImageResult parseWholePaper(java.util.List<VisionImage> images,
                                                 com.jiang.dto.ExamParsePaperContext context,
                                                 String promptPath) {
        StringBuilder lastError = new StringBuilder();
        String retryHint = "无";
        int retryCount = 0;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            retryCount = attempt;
            String prompt = buildWholePrompt(context, retryHint, promptPath);
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

            ExtractResult extracted = extractQuestions(cleaned, 0);
            return ExamParseImageResult.builder()
                    .questions(extracted.questions())
                    .paperQuestionTotal(extracted.paperQuestionTotal())
                    .droppedCount(extracted.droppedCount())
                    .retryCount(retryCount)
                    .warning(buildWarning(cleaned, extracted.questions(), extracted.droppedCount(), extracted.answerFixedCount()))
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
        return buildWholePrompt(context, retryHint, PAPER_PROMPT_PATH);
    }

    /**
     * 整卷 Prompt 渲染（占位符：subject/grade/imageTotal/retryHint/knowledgePoints/ocrText）
     *
     * <p>package-private：契约测试直接调用它渲染 paper / clean 两套模板，断言无残留占位符。</p>
     */
    String buildWholePrompt(com.jiang.dto.ExamParsePaperContext context, String retryHint,
                            String promptPath) {
        String template = loadTemplate(promptPath);
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
                // 2026-11-17 修复：Prompt 模板是 UTF-8，必须显式按 UTF-8 解码。
                // 用平台默认字符集时（Windows 中文 / POSIX C locale 下 JVM 默认 GBK），
                // 整份 Prompt 会变成乱码 ⇒ 模型收到乱码指令、输出质量不可控。
                // 与本类单图 Prompt 加载器（StandardCharsets.UTF_8）保持一致。
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("加载试卷解析 Prompt 模板失败: path={}, reason={}", path, e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "试卷解析 Prompt 模板加载失败");
        }
    }

    // ==================== 学生作答版（2026-11-17 双版本） ====================

    @Override
    public com.jiang.dto.ExamAnswerParseResult parseStudentAnswers(
            java.util.List<VisionImage> images,
            com.jiang.dto.ExamAnswerParseContext context) {
        StringBuilder lastError = new StringBuilder();
        String retryHint = "无";
        int retryCount = 0;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            retryCount = attempt;
            String prompt = buildAnswersPrompt(context, retryHint);
            String rawOutput;
            try {
                rawOutput = agentScopeClient.callVision(prompt, images);
            } catch (BusinessException e) {
                lastError = new StringBuilder("视觉模型调用失败: " + e.getMessage());
                log.warn("作答版解析调用失败，准备重试 {}/{}: imageTotal={}, reason={}",
                        attempt + 1, MAX_RETRIES, context.getImageTotal(), e.getMessage());
                retryHint = "上次调用失败（" + truncate(e.getMessage(), RETRY_HINT_MAX_LENGTH)
                        + "），请重新通读全部图片。";
                continue;
            }

            String cleaned = stripCodeFence(rawOutput);
            AgentOutputValidator.ValidationResult validation =
                    agentOutputValidator.validateExamAnswers(cleaned);
            if (!validation.isSuccess()) {
                lastError = new StringBuilder("作答版输出校验失败: " + validation.getMessage());
                log.warn("作答版输出不合格，准备重试 {}/{}: reason={}",
                        attempt + 1, MAX_RETRIES, validation.getMessage());
                retryHint = "上次输出不符合要求（"
                        + truncate(validation.getMessage(), RETRY_HINT_MAX_LENGTH)
                        + "），请严格按输出要求只返回合法 JSON。";
                continue;
            }

            AnswersExtract extracted = extractAnswers(cleaned);
            return com.jiang.dto.ExamAnswerParseResult.builder()
                    .answers(extracted.answers())
                    .droppedCount(extracted.droppedCount())
                    .retryCount(retryCount)
                    .warning(buildAnswerWarning(cleaned, extracted.droppedCount()))
                    .failureReason(null)
                    .build();
        }

        log.error("作答版解析重试耗尽: imageTotal={}, reason={}", context.getImageTotal(), lastError);
        return com.jiang.dto.ExamAnswerParseResult.builder()
                .answers(java.util.List.of())
                .retryCount(retryCount)
                .warning(null)
                .failureReason(truncate("作答版解析重试 " + MAX_RETRIES + " 次后仍失败: " + lastError,
                        RETRY_HINT_MAX_LENGTH * 2))
                .build();
    }

    String buildAnswersPrompt(com.jiang.dto.ExamAnswerParseContext context, String retryHint) {
        String template = loadTemplate(ANSWERS_PROMPT_PATH);
        String subject = (context.getSubjectName() == null || context.getSubjectName().isBlank())
                ? "当前学科" : context.getSubjectName().trim();
        String grade = (context.getGradeLabel() == null || context.getGradeLabel().isBlank())
                ? "当前" : context.getGradeLabel().trim();
        String hint = (retryHint == null || retryHint.isBlank()) ? "无" : retryHint.trim();
        String digest = context.getQuestionDigest() == null || context.getQuestionDigest().isBlank()
                ? "（无题目清单）" : context.getQuestionDigest().trim();
        String prompt = template
                .replace("{subject}", subject)
                .replace("{grade}", grade)
                .replace("{imageTotal}", String.valueOf(context.getImageTotal()))
                .replace("{retryHint}", hint)
                .replace("{questionDigest}", digest);
        Matcher matcher = UNRESOLVED_PLACEHOLDER.matcher(prompt);
        if (matcher.find()) {
            log.warn("作答版 Prompt 存在未替换占位符: {}", matcher.group());
        }
        return prompt;
    }

    private AnswersExtract extractAnswers(String json) {
        List<com.jiang.dto.ParsedAnswer> answers = new ArrayList<>();
        int dropped = 0;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(com.jiang.common.util.JsonPayloadExtractor.extract(json));
            JsonNode items = root.get("answers");
            if (items == null || !items.isArray()) {
                return new AnswersExtract(answers, dropped);
            }
            for (JsonNode item : items) {
                String error = agentOutputValidator.checkExamAnswerItem(item);
                if (error != null) {
                    dropped++;
                    log.warn("丢弃非法作答: reason={}", error);
                    continue;
                }
                answers.add(com.jiang.dto.ParsedAnswer.builder()
                        .paperNo(item.get("paperNo").asInt())
                        .studentAnswer(sanitize(item.path("studentAnswer").asText("")))
                        .answerStatus(item.get("answerStatus").asText())
                        .unclearFields(textList(item.get("unclearFields")))
                        .build());
            }
        } catch (Exception e) {
            log.error("作答版 JSON 反序列化失败: {}", e.getMessage());
        }
        return new AnswersExtract(answers, dropped);
    }

    private String buildAnswerWarning(String json, int droppedCount) {
        String warning = null;
        try {
            String modelWarning = OBJECT_MAPPER.readTree(json).path("imageWarning").asText("");
            if (!modelWarning.isBlank()) {
                warning = sanitize(modelWarning);
            }
        } catch (Exception ignored) {
            // 警告提取失败不影响主流程
        }
        if (droppedCount > 0) {
            String note = "已丢弃 " + droppedCount + " 条非法作答";
            warning = warning == null ? note : warning + "；" + note;
        }
        return warning;
    }

    private record AnswersExtract(List<com.jiang.dto.ParsedAnswer> answers, int droppedCount) {
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

            ExtractResult extracted = extractQuestions(cleaned, context.getImageSeq());
            return ExamParseImageResult.builder()
                    .questions(extracted.questions())
                    .paperQuestionTotal(extracted.paperQuestionTotal())
                    .droppedCount(extracted.droppedCount())
                    .retryCount(retryCount)
                    .warning(buildWarning(cleaned, extracted.questions(), extracted.droppedCount(), extracted.answerFixedCount()))
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

    private ExtractResult extractQuestions(String json, int imageSeq) {
        List<ParsedQuestion> questions = new ArrayList<>();
        Integer paperQuestionTotal = null;
        int dropped = 0;
        int answerFixed = 0;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(com.jiang.common.util.JsonPayloadExtractor.extract(json));
            JsonNode totalNode = root.get("paperQuestionTotal");
            if (totalNode != null && totalNode.isNumber()) {
                int value = totalNode.asInt();
                if (value > 0 && value <= 60) {
                    paperQuestionTotal = value;
                }
            }
            JsonNode items = root.get("questions");
            if (items == null || !items.isArray()) {
                return new ExtractResult(questions, paperQuestionTotal, dropped, answerFixed);
            }
            for (JsonNode item : items) {
                String error = agentOutputValidator.checkExamParseItem(item);
                if (error != null) {
                    // 单题非法：丢弃该题，不重试整图（design §3.4 步骤 2/3）
                    dropped++;
                    log.warn("丢弃非法解析题目: imageSeq={}, reason={}", imageSeq, error);
                    continue;
                }
                boolean[] fixed = new boolean[1];
                questions.add(toQuestion(item, imageSeq, fixed));
                if (fixed[0]) {
                    answerFixed++;
                }
            }
        } catch (Exception e) {
            log.error("试卷解析结果 JSON 反序列化失败: {}", e.getMessage());
        }
        return new ExtractResult(questions, paperQuestionTotal, dropped, answerFixed);
    }

    /**
     * 切题结果（2026-11-17：题数/丢弃数/答案修正数需要向上传递，不能只返回题目列表）
     */
    private record ExtractResult(List<ParsedQuestion> questions, Integer paperQuestionTotal,
                                 int droppedCount, int answerFixedCount) {
    }

    private ParsedQuestion toQuestion(JsonNode item, int imageSeq) {
        return toQuestion(item, imageSeq, new boolean[1]);
    }

    private ParsedQuestion toQuestion(JsonNode item, int imageSeq, boolean[] answerFixedFlag) {
        String formatType = item.get("formatType").asText();
        String correctAnswer = sanitize(textOf(item, "correctAnswer"));
        String explanation = sanitize(textOf(item, "explanation"));
        List<Map<String, String>> choices = new ArrayList<>();
        boolean answerFixedByExplanation = false;

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
            List<String> optionLabels = choices.stream()
                    .map(option -> option.get("label"))
                    .filter(label -> label != null)
                    .collect(java.util.stream.Collectors.toList());
            // 2026-11-17：标准答案与解析结论对齐。模型两个字段分别生成时会自相矛盾
            // （实测"解析故选 ABD / correctAnswer=AB"→ 学生答 ABD 被判错）。以解析结论为准。
            String fromExplanation = com.jiang.common.util.AnswerConclusionExtractor
                    .extractChoiceAnswer(explanation);
            if (fromExplanation != null
                    && com.jiang.common.util.ChoiceAnswer.withinOptions(
                            com.jiang.common.util.ChoiceAnswer.labels(fromExplanation), optionLabels)) {
                List<String> modelLabels = com.jiang.common.util.ChoiceAnswer.labels(correctAnswer);
                String modelCanonical = com.jiang.common.util.ChoiceAnswer.withinOptions(modelLabels, optionLabels)
                        ? com.jiang.common.util.ChoiceAnswer.canonical(modelLabels) : null;
                if (!fromExplanation.equals(modelCanonical)) {
                    log.warn("标准答案与解析结论不一致，按解析修正: imageSeq={}, correctAnswer={}, explanationAnswer={}",
                            imageSeq, modelCanonical, fromExplanation);
                    content.put("answer", fromExplanation);
                    answerFixedByExplanation = true;
                }
            }
            Object answerAfterCanonicalize = content.get("answer");
            String answer = answerAfterCanonicalize == null ? null : sanitize(String.valueOf(answerAfterCanonicalize));
            // 2026-11-12：支持多选题——答案可能是 "AB" / "A、B"；标签必须都能在本卷选项里找到
            List<String> answerLabels = com.jiang.common.util.ChoiceAnswer.labels(answer);
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
        // 2026-11-17：选择题的作答是"勾选项"，老师也会在正确选项上打勾；
        // 模型若只把 √/× 当学生答案，按老师批改处理（不猜具体选项）。
        if ("answered".equals(answerStatus) && isOnlyTickMark(studentAnswer)) {
            log.info("学生答案仅为对错符号，按老师批改处理: imageSeq={}", imageSeq);
            answerStatus = "blank";
            studentAnswer = "";
        }

        String difficulty = textOf(item, "difficulty");        if (difficulty != null) {
            difficulty = difficulty.trim();
            if (!"basic".equals(difficulty) && !"standard".equals(difficulty) && !"challenge".equals(difficulty)) {
                difficulty = null;
            }
        }

        if (answerFixedFlag != null && answerFixedFlag.length > 0) {
            answerFixedFlag[0] = answerFixedByExplanation;
        }

        return ParsedQuestion.builder()
                // 整卷模式（imageSeq=0）由模型标注每题所在图片；单图模式沿用图片序号
                .sourceImageIndex(resolveSourceImageIndex(item, imageSeq))
                .questionIndex(parseQuestionIndex(item))
                .paperNo(parsePaperNo(item))
                .stem(sanitize(item.get("stem").asText()))
                .formatType(formatType)
                .choiceMode(normalizeChoiceMode(textOf(item, "choiceMode"), formatType, correctAnswer))
                .choices(choices)
                .correctAnswer(correctAnswer)
                .studentAnswer(studentAnswer)
                .answerStatus(answerStatus)
                .difficulty(difficulty)
                .explanation(explanation)
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

    /** 纯对错符号（老师批改标记），不能当作学生答案 */
    private static final java.util.Set<String> TICK_MARKS = java.util.Set.of("√", "✓", "✔", "×", "✗", "✘");

    private boolean isOnlyTickMark(String answer) {
        return answer != null && TICK_MARKS.contains(answer.trim());
    }

    /**
     * 卷面题号：1..60 的整数；缺失/越界返回 null（新字段不参与丢弃判定）
     */
    private Integer parsePaperNo(JsonNode item) {
        JsonNode node = item.get("paperNo");
        if (node == null || !node.isNumber()) {
            return null;
        }
        int value = node.asInt();
        return value >= 1 && value <= 60 ? value : null;
    }

    /**
     * 题号：优先 questionIndex（1..60），缺失/非法时回退 paperNo
     *
     * <p>2026-11-17：服务端最终会用 paperNo 重新编号，模型漏给 questionIndex 不应整题丢弃
     * （否则又是一条"静默丢题"路径）。两者都没有时返回 null，由 {@code ExamParseNumbering} 兜底编号。</p>
     */
    private Integer parseQuestionIndex(JsonNode item) {
        JsonNode node = item.get("questionIndex");
        if (node != null && node.isNumber()) {
            int value = node.asInt();
            if (value >= 1 && value <= 60) {
                return value;
            }
        }
        return parsePaperNo(item);
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

    private String buildWarning(String json, List<ParsedQuestion> questions, int droppedCount,
                                int answerFixedCount) {
        String warning = null;
        try {
            String modelWarning = OBJECT_MAPPER.readTree(json).path("imageWarning").asText("");
            if (!modelWarning.isBlank()) {
                warning = sanitize(modelWarning);
            }
        } catch (Exception ignored) {
            // 警告提取失败不影响主流程
        }
        if (droppedCount > 0) {
            // 2026-11-17：被丢弃的题不再静默，必须让学生/排障看到
            String note = "已丢弃 " + droppedCount + " 道输出不合法的题目";
            warning = warning == null ? note : warning + "；" + note;
        }
        if (answerFixedCount > 0) {
            // 2026-11-17：标准答案按解析结论修正过 → 必须可见（否则学生看到"标准答案 AB / 解析故选 ABD"）
            String note = "已按解析结论修正 " + answerFixedCount + " 道标准答案";
            warning = warning == null ? note : warning + "；" + note;
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
            // 上限与 schema 的 unclearFields.maxItems 对齐（6），防止模型回传超长数组
            if (values.size() >= AgentOutputValidator.UNCLEAR_FIELDS_MAX) {
                break;
            }
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
