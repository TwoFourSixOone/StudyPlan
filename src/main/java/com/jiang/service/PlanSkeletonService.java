package com.jiang.service;

/**
 * 每日计划骨架服务（产品规则 #1）
 *
 * <p>创建学习目标时调用：由规划 Agent 把目标周期拆成每日计划，
 * 批量写入 {@code daily_plan} 骨架（状态 {@code pending_generation}、题目待生成）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
public interface PlanSkeletonService {

    /**
     * 为目标生成整段计划骨架
     *
     * <p>幂等：已存在的日期跳过。规划 Agent 失败时回退为"按知识点均匀分配"的模板计划。</p>
     *
     * @param goalId   目标 ID
     * @param tenantId 租户 ID
     * @return 实际新建的计划条数
     */
    int generateSkeleton(Long goalId, String tenantId);
}
