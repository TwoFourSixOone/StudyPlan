package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 每日作业视图对象
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeworkVO {

    /** 题目 ID（question_bank.question_id 或 generated_question.generated_question_id） */
    private Long questionId;

    /** 题目来源：bank / generated */
    private String questionSource;

    /** 题目格式类型：choice / numeric / short / self（来自 question_bank.type 或 generated_question.content JSON） */
    private String questionFormatType;

    /** 任务类型：review / redo / similar / comprehensive */
    private String taskType;

    /** 题干内容 */
    private String questionContent;

    /** 选择题选项（仅 questionFormatType=choice 时非空） */
    private List<String> choices;

    /** 难度：basic / standard / challenge */
    private String difficulty;

    /** 关联知识点 ID */
    private String knowledgePointId;

    /** 关联知识点名称 */
    private String knowledgePointName;

    /** 学生答案（null 表示未提交，非 null 表示已提交） */
    private String studentAnswer;

    /** 是否正确 */
    private Boolean isCorrect;
}
