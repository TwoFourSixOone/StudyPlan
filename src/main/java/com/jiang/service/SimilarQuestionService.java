package com.jiang.service;

import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.QuestionBank;

import java.math.BigDecimal;
import java.util.List;

/**
 * 举一反三新题生成服务
 * 负责为指定知识点生成举一反三新题，含检查 Agent 校验 + 相似度检测 + 循环重试
 * <p>
 * Change: 2026-11-03_similar_question_generation
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
public interface SimilarQuestionService {

    /**
     * 为指定知识点生成举一反三新题（含检查 Agent 校验 + 相似度检测 + 循环重试）
     *
     * @param studentId        学生 ID
     * @param tenantId         租户 ID
     * @param kpId             知识点 ID
     * @param masteryScore     掌握度
     * @param sourceQuestion   原错题内容
     * @param targetCount      目标生成数量
     * @param sourceContent    原错题题干（用于相似度比较，确保新题不与原题实质相同）
     * @param existingContents 已有新题内容列表（selectCheckedContentsByKp 结果）
     * @return 校验通过的新题列表（可能不足 targetCount）
     */
    List<GeneratedQuestion> generateWithValidation(
            Long studentId, String tenantId, String kpId,
            BigDecimal masteryScore, QuestionBank sourceQuestion,
            int targetCount, String sourceContent, List<String> existingContents);
}
