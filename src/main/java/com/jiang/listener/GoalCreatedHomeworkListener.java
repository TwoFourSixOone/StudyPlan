package com.jiang.listener;

import com.jiang.common.util.BusinessDate;
import com.jiang.event.GoalCreatedEvent;
import com.jiang.security.StudentContext;
import com.jiang.service.HomeworkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 目标创建后自动生成"今日作业"
 *
 * <p>场景：目标开始日期就是今天时，02:00 的定时任务已经错过，
 * 若等明天才出题，学生会以为功能坏了。因此创建目标后立即异步生成当日作业。</p>
 *
 * <p>为什么用事件：{@code GoalServiceImpl → HomeworkService} 会形成循环依赖
 * （HomeworkServiceImpl 依赖 GoalService），事件解耦更安全。</p>
 *
 * <p>为什么在 AFTER_COMMIT：目标与计划骨架必须先提交，后台线程才查得到。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Component
@Slf4j
public class GoalCreatedHomeworkListener {

    @Autowired
    private HomeworkService homeworkService;

    @Autowired
    @Qualifier("planningExecutor")
    private ThreadPoolExecutor planningExecutor;

    /**
     * 目标创建完成（事务提交后触发）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onGoalCreated(GoalCreatedEvent event) {
        LocalDate today = BusinessDate.today();
        if (event.getStartDate() == null || !today.equals(event.getStartDate())) {
            log.info("目标开始日期不是今天，跳过即时出题: goalId={}, startDate={}",
                    event.getGoalId(), event.getStartDate());
            return;
        }

        try {
            planningExecutor.execute(() -> {
                try {
                    StudentContext.setStudentId(event.getStudentId());
                    StudentContext.setTenantId(event.getTenantId());
                    homeworkService.generateDailyHomework(
                            event.getStudentId(), event.getTenantId(), event.getSubjectId(), today);
                    log.info("目标今日开始，已自动生成今日作业: goalId={}", event.getGoalId());
                } catch (Exception e) {
                    // 失败不阻塞创建流程，02:00 定时任务会补
                    log.warn("目标创建后自动生成今日作业失败（可由手动生成或次日定时补偿）: goalId={}, error={}",
                            event.getGoalId(), e.getMessage());
                } finally {
                    StudentContext.clear();
                }
            });
        } catch (Exception e) {
            log.warn("提交今日作业生成任务失败: goalId={}, error={}", event.getGoalId(), e.getMessage());
        }
    }
}
