package com.jiang.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jiang.common.util.BusinessDate;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.enums.GoalStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 学习目标到期任务（Change: 2026-11-23_goal_expiry_and_homework_hint）
 *
 * <p>每天 00:05 把「进行中但已过结束日期」的目标批量置为 {@code expired}。
 * 此前 {@link GoalStatus#EXPIRED} 只有枚举、没有任何流转代码，过期目标会一直显示 {@code active}，
 * 进而被前端当成"可生成作业"的目标（后端 {@code getActiveGoals} 虽有 end_date 过滤，但前端会先报错）。</p>
 *
 * <p>幂等：只更新 {@code status=active} 的行，重复执行影响 0 行。</p>
 *
 * <p>租户：任务线程无请求上下文，租户插件按 {@code TenantHandler} 回退 {@code default}
 * （与 {@code DailyHomeworkGenerationTask} 一致；真实多租户需按租户遍历，见隐性约定）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-23
 */
@Component
@Slf4j
public class GoalExpiryTask {

    @Autowired
    private LearningGoalMapper learningGoalMapper;

    /**
     * 每日 00:05 执行：把过期且仍 active 的目标置为 expired
     */
    @Scheduled(cron = "0 5 0 * * ?")
    public void expireOverdueGoals() {
        expireOverdueGoals(BusinessDate.today());
    }

    /**
     * 到期流转（包级可见，便于单测按指定日期执行）
     *
     * @param today 业务日期
     * @return 实际更新行数
     */
    int expireOverdueGoals(LocalDate today) {
        try {
            LambdaQueryWrapper<LearningGoal> wrapper = new LambdaQueryWrapper<LearningGoal>()
                    .eq(LearningGoal::getStatus, GoalStatus.ACTIVE.getCode())
                    .lt(LearningGoal::getEndDate, today);

            LearningGoal patch = new LearningGoal();
            patch.setStatus(GoalStatus.EXPIRED.getCode());

            int updated = learningGoalMapper.update(patch, wrapper);
            if (updated > 0) {
                log.info("目标到期流转完成: {} 个目标置为 expired（结束日期早于 {}）", updated, today);
            } else {
                log.debug("目标到期流转：无到期目标（{}）", today);
            }
            return updated;
        } catch (Exception e) {
            // 定时任务异常不能影响调度线程（与其它任务一致：只记日志）
            log.warn("目标到期流转失败（跳过本轮）: {}", e.getMessage());
            return 0;
        }
    }
}
