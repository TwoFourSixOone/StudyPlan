package com.jiang.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChoiceOptions 单元测试（隐性约定 #25）
 *
 * <p>覆盖 LLM 与历史数据可能出现的各种选项形态，确保落库/出参前都能归一化，
 * 避免选择题渲染成「有题干、无选项」的死题。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ChoiceOptions 选择题选项归一化")
class ChoiceOptionsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ==================== normalize ====================

    @Test
    @DisplayName("标准形态 {label,text} 原样保留")
    void should_keep_standard_shape() throws Exception {
        List<Map<String, String>> options = ChoiceOptions.normalize(
                json("[{\"label\":\"A\",\"text\":\"4\"},{\"label\":\"B\",\"text\":\"2\"}]"));

        assertEquals(2, options.size());
        assertEquals("A", options.get(0).get("label"));
        assertEquals("4", options.get(0).get("text"));
    }

    @Test
    @DisplayName("题库形态 {key,text}：label 取 key")
    void should_map_key_to_label() throws Exception {
        List<Map<String, String>> options = ChoiceOptions.normalize(
                json("[{\"key\":\"A\",\"text\":\"4\"},{\"key\":\"B\",\"text\":\"2\"}]"));

        assertEquals(2, options.size());
        assertEquals("A", options.get(0).get("label"));
        assertEquals("2", options.get(1).get("text"));
    }

    @Test
    @DisplayName("字符串数组带序号：\"A. 4\" 拆出 label 与 text")
    void should_split_prefixed_strings() {
        List<Map<String, String>> options = ChoiceOptions.normalize(
                List.of("A. 4", "B、2", "C）一样大", "D. 无法比较"));

        assertEquals(4, options.size());
        assertEquals("A", options.get(0).get("label"));
        assertEquals("4", options.get(0).get("text"));
        assertEquals("C", options.get(2).get("label"));
        assertEquals("一样大", options.get(2).get("text"));
    }

    @Test
    @DisplayName("字符串数组无序号：按出现顺序补 A/B/C")
    void should_assign_labels_for_plain_strings() {
        List<Map<String, String>> options = ChoiceOptions.normalize(List.of("4", "2", "一样大"));

        assertEquals(3, options.size());
        assertEquals("A", options.get(0).get("label"));
        assertEquals("4", options.get(0).get("text"));
        assertEquals("C", options.get(2).get("label"));
    }

    @Test
    @DisplayName("对象映射 {\"A\":\"4\"} 展开为数组")
    void should_expand_object_mapping() throws Exception {
        Map<String, Object> mapping = OBJECT_MAPPER.readValue(
                "{\"A\":\"4\",\"B\":\"2\"}", new TypeReference<Map<String, Object>>() {});

        List<Map<String, String>> options = ChoiceOptions.normalize(mapping);

        assertEquals(2, options.size());
        assertEquals("A", options.get(0).get("label"));
        assertEquals("4", options.get(0).get("text"));
        assertEquals("B", options.get(1).get("label"));
    }

    @Test
    @DisplayName("缺失 / null / 空数组 → 空列表")
    void should_return_empty_for_missing() throws Exception {
        assertTrue(ChoiceOptions.normalize(null).isEmpty());
        assertTrue(ChoiceOptions.normalize(List.of()).isEmpty());
        assertTrue(ChoiceOptions.normalize(json("[ ]")).isEmpty());
    }

    @Test
    @DisplayName("isUsable：至少 2 个且文本非空")
    void should_check_usability() throws Exception {
        assertFalse(ChoiceOptions.isUsable(ChoiceOptions.normalize(json("[]"))));
        assertFalse(ChoiceOptions.isUsable(ChoiceOptions.normalize(List.of("只有一个选项"))));
        assertTrue(ChoiceOptions.isUsable(ChoiceOptions.normalize(List.of("4", "2"))));
    }

    // ==================== canonicalize ====================

    @Test
    @DisplayName("canonicalize：标签重排为 A/B/C，答案由选项文本归一化为 label")
    void should_canonicalize_answer_text_to_label() {
        Map<String, Object> content = content("""
                {
                  "stem": "4 和 2 比，哪个数大？",
                  "choices": [{"label": "甲", "text": "4"}, {"label": "乙", "text": "2"}],
                  "answer": "2",
                  "formatType": "choice"
                }
                """);

        ChoiceOptions.canonicalize(content);

        List<Map<String, String>> options = choices(content);
        assertEquals("A", options.get(0).get("label"));
        assertEquals("B", options.get(1).get("label"));
        assertEquals("B", content.get("answer"));
    }

    @Test
    @DisplayName("canonicalize：答案 \"B. 2\" 归一化为 B")
    void should_canonicalize_labelled_answer() {
        Map<String, Object> content = content("""
                {
                  "stem": "题干",
                  "choices": ["A. 1", "B. 2", "C. 3", "D. 4"],
                  "answer": "B. 2",
                  "formatType": "choice"
                }
                """);

        ChoiceOptions.canonicalize(content);

        assertEquals(4, choices(content).size());
        assertEquals("B", content.get("answer"));
    }

    @Test
    @DisplayName("canonicalize：非选择题 choices 置空")
    void should_clear_choices_for_non_choice() {
        Map<String, Object> content = content("""
                {
                  "stem": "求 x",
                  "choices": ["A. 1", "B. 2"],
                  "answer": "1",
                  "formatType": "numeric"
                }
                """);

        ChoiceOptions.canonicalize(content);

        assertTrue(choices(content).isEmpty());
        assertEquals("1", content.get("answer"));
    }

    private Map<String, Object> content(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object json(String raw) {
        try {
            return OBJECT_MAPPER.readValue(raw, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> choices(Map<String, Object> content) {
        return (List<Map<String, String>>) content.get("choices");
    }
}
