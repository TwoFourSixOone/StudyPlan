package com.jiang.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 学生作答提取结果（2026-11-17，Change 2026-11-17_exam_dual_channel_parse）
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@Data
@Builder
public class ExamAnswerParseResult {

    /** 按 paperNo 的作答 */
    private List<ParsedAnswer> answers;

    /** 重试次数（仅内存） */
    private int retryCount;

    /** 告警（无可辨认作答/部分题无法解析等） */
    private String warning;

    /** 失败原因（null = 成功） */
    private String failureReason;

    /** 被丢弃的非法作答条数 */
    private int droppedCount;

    public boolean isSuccess() {
        return failureReason == null;
    }
}
