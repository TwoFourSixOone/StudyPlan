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
 * 题库实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "question_bank", autoResultMap = true)
public class QuestionBank extends BaseEntity {

    /**
     * 题目 ID
     */
    @TableId("question_id")
    private Long questionId;

    /**
     * 关联知识点
     */
    private String knowledgePointId;

    /**
     * 题干内容（DB 列类型 TEXT，最大 64KB，当前 prompt 较短无风险）
     */
    private String content;

    /**
     * 选项（选择题用）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String choices;

    /**
     * 答案
     */
    private String answer;

    /**
     * 解析
     */
    private String analysis;

    /**
     * 难度：basic / standard / challenge
     */
    private String difficulty;

    /**
     * 题型：numeric / choice / short / self
     */
    private String type;
}
