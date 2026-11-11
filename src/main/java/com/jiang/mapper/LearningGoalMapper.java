package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.LearningGoal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 学习目标 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface LearningGoalMapper extends BaseMapper<LearningGoal> {

    /**
     * 校验知识点是否被活跃学习目标引用
     * TODO: 当前 learning_goal 表无 knowledge_point_id 字段，暂返回 0
     * 后续可通过 daily_plan.content JSON 解析关联
     */
    @Select("SELECT 0")
    int countByKnowledgePointId(@Param("knowledgePointId") String knowledgePointId,
                                 @Param("tenantId") String tenantId);

    /**
     * 查询学生某科目下"进行中且覆盖指定日期"的学习目标（考试结算后信号检测用）
     *
     * @param studentId 学生 ID
     * @param tenantId  租户 ID
     * @param subjectId 科目 ID
     * @param today     参考日期（start_date ≤ today ≤ end_date）
     * @return 目标（无则 null）
     */
    @Select("SELECT goal_id, student_id, subject_id, target_score, direction, daily_question_limit, " +
            "paused_kp_ids, start_date, end_date, status, tenant_id, create_time, update_time " +
            "FROM learning_goal " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND subject_id = #{subjectId} AND status = 'active' " +
            "AND start_date <= #{today} AND end_date >= #{today} " +
            "ORDER BY goal_id DESC LIMIT 1")
    LearningGoal selectActiveByStudentSubject(@Param("studentId") Long studentId,
                                               @Param("tenantId") String tenantId,
                                               @Param("subjectId") Long subjectId,
                                               @Param("today") LocalDate today);
}
