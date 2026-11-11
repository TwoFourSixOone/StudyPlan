package com.jiang.common.util;

/**
 * 试卷来源题目引用（负 ID 命名空间）的唯一转换点（design v3 / D1-D4，约定 #26）
 *
 * <p>同一道试卷题在三条链路上必须是同一取值：</p>
 * <ul>
 *   <li>{@code error_question.question_id} = {@code -exam_question.id}</li>
 *   <li>{@code daily_plan.content[].questionId} = {@code -exam_question.id}</li>
 *   <li>{@code homework_submission_detail.question_id} = {@code -exam_question.id}</li>
 * </ul>
 *
 * <p>用负值而不是正数 exam id 的原因：{@code homework_submission_detail} 的唯一键是
 * {@code uk_plan_question(plan_id, question_id, tenant_id)}，同一份作业里
 * {@code question_bank.question_id=42} 与 {@code exam_question.id=42} 会经
 * {@code insertOrUpdate} 的 {@code ON DUPLICATE KEY UPDATE} 互相覆盖；负值与正数空间不相交，
 * 且"答题 → stage 推进"零转换。</p>
 *
 * <p><b>禁止在任何地方手写负号</b>：一律走本类，保证全链路唯一转换点（tasks D1）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
public final class ExamQuestionRefs {

    /** 题目来源：试卷（第三种来源，与 bank/generated 并列） */
    public static final String SOURCE_EXAM = "exam";

    /** 题目来源：题库 */
    public static final String SOURCE_BANK = "bank";

    /** 题目来源：生成题 */
    public static final String SOURCE_GENERATED = "generated";

    private ExamQuestionRefs() {
    }

    /**
     * 试卷题目 ID → 引用 ID（负值）：{@code toRefId(42) = -42}
     */
    public static Long toRefId(Long examQuestionId) {
        return examQuestionId == null ? null : -examQuestionId;
    }

    /**
     * 引用 ID → 试卷题目 ID：{@code examIdOf(-42) = 42}
     */
    public static Long examIdOf(Long refId) {
        return refId == null ? null : -refId;
    }

    /**
     * 是否为试卷来源引用（负 ID 命名空间）
     */
    public static boolean isExamRef(Long refId) {
        return refId != null && refId < 0;
    }
}
