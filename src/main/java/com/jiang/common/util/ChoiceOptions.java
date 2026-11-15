package com.jiang.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 选择题选项归一化工具（隐性约定 #25）
 *
 * <p>系统内的标准形态固定为 {@code [{"label":"A","text":"..."}]}，但 LLM 与历史数据
 * 可能出现字符串数组、{@code {key,text}}、对象映射等多种形态，本工具负责统一：</p>
 *
 * <ul>
 *   <li>字符串 {@code "A. 4"} → {@code {label:A, text:4}}</li>
 *   <li>字符串 {@code "4"} → {@code {label:A, text:4}}（按序号补 label）</li>
 *   <li>对象 {@code {key:"A", text:"4"}} → {@code {label:A, text:4}}</li>
 *   <li>映射 {@code {"A":"4"}} → {@code [{label:A, text:4}]}</li>
 * </ul>
 *
 * <p>同时负责把选择题答案归一化为「选项标签」（A/B/C/D），因为选择题批改是
 * 学生提交的 label 与 {@code content.answer} 的字符串比较。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Slf4j
public final class ChoiceOptions {

    /** 选择题至少需要的有效选项数 */
    public static final int MIN_OPTIONS = 2;

    /** 选项上限（label 只用 A-H） */
    public static final int MAX_OPTIONS = 8;

    private static final String LABELS = "ABCDEFGH";

    /** "A. xxx" / "A、xxx" / "A) xxx" / "A：xxx" */
    private static final Pattern LABEL_PREFIX =
            Pattern.compile("^\\s*([A-Ha-h])\\s*[.、．)）:：]\\s*(\\S.*)$", Pattern.DOTALL);

    /** "选项A" / "A项" */
    private static final Pattern LABEL_WORD =
            Pattern.compile("^(?:选项\\s*([A-Ha-h])|([A-Ha-h])\\s*项)$");

    private ChoiceOptions() {
    }

