package com.jiang.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * daily_plan.content JSON 元素 DTO
 * 存储每道题目的引用信息
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyPlanQuestionRef {

    /** 题目 ID（question_bank.question_id 或 generated_question.generated_question_id） */
    private Long questionId;

    /** 题目来源：bank / generated */
    private String questionSource;

    /** 任务类型：review / redo / similar / comprehensive */
    private String taskType;
}
