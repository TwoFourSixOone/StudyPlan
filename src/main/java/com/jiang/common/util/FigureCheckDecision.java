package com.jiang.common.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 裁剪配图二次确认结果判定（2026-11-12，2026-11-17 收紧）
 *
 * <p>把「裁剪块再问一次视觉模型」的一句话回答翻译成三态：</p>
 * <ul>
 *   <li>{@code true}：确认是印刷图形</li>
 *   <li>{@code false}：确认不是（文字/手写/题号分值/空白）→ 调用方换下一个候选框或回退整页原图</li>
 *   <li>{@code null}：无法判断（空回答/含糊）→ 调用方保留裁剪块（宁可展示裁图，也不误删）</li>
 * </ul>
 *
 * <p><b>2026-11-17 修复</b>：原实现"先宽匹配否定词"会把
 * {@code "printed（图中带坐标轴与标注文字）"} 判成不合格（命中了子串 {@code 文字}），
 * 导致带标注的几何图几乎必然被判掉、只能回退整页图。现改为：</p>
 * <ol>
 *   <li>先看**明确结论词**（{@code printed} / {@code invalid} 及其中文对应），按出现位置取最靠前的一个；</li>
 *   <li>再退回宽匹配（否定优先，保守）；</li>
 *   <li>都没有 → {@code null}（保留裁剪块）。</li>
 * </ol>
 *
 * @author StudyMate Team
 * @since 2026-11-12
 */
@Slf4j
public final class FigureCheckDecision {

    private FigureCheckDecision() {
    }

    /**
     * 判定模型回答
     *
     * @param modelAnswer 模型回答（期望 printed / invalid，允许夹带中英文说明）
     * @return true=印刷图形；false=不合格；null=无法判断
     */
    public static Boolean classify(String modelAnswer) {
        if (modelAnswer == null) {
            return null;
        }
        String text = modelAnswer.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        Boolean explicit = explicitConclusion(text);
        if (explicit != null) {
            return explicit;
        }
        // 没有明确结论词：退回宽匹配（否定优先，保守处理）
        if (containsAny(text, "不是印刷图形", "非印刷图形", "不合格", "invalid", "handwriting",
                "手写", "草稿", "draft", "空白", "题号", "分值", "以文字为主", "text only")) {
            return Boolean.FALSE;
        }
        if (containsAny(text, "printed", "印刷图形", "印刷的图", "合格", "是图形")) {
            return Boolean.TRUE;
        }
        log.debug("配图审核回答无法判定: {}", modelAnswer);
        return null;
    }

    /** 明确结论词：取最先出现的结论，并跳过被否定的肯定词（"不是印刷图形"） */
    private static Boolean explicitConclusion(String text) {
        int invalidIndex = indexOfAny(text, 0, false, "invalid", "不合格", "不是印刷图形", "非印刷图形",
                "不是图", "没有图形");
        int printedIndex = indexOfPositive(text);
        if (printedIndex >= 0 && invalidIndex >= 0) {
            return printedIndex < invalidIndex ? Boolean.TRUE : Boolean.FALSE;
        }
        if (printedIndex >= 0) {
            return Boolean.TRUE;
        }
        if (invalidIndex >= 0) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 肯定词位置：跳过前面 3 个字符内出现否定词的匹配（"不是印刷图形"） */
    private static int indexOfPositive(String text) {
        return indexOfAny(text, 0, true, "printed", "印刷图形", "印刷的图", "合格", "是图形");
    }

    private static int indexOfAny(String text, int from, boolean skipNegated, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int index = text.indexOf(needle, from);
            while (index >= 0) {
                if (!skipNegated || !isNegated(text, index)) {
                    break;
                }
                index = text.indexOf(needle, index + 1);
            }
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    /** 该位置前 3 个字符内是否有否定词（不是/非/不/没/无） */
    private static boolean isNegated(String text, int index) {
        int start = Math.max(0, index - 3);
        String prefix = text.substring(start, index);
        return prefix.contains("不是") || prefix.contains("非") || prefix.contains("不")
                || prefix.contains("没") || prefix.contains("无");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
