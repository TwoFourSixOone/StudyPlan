package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.vo.AdjustTriggerVO;
import com.jiang.vo.AdjustmentHistoryVO;
import com.jiang.vo.PlanDiffDetailVO;

/**
 * 计划调整服务接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface PlanAdjustmentService {

    /**
     * 触发调整（BLOCK-B：应用层防重）
     *
     * <p>P0-2 学生隔离：本入口读当前登录学生上下文，先校验 goal 归属
     * （{@code learning_goal.student_id + tenant_id}），他人/不存在的 goalId 统一
     * {@code PLAN_ADJUST_NOT_FOUND}（不区分存在性）后不再继续。</p>
     *
     * @param goalId 目标 ID
     * @return 触发结果
     */
    AdjustTriggerVO triggerAdjustment(Long goalId);

    /**
     * 触发调整（显式身份重载：Worker 线程无 StudentContext，design §8.2）
     *
     * <p><b>系统入口，不做归属校验</b>：调用方为定时任务与考试结算，目标与学生身份来自
     * {@code learning_goal} 本身（可信），本重载禁止读 {@code StudentContext}。
     * 学生端入口 {@link #triggerAdjustment(Long)} 必须先完成归属校验再委托到这里。</p>
     *
     * @param goalId    目标 ID
     * @param studentId 学生 ID（显式入参，不读 ThreadLocal）
     * @param tenantId  租户 ID（显式入参）
     * @return 触发结果
     */
    AdjustTriggerVO triggerAdjustment(Long goalId, Long studentId, String tenantId);

    /**
     * 确认 diff
     *
     * <p>P0-2 学生隔离：先经 {@code learning_goal.student_id} 校验 diff 归属当前登录学生，
     * 不存在/非本人/非本租户统一 {@code PLAN_ADJUST_NOT_FOUND}（不区分存在性）。</p>
     *
     * @param diffId diff ID
     */
    void confirmDiff(Long diffId);

    /**
     * 拒绝 diff
     *
     * <p>P0-2 学生隔离：归属校验口径同 {@link #confirmDiff(Long)}。</p>
     *
     * @param diffId diff ID
     */
    void rejectDiff(Long diffId);

    /**
     * 获取 diff 详情
     *
     * <p>P0-2 学生隔离：归属校验口径同 {@link #confirmDiff(Long)}，
     * 同租户内他人 diffId 不允许读出调整方案。</p>
     *
     * @param diffId diff ID
     * @return diff 详情
     */
    PlanDiffDetailVO getDiffDetail(Long diffId);

    /**
     * 获取调整历史
     *
     * <p>P0-2 学生隔离：只返回**当前登录学生**所属目标的调整日志
     * （{@code plan_adjustment_log} 无 student_id，经 {@code learning_goal} 反查 goalIds）；
     * 当前学生无目标时返回空分页，不得退化为全租户查询。</p>
     *
     * @param page 页码
     * @param size 每页大小
     * @return 分页结果
     */
    PageResult<AdjustmentHistoryVO> getAdjustmentHistory(Integer page, Integer size);
}
