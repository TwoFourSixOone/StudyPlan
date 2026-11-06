package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分析结果详情 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@Builder
public class ExamResultDetailVO {

    /**
     * 试卷 ID
     */
    private Long examPaperId;

    /**
     * 科目 ID
     */
    private Long subjectId;

    /**
     * 科目名称
     */
    private String subjectName;

    /**
     * 考试日期
     */
    private LocalDate examDate;

    /**
     * 试卷类型：midterm/final/other
     */
    private String paperType;

    /**
     * 试卷图片 URL 列表
     */
    private List<String> imageUrls;

    /**
     * 状态：pending/processing/completed/failed
     */
    private String status;

    /**
     * 批改状态：ungraded/graded
     */
    private String gradeStatus;

    /**
     * 总分（批改后回填）
     */
    private BigDecimal totalScore;

    /**
     * 题目详情列表
     */
    private List<ExamQuestionDetailVO> questions;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
