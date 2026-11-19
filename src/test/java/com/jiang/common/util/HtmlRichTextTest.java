package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题干 → 受控 HTML（rich-text 渲染，2026-11-17 demo）
 */
@DisplayName("HtmlRichText 题干富文本")
class HtmlRichTextTest {

    private static final String TABLE = "<table><tr><td></td><td>正常</td><td>不正常</td><td>合计</td></tr>"
            + "<tr><td>患该疾病</td><td>180</td><td>20</td><td>200</td></tr></table>";

    @Test
    @DisplayName("表格保留结构，并注入内联边框样式（rich-text 不吃外部 class）")
    void should_keepTableWithInlineStyle() {
        String html = HtmlRichText.toRichText("列联表：" + TABLE);

        assertTrue(html.contains("<table style=\"border-collapse:collapse"), html);
        assertTrue(html.contains("<td style=\"border:1px solid #dcdfe6"), html);
        assertTrue(html.contains("患该疾病"), html);
        assertTrue(html.contains("180"), html);
        assertTrue(html.contains("列联表："), html);
    }

    @Test
    @DisplayName("纯文本：转义 < > &，换行转 <br/>，已存在实体不重复转义")
    void should_escapePlainText() {
        String html = HtmlRichText.toRichText("若 x<y 且 y>3，则 x<3\n第二行 &nbsp; 结束");

        assertTrue(html.contains("x&lt;y"), html);
        assertTrue(html.contains("y&gt;3"), html);
        assertTrue(html.contains("<br/>"), html);
        assertTrue(html.contains("&nbsp;"), html);
        assertFalse(html.contains("x<y"), html);
    }

    @Test
    @DisplayName("安全：script/style 整块删除；白名单外标签丢弃且不透传任何属性")
    void should_stripUnsafeMarkup() {
        String html = HtmlRichText.toRichText(
                "<script>alert(1)</script><span onclick=\"evil()\" class=\"x\">文字</span><font color=red>红</font>");

        assertFalse(html.toLowerCase().contains("script"), html);
        assertFalse(html.contains("onclick"), html);
        assertFalse(html.contains("class="), html);
        assertFalse(html.contains("<font"), html);
        assertTrue(html.contains("文字"), html);
        assertTrue(html.contains("红"), html);
    }

    @Test
    @DisplayName("分块：文字块正常折行、表格块单独成块并带 min-width（宽表格横向滚动依据）")
    void should_splitIntoTextAndTableBlocks() {
        String multiColumn = "<table><tr><td>项目</td><td>正常</td><td>不正常</td><td>合计</td><td>占比</td></tr>"
                + "<tr><td>患该疾病</td><td>180</td><td>20</td><td>200</td><td>20%</td></tr></table>";
        java.util.List<com.jiang.vo.ExamStemBlockVO> blocks = HtmlRichText.toBlocks(
                "前言文字\n第二行" + multiColumn + "后记文字");

        assertEquals(3, blocks.size(), blocks.toString());
        assertEquals("text", blocks.get(0).getType());
        assertTrue(blocks.get(0).getHtml().contains("<br/>"), blocks.get(0).getHtml());
        assertEquals(0, blocks.get(0).getMinWidthPx());

        assertEquals("table", blocks.get(1).getType());
        assertTrue(blocks.get(1).getHtml().contains("min-width:"), blocks.get(1).getHtml());
        assertEquals(5 * 84, blocks.get(1).getMinWidthPx(), "5 列 → 420px 最小宽度");
        assertEquals(10, countCells(blocks.get(1).getHtml(), "<td"),
                "2 行 × 5 列 = 10 个单元格: " + blocks.get(1).getHtml());

        assertEquals("text", blocks.get(2).getType());
        assertTrue(blocks.get(2).getHtml().contains("后记文字"), blocks.get(2).getHtml());
    }

    @Test
    @DisplayName("分块：无表格时只有 text 块；列数少时 min-width 取下限 280")
    void should_useMinWidthFloor_forNarrowTable() {
        java.util.List<com.jiang.vo.ExamStemBlockVO> blocks = HtmlRichText.toBlocks(
                "表：<table><tr><td>甲</td><td>30</td></tr></table>");

        assertEquals(2, blocks.size(), blocks.toString());
        assertEquals("table", blocks.get(1).getType());
        assertEquals(280, blocks.get(1).getMinWidthPx());

        java.util.List<com.jiang.vo.ExamStemBlockVO> plain = HtmlRichText.toBlocks("只有文字，没有表格");
        assertEquals(1, plain.size());
        assertEquals("text", plain.get(0).getType());
        assertTrue(HtmlRichText.toBlocks(null).isEmpty());
    }

    private static int countCells(String html, String token) {
        int count = 0;
        int index = html.indexOf(token);
        while (index >= 0) {
            count++;
            index = html.indexOf(token, index + token.length());
        }
        return count;
    }

    @Test
    @DisplayName("空/空白入参 → null")
    void should_returnNull_whenBlank() {
        assertNull(HtmlRichText.toRichText(null));
        assertNull(HtmlRichText.toRichText("   "));
    }

    @Test
    @DisplayName("存储清洗：保留表格与换行，删掉 script 与未知标签；不做转义（渲染期才转义）")
    void should_sanitizeForStorage() {
        String raw = "题干<br>第二行\n" + TABLE + "<script>bad()</script><section>段</section>";
        String stored = HtmlRichText.sanitizeForStorage(raw);

        assertTrue(stored.contains("<table>"), stored);
        assertTrue(stored.contains("<br>"), stored);
        assertTrue(stored.contains("\n"), stored);
        assertFalse(stored.toLowerCase().contains("script"), stored);
        assertFalse(stored.contains("<section"), stored);
        assertTrue(stored.contains("段"), stored);
    }
}
