package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 试卷详情-单题（design §4.2：与作业答题区字段对齐，前端只读渲染）
 *
 * <p>零 DDL：{@code stem/choices/correctAnswer/explanation} 由
 * {@code ExamQuestionTextCodec} 从 {@code question_content} 解码得到；
 * {@code choices} 只放文本，label 由前端按序号渲染（约定 #25）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ExamQuestionItemVO {

    /** 题目 ID（exam_question.id） */
    private Long questionId;

    /** 题号（1..N，升序稳定） */
    private Integer questionIndex;

    /** 题型：choice/numeric/short/self */
    private String questionType;

    /** 难度：basic/standard/challenge（可为 null） */
    private String difficulty;

    /** 题干（解码后的纯文本；与 questionContent 同值，兼容旧字段名） */
    private String questionContent;

    /** 题干（解码） */
    private String stem;

    /** 选项文本列表（选择题；非选择题为空数组） */
    private List<String> choices;

    /** 标准答案（选择题为选项标签；缺失为 null） */
    private String correctAnswer;

    /** 解析（纯文本） */
    private String explanation;

    /** 学生作答 */
    private String studentAnswer;

    /** 是否答对：null = 无法判定/待人工 */
    private Boolean isCorrect;

    /** 得分（1 题 1 分制；无法判定为 null） */
    private BigDecimal score;

    /** 满分（1 题 1 分制；无法判定为 null） */
    private BigDecimal fullScore;

    /** 知识点 ID（待人工归因时为 null） */
    private String knowledgePointId;

    /** 知识点名称 */
    private String knowledgePointName;

    /** 归因状态：completed/pending_manual_review */
    private String attributionStatus;

    /** 作答展示态：blank（未作答）/ unreadable（无法辨识，待人工判定）/ judged（已判定） */
    private String answerState;
}
