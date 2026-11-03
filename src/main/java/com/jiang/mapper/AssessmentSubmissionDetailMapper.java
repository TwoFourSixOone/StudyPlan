package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.AssessmentSubmissionDetail;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评测答题明细 Mapper 接口
 * 使用 MyBatis-Plus 默认 CRUD，无需自定义 SQL
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Mapper
public interface AssessmentSubmissionDetailMapper extends BaseMapper<AssessmentSubmissionDetail> {
}
