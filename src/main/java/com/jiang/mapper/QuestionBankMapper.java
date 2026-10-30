package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.QuestionBank;
import org.apache.ibatis.annotations.Mapper;

/**
 * 题库 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface QuestionBankMapper extends BaseMapper<QuestionBank> {
}
