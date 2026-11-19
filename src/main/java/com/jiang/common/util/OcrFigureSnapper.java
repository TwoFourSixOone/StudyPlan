package com.jiang.common.util;

import com.jiang.dto.ParsedQuestion;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 用 GLM-OCR 的图片 bbox 提供"配图候选框"（2026-11-17）
 *
 * <p>视觉模型给的 {@code figureRect} 经常偏移；GLM-OCR 的 {@code layout_details} 里有精确的
 * {@code label=image} 区域（官方为归一化 [x1,y1,x2,y2]）。本工具把 OCR 框写进
 * {@link ParsedQuestion#getOcrFigureRect()}，<b>不覆盖模型框</b>：
 * 裁剪阶段按「模型框 → OCR 框」依次尝试并要求二次确认通过，任一通过即采用。</p>
 *
 * <p>如果不覆盖：模型框被拒时还能用 OCR 框补救；OCR 框是装饰图时也不会把模型框顶掉
 * （此前"配图二次确认不通过"反复出现的一个成因正是破坏性覆盖 + 没有第二次机会）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@Slf4j
public final class OcrFigureSnapper {

    /** 中心点距离阈值（归一化坐标） */
    private static final double MAX_SNAP_DISTANCE = 0.35;

    private OcrFigureSnapper() {
    }

    /**
     * 为题目补充 OCR 候选框
     *
     * @param questions   题目（sourceImageIndex 指向页）
     * @param rectsByPage 页序号（1 开始）→ 该页图片区域
     * @return 被补充候选框的题数
     */
    public static int snap(List<ParsedQuestion> questions, Map<Integer, List<Map<String, Double>>> rectsByPage) {
        if (questions == null || questions.isEmpty() || rectsByPage == null || rectsByPage.isEmpty()) {
            return 0;
        }
        int snapped = 0;
        for (ParsedQuestion question : questions) {
            List<Map<String, Double>> rects = rectsByPage.get(question.getSourceImageIndex());
            if (rects == null || rects.isEmpty()) {
                continue;
            }
            Map<String, Double> current = question.getFigureRect();
            Map<String, Double> target = null;
            if (current != null) {
                // 模型给了框：只有"附近存在一个像图的 OCR 框"时才作为候选（离得太远视为另一张图，不掺和）
                target = nearest(current, rects);
            } else if (Boolean.TRUE.equals(question.getHasPrintedFigure()) && countPlausible(rects) == 1) {
                // 模型说"有印刷图但没给坐标"且该页只有一个像一个图的 OCR 元素 → 直接作为候选
                target = firstPlausible(rects);
            }
            if (target == null) {
                continue;
            }
            question.setOcrFigureRect(target);
            snapped++;
        }
        if (snapped > 0) {
            log.info("配图候选框已由 OCR bbox 补充（不覆盖模型框）: candidates={}", snapped);
        }
        return snapped;
    }

    private static int countPlausible(List<Map<String, Double>> rects) {
        int count = 0;
        for (Map<String, Double> rect : rects) {
            if (FigureRects.isPlausible(rect)) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Double> firstPlausible(List<Map<String, Double>> rects) {
        for (Map<String, Double> rect : rects) {
            if (FigureRects.isPlausible(rect)) {
                return rect;
            }
        }
        return null;
    }

    private static Map<String, Double> nearest(Map<String, Double> current, List<Map<String, Double>> rects) {
        double cx = current.getOrDefault("x", 0d) + current.getOrDefault("width", 0d) / 2;
        double cy = current.getOrDefault("y", 0d) + current.getOrDefault("height", 0d) / 2;
        Map<String, Double> best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map<String, Double> rect : rects) {
            if (!FigureRects.isPlausible(rect)) {
                continue;
            }
            double rx = rect.getOrDefault("x", 0d) + rect.getOrDefault("width", 0d) / 2;
            double ry = rect.getOrDefault("y", 0d) + rect.getOrDefault("height", 0d) / 2;
            double distance = Math.hypot(cx - rx, cy - ry);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = rect;
            }
        }
        return bestDistance <= MAX_SNAP_DISTANCE ? best : null;
    }
}
