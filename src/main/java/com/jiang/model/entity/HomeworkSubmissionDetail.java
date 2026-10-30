package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 作业提交明细实体（只读不删）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("homework_submission_detail")
public class HomeworkSubmissionDetail extends BaseEntity {

    /**
     * 提交 ID
     */
    @TableId("submission_id")
    private Long submissionId;

    /**
     * 所属计划 ID
     */
    private Long planId;

    /**
     * 题目 ID（根据 questionSource 关联 question_bank 或 generated_question）
     */
    private Long questionId;

    /**
     * 题目来源：bank / generated
     */
    private String questionSource;

    /**
     * 任务类型：review / redo / similar / comprehensive
     */
    private String questionType;

    /**
     * 学生答案
     */
    private String studentAnswer;

    /**
     * 是否正确：0=错误, 1=正确
     */
    private Integer isCorrect;

    /**
     * 关联知识点
     */
    private String knowledgePointId;

    /**
     * 提交时间
     */
    private LocalDateTime submittedAt;
}
