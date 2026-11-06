package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.ExamAnalysisResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 试卷分析结果 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface ExamAnalysisResultMapper extends BaseMapper<ExamAnalysisResult> {

    /**
     * 按 ID 和学生 ID 查询考试分析结果（学习记录详情）
     */
    @Select("SELECT * FROM exam_analysis_result " +
            "WHERE analysis_id = #{id} AND student_id = #{studentId} AND tenant_id = #{tenantId}")
    ExamAnalysisResult selectByIdAndStudentId(@Param("id") Long id,
                                               @Param("studentId") Long studentId,
                                               @Param("tenantId") String tenantId);
}
