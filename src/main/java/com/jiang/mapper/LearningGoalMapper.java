package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.LearningGoal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

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

    /**
     * 查询某学生的全部目标 ID（计划调整历史按学生过滤用）
     *
     * <p><b>必须带 {@code student_id}</b>（隐性约定 #44）：{@code plan_adjustment_log} 只有
     * {@code goal_id}，学生归属在 {@code learning_goal}。此前调整历史只按 {@code tenant_id} 分页，
     * 会返回同租户内所有学生的调整记录。</p>
     *
     * <p>不过滤 {@code status}：历史记录要能回溯已结束/已过期的目标，漏掉会让学生"看不到自己的调整"。</p>
     *
     * @param studentId 当前登录学生 ID
     * @param tenantId  租户 ID
     * @return 目标 ID 列表（无目标时为空列表）
     */
    @Select("SELECT goal_id FROM learning_goal " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "ORDER BY goal_id DESC")
    List<Long> selectGoalIdsByStudent(@Param("studentId") Long studentId,
                                      @Param("tenantId") String tenantId);

    /**
     * 校验目标归属（学生端写入口的越权防护：触发计划调整）
     *
     * <p>只返回 {@code goal_id}（存在即归属成立），供 {@code triggerAdjustment(Long)} 在
     * 调用系统重载前做「他人的 goalId → 目标不存在」判定；不区分存在性，避免探测他人目标。</p>
     *
     * @param goalId    目标 ID（来自客户端入参）
     * @param studentId 当前登录学生 ID（服务端上下文）
     * @param tenantId  租户 ID
     * @return 归属成立时返回 goal_id；否则 {@code null}
     */
    @Select("SELECT goal_id FROM learning_goal " +
            "WHERE goal_id = #{goalId} AND student_id = #{studentId} AND tenant_id = #{tenantId}")
    Long selectOwnedGoalId(@Param("goalId") Long goalId,
                           @Param("studentId") Long studentId,
                           @Param("tenantId") String tenantId);
}
