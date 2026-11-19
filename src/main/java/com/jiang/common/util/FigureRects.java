package com.jiang.common.util;

import com.jiang.dto.ParsedQuestion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配图候选框（2026-11-17 修复）
 *
 * <p><b>问题背景</b>：`配图二次确认不通过（疑似文字/题号/手写），回退整页原图` 反复出现时，
 * 说明"当前这个框"裁出来的不是印刷图形。框有两个来源：视觉模型给的 `figureRect` 与
 * GLM-OCR `label=image` bbox 吸附出的 `ocrFigureRect`。历史上 OCR 吸附会**直接覆盖**模型框，
 * 于是模型框即使是对的也可能被一个装饰图的 bbox 顶掉，且被拒后没有第二次机会。</p>
 *
 * <p>本工具把两个来源整理成**有序候选列表**（模型框优先，其次 OCR 框），并过滤掉明显不可能
 * 是图形的框（整页、细条、面积过小、越界）。服务端逐个裁剪 + 二次确认，任一通过即采用，
 * 全部不通过才回退整页原图。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
public final class FigureRects {

    /** 候选框最小面积占比（相对整页）：更小的框基本是标注/符号碎片 */
    static final double MIN_AREA_RATIO = 0.015d;

    /** 候选框最大面积占比：接近整页的框没有裁剪意义，直接用整页回退 */
    static final double MAX_AREA_RATIO = 0.90d;

    /** 候选框最小边长（相对整页） */
    static final double MIN_SIDE_RATIO = 0.05d;

    /** 候选框宽高比上下限（过滤细长条：表头线、装订线、页边装饰） */
    static final double MIN_ASPECT = 0.20d;

    static final double MAX_ASPECT = 5.00d;

    private FigureRects() {
    }

    /**
     * 有序候选框：模型框 → OCR 框（去重 + 合理性过滤）
     *
     * @param question 题目（读取 {@code figureRect} 与 {@code ocrFigureRect}）
     * @return 可直接用于裁剪的候选框列表（可能为空）
     */
    public static List<Map<String, Double>> candidates(ParsedQuestion question) {
        List<Map<String, Double>> result = new ArrayList<>(2);
        if (question == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        addIfPlausible(result, seen, question.getFigureRect());
        addIfPlausible(result, seen, question.getOcrFigureRect());
        return result;
    }

    private static void addIfPlausible(List<Map<String, Double>> result, Set<String> seen,
                                       Map<String, Double> rect) {
        if (!isPlausible(rect)) {
            return;
        }
        String key = describe(rect);
        if (seen.add(key)) {
            result.add(rect);
        }
    }

    /**
     * 框是否"像一个图形"：四边齐全、在页内、面积 1.5%~90%、两边 ≥5%、宽高比 0.2~5
     *
     * @param rect 归一化框
     * @return true = 值得裁剪并送二次确认
     */
    public static boolean isPlausible(Map<String, Double> rect) {
        if (rect == null || rect.isEmpty()) {
            return false;
        }
        Double x = rect.get("x");
        Double y = rect.get("y");
        Double w = rect.get("width");
        Double h = rect.get("height");
        if (x == null || y == null || w == null || h == null) {
            return false;
        }
        if (w <= 0 || h <= 0 || w < MIN_SIDE_RATIO || h < MIN_SIDE_RATIO) {
            return false;
        }
        if (x < 0 || y < 0 || x + w > 1.001d || y + h > 1.001d) {
            return false;
        }
        double area = w * h;
        if (area < MIN_AREA_RATIO || area > MAX_AREA_RATIO) {
            return false;
        }
        double aspect = w / h;
        return aspect >= MIN_ASPECT && aspect <= MAX_ASPECT;
    }

    /** 日志友好的框描述（x/y/w/h 三位小数） */
    public static String describe(Map<String, Double> rect) {
        if (rect == null || rect.isEmpty()) {
            return "null";
        }
        return String.format(java.util.Locale.ROOT, "{x=%.3f, y=%.3f, w=%.3f, h=%.3f}",
                value(rect, "x"), value(rect, "y"), value(rect, "width"), value(rect, "height"));
    }

    private static double value(Map<String, Double> rect, String key) {
        Double raw = rect.get(key);
        return raw == null ? 0d : raw;
    }
}
