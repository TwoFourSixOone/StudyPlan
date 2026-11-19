package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 题干富文本块（2026-11-17 demo：宽表格横向滚动）
 *
 * <p>题干里可能同时有普通文字与内联表格。整段塞进一个 {@code <rich-text>} 时，
 * 想给表格加横向滚动就得让整段不换行（文字段会被挤坏），因此服务端把题干切成有序块：</p>
 * <ul>
 *   <li>{@code text} 块：普通文字（已 HTML 转义、换行已转 {@code <br/>}），直接 {@code <rich-text>}；</li>
 *   <li>{@code table} 块：一个完整表格（白名单标签 + 内联边框样式 + {@code min-width}），
 *       前端套 {@code <scroll-view scroll-x>} 横向滚动，宽表格不再挤压列宽。</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@Data
@Builder
public class ExamStemBlockVO {

    /** 块类型：text（文字）/ table（表格） */
    private String type;

    /** 该块的受控 HTML（前端 {@code <rich-text :nodes="...">} 直接渲染） */
    private String html;

    /**
     * 表格块的建议最小宽度（px）—— 由列数估算（列数 × 84，最少 280）
     *
     * <p>前端把它作为 {@code <rich-text>} 的 {@code min-width}，表格因此比屏幕宽 → 触发横向滚动；
     * 文字块该值为 0。</p>
     */
    private int minWidthPx;
}
