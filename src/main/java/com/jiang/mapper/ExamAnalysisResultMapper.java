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

    /**
     * 结算写学习记录前的查重（一条试卷一条记录：exam_name 由 科目+类型中文+日期 组成）
     *
     * <p>走 idx_student (tenant_id, student_id) 扫描该学生的考试记录（≤ 数十条），零 DDL 下不新增索引。</p>
     *
     * @return 已存在的 completed 记录数
     */
    @Select("SELECT COUNT(*) FROM exam_analysis_result " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND exam_name = #{examName} AND status = 'completed'")
    int countCompletedByExamName(@Param("studentId") Long studentId,
                                  @Param("tenantId") String tenantId,
                                  @Param("examName") String examName);
}
