package com.jiang.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 学生作答提取上下文（2026-11-17，Change 2026-11-17_exam_dual_channel_parse）
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@Data
@Builder
public class ExamAnswerParseContext {

    /** 学科名（Prompt 展示） */
    private String subjectName;

    /** 学生年级中文名（约定 #23） */
    private String gradeLabel;

    /** 图片总数 */
    private int imageTotal;

    /** 租户（日志/降级用） */
    private String tenantId;

    /** 题目清单（paperNo + 题干摘要 + 选项摘要，已渲染） */
    private String questionDigest;
}
