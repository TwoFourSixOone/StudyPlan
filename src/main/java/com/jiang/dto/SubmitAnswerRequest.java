package com.jiang.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提交单题答案请求 DTO
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
public class SubmitAnswerRequest {

    /** 所属计划 ID */
    @NotNull(message = "planId 不能为空")
    private Long planId;

    /** 题目 ID */
    @NotNull(message = "questionId 不能为空")
    private Long questionId;

    /** 题目来源：bank / generated */
    @NotNull(message = "questionSource 不能为空")
    private String questionSource;

    /** 任务类型：review / redo / similar / comprehensive */
    @NotNull(message = "taskType 不能为空")
    private String taskType;

    /** 学生答案 */
    private String studentAnswer;
}
