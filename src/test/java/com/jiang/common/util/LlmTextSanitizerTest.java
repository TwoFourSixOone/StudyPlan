package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmTextSanitizer 单元测试（隐性约定 #24）
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@DisplayName("LLM 输出纯文本清洗")
class LlmTextSanitizerTest {

    @Test
    @DisplayName("LaTeX 向量与点积转中文/符号")
    void should_convertLatexVectorAndDot() {
        String raw = "$设两向量为 \\vec{a}、\\vec{b}$，$\\vec{a}\\cdot\\vec{b}=0$";
        String text = LlmTextSanitizer.sanitize(raw);

        assertTrue(text.contains("向量a"), text);
        assertTrue(text.contains("向量b"), text);
        assertTrue(text.contains("·"), text);
        assertFalse(text.contains("\\\\vec"), text);
        assertFalse(text.contains("$"), text);
    }

    @Test
    @DisplayName("Markdown 加粗与标题标记被去掉")
    void should_stripMarkdown() {
        String text = LlmTextSanitizer.sanitize("**解析：**\n# 结论\n设 `a` 为零向量");

        assertFalse(text.contains("**"), text);
        assertFalse(text.contains("#"), text);
        assertFalse(text.contains("`"), text);
        assertTrue(text.contains("解析："), text);
    }

    @Test
    @DisplayName("常见符号命令转成字符")
    void should_mapSymbolCommands() {
        assertEquals("≠", LlmTextSanitizer.sanitize("\\neq"));
        assertEquals("≤", LlmTextSanitizer.sanitize("\\leq"));
        assertEquals("θ", LlmTextSanitizer.sanitize("\\theta"));
        assertEquals("(1)/(2)", LlmTextSanitizer.sanitize("\\frac{1}{2}"));
        assertEquals("√(x)", LlmTextSanitizer.sanitize("\\sqrt{x}"));
    }

    @Test
    @DisplayName("误转义产生的控制字符被清除")
    void should_removeControlChars() {
        String text = LlmTextSanitizer.sanitize("向量\u000Ba");
        assertEquals("向量a", text);
    }

    @Test
    @DisplayName("null 与空串原样返回")
    void should_returnAsIs_when_blank() {
        assertNull(LlmTextSanitizer.sanitize(null));
        assertEquals("", LlmTextSanitizer.sanitize(""));
    }
}
