package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.PlanAdjustmentLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 计划调整日志 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface PlanAdjustmentLogMapper extends BaseMapper<PlanAdjustmentLog> {

    /**
     * 批量插入日志（BLOCK-13 修复）
     */
    @Insert("<script>" +
            "INSERT INTO plan_adjustment_log " +
            "(goal_id, diff_id, trigger_signals, diff_content, action, action_at, tenant_id, create_time, update_time) " +
            "VALUES " +
            "<foreach collection='logs' item='log' separator=','>" +
            "(#{log.goalId}, #{log.diffId}, #{log.triggerSignals}, #{log.diffContent}, " +
            "#{log.action}, #{log.actionAt}, #{log.tenantId}, NOW(), NOW())" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("logs") List<PlanAdjustmentLog> logs);

    /**
     * 统计指定时间窗口内"已确认"的调整次数（考试结算后 12h 信号调整去重，design §8.2/B2）
     *
     * <p>必须查 {@code action='confirmed'}：{@code PlanDiffServiceImpl.applyDiff} 实际写的是
     * confirmed（不新增 'applied'，D12）。</p>
     *
     * @param goalId   目标 ID
     * @param tenantId 租户 ID
     * @param since    窗口起点（now - dedupHours）
     * @return 命中条数
     */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM plan_adjustment_log " +
            "WHERE goal_id = #{goalId} AND tenant_id = #{tenantId} " +
            "AND action = 'confirmed' AND action_at >= #{since}")
    int countConfirmedSince(@Param("goalId") Long goalId,
                            @Param("tenantId") String tenantId,
                            @Param("since") java.time.LocalDateTime since);
}
