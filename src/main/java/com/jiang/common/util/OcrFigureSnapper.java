package com.jiang.common.util;

import com.jiang.dto.ParsedQuestion;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 用 GLM-OCR 的图片 bbox 校正题目配图区域（2026-11-17）
 *
 * <p>视觉模型给的 {@code figureRect} 经常偏移；GLM-OCR 的 {@code layout_details} 里有精确的
 * {@code label=image} 区域。这里按中心点距离就近吸附（≤0.35），并支持"模型说有图但没给坐标"时
 * 页面只有一个图元素就直接用。</p>
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
     * 就地校正题目配图区域
     *
     * @param questions   题目（sourceImageIndex 指向页）
     * @param rectsByPage 页序号（1 开始）→ 该页图片区域
     * @return 被校正的题数
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
                target = nearest(current, rects);
            } else if (Boolean.TRUE.equals(question.getHasPrintedFigure()) && rects.size() == 1) {
                target = rects.get(0);
            }
            if (target == null || target == current) {
                continue;
            }
            question.setFigureRect(target);
            snapped++;
        }
        if (snapped > 0) {
            log.info("配图区域已按 OCR bbox 吸附: snapped={}", snapped);
        }
        return snapped;
    }

    private static Map<String, Double> nearest(Map<String, Double> current, List<Map<String, Double>> rects) {
        double cx = current.getOrDefault("x", 0d) + current.getOrDefault("width", 0d) / 2;
        double cy = current.getOrDefault("y", 0d) + current.getOrDefault("height", 0d) / 2;
        Map<String, Double> best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map<String, Double> rect : rects) {
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
