package com.jiang.task;

import com.jiang.service.PlanDiffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 计划调整定时任务
 * 修复 IMP-24：每天凌晨 2 点执行过期 diff 清理
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Component
@Slf4j
public class PlanAdjustmentScheduledTask {

    @Autowired
    private PlanDiffService planDiffService;

    /**
     * 修复 IMP-24：每天凌晨 2 点执行过期 diff 清理
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanExpiredDiffs() {
        log.info("开始清理过期 plan diff...");
        try {
            int count = planDiffService.cleanExpiredDiffs();
            log.info("过期 plan diff 清理完成，处理 {} 条", count);
        } catch (Exception e) {
            log.error("过期 plan diff 清理失败", e);
        }
    }
}
