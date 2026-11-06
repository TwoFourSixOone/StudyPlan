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
}
