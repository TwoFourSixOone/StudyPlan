package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.QuestionBank;
import org.apache.ibatis.annotations.Mapper;

/**
 * 题库 Mapper 接口
 * <p>
 * 注意：content 列类型为 TEXT（最大 64KB），当前 prompt 较短无风险，
 * 若后续题干内容增长需关注容量上限。
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface QuestionBankMapper extends BaseMapper<QuestionBank> {
}
