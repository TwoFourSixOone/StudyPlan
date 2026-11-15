package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 试卷诊断结果 VO（GET /api/student/exam/papers/{paperId}/diagnosis，新用户引导方案 A）
 *
 * <p>固定 4 条 SQL：① paper + 科目名（含归属校验）② 题目按卷（复用
 * {@code selectByPaperOrderByIndex}）③ 该卷来源的活跃错题数 ④ 薄弱知识点名称 + 当前掌握度批量；
 * 只读、不写库、不触发 LLM。1 题 1 分制（与 {@link ExamPaperDetailVO} 同口径），
 * 未判定题（{@code is_correct=NULL}）不计入正确率分母。</p>
 *
 * <p>试卷未解析完成 / 0 题时返回 200：各计数为 0、{@code accuracy=null}，不抛异常。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-15
 */
@Data
@Builder
public class ExamDiagnosisVO {

    /** 试卷 ID */
    private Long paperId;

    private Long subjectId;

    /** 科目名称 */
    private String subjectName;

    /** 考试日期 */
    private LocalDate examDate;

    /** 试卷类型 code（midterm/final/monthly/mock/other） */
    private String paperType;

    /** 题目总数（含未判定题） */
    private Integer totalQuestions;

    /** 已判定题数（不可判定题不计入分母） */
    private Integer judgedCount;

    /** 答对题数 */
    private Integer correctCount;

    /** 得分（1 题 1 分制 = correctCount） */
    private BigDecimal userScore;

    /** 总分（1 题 1 分制 = judgedCount） */
    private BigDecimal totalScore;

    /** 正确率 = correctCount / judgedCount（0–1，保留 4 位 HALF_UP；judged=0 时为 null，前端 ×100 渲染百分比） */
    private BigDecimal accuracy;

    /** 薄弱知识点（该卷答错数 > 0，按 wrongCount desc、masteryScore asc 取前 10；无则空数组） */
    private List<ExamDiagnosisWeakKpVO> weakKnowledgePoints;

    /** 该试卷来源、status=active 的错题数（约定 #26：exam 来源 question_id = -exam_question.id） */
    private Integer errorQuestionCount;

    /** 置信说明（固定文案，单份试卷样本量提示） */
    private String confidenceNote;
}
