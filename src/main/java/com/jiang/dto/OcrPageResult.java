package com.jiang.dto;

import java.util.List;
import java.util.Map;

/**
 * GLM-OCR 单页解析结果（2026-11-17）
 *
 * @param markdown    该页 OCR 文本（Markdown；表格为 HTML、公式为文本）
 * @param imageRects  该页图片/图形的归一化区域（{x,y,width,height}，来自 layout_details 的 bbox_2d）
 * @author StudyMate Team
 * @since 2026-11-17
 */
public record OcrPageResult(String markdown, List<Map<String, Double>> imageRects) {

    public boolean isEmpty() {
        return (markdown == null || markdown.isBlank()) && (imageRects == null || imageRects.isEmpty());
    }
}
