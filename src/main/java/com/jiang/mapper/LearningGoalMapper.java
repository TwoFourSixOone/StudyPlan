package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.LearningGoal;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学习目标 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface LearningGoalMapper extends BaseMapper<LearningGoal> {
}
