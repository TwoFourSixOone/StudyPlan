package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评测答题明细实体（只读不删）
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("assessment_submission_detail")
public class AssessmentSubmissionDetail extends BaseEntity {

    /**
     * 答题明细 ID
     */
    @TableId("detail_id")
    private Long detailId;

    /**
     * 所属评测 ID
     */
    private Long assessmentId;

    /**
     * 学生 ID
     */
    private Long studentId;

    /**
     * 题目 ID（关联 question_bank）
     */
    private Long questionId;

    /**
     * 关联知识点
     */
    private String knowledgePointId;

    /**
     * 学生答案
     */
    private String studentAnswer;

    /**
     * 是否正确：0=错误, 1=正确
     */
    private Integer isCorrect;

    /**
     * 题目难度（冗余）：basic/standard/challenge
     */
    private String difficulty;

    /**
     * 提交时间
     */
    private LocalDateTime submittedAt;
}
