package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 历史试卷列表项（GET /api/student/exam/papers，design §10.1）
 *
 * <p>以 {@code exam_paper} 为准（不复用 exam_analysis_result 逻辑）；不返回 image_urls 等大字段。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ExamPaperListItemVO {

    /** 试卷 ID */
    private Long paperId;

    /** 科目 ID */
    private Long subjectId;

    /** 科目名称（subject.name） */
    private String subjectName;

    /** 考试日期 */
    private LocalDate examDate;

    /** 试卷类型 code：midterm/final/monthly/mock/other */
    private String paperType;

    /** 试卷类型中文名（前端不直接展示 code，约定 #21/S13） */
    private String paperTypeName;

    /** 解析状态：uploading/pending/processing/completed/failed */
    private String status;

    /** 判分状态：ungraded/graded */
    private String gradeStatus;

    /** 题目总数 */
    private Integer questionCount;

    /** 正确题数（is_correct = 1） */
    private Integer correctCount;

    /** 已判定题数（is_correct IS NOT NULL，1 题 1 分制的分母） */
    private Integer judgedCount;

    /** 得分（1 题 1 分制 = correctCount） */
    private BigDecimal userScore;

    /** 总分（1 题 1 分制 = judgedCount，与 design §7.5 唯一口径一致） */
    private BigDecimal totalScore;

    /** 创建时间 */
    private LocalDateTime createTime;
}
