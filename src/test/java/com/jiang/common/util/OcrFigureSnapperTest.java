package com.jiang.common.util;

import com.jiang.dto.ParsedQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OCR bbox 吸附测试（2026-11-17）
 */
@DisplayName("OcrFigureSnapper 配图区域吸附")
class OcrFigureSnapperTest {

    private Map<String, Double> rect(double x, double y, double w, double h) {
        Map<String, Double> rect = new LinkedHashMap<>();
        rect.put("x", x);
        rect.put("y", y);
        rect.put("width", w);
        rect.put("height", h);
        return rect;
    }

    private ParsedQuestion question(int page, Map<String, Double> figureRect, Boolean hasPrintedFigure) {
        return ParsedQuestion.builder()
                .sourceImageIndex(page)
                .stem("题干")
                .formatType("short")
                .figureRect(figureRect)
                .hasPrintedFigure(hasPrintedFigure)
                .build();
    }

    @Test
    @DisplayName("模型坐标偏移 → 吸附到最近的 OCR 图片 bbox")
    void should_snapToNearestRect() {
        ParsedQuestion q = question(1, rect(0.55, 0.45, 0.30, 0.25), true);
        Map<String, Double> ocr = rect(0.50, 0.40, 0.40, 0.30);
        int snapped = OcrFigureSnapper.snap(List.of(q), Map.of(1, List.of(ocr)));
        assertEquals(1, snapped);
        assertEquals(0.5, q.getFigureRect().get("x"), 1e-6);
        assertEquals(0.4, q.getFigureRect().get("width"), 1e-6);
    }

    @Test
    @DisplayName("距离过远（>0.35）→ 不吸附")
    void should_keepRect_whenTooFar() {
        ParsedQuestion q = question(1, rect(0.02, 0.02, 0.10, 0.10), true);
        Map<String, Double> ocr = rect(0.80, 0.80, 0.15, 0.15);
        int snapped = OcrFigureSnapper.snap(List.of(q), Map.of(1, List.of(ocr)));
        assertEquals(0, snapped);
        assertEquals(0.02, q.getFigureRect().get("x"), 1e-6);
    }

    @Test
    @DisplayName("模型说有印刷图但没给坐标 + 该页只有一个图元素 → 直接采用")
    void should_assignWhenMissingRect() {
        ParsedQuestion q = question(2, null, true);
        Map<String, Double> ocr = rect(0.1, 0.2, 0.3, 0.4);
        int snapped = OcrFigureSnapper.snap(List.of(q), Map.of(2, List.of(ocr)));
        assertEquals(1, snapped);
        assertEquals(0.3, q.getFigureRect().get("width"), 1e-6);
    }

    @Test
    @DisplayName("没有 OCR 图元素 / 入参为空 → 不处理")
    void should_doNothing_whenNoRects() {
        ParsedQuestion q = question(1, rect(0.1, 0.1, 0.2, 0.2), true);
        assertEquals(0, OcrFigureSnapper.snap(List.of(q), Map.of()));
        assertEquals(0, OcrFigureSnapper.snap(null, Map.of()));
    }
}
