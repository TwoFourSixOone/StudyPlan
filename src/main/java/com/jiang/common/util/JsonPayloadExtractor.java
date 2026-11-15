package com.jiang.common.util;

import lombok.extern.slf4j.Slf4j;

/**
 * LLM 输出的 JSON 载荷提取（2026-11-12）
 *
 * <p>模型经常把 JSON 包在 {@code ```json ... ```} 代码块里，或在前后加一句说明；
 * 直接 {@code readTree} 会因第一个字符是反引号而整批失败（实测：归因 Agent 因此把
 * 2 道题降级为"待人工归因"）。本工具负责：</p>
 * <ol>
 *   <li>剥离 Markdown 代码围栏（{@code ```json} / {@code ```JSON} / {@code ```}）</li>
 *   <li>截取第一个 {@code {} / {@code [}} 到最后一个对应的 {@code }} / {@code ]}</li>
 * </ol>
 *
 * <p>只做"定位 JSON 载荷"，不修复 JSON 语法——语法错误仍应由校验器判失败。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-12
 */
@Slf4j
public final class JsonPayloadExtractor {

    private JsonPayloadExtractor() {
    }

    /**
     * 提取 JSON 载荷；无法定位时原样返回（交给调用方报解析失败）
     */
    public static String extract(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return text;
        }
        text = stripCodeFence(text);
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start;
        char open;
        char close;
        if (objectStart < 0 && arrayStart < 0) {
            return text;
        }
        if (objectStart < 0) {
            start = arrayStart;
            open = '[';
            close = ']';
        } else if (arrayStart < 0) {
            start = objectStart;
            open = '{';
            close = '}';
        } else if (objectStart < arrayStart) {
            start = objectStart;
            open = '{';
            close = '}';
        } else {
            start = arrayStart;
            open = '[';
            close = ']';
        }
        int end = text.lastIndexOf(close);
        if (end <= start) {
            return text;
        }
        String payload = text.substring(start, end + 1).trim();
        if (payload.length() != text.length()) {
            log.debug("JSON 载荷已从模型输出中提取: 原始长度={}, 提取长度={}", text.length(), payload.length());
        }
        return payload;
    }

    /**
     * 剥离 Markdown 代码围栏（可能首尾各一个）
     */
    private static String stripCodeFence(String text) {
        String body = text;
        if (body.startsWith("```")) {
            int firstLineEnd = body.indexOf('\n');
            body = firstLineEnd >= 0 ? body.substring(firstLineEnd + 1) : body;
        }
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.trim();
    }
}
