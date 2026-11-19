package com.jiang.common.util;

import com.jiang.dto.ParsedQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卷面题号保真 / 缺题检测（Change 2026-11-17_exam_parse_reliability）
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@DisplayName("ExamParseNumbering 题号保真与完整性")
class ExamParseNumberingTest {

    private static List<ParsedQuestion> questions(Integer... paperNos) {
        List<ParsedQuestion> list = new ArrayList<>();
        for (Integer paperNo : paperNos) {
            list.add(ParsedQuestion.builder().paperNo(paperNo).stem("t" + paperNo).build());
        }
        return list;
    }

    @Test
    @DisplayName("全部题号合法且不重复 → 原样保留，按题号排序")
    void should_preservePaperNos() {
        List<ParsedQuestion> list = questions(3, 1, 2);
        ExamParseNumbering.Result result = ExamParseNumbering.apply(list, 3);

        assertTrue(result.preserved());
        assertEquals(List.of(1, 2, 3),
                list.stream().map(ParsedQuestion::getQuestionIndex).toList());
        assertTrue(result.missingPaperNos().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("缺第 16 题（总数 19）→ 保留缺口 + 告警点名")
    void should_detectMissingAndKeepGap() {
        List<Integer> nos = new ArrayList<>();
        for (int i = 1; i <= 19; i++) {
            if (i != 16) {
                nos.add(i);
            }
        }
        List<ParsedQuestion> list = questions(nos.toArray(new Integer[0]));
        ExamParseNumbering.Result result = ExamParseNumbering.apply(list, 19);

        assertTrue(result.preserved());
        assertEquals(List.of(16), result.missingPaperNos());
        assertEquals(17, list.get(15).getQuestionIndex(), "第 16 个元素应是卷面第 17 题");
        assertEquals(19, list.get(17).getQuestionIndex(), "最后一个元素应是卷面第 19 题");
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("缺第 16 题"), result.warnings().get(0));
    }

    @Test
    @DisplayName("有题缺 paperNo → 回退按图片顺序 1..N 并告警")
    void should_fallbackWhenPaperNoMissing() {
        List<ParsedQuestion> list = questions(1, null, 3);
        ExamParseNumbering.Result result = ExamParseNumbering.apply(list, 3);

        assertFalse(result.preserved());
        assertEquals(List.of(1, 2, 3),
                list.stream().map(ParsedQuestion::getQuestionIndex).toList());
        assertTrue(result.warnings().get(0).contains("未能识别卷面题号"));
    }

    @Test
    @DisplayName("题号重复 → 回退 1..N + 告警，不覆盖配图/归因 key")
    void should_fallbackWhenPaperNoDuplicated() {
        List<ParsedQuestion> list = questions(1, 2, 2);
        ExamParseNumbering.Result result = ExamParseNumbering.apply(list, 3);

        assertFalse(result.preserved());
        assertEquals(List.of(2), result.duplicatePaperNos());
        assertEquals(List.of(1, 2, 3),
                list.stream().map(ParsedQuestion::getQuestionIndex).toList());
        assertTrue(result.warnings().get(0).contains("题号重复"));
    }

    @Test
    @DisplayName("题号超过整卷题数 → 回退 + 明确原因（不是「超出范围」）")
    void should_fallbackWhenPaperNoExceedsTotal() {
        List<ParsedQuestion> list = questions(1, 2, 20);
        ExamParseNumbering.Result result = ExamParseNumbering.apply(list, 19);

        assertFalse(result.preserved());
        assertTrue(result.warnings().get(0).contains("题号超过整卷题数"), result.warnings().get(0));
        assertTrue(result.warnings().get(0).contains("已按图片顺序编号"), result.warnings().get(0));
    }

    @Test
    @DisplayName("题号越界（>60）→ 回退 + 「超出范围」原因")
    void should_fallbackWhenPaperNoOutOfRange() {
        List<ParsedQuestion> list = questions(1, 2, 88);
        ExamParseNumbering.Result result = ExamParseNumbering.apply(list, 0);

        assertFalse(result.preserved());
        assertTrue(result.warnings().get(0).contains("题号超出范围"), result.warnings().get(0));
        assertEquals(List.of(1, 2, 3),
                list.stream().map(ParsedQuestion::getQuestionIndex).toList());
    }

    @Test
    @DisplayName("总数未知（0）→ 用最大题号是否等于题数做粗检")
    void should_detectGapWhenTotalUnknown() {
        List<ParsedQuestion> list = questions(1, 2, 4);
        ExamParseNumbering.Result result = ExamParseNumbering.apply(list, 0);

        assertTrue(result.preserved());
        assertEquals(3, list.size());
        assertTrue(result.warnings().get(0).contains("题号不连续"), result.warnings().get(0));
    }

    @Test
    @DisplayName("空题目 → 不报错、不告警")
    void should_handleEmpty() {
        ExamParseNumbering.Result result = ExamParseNumbering.apply(new ArrayList<>(), 19);
        assertFalse(result.preserved());
        assertTrue(result.warnings().isEmpty());
    }
}
