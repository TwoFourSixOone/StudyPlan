package com.jiang.common.util;

import java.util.regex.Pattern;

/**
 * LLM 输出纯文本清洗（隐性约定 #24）
 *
 * <p>小程序页面以纯文本渲染解析，若模型输出 LaTeX / Markdown 会出现
 * {@code $ec{a}$}（{@code \v} 被当转义）、{@code **解析：**} 之类的乱码。
 * 本工具做兜底转换：</p>
 * <ul>
 *   <li>去掉 {@code $} / {@code $$} 公式定界符、Markdown 加粗/标题/代码标记</li>
 *   <li>把常见 LaTeX 命令转成可读中文或符号（{@code \vec{a}} → 向量a、{@code \cdot} → ·）</li>
 *   <li>清除控制字符（含被误转义产生的垂直制表符）</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
public final class LlmTextSanitizer {

    private static final Pattern VEC = Pattern.compile("\\\\vec\\{([^}]*)}");
    private static final Pattern FRAC = Pattern.compile("\\\\frac\\{([^}]*)\\}\\{([^}]*)}");
    private static final Pattern SQRT = Pattern.compile("\\\\sqrt\\{([^}]*)}");
    private static final Pattern LEFT_RIGHT = Pattern.compile("\\\\(left|right)");
    private static final Pattern TEXT_CMD = Pattern.compile("\\\\(text|mathrm|mathbf|mathit)\\{([^}]*)}");
    /**
     * 花括号上下标（v4/D25）：{@code x^{2}} → {@code x^(2)}、{@code a_{n}} → {@code a_(n)}
     *
     * <p>只转换花括号形式，**不移除 {@code ^} 本身**——纯文本 {@code x^2} / {@code a_n} 是允许的写法。</p>
     */
    private static final Pattern SUP_SUB_BRACE = Pattern.compile("([\\^_])\\{([^}]*)}");
    private static final Pattern GENERIC_CMD = Pattern.compile("\\\\[a-zA-Z]+");
    private static final Pattern MATH_DELIM = Pattern.compile("\\${1,2}");
    private static final Pattern MARKDOWN = Pattern.compile("(\\*\\*|__|`{1,3}|^#{1,6}\\s*|^[-*+]\\s+)", Pattern.MULTILINE);
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");

    private LlmTextSanitizer() {
    }

    /**
     * 清洗为可读纯文本
     *
     * @param raw LLM 原始输出（可为 null）
     * @return 清洗后的文本；入参为 null 时返回 null
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        // 0. HTML 清洗（2026-11-17）：保留白名单标签（试卷表格要能按表格渲染），
        //    删掉 script/style 与白名单外标签；纯文本派生在展示/判分侧按需做（HtmlTableTextifier）。
        String text = com.jiang.common.util.HtmlRichText.sanitizeForStorage(raw);

        // 1. 控制字符（\v、\f 等被误转义的结果）
        text = CONTROL.matcher(text).replaceAll("");

        // 2. 常见 LaTeX 结构
        text = VEC.matcher(text).replaceAll("向量$1");
        text = FRAC.matcher(text).replaceAll("($1)/($2)");
        text = SQRT.matcher(text).replaceAll("√($1)");
        text = LEFT_RIGHT.matcher(text).replaceAll("");
        text = TEXT_CMD.matcher(text).replaceAll("$2");
        // 2.1 花括号上下标 → 圆括号（保留 ^ / _，纯文本 x^2、a_n 不受影响）
        text = SUP_SUB_BRACE.matcher(text).replaceAll("$1($2)");

        // 3. 常见符号命令
        text = text.replace("\\cdot", "·")
                .replace("\\times", "×")
                .replace("\\div", "÷")
                .replace("\\pm", "±")
                .replace("\\leq", "≤")
                .replace("\\le", "≤")
                .replace("\\geq", "≥")
                .replace("\\ge", "≥")
                .replace("\\neq", "≠")
                .replace("\\ne", "≠")
                .replace("\\approx", "≈")
                .replace("\\theta", "θ")
                .replace("\\alpha", "α")
                .replace("\\beta", "β")
                .replace("\\pi", "π")
                .replace("\\angle", "∠")
                .replace("\\triangle", "△")
                .replace("\\perp", "⊥")
                .replace("\\parallel", "∥")
                .replace("\\infty", "∞")
                .replace("\\rightarrow", "→")
                .replace("\\to", "→");

        // 4. 剩余未知命令：去掉反斜杠保留命令名，避免出现裸反斜杠
        text = GENERIC_CMD.matcher(text).replaceAll("");

        // 5. 公式定界符与 Markdown 标记
        text = MATH_DELIM.matcher(text).replaceAll("");
        text = MARKDOWN.matcher(text).replaceAll("");

        // 6. 折叠多余空白（保留换行）
        text = text.replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n");

        // 7. v4/D25 兜底：嵌套/残留的花括号（如 \frac{x^{2}}{a_{n}} 的中间态）一律去掉——
        //    纯文本渲染不接受任何花括号上下标；`x^2` / `a_n` 不受影响
        text = text.replace("{", "").replace("}", "");

        return text.trim();
    }
}
