package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.GeneratedQuestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 生成新题 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface GeneratedQuestionMapper extends BaseMapper<GeneratedQuestion> {
}