    /**
     * 任意形态的选项 → 标准形态（label 按出现顺序补齐，不保证 A/B/C 连续）
     */
    public static List<Map<String, String>> normalize(Object raw) {
        List<Map<String, String>> options = new ArrayList<>();
        if (raw == null) {
            return options;
        }
        if (raw instanceof JsonNode node) {
            addNode(options, node);
            return options;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                addValue(options, item);
            }
            return options;
        }
        if (raw instanceof Map<?, ?> map) {
            if (isSingleOption(map)) {
                addEntry(options, labelOf(map), textOf(map));
            } else {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    addEntry(options, String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return options;
        }
        addText(options, String.valueOf(raw));
        return options;
    }

    /**
     * 选项是否可用于作答：至少 {@link #MIN_OPTIONS} 个且每个 text 非空
     */
    public static boolean isUsable(List<Map<String, String>> options) {
        if (options == null || options.size() < MIN_OPTIONS) {
            return false;
        }
        for (Map<String, String> option : options) {
            if (option == null || option.get("text") == null || option.get("text").isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 就地归一化 content：选项重排为 A/B/C… 连续标签，选择题答案转为标签
     *
     * <p>非选择题统一写入空数组，保证落库形态一致。</p>
     */
    public static void canonicalize(Map<String, Object> content) {
        if (content == null) {
            return;
        }
        if (!"choice".equals(content.get("formatType"))) {
            content.put("choices", List.of());
            return;
        }

        List<Map<String, String>> rawOptions = normalize(content.get("choices"));
        String rawAnswer = asText(content.get("answer"));
        // 2026-11-12：支持多选题——答案可能是 "AB" / "A、B" / "ba"
        List<String> rawAnswerLabels = ChoiceAnswer.labels(rawAnswer);

        List<Map<String, String>> options = new ArrayList<>();
        List<String> matchedLabels = new ArrayList<>();
        int limit = Math.min(rawOptions.size(), MAX_OPTIONS);
        for (int i = 0; i < limit; i++) {
            Map<String, String> raw = rawOptions.get(i);
            String newLabel = String.valueOf(LABELS.charAt(i));
            String text = raw.get("text");
            options.add(option(newLabel, text));

            String oldLabel = raw.get("label");
            if (oldLabel != null && rawAnswerLabels.contains(oldLabel.trim().toUpperCase())) {
                matchedLabels.add(newLabel);
            }
        }

        // 答案只给了字母（如 "B"）且未与旧标签匹配 → 按字母位置回退（单选兼容逻辑）
        if (matchedLabels.isEmpty() && rawAnswerLabels.size() == 1) {
            int index = rawAnswerLabels.get(0).charAt(0) - 'A';
            if (index >= 0 && index < options.size()) {
                matchedLabels.add(String.valueOf(LABELS.charAt(index)));
            }
        }

        // 答案填的是"选项内容"而不是标签 → 按文本匹配（单选兼容逻辑）
        if (matchedLabels.isEmpty() && rawAnswerLabels.isEmpty()
                && rawAnswer != null && !rawAnswer.isBlank()) {
            for (int i = 0; i < limit; i++) {
                String text = rawOptions.get(i).get("text");
                if (text != null && text.trim().equalsIgnoreCase(rawAnswer.trim())) {
                    matchedLabels.add(String.valueOf(LABELS.charAt(i)));
                    break;
                }
            }
        }

        content.put("choices", options);
        if (!matchedLabels.isEmpty()) {
            content.put("answer", ChoiceAnswer.canonical(matchedLabels));
        } else if (rawAnswer != null && !rawAnswer.isBlank()) {
            log.warn("选择题答案无法归一化为选项标签: answer={}, optionCount={}", rawAnswer, options.size());
        }
    }

    private static boolean isSingleOption(Map<?, ?> map) {
        for (String key : new String[]{"label", "key", "text", "content", "value", "title", "option"}) {
            if (map.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static void addValue(List<Map<String, String>> options, Object item) {
        if (item == null) {
            return;
        }
        if (item instanceof JsonNode node) {
            addNode(options, node);
        } else if (item instanceof Map<?, ?> map) {
            addEntry(options, labelOf(map), textOf(map));
        } else {
            addText(options, String.valueOf(item));
        }
    }

    private static void addNode(List<Map<String, String>> options, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                addNode(options, item);
            }
            return;
        }
        if (node.isObject()) {
            boolean single = node.has("label") || node.has("key") || node.has("text");
            if (single) {
                addEntry(options, node.has("label") ? node.get("label").asText(null)
                                : node.has("key") ? node.get("key").asText(null) : null,
                        node.has("text") ? node.get("text").asText(null) : node.toString());
                return;
            }
            String label = null;
            String text = null;
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String name = field.getKey().toLowerCase();
                if (label == null && ("label".equals(name) || "key".equals(name) || "title".equals(name))) {
                    label = field.getValue().asText(null);
                } else if (text == null && ("text".equals(name) || "content".equals(name)
                        || "value".equals(name) || "option".equals(name))) {
                    text = field.getValue().asText(null);
                }
            }
            if (label != null || text != null) {
                addEntry(options, label, text);
                return;
            }
            // 形如 {"A":"4","B":"2"} 的对象映射
            var entries = node.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                addEntry(options, entry.getKey(), entry.getValue().asText(null));
            }
            return;
        }
        addText(options, node.asText());
    }

    private static void addEntry(List<Map<String, String>> options, String label, Object text) {
        String resolvedLabel = normalizeLabel(label, options.size());
        String resolvedText = text instanceof String value ? value : asText(text);
        if (resolvedText == null || resolvedText.isBlank()) {
            return;
        }
        // label 重复时按顺序重新分配，避免前端按序号渲染时错位
        final String candidate = resolvedLabel;
        boolean duplicated = options.stream().anyMatch(o -> candidate.equals(o.get("label")));
        if (duplicated) {
            resolvedLabel = String.valueOf(LABELS.charAt(options.size() % MAX_OPTIONS));
        }
        options.add(option(resolvedLabel, resolvedText.trim()));
    }

    private static void addText(List<Map<String, String>> options, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = LABEL_PREFIX.matcher(text);
        if (matcher.matches()) {
            addEntry(options, matcher.group(1), matcher.group(2));
            return;
        }
        addEntry(options, null, text.trim());
    }

    private static String normalizeLabel(String label, int index) {
        if (label != null) {
            String trimmed = label.trim();
            Matcher word = LABEL_WORD.matcher(trimmed);
            if (word.matches()) {
                String letter = word.group(1) != null ? word.group(1) : word.group(2);
                return letter.toUpperCase();
            }
            if (trimmed.length() == 1 && LABELS.indexOf(Character.toUpperCase(trimmed.charAt(0))) >= 0) {
                return trimmed.toUpperCase();
            }
        }
        return String.valueOf(LABELS.charAt(index % MAX_OPTIONS));
    }

    private static Map<String, String> option(String label, String text) {
        Map<String, String> option = new java.util.LinkedHashMap<>();
        option.put("label", label);
        option.put("text", text == null ? "" : text.trim());
        return option;
    }

    private static String labelOf(Map<?, ?> map) {
        for (Object key : new Object[]{"label", "key", "title"}) {
            if (map.containsKey(key)) {
                return asText(map.get(key));
            }
        }
        return null;
    }

    private static String textOf(Map<?, ?> map) {
        for (Object key : new Object[]{"text", "content", "value", "option"}) {
            if (map.containsKey(key)) {
                return asText(map.get(key));
            }
        }
        return null;
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            return node.asText(null);
        }
        return String.valueOf(value);
    }
}
