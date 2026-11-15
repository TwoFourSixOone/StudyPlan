package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配图二次确认结果判定测试（2026-11-12）
 */
@DisplayName("FigureCheckDecision 配图审核判定")
class FigureCheckDecisionTest {

    @Test
    @DisplayName("printed / 印刷图形 → 通过")
    void should_acceptPrinted() {
        assertTrue(FigureCheckDecision.classify("printed"));
        assertTrue(FigureCheckDecision.classify("这是印刷的几何图形"));
    }

    @Test
    @DisplayName("invalid / 题号 / 分值 / 学生手写 / 空白 → 不通过")
    void should_rejectInvalid() {
        assertFalse(FigureCheckDecision.classify("invalid"));
        assertFalse(FigureCheckDecision.classify("这是题号和分值（17 分）"));
        assertFalse(FigureCheckDecision.classify("学生手写的草稿"));
        assertFalse(FigureCheckDecision.classify("handwriting draft"));
        assertFalse(FigureCheckDecision.classify("空白"));
    }

    @Test
    @DisplayName("否定优先：即使夹带 printed 也判不通过（模型可能说'不是 printed，是手写'）")
    void should_prioritizeNegative() {
        assertFalse(FigureCheckDecision.classify("不是 printed，是学生手写"));
    }

    @Test
    @DisplayName("空回答/含糊 → null（调用方保留裁剪块）")
    void should_returnNull_whenUnclear() {
        assertNull(FigureCheckDecision.classify(null));
        assertNull(FigureCheckDecision.classify("  "));
        assertNull(FigureCheckDecision.classify("好的"));
        assertEquals(Boolean.FALSE, FigureCheckDecision.classify("text"));
    }
}
