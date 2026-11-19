package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 配图二次确认判定（2026-11-12，2026-11-17 收紧）
 *
 * <p>回归重点：带标注文字的**图形**不能被判掉（原实现命中子串"文字"就判不合格，
 * 造成"配图二次确认不通过"反复回退整页图）。</p>
 */
@DisplayName("FigureCheckDecision 配图审核判定")
class FigureCheckDecisionTest {

    @Test
    @DisplayName("明确结论词：printed → true，invalid → false（大小写/标点无关）")
    void should_classifyExplicitAnswers() {
        assertEquals(Boolean.TRUE, FigureCheckDecision.classify("printed"));
        assertEquals(Boolean.TRUE, FigureCheckDecision.classify("  PRINTED. "));
        assertEquals(Boolean.FALSE, FigureCheckDecision.classify("invalid"));
        assertEquals(Boolean.FALSE, FigureCheckDecision.classify("Invalid。"));
    }

    @Test
    @DisplayName("判合格但答案里带\"文字/标注\"描述 → 仍判合格（2026-11-17 修复点）")
    void should_keepFigure_withAnnotationText() {
        assertEquals(Boolean.TRUE, FigureCheckDecision.classify("printed"));
        assertEquals(Boolean.TRUE, FigureCheckDecision.classify("printed（图中带坐标轴与标注文字）"));
        assertEquals(Boolean.TRUE, FigureCheckDecision.classify("是印刷图形，含题号标注"));
        assertEquals(Boolean.TRUE, FigureCheckDecision.classify("printed，周围有少量题干文字"));
    }

    @Test
    @DisplayName("判不合格：手写/草稿/空白/题号分值/纯文字")
    void should_classifyInvalidDescriptions() {
        assertEquals(Boolean.FALSE, FigureCheckDecision.classify("这是学生手写答案"));
        assertEquals(Boolean.FALSE, FigureCheckDecision.classify("草稿，不是印刷图形"));
        assertEquals(Boolean.FALSE, FigureCheckDecision.classify("空白页"));
        assertEquals(Boolean.FALSE, FigureCheckDecision.classify("题号与分值标注"));
        assertEquals(Boolean.FALSE, FigureCheckDecision.classify("以文字为主"));
    }

    @Test
    @DisplayName("同时出现两个结论词 → 以最先出现的为准")
    void should_useFirstConclusionWord() {
        assertEquals(Boolean.TRUE, FigureCheckDecision.classify("printed，不是 invalid"));
        assertEquals(Boolean.FALSE, FigureCheckDecision.classify("invalid，不是 printed"));
    }

    @Test
    @DisplayName("空/含糊 → null（调用方保留裁剪块）")
    void should_returnNull_whenAmbiguous() {
        assertNull(FigureCheckDecision.classify(null));
        assertNull(FigureCheckDecision.classify("   "));
        assertNull(FigureCheckDecision.classify("看起来像是个图吧"));
    }
}
