package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Agent 生成新题实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "generated_question", autoResultMap = true)
public class GeneratedQuestion extends BaseEntity {

    /**
     * 新题 ID
     */
    @TableId("generated_question_id")
    private Long generatedQuestionId;

    /**
     * 所属每日计划 ID
     */
    private Long dailyPlanId;

    /**
     * 关联知识点
     */
    private String knowledgePointId;

    /**
     * 触发生成的原错题 ID（可为空）
     */
    private Long sourceQuestionId;

    /**
     * 题目内容
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String content;

    /**
     * Agent 声明难度：basic / standard / challenge
     */
    private String difficulty;

    /**
     * 是否通过相似度校验：0=未通过, 1=已通过
     */
    private Integer similarityChecked;

    /**
     * 题干内容哈希（SimHash，用于快速相似度比较）
     * Change: 2026-11-03_similar_question_generation
     */
    private String contentHash;
}
