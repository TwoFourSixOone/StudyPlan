package com.jiang.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 表格 → 手机端可读纯文本（2026-11-17）
 *
 * <p><b>问题</b>：解析 Prompt 允许"表格内联进题干（HTML 或 markdown 均可）"，而 OCR 文本里表格本身
 * 就是 HTML，于是模型把 {@code <table><tr><td>…} 原样写进 {@code stem}；详情页用 {@code <text>}
 * 纯文本渲染 → 学生看到一堆标签（真实卷概率统计题：整张列联表以 {@code <table>} 标签显示）。</p>
 *
 * <p><b>为什么在服务端转、而不是前端上 rich-text</b>：题面按"零 DDL 段落纯文本"承载（约定 #24），
 * 判分/归因/学习记录都吃这份文本；上富文本需要改所有渲染点且模型 HTML 不可控。
 * 服务端转换成"表头 + 每行 键：值、值"的手机友好纯文本，一次修好详情页/错题本/作业回看/判分输入。</p>
 *
 * <p><b>输出形态</b>（列联表例）：</p>
 * <pre>
 * 【表格】
 * 患该疾病：正常 180，不正常 20，合计 200
 * 未患该疾病：正常 780，不正常 20，合计 800
 * 合计：正常 960，不正常 40，合计 1000
 * </pre>
 * <p>没有"行标题列"的表退化为 {@code 表头 | 列 | 列} + 每行 {@code 值 | 值 | 值}。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
public final class HtmlTableTextifier {

    private static final Pattern TABLE = Pattern.compile("<table[^>]*>(.*?)</table>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[dh](?:\\s[^>]*)?>(.*?)</t[dh]>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 行内排版标签（去掉标签保留内容；<br> 转换行） */
    private static final Pattern BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_TAG = Pattern.compile(
            "</?(?:p|div|span|b|i|u|strong|em|sup|sub|font|hr|li|ul|ol|thead|tbody|tfoot|caption|"
                    + "table|tr|td|th|br)(?:\\s[^>]*)?/?>",
            Pattern.CASE_INSENSITIVE);

    /** 表格内未识别的残留标签（兜底清除，防止把 HTML 漏给学生） */
    private static final Pattern ANY_TAG = Pattern.compile("<[^<>]{1,80}>");

    private HtmlTableTextifier() {
    }

    /**
     * 把文本里的 HTML 表格与常见排版标签转成纯文本
     *
     * @param raw 原始文本（可为 null）
     * @return 纯文本；不含表格时只做标签/实体归一
     */
    public static String textify(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String result = raw;
        if (result.toLowerCase().contains("<table")) {
            Matcher matcher = TABLE.matcher(result);
            StringBuilder builder = new StringBuilder();
            int last = 0;
            while (matcher.find()) {
                builder.append(result, last, matcher.start());
                builder.append(renderTable(matcher.group(1)));
                last = matcher.end();
            }
            builder.append(result.substring(last));
            result = builder.toString();
        }
        return inlineNormalize(result, true);
    }

    /**
     * 只做"表格 → 纯文本"的读时兜底（存量数据兼容）
     *
     * <p>不含 {@code <table} 的文本**原样返回**：读时不做任何清洗，避免误伤数学符号（如 {@code a<b}）。
     * 用于详情 VO / 判分入参这类"存量数据也要能看"的位置。</p>
     *
     * @param raw 已落库的题干/解析文本
     * @return 含表格时转成纯文本；否则原样
     */
    public static String textifyTablesOnly(String raw) {
        if (raw == null || raw.isBlank() || !raw.toLowerCase().contains("<table")) {
            return raw;
        }
        return textify(raw);
    }

    /**
     * 表格块 → 纯文本（表头 + 键值行 / 竖线行）
     */
    static String renderTable(String tableInner) {
        List<List<String>> rows = new ArrayList<>();
        Matcher rowMatcher = ROW.matcher(tableInner == null ? "" : tableInner);
        while (rowMatcher.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = CELL.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                String cell = inlineNormalize(cellMatcher.group(1), false).replace("\n", " ").trim();
                if (!cell.isEmpty()) {
                    cells.add(cell);
                }
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return "\n";
        }
        StringBuilder out = new StringBuilder("\n【表格】\n");
        List<String> header = rows.size() > 1 ? rows.get(0) : List.of();
        List<List<String>> data = rows.size() > 1 ? rows.subList(1, rows.size()) : rows;

        boolean hasLabelColumn = !header.isEmpty() && data.stream()
                .allMatch(row -> row.size() == header.size() + 1);
        if (hasLabelColumn) {
            for (List<String> row : data) {
                out.append(row.get(0)).append('：');
                for (int index = 1; index < row.size(); index++) {
                    if (index > 1) {
                        out.append('，');
                    }
                    out.append(header.get(index - 1)).append(' ').append(row.get(index));
                }
                out.append('\n');
            }
            return out.toString();
        }

        if (!header.isEmpty() && data.stream().allMatch(row -> row.size() == header.size())) {
            out.append(String.join(" | ", header)).append('\n');
        }
        for (List<String> row : data) {
            out.append(String.join(" | ", row)).append('\n');
        }
        return out.toString();
    }

    /**
     * 行内归一：换行标签转 \n、去排版标签、解实体、收敛空白
     *
     * @param text          文本
     * @param stripAnyTag   是否连未识别的残留标签一起清除（表格单元格内部总是清）
     */
    static String inlineNormalize(String text, boolean stripAnyTag) {
        if (text == null) {
            return "";
        }
        String result = BR.matcher(text).replaceAll("\n");
        result = INLINE_TAG.matcher(result).replaceAll("");
        if (stripAnyTag) {
            result = ANY_TAG.matcher(result).replaceAll("");
        }
        result = result.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        if (stripAnyTag) {
            result = result.replaceAll("[ \\t]{2,}", " ")
                    .replaceAll("\\n[ \\t]+", "\n")
                    .replaceAll("\\n{3,}", "\n\n");
        }
        return result;
    }
}
