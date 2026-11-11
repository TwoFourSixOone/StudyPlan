package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯文本清洗 v4 记号规则测试（design D25 / tasks E3）
 *
 * <p>重点是"允许 x^2 / a_n，只转换花括号形式"——v4 之前 {code ^{2}} 会原样留下，
 * 且不能把合法的 {@code ^} 一并删掉。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("LlmTextSanitizer v4 数学记号")
class LlmTextSanitizerMathNotationTest {

    @Test
    @DisplayName("花括号幂/下标 → 圆括号：x^{2} → x^(2)，a_{n} → a_(n)")
    void should_convertBraceSuperscriptAndSubscript() {
        assertEquals("x^(2)", LlmTextSanitizer.sanitize("x^{2}"));
        assertEquals("a_(n)", LlmTextSanitizer.sanitize("a_{n}"));
        assertEquals("x^(n+1) + a_(n-1)", LlmTextSanitizer.sanitize("x^{n+1} + a_{n-1}"));
    }

    @Test
    @DisplayName("纯文本 x^2 / a_n 不被破坏")
    void should_keepPlainTextPowerAndSubscript() {
        assertEquals("x^2 + a_n", LlmTextSanitizer.sanitize("x^2 + a_n"));
        assertEquals("2^10 = 1024", LlmTextSanitizer.sanitize("2^10 = 1024"));
    }

    @Test
    @DisplayName("反斜杠命令仍被转为可读形式（\\sqrt / \\frac / \\vec）")
    void should_mapLatexCommands() {
        assertEquals("√(3)", LlmTextSanitizer.sanitize("\\sqrt{3}"));
        assertEquals("(1)/(2)", LlmTextSanitizer.sanitize("\\frac{1}{2}"));
        assertTrue(LlmTextSanitizer.sanitize("\\vec{a}").contains("向量"));
    }

    @Test
    @DisplayName("清洗后不残留花括号与反斜杠命令")
    void should_removeBracesAndCommands() {
        String text = LlmTextSanitizer.sanitize("\\frac{x^{2}}{a_{n}} \\cdot 3");
        assertFalse(text.contains("{"), text);
        assertFalse(text.contains("\\"), text);
        assertTrue(text.contains("x^(2)"), text);
        assertTrue(text.contains("a_(n)"), text);
    }
}
