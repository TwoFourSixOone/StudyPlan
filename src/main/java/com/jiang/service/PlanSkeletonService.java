package com.jiang.service;

/**
 * 每日计划骨架服务（产品规则 #1）
 *
 * <p>两段式：</p>
 * <ol>
 *   <li>{@link #generateSkeleton} —— 创建目标时**同步**写入模板骨架（纯 DB 操作，秒级）</li>
 *   <li>{@link #refineWithAgent} —— 后台**异步**调用规划 Agent 细化（LLM 可能超时，不阻塞创建）</li>
 * </ol>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
public interface PlanSkeletonService {

    /**
     * 生成模板骨架（同步，不调用 LLM）
     *
     * <p>按知识点 ID 顺序轮转分配到目标周期内的每一天，保证创建目标后立即有计划可看。
     * 幂等：已存在的日期跳过。</p>
     *
     * @return 新建计划条数
     */
    int generateSkeleton(Long goalId, String tenantId);

    /**
     * 调用规划 Agent 细化骨架（异步，失败静默返回 0）
     *
     * <p>只覆盖仍处于 {@code pending_generation} 的计划，不影响已开始/已完成的日期。</p>
     *
     * @return 更新的计划条数
     */
    int refineWithAgent(Long goalId, String tenantId);
}
