package com.jiang.service;

import com.jiang.dto.GradeRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 判分公共组件（作业批改 / 试卷结算共用，design D5 / §6.1）
 *
 * <p><b>判分口径（与作业批改一致，S3 契约表）</b></p>
 * <table border="1">
 *   <tr><th>场景</th><th>判分方式</th><th>返回值</th></tr>
 *   <tr><td>choice + 有答案</td><td>{@code studentAnswer.trim().equalsIgnoreCase(correctAnswer.trim())}</td><td>true/false</td></tr>
 *   <tr><td>choice + 缺答案</td><td>不调用 AI，直接判错</td><td>false</td></tr>
 *   <tr><td>numeric/short/self + 有答案</td><td>{@code grading_v1.txt} AI 批改</td><td>true/false</td></tr>
 *   <tr><td>非 choice 缺答案 / AI 异常 / 返回不可解析</td><td>回退字符串比较</td><td>false（典型）</td></tr>
 *   <tr><td>作答空白（blank）</td><td>未作答</td><td>false</td></tr>
 *   <tr><td>作答无法辨识（unreadable）</td><td>不判分</td><td>null</td></tr>
 *   <tr><td>批量预算内未完成</td><td>超时</td><td>null</td></tr>
 * </table>
 *
 * <p>{@code null} 的唯一来源是"作答无法辨识"与"批处理超时"；答案缺失不再产生 null。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
public interface AnswerGradingService {

    /**
     * 单题判分
     *
     * @param formatType     题型（choice/numeric/short/self）
     * @param questionContent 题干
     * @param correctAnswer  标准答案
     * @param studentAnswer  学生作答
     * @param answerStatus   作答状态（answered/blank/unreadable，可空视为 answered）
     * @param subjectName    科目名称（拼 Prompt）
     * @param gradeLabel     年级中文名（{@code GradeMatcher.displayName}，空值"当前"，约定 #23）
     * @return true/false；null 仅表示无法判定（unreadable）
     */
    Boolean grade(String formatType, String questionContent, String correctAnswer,
                  String studentAnswer, String answerStatus, String subjectName, String gradeLabel);

    /**
     * 批量判分
     *
     * <p>choice/空白/unreadable 同步处理（无 IO）；其余走受限并发 AI 批改，
     * 统一经过全局 {@code LlmConcurrencyLimiter}（在途 LLM ≤3），
     * {@code budget} 内未完成的题返回 null。</p>
     *
     * @param requests    待判分题目（key 由调用方指定）
     * @param subjectName 科目名称
     * @param gradeLabel  年级中文名
     * @param budget      批处理总预算
     * @param concurrency 并发提示（实际由全局闸门约束）
     * @return key → 判分结果（可能为 null）
     */
    Map<Integer, Boolean> gradeBatch(List<GradeRequest> requests, String subjectName,
                                     String gradeLabel, Duration budget, int concurrency);
}
