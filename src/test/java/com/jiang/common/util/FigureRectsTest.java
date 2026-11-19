package com.jiang.common.util;

import com.jiang.dto.ParsedQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配图候选框（2026-11-17）
 *
 * <p>候选顺序 = 模型框 → OCR 框；不像图的框（整页/细条/极小/越界）直接不进候选，
 * 避免"必然被二次确认拒绝"的多余模型调用。</p>
 */
@DisplayName("FigureRects 配图候选框")
class FigureRectsTest {

    private Map<String, Double> rect(double x, double y, double w, double h) {
        Map<String, Double> rect = new LinkedHashMap<>();
        rect.put("x", x);
        rect.put("y", y);
        rect.put("width", w);
        rect.put("height", h);
        return rect;
    }

    @Test
    @DisplayName("模型框在前、OCR 框在后")
    void should_orderModelRectFirst() {
        ParsedQuestion q = ParsedQuestion.builder()
                .figureRect(rect(0.10, 0.10, 0.30, 0.30))
                .ocrFigureRect(rect(0.60, 0.60, 0.30, 0.30))
                .build();

        List<Map<String, Double>> candidates = FigureRects.candidates(q);

        assertEquals(2, candidates.size());
        assertEquals(0.10, candidates.get(0).get("x"), 1e-6);
        assertEquals(0.60, candidates.get(1).get("x"), 1e-6);
    }

    @Test
    @DisplayName("两个来源重复 → 去重为一个候选")
    void should_dedupeSameRects() {
        ParsedQuestion q = ParsedQuestion.builder()
                .figureRect(rect(0.10, 0.10, 0.30, 0.30))
                .ocrFigureRect(rect(0.10, 0.10, 0.30, 0.30))
                .build();

        assertEquals(1, FigureRects.candidates(q).size());
    }

    @Test
    @DisplayName("合理性过滤：整页/细条/极小/越界/缺字段 都不算候选")
    void should_filterImplausibleRects() {
        assertFalse(FigureRects.isPlausible(rect(0.0, 0.0, 1.0, 1.0)), "整页没有裁剪意义");
        assertFalse(FigureRects.isPlausible(rect(0.0, 0.4, 0.98, 0.02)), "细长条");
        assertFalse(FigureRects.isPlausible(rect(0.5, 0.5, 0.02, 0.02)), "极小碎片");
        assertFalse(FigureRects.isPlausible(rect(0.9, 0.9, 0.3, 0.3)), "越界");

        Map<String, Double> missingHeight = new LinkedHashMap<>();
        missingHeight.put("x", 0.1);
        missingHeight.put("y", 0.1);
        missingHeight.put("width", 0.3);
        assertFalse(FigureRects.isPlausible(missingHeight), "缺字段");
        assertFalse(FigureRects.isPlausible(null), "null");
        assertFalse(FigureRects.isPlausible(Map.of()), "空 map");
    }

    @Test
    @DisplayName("正常几何图框（如 0.12/0.35/0.30/0.22）算候选")
    void should_acceptNormalFigureRect() {
        assertTrue(FigureRects.isPlausible(rect(0.12, 0.35, 0.30, 0.22)));
        assertTrue(FigureRects.isPlausible(rect(0.55, 0.10, 0.35, 0.25)));
    }
}
