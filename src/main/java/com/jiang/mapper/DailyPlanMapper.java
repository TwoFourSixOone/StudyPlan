package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.DailyPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

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
}
