package com.jiang.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 提交评测答案请求 DTO
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
public class SubmitAssessmentRequest {

    /**
     * 答案列表
     */
    @NotEmpty(message = "答案列表不能为空")
    @Valid
    private List<AnswerItem> answers;
}
