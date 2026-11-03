package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.HomeworkSubmissionDetail;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 作业提交明细 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface HomeworkSubmissionDetailMapper extends BaseMapper<HomeworkSubmissionDetail> {

    /**
     * 插入或更新提交记录（覆盖语义，基于唯一约束 uk_plan_question）
     * 包含 question_source、question_type、submitted_at 字段
     */
    @Insert("INSERT INTO homework_submission_detail " +
            "(plan_id, question_id, question_source, question_type, " +
            "student_answer, is_correct, knowledge_point_id, submitted_at, " +
            "student_id, tenant_id, create_time, update_time) " +
            "VALUES (#{planId}, #{questionId}, #{questionSource}, #{questionType}, " +
            "#{studentAnswer}, #{isCorrect}, #{knowledgePointId}, #{submittedAt}, " +
            "#{studentId}, #{tenantId}, #{createTime}, #{updateTime}) " +
            "ON DUPLICATE KEY UPDATE " +
            "student_answer = VALUES(student_answer), " +
            "is_correct = VALUES(is_correct), " +
            "submitted_at = VALUES(submitted_at), " +
            "update_time = VALUES(update_time)")
    int insertOrUpdate(HomeworkSubmissionDetail submission);

    /**
     * 查询当日提交记录（按 question_id 去重，取最新 submission_id）
     */
    @Select("SELECT s1.submission_id, s1.plan_id, s1.question_id, s1.question_source, " +
            "s1.question_type, s1.student_answer, s1.is_correct, " +
            "s1.knowledge_point_id, s1.submitted_at, " +
            "s1.tenant_id, s1.create_time, s1.update_time " +
            "FROM homework_submission_detail s1 " +
            "INNER JOIN (" +
            "  SELECT question_id, MAX(submission_id) AS max_id " +
            "  FROM homework_submission_detail " +
            "  WHERE plan_id = #{planId} AND tenant_id = #{tenantId} " +
            "  GROUP BY question_id" +
            ") s2 ON s1.submission_id = s2.max_id")
    List<HomeworkSubmissionDetail> selectByPlanId(@Param("planId") Long planId,
                                                  @Param("tenantId") String tenantId);
}
