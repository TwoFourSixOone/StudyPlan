package com.jiang.common.util;

import com.jiang.model.entity.QuestionBank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 专项评测出题选择器
 * 策略：每知识点 3 道题，优先 basic/standard/challenge 各 1 道，不足随机补齐
 * IMP-1 修复：接受可注入的 Random 实例，测试时注入固定种子保证可复现
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
public final class AssessmentQuestionSelector {

    public static final int QUESTIONS_PER_KP = 3;
    public static final int MAX_KPS_PER_ASSESSMENT = 5;

    private static final String DIFFICULTY_BASIC = "basic";
    private static final String DIFFICULTY_STANDARD = "standard";
    private static final String DIFFICULTY_CHALLENGE = "challenge";

    private AssessmentQuestionSelector() {
        // 工具类禁止实例化
    }

    /**
     * 从候选题目中选取指定数量的题目（使用默认 Random）
     *
     * @param candidates 候选题目列表
     * @param count      需选取数量
     * @return 选中的题目列表
     */
    public static List<QuestionBank> select(List<QuestionBank> candidates, int count) {
        return select(candidates, count, new Random());
    }

    /**
     * IMP-1 修复：可注入 Random 实例，测试时传入固定种子 new Random(42) 保证可复现
     *
     * @param candidates 按难度排序的候选题目列表
     * @param count      需选取数量（默认 3）
     * @param random     随机数生成器（测试时注入固定种子）
     * @return 选中的题目列表
     */
    public static List<QuestionBank> select(List<QuestionBank> candidates, int count, Random random) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 候选不足 count 道时，返回全部候选
        if (candidates.size() <= count) {
            return new ArrayList<>(candidates);
        }

        List<QuestionBank> selected = new ArrayList<>();
        List<Long> selectedIds = new ArrayList<>();

        // 按难度分组选取：basic → standard → challenge 各 1 道
        String[] difficultyOrder = {DIFFICULTY_BASIC, DIFFICULTY_STANDARD, DIFFICULTY_CHALLENGE};
        for (String difficulty : difficultyOrder) {
            if (selected.size() >= count) {
                break;
            }
            List<QuestionBank> group = candidates.stream()
                    .filter(q -> difficulty.equals(q.getDifficulty()))
                    .filter(q -> !selectedIds.contains(q.getQuestionId()))
                    .collect(Collectors.toList());
            if (!group.isEmpty()) {
                QuestionBank pick = group.get(random.nextInt(group.size()));
                selected.add(pick);
                selectedIds.add(pick.getQuestionId());
            }
        }

        // 不足 count 道时，从剩余候选题目随机补齐
        if (selected.size() < count) {
            List<QuestionBank> remaining = candidates.stream()
                    .filter(q -> !selectedIds.contains(q.getQuestionId()))
                    .collect(Collectors.toList());
            Collections.shuffle(remaining, random);
            for (QuestionBank q : remaining) {
                if (selected.size() >= count) {
                    break;
                }
                selected.add(q);
                selectedIds.add(q.getQuestionId());
            }
        }

        return selected;
    }
}
