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
     *
     * <p><b>必须带 student_id（2026-11-19 修复）</b>：该表冗余了 student_id 列（含 idx_student 索引），
     * 作业读取整体按学生隔离——即使调用方误传他人 plan_id 也读不到别人的作答。</p>
     */
    @Select("SELECT s1.submission_id, s1.plan_id, s1.question_id, s1.question_source, " +
            "s1.question_type, s1.student_answer, s1.is_correct, " +
            "s1.knowledge_point_id, s1.submitted_at, s1.analysis, " +
            "s1.tenant_id, s1.create_time, s1.update_time " +
            "FROM homework_submission_detail s1 " +
            "INNER JOIN (" +
            "  SELECT question_id, MAX(submission_id) AS max_id " +
            "  FROM homework_submission_detail " +
            "  WHERE plan_id = #{planId} AND student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "  GROUP BY question_id" +
            ") s2 ON s1.submission_id = s2.max_id " +
            "WHERE s1.student_id = #{studentId} AND s1.tenant_id = #{tenantId}")
    List<HomeworkSubmissionDetail> selectByPlanId(@Param("planId") Long planId,
                                                  @Param("studentId") Long studentId,
                                                  @Param("tenantId") String tenantId);

    /**
     * 查询学生已做过的题库题目 ID（产品规则 #3：题库优先抽题时需排除）
     *
     * <p>路径：homework_submission_detail → daily_plan → learning_goal → student_id</p>
     *
     * @param studentId 学生 ID
     * @param tenantId  租户 ID
     * @return 已做过的 question_bank.question_id 列表
     */
    @Select("SELECT DISTINCT hsd.question_id FROM homework_submission_detail hsd " +
            "JOIN daily_plan dp ON dp.plan_id = hsd.plan_id AND dp.tenant_id = hsd.tenant_id " +
            "JOIN learning_goal lg ON lg.goal_id = dp.goal_id " +
            "WHERE lg.student_id = #{studentId} AND hsd.tenant_id = #{tenantId} " +
            "AND hsd.question_source = 'bank'")
    List<Long> selectAnsweredBankQuestionIds(@Param("studentId") Long studentId,
                                              @Param("tenantId") String tenantId);

    /**
     * 查询最近 N 天的作业提交正确率（SIG-1 信号检测）
     * 修复 H-5：增加 tenant_id 隔离
     *
     * @param goalId     目标 ID
     * @param windowDays 窗口天数
     * @param tenantId   租户 ID
     * @return 正确率列表（按日期升序）
     */
    @Select("SELECT COALESCE(SUM(is_correct) * 1.0 / COUNT(*), 0) AS accuracy " +
            "FROM homework_submission_detail d " +
            "JOIN daily_plan p ON d.plan_id = p.plan_id AND p.tenant_id = #{tenantId} " +
            "WHERE p.goal_id = #{goalId} " +
            "AND d.tenant_id = #{tenantId} " +
            "AND d.submitted_at >= DATE_SUB(CURDATE(), INTERVAL #{windowDays} DAY) " +
            "GROUP BY DATE(d.submitted_at) " +
            "ORDER BY DATE(d.submitted_at) ASC")
    List<Double> selectRecentAccuracy(@Param("goalId") Long goalId,
                                      @Param("windowDays") int windowDays,
                                      @Param("tenantId") String tenantId);

    /**
     * 按 ID 和学生 ID 查询作业提交详情（学习记录详情）
     */
    @Select("SELECT * FROM homework_submission_detail " +
            "WHERE submission_id = #{id} AND student_id = #{studentId} AND tenant_id = #{tenantId}")
    HomeworkSubmissionDetail selectByIdAndStudentId(@Param("id") Long id,
                                                     @Param("studentId") Long studentId,
                                                     @Param("tenantId") String tenantId);
}
