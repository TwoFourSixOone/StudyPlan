package com.jiang.common.util;

import com.jiang.dto.ParsedQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OCR bbox → 配图候选框（2026-11-17）
 *
 * <p>关键语义：OCR 框**不覆盖**模型框，只作为第二候选（模型框被二次确认拒绝时可以补救）。</p>
 */
@DisplayName("OcrFigureSnapper 配图候选框")
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
    @DisplayName("模型框保留不动，OCR 框写入候选（不覆盖模型框）")
    void should_keepModelRect_andStoreOcrCandidate() {
        Map<String, Double> modelRect = rect(0.55, 0.45, 0.30, 0.25);
        ParsedQuestion q = question(1, modelRect, true);
        Map<String, Double> ocr = rect(0.50, 0.40, 0.40, 0.30);

        int snapped = OcrFigureSnapper.snap(List.of(q), Map.of(1, List.of(ocr)));

        assertEquals(1, snapped);
        assertEquals(0.55, q.getFigureRect().get("x"), 1e-6, "模型框不能被 OCR 覆盖");
        assertEquals(0.30, q.getFigureRect().get("width"), 1e-6);
        assertNotNull(q.getOcrFigureRect(), "OCR 框应作为候选保留");
        assertEquals(0.40, q.getOcrFigureRect().get("width"), 1e-6);
    }

    @Test
    @DisplayName("距离过远（>0.35）→ 不提供候选")
    void should_skipCandidate_whenTooFar() {
        ParsedQuestion q = question(1, rect(0.02, 0.02, 0.10, 0.10), true);
        Map<String, Double> ocr = rect(0.80, 0.80, 0.15, 0.15);

        assertEquals(0, OcrFigureSnapper.snap(List.of(q), Map.of(1, List.of(ocr))));
        assertNull(q.getOcrFigureRect());
        assertEquals(0.02, q.getFigureRect().get("x"), 1e-6);
    }

    @Test
    @DisplayName("模型说有印刷图但没给坐标 + 该页只有一个像图的元素 → 作为候选")
    void should_provideCandidate_whenModelRectMissing() {
        ParsedQuestion q = question(2, null, true);
        Map<String, Double> ocr = rect(0.1, 0.2, 0.3, 0.4);

        assertEquals(1, OcrFigureSnapper.snap(List.of(q), Map.of(2, List.of(ocr))));
        assertEquals(0.3, q.getOcrFigureRect().get("width"), 1e-6);
        assertNull(q.getFigureRect(), "候选不写回模型框字段");
    }

    @Test
    @DisplayName("不像图的 OCR 元素（整页/细条/极小）不提供候选")
    void should_skipImplausibleRects() {
        ParsedQuestion q = question(3, null, true);
        Map<Integer, List<Map<String, Double>>> page = Map.of(3, List.of(
                rect(0.0, 0.0, 1.0, 1.0),      // 整页
                rect(0.0, 0.30, 0.98, 0.02),   // 细长条
                rect(0.50, 0.50, 0.03, 0.03))); // 极小

        assertEquals(0, OcrFigureSnapper.snap(List.of(q), page));
        assertNull(q.getOcrFigureRect());
    }

    @Test
    @DisplayName("没有 OCR 图元素 / 入参为空 → 不处理")
    void should_doNothing_whenNoRects() {
        ParsedQuestion q = question(1, rect(0.1, 0.1, 0.2, 0.2), true);
        assertEquals(0, OcrFigureSnapper.snap(List.of(q), Map.of()));
        assertEquals(0, OcrFigureSnapper.snap(null, Map.of()));
    }
}
