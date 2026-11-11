package com.jiang.dto;

/**
 * 考试掌握度事件（批量更新入参，design §7.3）
 *
 * @param knowledgePointId 归因得到的知识点 ID
 * @param isCorrect        是否答对（答对答错都更新掌握度）
 * @param difficulty       试卷声明的难度（basic/standard/challenge，可空 → D_norm=0.5）
 * @author StudyMate Team
 * @since 2026-11-11
 */
public record ExamMasteryEvent(String knowledgePointId, boolean isCorrect, String difficulty) {
}
