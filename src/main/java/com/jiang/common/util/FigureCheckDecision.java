package com.jiang.common.util;

import lombok.extern.slf4j.Slf4j;

/**
 * 裁剪配图二次确认结果判定（2026-11-12）
 *
 * <p>把裁剪块再发给视觉模型，问它是"印刷图形"还是"文字/题号/学生手写/空白"；
 * 本工具只负责把模型的一句话回答翻译成三态，便于单测与复用：</p>
 * <ul>
 *   <li>{@code true}：确认是印刷图形</li>
 *   <li>{@code false}：确认不是（文字/手写/题号/空白）→ 调用方回退整页原图</li>
 *   <li>{@code null}：无法判断（空回答/含糊）→ 调用方保留裁剪块（宁可展示裁图，也不误删）</li>
 * </ul>
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
     * @param modelAnswer 模型回答（预期 printed / invalid，允许夹带中英文）
     * @return true=印刷图形；false=不合格；null=无法判断
     */
    public static Boolean classify(String modelAnswer) {
        if (modelAnswer == null) {
            return null;
        }
        String text = modelAnswer.trim().toLowerCase();
        if (text.isEmpty()) {
            return null;
        }
        // 先判否定：模型可能说"不是 printed，是学生手写"
        if (text.contains("invalid") || text.contains("手写") || text.contains("文字")
                || text.contains("题号") || text.contains("分值") || text.contains("空白")
                || text.contains("draft") || text.contains("handwriting") || text.contains("text")) {
            return Boolean.FALSE;
        }
        if (text.contains("printed") || text.contains("印刷") || text.contains("图形合格")
                || text.contains("figure")) {
            return Boolean.TRUE;
        }
        log.debug("配图审核回答无法判定: {}", modelAnswer);
        return null;
    }
}
