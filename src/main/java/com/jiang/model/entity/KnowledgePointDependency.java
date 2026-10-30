package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 知识点先修关系实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_point_dependency")
public class KnowledgePointDependency extends BaseEntity {

    /**
     * 关系 ID
     */
    @TableId("dependency_id")
    private Long dependencyId;

    /**
     * 知识点 ID
     */
    private String knowledgePointId;

    /**
     * 先修知识点 ID
     */
    private String prerequisiteId;

    /**
     * 关系类型：hard / soft
     */
    private String dependencyType;
}
