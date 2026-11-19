package com.jiang.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单题学生作答（2026-11-17，Change 2026-11-17_exam_dual_channel_parse）
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@Data
@Builder
public class ParsedAnswer {

    /** 卷面题号（与 clean 版题目对齐） */
    private Integer paperNo;

    /** 学生作答（纯文本，约定 #24） */
    private String studentAnswer;

    /** answered/blank/unreadable */
    private String answerStatus;

    /** 无法辨认的字段 */
    private List<String> unclearFields;
}
