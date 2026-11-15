package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选择题答案归一与多选判定测试（Change 2026-11-12_exam_quality_v2）
 */
@DisplayName("ChoiceAnswer 多选答案工具")
class ChoiceAnswerTest {

    @Test
    @DisplayName("labels: 各种写法都能抽出标签并去重排序")
    void should_extractLabels() {
        assertEquals(List.of("A", "B"), ChoiceAnswer.labels("AB"));
        assertEquals(List.of("A", "B"), ChoiceAnswer.labels("b、a"));
        assertEquals(List.of("A", "B", "C"), ChoiceAnswer.labels("C,A,B"));
        assertEquals(List.of("A", "B", "D"), ChoiceAnswer.labels("选ABD"));
        assertEquals(List.of("A", "B"), ChoiceAnswer.labels(" A, B "));
        assertTrue(ChoiceAnswer.labels("").isEmpty());
        assertTrue(ChoiceAnswer.labels(null).isEmpty());
        assertTrue(ChoiceAnswer.labels("正确").isEmpty());
    }

    @Test
    @DisplayName("isMulti: 标签数 ≥ 2")
    void should_detectMulti() {
        assertFalse(ChoiceAnswer.isMulti("B"));
        assertTrue(ChoiceAnswer.isMulti("AB"));
        assertTrue(ChoiceAnswer.isMulti("A、B、D"));
    }

    @Test
    @DisplayName("sameAnswer: 集合相等才对，少选/多选都错")
    void should_compareStrictly() {
        assertTrue(ChoiceAnswer.sameAnswer("ABD", "DBA"));
        assertTrue(ChoiceAnswer.sameAnswer("A、B", "AB"));
        assertFalse(ChoiceAnswer.sameAnswer("AB", "ABD"));
        assertFalse(ChoiceAnswer.sameAnswer("ABD", "AB"));
        assertFalse(ChoiceAnswer.sameAnswer("", "AB"));
        assertFalse(ChoiceAnswer.sameAnswer("AB", null));
    }

    @Test
    @DisplayName("withinOptions: 答案标签必须都在选项内")
    void should_validateWithinOptions() {
        assertTrue(ChoiceAnswer.withinOptions(List.of("A", "C"), List.of("A", "B", "C", "D")));
        assertFalse(ChoiceAnswer.withinOptions(List.of("E"), List.of("A", "B", "C", "D")));
    }
}
