package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.LearningGoal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 学习目标 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface LearningGoalMapper extends BaseMapper<LearningGoal> {

    /**
     * 校验知识点是否被活跃学习目标引用
     * TODO: 当前 learning_goal 表无 knowledge_point_id 字段，暂返回 0
     * 后续可通过 daily_plan.content JSON 解析关联
     */
    @Select("SELECT 0")
    int countByKnowledgePointId(@Param("knowledgePointId") String knowledgePointId,
                                 @Param("tenantId") String tenantId);
}
