package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 试卷详情（GET /api/student/exam/papers/{paperId}，design §10.2）
 *
 * <p>固定 3 条 SQL：paper + 题目（按题号升序）+ 知识点名称批量映射。
 * 只返回图片 URL（≤8 条），<b>不返回 base64 或图片内容</b>。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ExamPaperDetailVO {

    /** 试卷 ID */
    private Long paperId;

    private Long subjectId;

    /** 科目名称 */
    private String subjectName;

    private LocalDate examDate;

    /** 试卷类型 code / 中文名 */
    private String paperType;
    private String paperTypeName;

    /** 解析状态：uploading/pending/processing/completed/failed */
    private String status;

    /** 判分状态：ungraded/graded */
    private String gradeStatus;

    /** 试卷图片 URL（仅 URL 字符串，不含 base64/图片内容） */
    private List<String> imageUrls;

    /** 图片张数 */
    private Integer imageCount;

    /** 部分图片未识别时的提示（零 DDL 存 exam_paper.remark；无则 null） */
    private String imageWarning;

    /** 题目总数 */
    private Integer questionCount;

    /** 正确题数 */
    private Integer correctCount;

    /** 已判定题数（未判定的题不计入分母） */
    private Integer judgedCount;

    /** 得分（1 题 1 分制 = correctCount） */
    private BigDecimal userScore;

    /** 总分（1 题 1 分制 = judgedCount） */
    private BigDecimal totalScore;

    private LocalDateTime createTime;

    /** 题目列表（按 question_index 升序；解析中/失败时可能为空数组） */
    private List<ExamQuestionItemVO> questions;
}
