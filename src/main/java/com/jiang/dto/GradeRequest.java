package com.jiang.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 判分请求（批量判分入参，design §6.1）
 *
 * <p>{@link #key} 由调用方指定（试卷侧用 exam_question.id），用于把判分结果回填到对应题目。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GradeRequest {

    /** 调用方自定义键（试卷侧 = exam_question.id） */
    private Integer key;

    /** 题型：choice/numeric/short/self */
    private String formatType;

    /** 题干（纯文本） */
    private String questionContent;

    /** 标准答案（选择题为选项标签） */
    private String correctAnswer;

    /** 学生作答 */
    private String studentAnswer;

    /** 作答状态：answered/blank/unreadable（unreadable → 判分 null） */
    private String answerStatus;
}
