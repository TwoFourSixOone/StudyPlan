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
}
