package com.jiang.common.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从「解析（explanation）」里抽取结论答案（2026-11-17）
 *
 * <p><b>为什么需要</b>：视觉模型把 `correctAnswer` 与 `explanation` 当两个字段分别生成，
 * 实测会出现"解析算出来是 A、correctAnswer 却写 C"（试卷详情页表现为
 * 「我的答案 D / 标准答案 C / 0 分 / 解析第 4 步写选 A」）。产品规则 #4 要求
 * 「解析结论须与 correctAnswer 一致」，因此服务端以**解析结论**为兜底真值。</p>
 *
 * <p><b>抽取策略（保守，两级）</b>：</p>
 * <ol>
 *   <li><b>结论区优先</b>：只看解析末尾 2 个非空行（结论通常在第 4 步/最后一行），
 *       允许弱引导词「选 A」；</li>
 *   <li><b>全文兜底</b>：末尾没抽到时，全文只认强引导词
 *       （故选/应选/答案选/答案为/答案是/正确答案是(为|：)/正确选项是(为|：)/选择）；</li>
 *   <li>多处命中取**最后**一处；命中后紧跟「则/都/均/或/与/和/、/若/但」等枚举/条件词的一律丢弃
 *       （"若选 A 则…"、"选 A、B 都错" 这类是干扰项分析，不是结论）；</li>
 *   <li>抽不到 → 返回 null（不改动原值）；非选择题不做推断。</li>
 * </ol>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
public final class AnswerConclusionExtractor {

    /** 强引导词 + 选项字母串（"故…选 A" 之外的所有明确结论表达） */
    private static final Pattern STRONG = Pattern.compile(
            "(?:故选|应选|答案选|答案为|答案是|正确答案(?:为|是|\\s*[：:])|"
                    + "正确选项(?:为|是|\\s*[：:])|选择|答案\\s*[：:])\\s*([A-Ja-j]{1,8})");

    /** 弱引导词（只在"结论区"使用）：选 A / 选A */
    private static final Pattern WEAK = Pattern.compile("选\\s*([A-Ja-j]{1,8})");

    /** 命中后紧跟这些字符 → 是干扰项枚举/条件句，不是结论 */
    private static final Set<Character> REJECT_TAIL = Set.of(
            '则', '都', '均', '或', '与', '和', '、', '，', ',', '；', ';', '若', '但', '非', '才', '就');

    /** 结论区行数（末尾 N 个非空行） */
    private static final int CONCLUSION_TAIL_LINES = 2;

    private AnswerConclusionExtractor() {
    }

    /**
     * 从解析文本抽取选择题结论标签集合
     *
     * @param explanation 解析文本（多步、多行）
     * @return 规范化标签串（升序、去重，如 "ABD"）；抽不到返回 null
     */
    public static String extractChoiceAnswer(String explanation) {
        if (explanation == null || explanation.isBlank()) {
            return null;
        }
        String tail = conclusionTail(explanation);
        String fromTail = lastValidMatch(tail, true);
        if (fromTail != null) {
            return fromTail;
        }
        return lastValidMatch(explanation, false);
    }

    /** 末尾 N 个非空行（解析结论通常在第 4 步/最后一行） */
    static String conclusionTail(String explanation) {
        String[] lines = explanation.split("\\r?\\n");
        StringBuilder tail = new StringBuilder();
        int taken = 0;
        for (int index = lines.length - 1; index >= 0 && taken < CONCLUSION_TAIL_LINES; index--) {
            String line = lines[index];
            if (line == null || line.isBlank()) {
                continue;
            }
            tail.insert(0, line + "\n");
            taken++;
        }
        return taken == 0 ? explanation : tail.toString();
    }

    /**
     * 取最后一处「有效」匹配
     *
     * @param text        待扫描文本
     * @param allowWeak   是否接受弱引导词「选 A」
     * @return 规范化标签串或 null
     */
    private static String lastValidMatch(String text, boolean allowWeak) {
        Candidate best = scan(text, STRONG);
        if (allowWeak) {
            Candidate weak = scan(text, WEAK);
            if (weak != null && (best == null || weak.index > best.index)) {
                best = weak;
            }
        }
        return best == null ? null : best.value;
    }

    private static Candidate scan(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        Candidate last = null;
        while (matcher.find()) {
            String normalized = normalize(matcher.group(1));
            if (normalized == null) {
                continue;
            }
            if (followedByRejectChar(text, matcher.end(1))) {
                continue;
            }
            last = new Candidate(matcher.start(), normalized);
        }
        return last;
    }

    /** 命中后紧跟枚举/条件词 → 说明这是在分析干扰项（"选 A 则…"、"选 A、B 都错"） */
    private static boolean followedByRejectChar(String text, int endIndex) {
        for (int index = endIndex; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current)) {
                continue;
            }
            return REJECT_TAIL.contains(current);
        }
        return false;
    }

    /**
     * 字母串 → 升序去重大写标签串
     *
     * @param raw 如 "abd"
     * @return 如 "ABD"；非法返回 null
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (upper.isEmpty() || upper.length() > 8) {
            return null;
        }
        List<String> labels = ChoiceAnswer.labels(upper);
        if (labels.isEmpty()) {
            return null;
        }
        return ChoiceAnswer.canonical(labels);
    }

    /** 匹配位置 + 规范化结果 */
    private record Candidate(int index, String value) {
    }
}
