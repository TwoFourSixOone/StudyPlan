package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 单题详情 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@Builder
public class ExamQuestionDetailVO {

    /**
     * 题目 ID
     */
    private Long questionId;

    /**
     * 题目序号
     */
    private Integer questionIndex;

    /**
     * 题目内容
     */
    private String questionContent;

    /**
     * 题目图片 URL
     */
    private String questionImageUrl;

    /**
     * 知识点 ID
     */
    private String knowledgePointId;

    /**
     * 知识点名称
     */
    private String knowledgePointName;

    /**
     * 归因状态：pending/manual_review/completed
     */
    private String attributionStatus;

    /**
     * 学生答案
     */
    private String studentAnswer;

    /**
     * 是否正确：0-错误，1-正确，NULL-未录入
     */
    private Integer isCorrect;

    /**
     * 得分
     */
    private BigDecimal score;

    /**
     * 满分
     */
    private BigDecimal fullScore;

    /**
     * 题型：choice/numeric/short
     */
    private String questionType;

    /**
     * 难度：basic/standard/challenge
     */
    private String difficulty;
}
