package com.jiang.service.impl;

import com.jiang.common.util.GradeMatcher;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.model.entity.QuestionBank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题库抽题器（产品规则 #3：题库优先）
 *
 * <p>每日作业的"举一反三""知识回顾"优先从 {@code question_bank} 抽题，
 * 题库不足时再由 Agent 生成补足。抽题口径：</p>
 * <ul>
 *   <li>只取该知识点下的可用题（{@code selectActiveByKp}）</li>
 *   <li>年级匹配用 {@link GradeMatcher#matchesQuestion}（题目 grade 为空时回退知识点范围）</li>
 *   <li>排除本次计划已选题目与该学生历史已做题目</li>
 *   <li>按 questionId 升序取前 N 道（可预测、可测试）</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Component
@Slf4j
public class BankQuestionPicker {

    @Autowired
    private QuestionBankMapper questionBankMapper;

    /**
     * 从题库抽取指定数量的题目
     *
     * @param kpId         知识点 ID
     * @param tenantId     租户 ID
     * @param count        需要的数量
     * @param studentGrade 学生年级（可为 null，表示不过滤）
     * @param kpGradeRange 知识点年级范围（题目未标注年级时的回退依据）
     * @param excluded     需排除的题目 ID（本次计划已选 + 历史已做）
     * @return 抽到的题目（可能少于 count，甚至为空）
     */
    public List<QuestionBank> pick(String kpId, String tenantId, int count,
                                   Integer studentGrade, String kpGradeRange,
                                   Set<Long> excluded) {
        if (count <= 0) {
            return List.of();
        }
        try {
            List<QuestionBank> candidates = questionBankMapper.selectActiveByKp(kpId, tenantId);
            if (candidates == null || candidates.isEmpty()) {
                return List.of();
            }
            return candidates.stream()
                    .filter(q -> GradeMatcher.matchesQuestion(studentGrade, q.getGrade(), kpGradeRange))
                    .filter(q -> excluded == null || !excluded.contains(q.getQuestionId()))
                    .sorted(Comparator.comparing(QuestionBank::getQuestionId))
                    .limit(count)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // 抽题失败不阻塞出题：记日志后由 Agent 补足
            log.error("题库抽题失败，降级为 Agent 生成: kpId={}, error={}", kpId, e.getMessage());
            return List.of();
        }
    }
}
