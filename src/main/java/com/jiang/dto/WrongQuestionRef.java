package com.jiang.dto;

/**
 * 错题入队引用（批量入队入参）
 *
 * <p>试卷来源的 {@link #questionId} 使用负命名空间 {@code -exam_question.id}
 * （design D4 / 约定 #26）：{@code error_question.question_id} 是 NOT NULL + 唯一索引，
 * 题库/生成题都是自增正数，负值保证不碰撞，并能被 {@code question_id > 0} 的查询天然排除。</p>
 *
 * @param questionId       题目 ID（试卷来源为负值）
 * @param knowledgePointId 知识点 ID（无知识点的错题不入队）
 * @author StudyMate Team
 * @since 2026-11-11
 */
public record WrongQuestionRef(Long questionId, String knowledgePointId) {
}
