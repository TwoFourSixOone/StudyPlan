package com.jiang.common.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 试卷题面编解码工具（零 DDL 承载，design §4.1）
 *
 * <p>{@code exam_question} 没有 choices / correct_answer / explanation 列，因此把结构化字段
 * 编码进 {@code question_content}（TEXT）的分段纯文本：</p>
 *
 * <pre>
 * 【题干】&lt;stem&gt;
 * 【选项】
 * A. &lt;text&gt;
 * B. &lt;text&gt;
 * 【正确答案】&lt;correctAnswer&gt;
 * 【解析】&lt;explanation&gt;
 * </pre>
 *
 * <p>解码器容错：无任何标记时整段作为 stem（兼容旧数据/降级题）；缺段落不报错。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
public final class ExamQuestionTextCodec {

    public static final String STEM_MARK = "【题干】";
    public static final String CHOICES_MARK = "【选项】";
    public static final String ANSWER_MARK = "【正确答案】";
    public static final String EXPLANATION_MARK = "【解析】";
    /**
     * 作答状态段（可选）：仅当模型判定为 blank/unreadable 时写入。
     *
     * <p>零 DDL 下表里没有 answer_status 列，而"作答无法辨识（unreadable）→ 判分 NULL、
     * 不参与记账"必须在 <b>TX-A 之后的判分/重试路径</b>也能还原（design D17：重试直接用库中行判分），
     * 因此作为可选段落编码进 {@code question_content}（解析端容错，旧数据无此段 = answered）。</p>
     */
    public static final String ANSWER_STATUS_MARK = "【作答状态】";

    private static final String[] ALL_MARKS = {STEM_MARK, CHOICES_MARK, ANSWER_MARK, EXPLANATION_MARK,
            ANSWER_STATUS_MARK};

    /** "A. xxx" / "A、xxx" / "A) xxx" / "A：xxx" */
    private static final Pattern OPTION_LINE =
            Pattern.compile("^\\s*([A-Ha-h])\\s*[.、．)）:：]\\s*(.*)$");

    private ExamQuestionTextCodec() {
    }

    /**
     * 编码为分段纯文本
     *
     * @param stem          题干（约定 #24 已清洗）
     * @param choices       选项（标准形态，可为空）
     * @param correctAnswer 标准答案（可空）
     * @param explanation   解析（可空）
     * @return 落库文本
     */
    public static String encode(String stem, List<Map<String, String>> choices,
                                String correctAnswer, String explanation) {
        return encode(stem, choices, correctAnswer, explanation, null);
    }

    /**
     * 编码为分段纯文本（含作答状态段，仅 blank/unreadable 时写入）
     *
     * @param answerStatus answered/blank/unreadable（null 或 answered 不写该段）
     */
    public static String encode(String stem, List<Map<String, String>> choices,
                                String correctAnswer, String explanation, String answerStatus) {
        StringBuilder sb = new StringBuilder();
        sb.append(STEM_MARK).append(stem == null ? "" : stem.trim());
        if (choices != null && !choices.isEmpty()) {
            sb.append('\n').append(CHOICES_MARK);
            for (Map<String, String> choice : choices) {
                if (choice == null) {
                    continue;
                }
                String label = choice.get("label");
                String text = choice.get("text");
                if (text == null || text.isBlank()) {
                    continue;
                }
                sb.append('\n').append(label == null ? "" : label.trim()).append(". ").append(text.trim());
            }
        }
        if (correctAnswer != null && !correctAnswer.isBlank()) {
            sb.append('\n').append(ANSWER_MARK).append(correctAnswer.trim());
        }
        if (explanation != null && !explanation.isBlank()) {
            sb.append('\n').append(EXPLANATION_MARK).append(explanation.trim());
        }
        if (answerStatus != null && !answerStatus.isBlank() && !"answered".equals(answerStatus)) {
            sb.append('\n').append(ANSWER_STATUS_MARK).append(answerStatus.trim());
        }
        return sb.toString();
    }

    /**
     * 解码落库文本
     *
     * @param content {@code exam_question.question_content}
     * @return 结构化题面（非空字段保证 trim；choices 只含 label+text）
     */
    public static Decoded decode(String content) {
        if (content == null || content.isBlank()) {
            return new Decoded("", List.of(), null, null, null);
        }
        Map<String, String> sections = splitSections(content);
        if (sections.isEmpty()) {
            // 无任何标记：整段作为 stem（旧数据/降级题兼容）
            return new Decoded(content.trim(), List.of(), null, null, null);
        }
        String stem = trimToNull(sections.get(STEM_MARK));
        String choicesBlock = sections.get(CHOICES_MARK);
        String answer = trimToNull(sections.get(ANSWER_MARK));
        String explanation = trimToNull(sections.get(EXPLANATION_MARK));
        String answerStatus = trimToNull(sections.get(ANSWER_STATUS_MARK));
        return new Decoded(stem == null ? "" : stem, parseChoices(choicesBlock), answer, explanation,
                answerStatus);
    }

    /**
     * 解码结果（零 DDL 题面的结构化视图）
     */
    public record Decoded(String stem, List<Map<String, String>> choices,
                          String correctAnswer, String explanation, String answerStatus) {
    }

    private static Map<String, String> splitSections(String content) {
        List<int[]> positions = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        for (String mark : ALL_MARKS) {
            int index = content.indexOf(mark);
            if (index >= 0) {
                positions.add(new int[]{index, mark.length()});
                marks.add(mark);
            }
        }
        Map<String, String> sections = new LinkedHashMap<>();
        if (positions.isEmpty()) {
            return sections;
        }
        // 按出现位置排序（标记可能乱序/缺失）
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Integer.compare(positions.get(a)[0], positions.get(b)[0]));

        for (int i = 0; i < order.size(); i++) {
            int current = order.get(i);
            int start = positions.get(current)[0] + positions.get(current)[1];
            int end = content.length();
            if (i + 1 < order.size()) {
                end = positions.get(order.get(i + 1))[0];
            }
            if (end < start) {
                end = start;
            }
            sections.put(marks.get(current), content.substring(start, end));
        }
        return sections;
    }

    private static List<Map<String, String>> parseChoices(String block) {
        List<Map<String, String>> choices = new ArrayList<>();
        if (block == null || block.isBlank()) {
            return choices;
        }
        int index = 0;
        for (String rawLine : block.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = OPTION_LINE.matcher(line);
            String label;
            String text;
            if (matcher.matches()) {
                label = matcher.group(1).toUpperCase();
                text = matcher.group(2).trim();
            } else {
                label = String.valueOf((char) ('A' + Math.min(index, 7)));
                text = line;
            }
            if (text.isEmpty()) {
                continue;
            }
            Map<String, String> option = new LinkedHashMap<>();
            option.put("label", label);
            option.put("text", text);
            choices.add(option);
            index++;
        }
        return choices;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
