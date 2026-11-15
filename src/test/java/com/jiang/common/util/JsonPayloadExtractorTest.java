package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 输出 JSON 载荷提取测试（2026-11-12：归因 Agent 被 ```json 围栏整批降级的修复）
 */
@DisplayName("JsonPayloadExtractor 代码围栏清洗")
class JsonPayloadExtractorTest {

    @Test
    @DisplayName("```json 围栏 → 去掉围栏")
    void should_stripJsonFence() {
        String raw = "```json\n[{\"questionIndex\":1}]\n```";
        assertEquals("[{\"questionIndex\":1}]", JsonPayloadExtractor.extract(raw));
    }

    @Test
    @DisplayName("``` 无语言标记 + 前后说明文字 → 只留 JSON 数组")
    void should_stripFenceAndProse() {
        String raw = "好的，结果如下：\n```\n[{\"a\":1}]\n```\n以上。";
        assertEquals("[{\"a\":1}]", JsonPayloadExtractor.extract(raw));
    }

    @Test
    @DisplayName("对象载荷 + 前后废话 → 截取首个 { 到末个 }")
    void should_extractObjectPayload() {
        String raw = "解析结果：{\"questions\":[{\"stem\":\"x\"}]} 结束";
        assertEquals("{\"questions\":[{\"stem\":\"x\"}]}", JsonPayloadExtractor.extract(raw));
    }

    @Test
    @DisplayName("无 JSON → 原样返回（交给校验器判失败）")
    void should_returnRaw_when_noJson() {
        assertEquals("没有 JSON", JsonPayloadExtractor.extract("没有 JSON"));
        assertTrue(JsonPayloadExtractor.extract(null) == null);
    }

    @Test
    @DisplayName("首尾同为数组时按 [ ] 截取（多选答案等嵌套场景）")
    void should_extractNestedArray() {
        String raw = "```json\n[{\"choices\":[{\"label\":\"A\"}]}]\n```";
        assertEquals("[{\"choices\":[{\"label\":\"A\"}]}]", JsonPayloadExtractor.extract(raw));
    }
}
