package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.KnowledgePointStandard;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识点-课程标准关联 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface KnowledgePointStandardMapper extends BaseMapper<KnowledgePointStandard> {
}
