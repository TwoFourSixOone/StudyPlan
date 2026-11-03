package com.jiang.common.util;

import com.jiang.model.entity.QuestionBank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AssessmentQuestionSelector 单元测试
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@DisplayName("AssessmentQuestionSelector 单元测试")
class AssessmentQuestionSelectorTest {

    // ==================== 正常场景 ====================

    @Test
    @DisplayName("三种难度各有题时，每种恰好选 1 道")
    void should_selectOneFromEachDifficulty_when_allAvailable() {
        List<QuestionBank> candidates = Arrays.asList(
                buildQuestion(1L, "basic"),
                buildQuestion(2L, "basic"),
                buildQuestion(3L, "standard"),
                buildQuestion(4L, "standard"),
                buildQuestion(5L, "challenge"),
                buildQuestion(6L, "challenge"));

        List<QuestionBank> selected = AssessmentQuestionSelector.select(candidates, 3, new Random(42));

        assertEquals(3, selected.size());
        // 验证三种难度各有一道
        assertTrue(selected.stream().anyMatch(q -> "basic".equals(q.getDifficulty())));
        assertTrue(selected.stream().anyMatch(q -> "standard".equals(q.getDifficulty())));
        assertTrue(selected.stream().anyMatch(q -> "challenge".equals(q.getDifficulty())));
    }

    @Test
    @DisplayName("某难度缺题时，从其他难度随机补齐")
    void should_fillRandomly_when_oneDifficultyMissing() {
        List<QuestionBank> candidates = Arrays.asList(
                buildQuestion(1L, "basic"),
                buildQuestion(2L, "standard"),
                buildQuestion(3L, "standard"),
                buildQuestion(4L, "standard"));
        // 无 challenge 难度

        List<QuestionBank> selected = AssessmentQuestionSelector.select(candidates, 3, new Random(42));

        assertEquals(3, selected.size());
        assertTrue(selected.stream().anyMatch(q -> "basic".equals(q.getDifficulty())));
        assertTrue(selected.stream().anyMatch(q -> "standard".equals(q.getDifficulty())));
    }

    @Test
    @DisplayName("候选不足 count 道时，返回全部候选")
    void should_returnAll_when_candidatesLessThanCount() {
        List<QuestionBank> candidates = Arrays.asList(
                buildQuestion(1L, "basic"),
                buildQuestion(2L, "standard"));

        List<QuestionBank> selected = AssessmentQuestionSelector.select(candidates, 3);

        assertEquals(2, selected.size());
    }

    @Test
    @DisplayName("候选题目为空时，返回空列表")
    void should_returnEmpty_when_candidatesEmpty() {
        List<QuestionBank> selected = AssessmentQuestionSelector.select(
                Collections.emptyList(), 3);

        assertTrue(selected.isEmpty());
    }

    @Test
    @DisplayName("candidates 为 null 时，返回空列表")
    void should_returnEmpty_when_candidatesNull() {
        List<QuestionBank> selected = AssessmentQuestionSelector.select(null, 3);

        assertTrue(selected.isEmpty());
    }

    // ==================== IMP-1：固定种子可复现 ====================

    @Test
    @DisplayName("IMP-1：固定种子 new Random(42) 多次调用结果可复现")
    void should_returnSameResult_when_fixedSeed() {
        List<QuestionBank> candidates = Arrays.asList(
                buildQuestion(1L, "basic"),
                buildQuestion(2L, "basic"),
                buildQuestion(3L, "standard"),
                buildQuestion(4L, "standard"),
                buildQuestion(5L, "challenge"),
                buildQuestion(6L, "challenge"));

        List<QuestionBank> first = AssessmentQuestionSelector.select(candidates, 3, new Random(42));
        List<QuestionBank> second = AssessmentQuestionSelector.select(candidates, 3, new Random(42));

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getQuestionId(), second.get(i).getQuestionId());
        }
    }

    // ==================== 不重复选取 ====================

    @Test
    @DisplayName("选取的题目 ID 不重复")
    void should_notRepeat_when_selecting() {
        List<QuestionBank> candidates = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            String diff = i % 3 == 0 ? "basic" : (i % 3 == 1 ? "standard" : "challenge");
            candidates.add(buildQuestion(i, diff));
        }

        List<QuestionBank> selected = AssessmentQuestionSelector.select(candidates, 3, new Random(99));

        long distinctCount = selected.stream()
                .map(QuestionBank::getQuestionId)
                .distinct()
                .count();
        assertEquals(selected.size(), distinctCount);
    }

    // ==================== 辅助方法 ====================

    private QuestionBank buildQuestion(Long questionId, String difficulty) {
        QuestionBank q = new QuestionBank();
        q.setQuestionId(questionId);
        q.setKnowledgePointId("kp_001");
        q.setContent("题目内容");
        q.setDifficulty(difficulty);
        q.setType("choice");
        q.setAnswer("A");
        return q;
    }
}
