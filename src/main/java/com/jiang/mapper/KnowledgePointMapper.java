package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.KnowledgePoint;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识点 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface KnowledgePointMapper extends BaseMapper<KnowledgePoint> {
}
