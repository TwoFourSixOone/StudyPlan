package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 试卷题目表实体
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("exam_question")
public class ExamQuestion extends BaseEntity {

    /**
     * 题目 ID
     */
    @TableId("id")
    private Long id;

    /**
     * 试卷 ID
     */
    private Long examPaperId;

    /**
     * 题目序号
     */
    private Integer questionIndex;

    /**
     * 题目内容（OCR 识别结果）
     */
    private String questionContent;

    /**
     * 题目图片 URL（可选）
     */
    private String questionImageUrl;

    /**
     * 归因的知识点 ID（待人工归因时为 NULL）
     * NOTE-1：类型为 String，与 knowledge_point.knowledge_point_id 一致
     */
    private String knowledgePointId;

    /**
     * 归因状态：pending/manual_review/completed
     */
    private String attributionStatus;

    /**
     * 学生答案（学生手动录入）
     */
    private String studentAnswer;

    /**
     * 是否正确：0-错误，1-正确，NULL-未录入
     */
    private Integer isCorrect;

    /**
     * 得分（学生手动录入）
     */
    private BigDecimal score;

    /**
     * 满分（学生手动录入）
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
