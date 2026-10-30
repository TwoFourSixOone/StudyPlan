package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.Student;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学生 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface StudentMapper extends BaseMapper<Student> {
}
