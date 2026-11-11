package com.jiang.common.util;

import com.jiang.model.entity.ExamQuestion;
import com.jiang.model.entity.QuestionBank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试卷题 → 临时 QuestionBank 适配器测试（design v3 §7.2.1-6 / tasks D6-D8）
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamQuestionSourceAdapter 试卷源题适配")
class ExamQuestionSourceAdapterTest {

    private static ExamQuestion question(String stem, List<Map<String, String>> choices,
                                         String answer, String explanation, String type,
                                         String difficulty, String kpId) {
        ExamQuestion question = new ExamQuestion();
        question.setId(501L);
        question.setKnowledgePointId(kpId);
        question.setQuestionType(type);
        question.setDifficulty(difficulty);
        question.setTenantId("default");
        question.setQuestionContent(ExamQuestionTextCodec.encode(stem, choices, answer, explanation));
        return question;
    }

    @Test
    @DisplayName("字段映射：题干+选项行 / 答案 / 解析 / 题型 / 难度 / 知识点 / 选项 JSON；不落库")
    void should_mapExamQuestion_toTransientQuestionBank() {
        ExamQuestion source = question("下列数中最小的数是（ ）",
                List.of(Map.of("label", "A", "text", "-3"), Map.of("label", "B", "text", "0")),
                "A", "负数最小", "choice", "basic", "kp-1");

        QuestionBank adapted = ExamQuestionSourceAdapter.toQuestionBank(source);

        assertEquals(501L, adapted.getQuestionId());
        assertEquals("kp-1", adapted.getKnowledgePointId());
        assertTrue(adapted.getContent().startsWith("下列数中最小的数是（ ）"));
        assertTrue(adapted.getContent().contains("-3"));
        assertTrue(adapted.getContent().contains("0"));
        assertEquals("A", adapted.getAnswer());
        assertEquals("负数最小", adapted.getAnalysis());
        assertEquals("choice", adapted.getType());
        assertEquals("basic", adapted.getDifficulty());
        assertNull(adapted.getGrade(), "临时副本不继承年级（由 kp 范围决定）");
        assertTrue(adapted.getChoices() != null && adapted.getChoices().contains("\"label\":\"A\""),
                "选项以标准 JSON 形态承载: " + adapted.getChoices());
        assertTrue(ExamQuestionSourceAdapter.hasUsableAnswer(source));
    }

    @Test
    @DisplayName("缺标准答案 → hasUsableAnswer=false（调用方跳过该 kp 并写降级日志）")
    void should_reportUnusable_when_answerMissing() {
        ExamQuestion source = question("求 1+1", List.of(), null, null, "short", null, "kp-2");

        assertFalse(ExamQuestionSourceAdapter.hasUsableAnswer(source));
        assertFalse(ExamQuestionSourceAdapter.hasUsableAnswer(question("题干", List.of(), "  ", null,
                "short", null, "kp-2")));
        assertFalse(ExamQuestionSourceAdapter.hasUsableAnswer(null));
    }

    @Test
    @DisplayName("无题干 → 返回 null（无题面无法作为举一反三源题）")
    void should_returnNull_when_stemMissing() {
        ExamQuestion source = new ExamQuestion();
        source.setId(502L);
        source.setQuestionContent(ExamQuestionTextCodec.ANSWER_MARK + "A");

        assertNull(ExamQuestionSourceAdapter.toQuestionBank(source));
        assertNull(ExamQuestionSourceAdapter.toQuestionBank(null));
    }

    @Test
    @DisplayName("空选项 → choices 为 null（不写空 JSON 数组）")
    void should_keepChoicesNull_when_noChoices() {
        QuestionBank adapted = ExamQuestionSourceAdapter.toQuestionBank(
                question("计算 1+1", List.of(), "2", null, "numeric", null, "kp-3"));

        assertNull(adapted.getChoices());
        assertEquals("2", adapted.getAnswer());
    }
}
