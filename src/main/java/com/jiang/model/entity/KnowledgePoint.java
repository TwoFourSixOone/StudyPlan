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
 * 知识点实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "knowledge_point", autoResultMap = true)
public class KnowledgePoint extends BaseEntity {

    /**
     * 知识点唯一 ID（不可复用）
     */
    @TableId("knowledge_point_id")
    private String knowledgePointId;

    /**
     * 科目（冗余字段，以 subject.name 为准）
     */
    private String subject;

    /**
     * 知识点名称
     */
    private String name;

    /**
     * 层级：1=章节, 2=知识点, 3=子知识点
     */
    private Integer level;

    /**
     * 父节点 ID
     */
    private String parentId;

    /**
     * 状态：pending_review / active / disabled
     */
    private String status;

    /**
     * 关联的课程标准 ID 数组（冗余存储，供 Agent 读取）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String standards;

    /**
     * 掌握度评判标准
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String masteryCriteria;

    /**
     * 诊断题目信息
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String diagnosticQuestion;
}
