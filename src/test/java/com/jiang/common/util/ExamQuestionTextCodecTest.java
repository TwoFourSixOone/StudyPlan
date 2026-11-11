package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试卷题面编解码测试（零 DDL：question_content 分段文本，design §4.1）
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamQuestionTextCodec 题面编解码")
class ExamQuestionTextCodecTest {

    @Test
    @DisplayName("编码 → 解码往返一致（含选项/答案/解析）")
    void should_roundTrip_when_allSectionsPresent() {
        List<Map<String, String>> choices = List.of(
                Map.of("label", "A", "text", "-3"),
                Map.of("label", "B", "text", "0"));

        String encoded = ExamQuestionTextCodec.encode("下列各数中，最小的数是（ ）", choices, "A", "负数小于 0");
        ExamQuestionTextCodec.Decoded decoded = ExamQuestionTextCodec.decode(encoded);

        assertEquals("下列各数中，最小的数是（ ）", decoded.stem());
        assertEquals(2, decoded.choices().size());
        assertEquals("A", decoded.choices().get(0).get("label"));
        assertEquals("-3", decoded.choices().get(0).get("text"));
        assertEquals("B", decoded.choices().get(1).get("label"));
        assertEquals("A", decoded.correctAnswer());
        assertEquals("负数小于 0", decoded.explanation());
    }

    @Test
    @DisplayName("无任何标记的旧数据 → 整段作为 stem")
    void should_fallbackToStem_when_noMarkers() {
        String legacy = "[OCR 识别失败，待人工处理]";

        ExamQuestionTextCodec.Decoded decoded = ExamQuestionTextCodec.decode(legacy);

        assertEquals(legacy, decoded.stem());
        assertTrue(decoded.choices().isEmpty());
        assertNull(decoded.correctAnswer());
        assertNull(decoded.explanation());
    }

    @Test
    @DisplayName("缺段落容错：只有题干+答案也能解出")
    void should_tolerateMissingSections() {
        String content = ExamQuestionTextCodec.STEM_MARK + "求 1+1"
                + "\n" + ExamQuestionTextCodec.ANSWER_MARK + "2";

        ExamQuestionTextCodec.Decoded decoded = ExamQuestionTextCodec.decode(content);

        assertEquals("求 1+1", decoded.stem());
        assertEquals("2", decoded.correctAnswer());
        assertNull(decoded.explanation());
        assertTrue(decoded.choices().isEmpty());
    }

    @Test
    @DisplayName("选项顺序稳定，非选择题不写选项段")
    void should_keepOrderAndSkipBlankSections() {
        String encoded = ExamQuestionTextCodec.encode("题干", List.of(), null, null);

        assertEquals(ExamQuestionTextCodec.STEM_MARK + "题干", encoded);

        String withChoices = ExamQuestionTextCodec.encode("题干",
                List.of(Map.of("label", "A", "text", "1"), Map.of("label", "B", "text", "2")), null, null);
        ExamQuestionTextCodec.Decoded decoded = ExamQuestionTextCodec.decode(withChoices);
        assertEquals("A", decoded.choices().get(0).get("label"));
        assertEquals("1", decoded.choices().get(0).get("text"));
        assertEquals("B", decoded.choices().get(1).get("label"));
    }

    @Test
    @DisplayName("空内容/仅标记 → 空 stem，不抛异常")
    void should_handleEmptyContent() {
        assertEquals("", ExamQuestionTextCodec.decode(null).stem());
        assertEquals("", ExamQuestionTextCodec.decode("").stem());
    }
}
