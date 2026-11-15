package com.jiang.common.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 选择题答案归一与比对（2026-11-12：支持多选题）
 *
 * <p>标准形态：选项标签字符串，单个如 {@code "B"}，多个按字母序连写如 {@code "ABD"}。
 * 模型/学生可能写成 {@code "A、B"}、{@code "A,B"}、{@code "ba"}、{@code "选 AB"}，
 * 这里统一抽出 A–H 字母并去重排序。</p>
 *
 * <p>判分口径（产品规则 #4，2026-11-12 确认）：<b>严格集合相等</b>——多选、少选都算错。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-12
 */
@Slf4j
public final class ChoiceAnswer {

    /** 选项标签上限（与 ChoiceOptions.MAX_OPTIONS 一致） */
    private static final String LABELS = "ABCDEFGH";

    private ChoiceAnswer() {
    }

    /**
     * 抽取答案中的选项标签（大写、去重、升序）
     *
     * <p>{@code "A、B"} → {@code [A, B]}；{@code "选ABD"} → {@code [A, B, D]}；
     * 无字母时返回空列表。</p>
     */
    public static List<String> labels(String rawAnswer) {
        List<String> result = new ArrayList<>(2);
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return result;
        }
        TreeSet<Character> letters = new TreeSet<>();
        for (int i = 0; i < rawAnswer.length(); i++) {
            char c = Character.toUpperCase(rawAnswer.charAt(i));
            if (LABELS.indexOf(c) >= 0) {
                letters.add(c);
            }
        }
        for (Character c : letters) {
            result.add(String.valueOf(c));
        }
        return result;
    }

    /**
     * 标签列表 → 标准答案串（升序连写）
     */
    public static String canonical(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        TreeSet<String> sorted = new TreeSet<>(labels);
        return String.join("", sorted);
    }

    /**
     * 是否多选题答案（标签数 ≥ 2）
     */
    public static boolean isMulti(String rawAnswer) {
        return labels(rawAnswer).size() > 1;
    }

    /**
     * 严格集合相等：两边标签集合完全一致才算对（多选、少选都算错）
     *
     * <p>任一边为空/无标签 → false（未作答或答案缺失由调用方先行判定）。</p>
     */
    public static boolean sameAnswer(String studentAnswer, String correctAnswer) {
        if (studentAnswer == null || studentAnswer.isBlank()
                || correctAnswer == null || correctAnswer.isBlank()) {
            return false;
        }
        List<String> left = labels(studentAnswer);
        List<String> right = labels(correctAnswer);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return new TreeSet<>(left).equals(new TreeSet<>(right));
    }

    /**
     * 答案标签是否都在给定选项标签集合内（选择题答案合法性校验）
     */
    public static boolean withinOptions(List<String> answerLabels, List<String> optionLabels) {
        if (answerLabels == null || answerLabels.isEmpty()
                || optionLabels == null || optionLabels.isEmpty()) {
            return false;
        }
        return optionLabels.containsAll(answerLabels);
    }
}
