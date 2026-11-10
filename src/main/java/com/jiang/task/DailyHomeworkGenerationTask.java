package com.jiang.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.enums.GoalStatus;
import com.jiang.security.StudentContext;
import com.jiang.service.HomeworkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日作业自动生成任务
 *
 * <p>每天 02:00 为所有"进行中且日期覆盖今天"的学习目标生成当日每日作业。
 * 学生无需手动点击「生成今日作业」。</p>
 *
 * <p>幂等策略：当日计划已存在（任意状态）即跳过——凌晨重跑不能覆盖学生
 * 已作答或进行中的作业。多实例并发时另有 {@code daily_plan} 唯一索引兜底。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Component
@Slf4j
public class DailyHomeworkGenerationTask {

    @Autowired
    private LearningGoalMapper learningGoalMapper;

    @Autowired
    private DailyPlanMapper dailyPlanMapper;

    @Autowired
    private HomeworkService homeworkService;

    /**
     * 每天凌晨 2 点生成当日作业（显式指定时区，避免服务器默认 UTC 导致跑到北京时间 10:00）
     */
    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Shanghai")
    public void generateTodayHomework() {
        LocalDate today = LocalDate.now();
        log.info("[DailyHomework] 开始生成 {} 的每日作业", today);

        List<LearningGoal> goals;
        try {
            goals = learningGoalMapper.selectList(
                    new LambdaQueryWrapper<LearningGoal>()
                            .eq(LearningGoal::getStatus, GoalStatus.ACTIVE.getCode())
                            .le(LearningGoal::getStartDate, today)
                            .ge(LearningGoal::getEndDate, today)
            );
        } catch (Exception e) {
            log.error("[DailyHomework] 查询进行中目标失败，本次任务中止", e);
            return;
        }

        if (goals == null || goals.isEmpty()) {
            log.info("[DailyHomework] 无进行中的学习目标，跳过");
            return;
        }

        int success = 0;
        int skipped = 0;
        int failed = 0;

        for (LearningGoal goal : goals) {
            String tenantId = goal.getTenantId() == null ? "default" : goal.getTenantId();
            Long studentId = goal.getStudentId();
            Long subjectId = goal.getSubjectId();

            try {
                // 幂等：当日计划已存在则跳过（不覆盖已作答/进行中的作业）
                DailyPlan existing = dailyPlanMapper.selectByGoalIdAndDate(goal.getGoalId(), tenantId, today);
                if (existing != null) {
                    skipped++;
                    continue;
                }

                // 定时线程没有请求上下文，需显式设置，供 generateDailyHomework 内部取目标与年级
                StudentContext.setStudentId(studentId);
                StudentContext.setTenantId(tenantId);

                homeworkService.generateDailyHomework(studentId, tenantId, subjectId, today);
                success++;
                log.info("[DailyHomework] 生成成功: studentId={}, subjectId={}, goalId={}",
                        studentId, subjectId, goal.getGoalId());
            } catch (Exception e) {
                // 单个学生失败不影响其他学生
                failed++;
                log.error("[DailyHomework] 生成失败: studentId={}, subjectId={}, goalId={}, error={}",
                        studentId, subjectId, goal.getGoalId(), e.getMessage());
            } finally {
                StudentContext.clear();
            }
        }

        log.info("[DailyHomework] 当日作业生成完成: 目标总数={}, 成功={}, 跳过(已存在)={}, 失败={}",
                goals.size(), success, skipped, failed);
    }
}
