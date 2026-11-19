package com.jiang.common.util;

import com.jiang.vo.ExamStemBlockVO;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题干/解析 → 小程序 {@code <rich-text>} 可用的受控 HTML（2026-11-17，demo：表格真实渲染 + 宽表格横向滚动）
 *
 * <p><b>背景</b>：试卷题干里的列联表/统计表由模型以 HTML 表格内联（OCR 的表格本身就是 HTML），
 * {@code <text>} 是纯文本渲染会显示成一堆标签；demo 阶段要的是"表格长得像表格"。</p>
 *
 * <p><b>分块输出</b>：{@link #toBlocks(String)} 把题干切成 {@code text} / {@code table} 块：
 * 表格块带 {@code min-width}（按列数估算），前端套 {@code <scroll-view scroll-x>} 就能横向滚动，
 * 而文字块仍正常折行（整段不切块时只能"给整段加 nowrap"，会把文字挤坏）。</p>
 *
 * <p><b>安全与稳定性</b>：</p>
 * <ul>
 *   <li>只保留白名单标签（表格/加粗/斜体/上下标/换行/列表/段落），其余标签丢弃（内容保留为转义文本）；</li>
 *   <li>{@code <script>/<style>} 整块删除；任何标签属性都不透传（杜绝 onclick / class / style 注入）；</li>
 *   <li>文本段落做 HTML 转义（{@code < > &}），并把换行转成 {@code <br/>}；
 *       已存在的实体（{@code &nbsp;}）不重复转义；</li>
 *   <li>只把**已知标签名**当标签：{@code x<y 且 y>3} 这类数学写法按文本转义，不会被吞掉；</li>
 *   <li>给 {@code <table>/<td>/<th>} 注入**内联样式**（rich-text 不吃外部 class），并给表格
 *       {@code min-width}（列数 × 84px，≥280px），保证宽表格可横向滚动。</li>
 * </ul>
 *
 * <p><b>与纯文本的关系</b>：判分/归因/学习记录仍用纯文本（{@link HtmlTableTextifier} 派生），
 * 富文本只用于展示，避免"给模型喂标签"。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
public final class HtmlRichText {

    /** 白名单标签（微信 rich-text 支持表格，见官方 nodes 支持列表） */
    private static final Set<String> ALLOWED = new LinkedHashSet<>(List.of(
            "table", "thead", "tbody", "tfoot", "tr", "td", "th", "col", "colgroup",
            "p", "div", "span", "br", "b", "strong", "i", "em", "u", "s", "sub", "sup",
            "ul", "ol", "li", "hr", "code", "blockquote"));

    /**
     * 已知 HTML 标签名（白名单 + 需要丢弃的常见标签）
     *
     * <p>只对"已知标签名"做标签处理：{@code x<y 且 y>3} 这类数学写法里的 {@code <y …>} 不能被当成标签，
     * 否则会把不等式算式的中间部分整段吃掉（2026-11-17 单测发现）。</p>
     */
    private static final Set<String> KNOWN_TAGS = new LinkedHashSet<>(List.of(
            "table", "thead", "tbody", "tfoot", "tr", "td", "th", "col", "colgroup",
            "p", "div", "span", "br", "b", "strong", "i", "em", "u", "s", "sub", "sup",
            "ul", "ol", "li", "hr", "code", "blockquote",
            "a", "img", "font", "center", "section", "article", "nav", "header", "footer",
            "form", "input", "button", "textarea", "select", "option",
            "video", "audio", "canvas", "svg", "math", "pre", "dl", "dt", "dd", "figure", "figcaption"));

    /** 需要整块删除的标签（含内容） */
    private static final Pattern DANGEROUS_BLOCK = Pattern.compile(
            "<(script|style|iframe|object|embed)[^>]*>.*?</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 完整表格块（用于把题干切成"文字 / 表格"块） */
    private static final Pattern TABLE_BLOCK = Pattern.compile("<table[^>]*>.*?</table>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern CELL = Pattern.compile("<t[dh](?:\\s[^>]*)?>(.*?)</t[dh]>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 任意标签：<name ...> / </name> / <name/> */
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9]*)([^>]*)>");

    /** 已存在实体（不重复转义 & 号） */
    private static final Pattern ENTITY = Pattern.compile("&#?[A-Za-z0-9]{1,8};");

    private static final String TABLE_STYLE_HEAD = "border-collapse:collapse;margin:8px 0;font-size:inherit;";
    private static final String CELL_STYLE = "border:1px solid #dcdfe6;padding:4px 8px;vertical-align:middle;";

    /** 每列估算宽度（px）与表格最小宽度下限 */
    private static final int COLUMN_WIDTH_PX = 84;
    private static final int MIN_TABLE_WIDTH_PX = 280;

    private HtmlRichText() {
    }

    /**
     * 题干 → 有序富文本块（text / table），供前端分别渲染（表格块可横向滚动）
     *
     * @param raw 题干原文（可为 null）
     * @return 块列表（入参为空返回空列表）
     */
    public static List<ExamStemBlockVO> toBlocks(String raw) {
        List<ExamStemBlockVO> blocks = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return blocks;
        }
        String text = DANGEROUS_BLOCK.matcher(raw.replace("\r\n", "\n")).replaceAll("");
        Matcher matcher = TABLE_BLOCK.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            addTextBlock(blocks, text.substring(cursor, matcher.start()));
            addTableBlock(blocks, matcher.group());
            cursor = matcher.end();
        }
        addTextBlock(blocks, text.substring(cursor));
        return blocks;
    }

    private static void addTextBlock(List<ExamStemBlockVO> blocks, String segment) {
        String html = renderInline(segment);
        if (html.isBlank()) {
            return;
        }
        blocks.add(ExamStemBlockVO.builder().type("text").html(html).minWidthPx(0).build());
    }

    private static void addTableBlock(List<ExamStemBlockVO> blocks, String tableHtml) {
        List<List<String>> rows = parseRows(tableHtml);
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        int minWidth = Math.max(MIN_TABLE_WIDTH_PX, columns * COLUMN_WIDTH_PX);
        blocks.add(ExamStemBlockVO.builder()
                .type("table")
                .html(renderTable(rows, minWidth))
                .minWidthPx(minWidth)
                .build());
    }

    /** 表格 → 受控 HTML（含 min-width，前端据此横向滚动） */
    private static String renderTable(List<List<String>> rows, int minWidth) {
        StringBuilder out = new StringBuilder(256);
        out.append("<table style=\"").append(TABLE_STYLE_HEAD)
                .append("min-width:").append(minWidth).append("px;\">");
        for (List<String> row : rows) {
            out.append("<tr>");
            for (String cell : row) {
                out.append("<td style=\"").append(CELL_STYLE).append("\">");
                out.append(cell);
                out.append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</table>");
        return out.toString();
    }

    /** 行/单元格解析（单元格内容做行内归一：白名单标签保留、文本转义） */
    private static List<List<String>> parseRows(String tableHtml) {
        List<List<String>> rows = new ArrayList<>();
        Matcher rowMatcher = ROW.matcher(tableHtml == null ? "" : tableHtml);
        while (rowMatcher.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = CELL.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                cells.add(renderInline(cellMatcher.group(1)));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    /**
     * 文本 → 受控 HTML（整段；表格也会被渲染，但**不含**分块与 min-width 信息）
     *
     * <p>等价于把 {@link #toBlocks(String)} 的 html 依次拼接，供需要单字段 HTML 的调用方使用。</p>
     *
     * @param raw 题干/解析原文（可为 null）
     * @return HTML；入参为空返回 null
     */
    public static String toRichText(String raw) {
        List<ExamStemBlockVO> blocks = toBlocks(raw);
        if (blocks.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (ExamStemBlockVO block : blocks) {
            out.append(block.getHtml());
        }
        return out.toString().trim();
    }

    /**
     * 行内文本归一：白名单标签保留、未知"标签"按文本处理、文本转义、换行转 {@code <br/>}
     */
    static String renderInline(String segment) {
        if (segment == null || segment.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(segment.length() + 32);
        Matcher matcher = TAG.matcher(segment);
        int cursor = 0;
        while (matcher.find()) {
            appendEscapedText(out, segment.substring(cursor, matcher.start()));
            String closing = matcher.group(1);
            String name = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
            if (!KNOWN_TAGS.contains(name)) {
                // 不是已知标签（很可能是数学里的 "x<y …>"）→ 按文本转义，别吞掉内容
                appendEscapedText(out, matcher.group());
            } else if (ALLOWED.contains(name)) {
                if (!closing.isEmpty()) {
                    if (!"br".equals(name) && !"hr".equals(name)) {
                        out.append("</").append(name).append('>');
                    }
                } else if ("br".equals(name) || "hr".equals(name)) {
                    out.append('<').append(name).append("/>");
                } else {
                    out.append('<').append(name).append('>');
                }
            }
            cursor = matcher.end();
        }
        appendEscapedText(out, segment.substring(cursor));
        return out.toString().trim();
    }

    /** 纯文本片段：转义 + 换行转 {@code <br/>} */
    private static void appendEscapedText(StringBuilder out, String segment) {
        if (segment.isEmpty()) {
            return;
        }
        StringBuilder escaped = new StringBuilder(segment.length() + 16);
        Matcher entity = ENTITY.matcher(segment);
        int cursor = 0;
        while (entity.find()) {
            escaped.append(escapePlain(segment.substring(cursor, entity.start())));
            escaped.append(entity.group());
            cursor = entity.end();
        }
        escaped.append(escapePlain(segment.substring(cursor)));
        out.append(escaped.toString().replace("\n", "<br/>"));
    }

    private static String escapePlain(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * 存储前清洗：删掉危险标签块、丢弃白名单外的标签，其余文本（含换行、含表格）原样保留
     *
     * <p>与 {@link #toRichText} 的区别：这里**不做转义与换行转换**（那是渲染期的事），
     * 只保证入库文本不含脚本/未知标签，避免把脏 HTML 存进 {@code question_content}。</p>
     *
     * @param raw 解析输出文本
     * @return 清洗后的存储文本
     */
    public static String sanitizeForStorage(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String text = DANGEROUS_BLOCK.matcher(raw.replace("\r\n", "\n")).replaceAll("");
        StringBuilder out = new StringBuilder(text.length());
        Matcher matcher = TAG.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            out.append(text, cursor, matcher.start());
            String closing = matcher.group(1);
            String name = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
            if (!KNOWN_TAGS.contains(name)) {
                // 未知"标签"（可能是数学表达式）→ 原样保留
                out.append(matcher.group());
            } else if (ALLOWED.contains(name)) {
                if (closing.isEmpty()) {
                    out.append('<').append(name).append('>');
                } else if (!"br".equals(name) && !"hr".equals(name)) {
                    out.append("</").append(name).append('>');
                }
            }
            cursor = matcher.end();
        }
        out.append(text.substring(cursor));
        return out.toString();
    }
}
