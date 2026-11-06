package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 分析结果列表项 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@Builder
public class ExamResultListVO {

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
     * 状态：pending/processing/completed/failed
     */
    private String status;

    /**
     * 批改状态：ungraded/graded
     */
    private String gradeStatus;

    /**
     * 总分
     */
    private BigDecimal totalScore;
}
