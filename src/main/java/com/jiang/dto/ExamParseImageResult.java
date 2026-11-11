package com.jiang.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 视觉解析结果（单张图片）
 *
 * <p>结构的详细校验规则见 design §3.4：单题非法只丢弃该题，不重试整图；
 * 整图 JSON 非法/有效题数为 0 时该图判失败。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ExamParseImageResult {

    /** 解析出的题目（题号为图内题号，跨图重排由 ExamParseService 负责） */
    private List<ParsedQuestion> questions;

    /** 本图切题重试次数（仅内存计数，不写 ocr_retry_count，design D14/B3） */
    private int retryCount;

    /** 本图告警（部分题被丢弃/整图无题时给出原因，可空） */
    private String warning;

    /** 整图失败原因（为 null 表示解析成功；非 null 时 questions 为空） */
    private String failureReason;

    public boolean isSuccess() {
        return failureReason == null;
    }
}
