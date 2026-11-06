package com.jiang.task;

import com.jiang.service.KnowledgePointGovernanceService;
import com.jiang.vo.KnowledgePointOverdueVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识点校对超期提醒定时任务
 * 修复 IMP-13：使用 7 个自然日（非工作日）
 * 修复 IMP-15：log.warn 输出结构化告警信息
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Slf4j
@Component
public class KnowledgePointReviewTask {

    @Autowired
    private KnowledgePointGovernanceService governanceService;

    /**
     * 每天凌晨 3 点检查超期未校对知识点
     * 修复 IMP-15：Demo 阶段使用 log.warn 输出结构化告警信息
     * 后续迭代可接入站内信/邮件/短信
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void checkOverdueKnowledgePoints() {
        List<KnowledgePointOverdueVO> overdueList = governanceService.getOverdueKnowledgePoints();
        if (overdueList.isEmpty()) {
            log.info("[KnowledgePointReview] 无超期未校对知识点");
            return;
        }

        // 修复 IMP-15：结构化告警日志
        for (KnowledgePointOverdueVO vo : overdueList) {
            log.warn("[ALERT] 知识点校对超期: id={}, name={}, subject={}, overdueDays={}",
                    vo.getId(), vo.getName(), vo.getSubjectName(), vo.getOverdueDays());
        }
        log.warn("[ALERT] 超期未校对知识点共 {} 条，请及时处理", overdueList.size());
    }
}
