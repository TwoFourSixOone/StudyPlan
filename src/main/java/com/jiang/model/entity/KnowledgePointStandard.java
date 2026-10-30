package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 知识点-课程标准关联实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_point_standard")
public class KnowledgePointStandard extends BaseEntity {

    /**
     * 关联 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 知识点 ID
     */
    private String knowledgePointId;

    /**
     * 课程标准 ID
     */
    private Long standardId;
}
