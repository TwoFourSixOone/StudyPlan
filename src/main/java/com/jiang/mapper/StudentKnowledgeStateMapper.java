package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.StudentKnowledgeState;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学生知识点掌握度状态 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface StudentKnowledgeStateMapper extends BaseMapper<StudentKnowledgeState> {
}
