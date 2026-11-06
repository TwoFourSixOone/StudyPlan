package com.jiang.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 作业逐题详情 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class HomeworkQuestionDetailVO {

    /** 题目 ID */
    private Long questionId;

    /** 题目内容 */
    private String questionContent;

    /** 题目类型 */
    private String questionType;

    /** 学生答案 */
    private String studentAnswer;

    /** 是否正确 */
    private Boolean isCorrect;

    /** 知识点 ID */
    private String knowledgePointId;

    /** 知识点名称 */
    private String knowledgePointName;
}
