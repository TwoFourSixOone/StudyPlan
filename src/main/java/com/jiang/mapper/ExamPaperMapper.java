package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.ExamPaper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 试卷主表 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Mapper
public interface ExamPaperMapper extends BaseMapper<ExamPaper> {
}
