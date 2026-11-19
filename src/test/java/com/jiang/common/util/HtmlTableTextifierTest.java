package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML 表格 → 纯文本（2026-11-17）
 *
 * <p>回归来源：真实卷概率统计题，题干里列联表以 {@code <table>} 标签原样显示给学生。</p>
 */
@DisplayName("HtmlTableTextifier 表格转纯文本")
class HtmlTableTextifierTest {

    /** 真实卷原文（截图的列联表） */
    private static final String REAL_TABLE = """
            为研究某疾病与超声波检查结果的关系，从做过超声波检查的人群中随机调查了1000人，得到如下的列联表：
            <table><tr><td></td><td>正常</td><td>不正常</td><td>合计</td></tr><tr><td>患该疾病</td><td>180</td><td>20</td><td>200</td></tr><tr><td>未患该疾病</td><td>780</td><td>20</td><td>800</td></tr><tr><td>合计</td><td>960</td><td>40</td><td>1000</td></tr></table>
            (1) 记超声波检查结果不正常者患有该疾病的概率为 p，求 p 的估计值；
            """;

    @Test
    @DisplayName("真实卷列联表 → 表头 + 键值行，且不含任何 HTML 标签")
    void should_convertRealContingencyTable() {
        String text = HtmlTableTextifier.textify(REAL_TABLE);

        assertFalse(text.toLowerCase().contains("<table"), text);
        assertFalse(text.toLowerCase().contains("<td"), text);
        assertFalse(text.toLowerCase().contains("<tr"), text);
        assertTrue(text.contains("【表格】"), text);
        assertTrue(text.contains("患该疾病：正常 180，不正常 20，合计 200"), text);
        assertTrue(text.contains("未患该疾病：正常 780，不正常 20，合计 800"), text);
        assertTrue(text.contains("合计：正常 960，不正常 40，合计 1000"), text);
        // 表格前后的文字要保留
        assertTrue(text.startsWith("为研究某疾病与超声波检查结果的关系"), text);
        assertTrue(text.contains("(1) 记超声波检查结果不正常者患有该疾病的概率为 p"), text);
    }

    @Test
    @DisplayName("无行标题列的表 → 表头一行 + 竖线分隔的数据行")
    void should_convertTableWithoutLabelColumn() {
        String html = "<table><tr><th>组别</th><th>人数</th></tr>"
                + "<tr><td>甲</td><td>30</td></tr><tr><td>乙</td><td>20</td></tr></table>";

        String text = HtmlTableTextifier.textify(html);

        assertTrue(text.contains("【表格】"), text);
        assertTrue(text.contains("组别 | 人数"), text);
        assertTrue(text.contains("甲 | 30"), text);
        assertTrue(text.contains("乙 | 20"), text);
    }

    @Test
    @DisplayName("单元格内 <br>、实体、行内标签 → 转换行/去标签/解实体")
    void should_normalizeInlineMarkup() {
        String text = HtmlTableTextifier.textify(
                "第一行<br>第二行 &nbsp;&amp; <span>强调</span> <b>粗体</b>");

        assertFalse(text.contains("<br"), text);
        assertFalse(text.contains("<span"), text);
        assertFalse(text.contains("<b>"), text);
        assertTrue(text.contains("第一行\n第二行"), text);
        assertTrue(text.contains("& ") || text.contains("&"), text);
        assertTrue(text.contains("强调"), text);
        assertTrue(text.contains("粗体"), text);
    }

    @Test
    @DisplayName("纯文本（无标签）原样保留；null 返回 null")
    void should_keepPlainText() {
        assertEquals("已知集合 A={1,2,3}，求补集。", HtmlTableTextifier.textify("已知集合 A={1,2,3}，求补集。"));
        assertNull(HtmlTableTextifier.textify(null));
        assertEquals("", HtmlTableTextifier.textify(""));
    }

    @Test
    @DisplayName("未闭合/无行的表格容错：不抛异常、不残留标签")
    void should_tolerateBrokenTable() {
        String broken = "表：<table><tr><td>甲</td></tr>损坏";
        String text = HtmlTableTextifier.textify(broken);
        assertFalse(text.toLowerCase().contains("<td"), text);
        assertTrue(text.contains("甲"), text);
    }

    @Test
    @DisplayName("读时兜底 textifyTablesOnly：不含表格的文本原样返回（不误伤数学符号 a<b）")
    void should_onlyTouchTextWithTable() {
        String plain = "若 a<b 且 b>c，则 a<c。";
        assertEquals(plain, HtmlTableTextifier.textifyTablesOnly(plain));
        assertNull(HtmlTableTextifier.textifyTablesOnly(null));

        String legacy = HtmlTableTextifier.textifyTablesOnly(
                "<table><tr><td></td><td>正常</td></tr><tr><td>合计</td><td>960</td></tr></table>");
        assertFalse(legacy.toLowerCase().contains("<table"), legacy);
        assertTrue(legacy.contains("合计：正常 960"), legacy);
    }

    @Test
    @DisplayName("存储清洗保留表格（rich-text 渲染用），纯文本派生仍按需可用")
    void should_keepTableForStorage_butDerivePlainTextOnDemand() {
        String stored = LlmTextSanitizer.sanitize(REAL_TABLE);
        assertTrue(stored.toLowerCase().contains("<table"), "存储层保留表格结构（demo 用 rich-text 渲染）");
        assertFalse(stored.toLowerCase().contains("<script"), stored);

        String plain = HtmlTableTextifier.textifyTablesOnly(stored);
        assertFalse(plain.toLowerCase().contains("<table"), plain);
        assertTrue(plain.contains("患该疾病：正常 180，不正常 20，合计 200"), plain);
    }
}
