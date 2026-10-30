package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.KnowledgePointDependency;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识点先修关系 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface KnowledgePointDependencyMapper extends BaseMapper<KnowledgePointDependency> {
}
