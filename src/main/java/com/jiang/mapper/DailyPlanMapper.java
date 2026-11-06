package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.DailyPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日计划 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface DailyPlanMapper extends BaseMapper<DailyPlan> {

    /**
     * 查询指定日期作业计划（通过 goal_id + plan_date + tenant_id 定位）
     */
    @Select("SELECT plan_id, goal_id, plan_date, content, status, " +
            "tenant_id, create_time, update_time " +
            "FROM daily_plan " +
            "WHERE goal_id = #{goalId} AND tenant_id = #{tenantId} " +
            "AND plan_date = #{planDate}")
    DailyPlan selectByGoalIdAndDate(@Param("goalId") Long goalId,
                                    @Param("tenantId") String tenantId,
                                    @Param("planDate") LocalDate planDate);

    /**
     * 查询指定科目在指定日期的作业计划（不依赖活跃目标，支持查看历史作业）
     * 通过 JOIN learning_goal 表关联 subject_id
     */
    @Select("SELECT dp.plan_id, dp.goal_id, dp.plan_date, dp.content, dp.status, " +
            "dp.tenant_id, dp.create_time, dp.update_time " +
            "FROM daily_plan dp " +
            "JOIN learning_goal lg ON dp.goal_id = lg.goal_id " +
            "WHERE lg.subject_id = #{subjectId} AND dp.tenant_id = #{tenantId} " +
            "AND dp.plan_date = #{planDate} " +
            "ORDER BY dp.create_time DESC LIMIT 1")
    DailyPlan selectBySubjectAndDate(@Param("subjectId") Long subjectId,
                                      @Param("tenantId") String tenantId,
                                      @Param("planDate") LocalDate planDate);

    /**
     * 更新作业状态（状态机校验：WHERE status = #{expectedStatus}）
     * V1 无 version 字段，通过状态机防止并发冲突
     */
    @Update("UPDATE daily_plan SET status = #{newStatus}, update_time = NOW() " +
            "WHERE plan_id = #{planId} AND tenant_id = #{tenantId} " +
            "AND status = #{expectedStatus}")
    int updateStatusWithCheck(@Param("planId") Long planId,
                              @Param("newStatus") String newStatus,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("tenantId") String tenantId);

    /**
     * 更新作业内容和状态（状态机校验：仅在 pending_generation 状态允许更新）
     */
    @Update("UPDATE daily_plan SET content = #{content}, status = #{newStatus}, " +
            "update_time = NOW() " +
            "WHERE plan_id = #{planId} AND tenant_id = #{tenantId} " +
            "AND status = #{expectedStatus}")
    int updateContentAndStatus(@Param("planId") Long planId,
                               @Param("content") String content,
                               @Param("newStatus") String newStatus,
                               @Param("expectedStatus") String expectedStatus,
                               @Param("tenantId") String tenantId);

    /**
     * 查询最近 30 天内未完成作业的日期列表（SIG-3 信号检测）
     * 修复 Sev-6：返回日期列表，由 Service 层计算最大连续天数
     *
     * @param goalId   目标 ID
     * @param tenantId 租户 ID
     * @return 未完成的日期列表（按日期升序）
     */
    @Select("SELECT plan_date FROM daily_plan " +
            "WHERE goal_id = #{goalId} " +
            "AND tenant_id = #{tenantId} " +
            "AND status = 'incomplete' " +
            "AND plan_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) " +
            "ORDER BY plan_date ASC")
    List<LocalDate> selectIncompleteDays(@Param("goalId") Long goalId,
                                          @Param("tenantId") String tenantId);
}
