package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.PlanAdjustmentDiff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计划调整 diff Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Mapper
public interface PlanAdjustmentDiffMapper extends BaseMapper<PlanAdjustmentDiff> {

    /**
     * 按 goalId 和 status 统计数量（BLOCK-B 应用层防重）
     */
    @Select("SELECT COUNT(*) FROM plan_adjustment_diff " +
            "WHERE goal_id = #{goalId} AND status = #{status} AND tenant_id = #{tenantId}")
    int countByGoalIdAndStatus(@Param("goalId") Long goalId,
                               @Param("status") String status,
                               @Param("tenantId") String tenantId);

    /**
     * 批量更新过期 diff 状态（BLOCK-13 修复）
     * 修复 H-2：增加 tenant_id 隔离
     */
    @Update("UPDATE plan_adjustment_diff SET status = 'expired', " +
            "update_time = NOW() " +
            "WHERE expire_at < #{now} AND status = #{status} AND tenant_id = #{tenantId}")
    int batchExpire(@Param("now") LocalDateTime now,
                    @Param("status") String status,
                    @Param("tenantId") String tenantId);

    /**
     * 查询指定时间之前过期的 diff
     */
    @Select("SELECT * FROM plan_adjustment_diff " +
            "WHERE expire_at < #{now} AND status = #{status} " +
            "AND tenant_id = #{tenantId}")
    List<PlanAdjustmentDiff> selectByExpireAtBefore(@Param("now") LocalDateTime now,
                                                     @Param("status") String status,
                                                     @Param("tenantId") String tenantId);

    /**
     * 按 diffId 查询**属于指定学生**的 diff（学生端读/写入口的归属校验唯一查询）
     *
     * <p><b>必须 JOIN learning_goal（隐性约定 #44）</b>：{@code plan_adjustment_diff} 只有
     * {@code goal_id}，学生归属在 {@code learning_goal.student_id}。此前三个学生端入口只校验
     * {@code tenant_id}，同租户内学生 A 拿到 B 的 diffId 即可读取 B 的调整方案，
     * 甚至 confirm 后由 {@code applyDiff} 改写 B 的目标策略与未来 daily_plan。</p>
     *
     * <p>非本人 / 不存在 / 非本租户统一返回 {@code null}，由调用方抛
     * {@code PLAN_ADJUST_NOT_FOUND}（不区分存在性，避免探测他人数据）。</p>
     *
     * @param diffId    diff ID
     * @param studentId 当前登录学生 ID（服务端上下文，禁止取客户端入参）
     * @param tenantId  租户 ID
     * @return 归属当前学生的 diff；否则 {@code null}
     */
    @Select("SELECT d.id, d.goal_id, d.status, d.trigger_signals, d.diff_content, " +
            "d.expire_at, d.confirmed_at, d.rejected_at, d.tenant_id, " +
            "d.create_time, d.update_time " +
            "FROM plan_adjustment_diff d " +
            "JOIN learning_goal lg ON d.goal_id = lg.goal_id " +
            "WHERE d.id = #{diffId} " +
            "AND d.tenant_id = #{tenantId} " +
            "AND lg.student_id = #{studentId} " +
            "AND lg.tenant_id = #{tenantId}")
    PlanAdjustmentDiff selectByIdWithStudent(@Param("diffId") Long diffId,
                                             @Param("studentId") Long studentId,
                                             @Param("tenantId") String tenantId);
}
