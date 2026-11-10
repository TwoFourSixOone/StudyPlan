package com.jiang.task;

import com.jiang.service.ExamPaperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 试卷解析任务 Worker
 * 修复 IMP-10：使用线程池并发消费
 * 修复 BLOCK-5：Worker 同步调用 processExamAnalysis，不使用 @Async
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Component
@Slf4j
public class ExamAnalysisWorker {

    private static final String QUEUE_NAME = "exam_analysis_queue";

    /**
     * 上一次 Redis 告警时间（用于限流，避免每秒刷屏）
     */
    private LocalDateTime lastRedisWarnAt;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ExamPaperService examPaperService;

    @Autowired
    @Qualifier("examAnalysisExecutor")
    private ThreadPoolExecutor executor;

    /**
     * 定时消费任务队列
     * fixedDelay = 1000ms（每秒检查一次）
     */
    @Scheduled(fixedDelay = 1000)
    public void processTasks() {
        try {
            // 批量拉取任务（最多 3 个）
            for (int i = 0; i < 3; i++) {
                Object taskIdObj = redisTemplate.opsForList().rightPop(QUEUE_NAME);
                if (taskIdObj == null) {
                    break;
                }

                Long taskId;
                try {
                    taskId = Long.parseLong(taskIdObj.toString());
                } catch (NumberFormatException e) {
                    log.warn("任务 ID 格式错误: {}", taskIdObj);
                    continue;
                }

                // 提交到线程池执行
                executor.submit(() -> {
                    try {
                        log.info("开始处理试卷解析任务: taskId={}", taskId);
                        examPaperService.processExamAnalysis(taskId);
                        log.info("试卷解析任务完成: taskId={}", taskId);
                    } catch (Exception e) {
                        log.error("试卷解析任务失败: taskId={}", taskId, e);
                    }
                });
            }
        } catch (Exception e) {
            // @EnableScheduling 后该任务每秒执行一次；Redis 不可用时必须兜底，
            // 否则每秒抛异常刷屏。同类告警限流为每分钟一条。
            if (lastRedisWarnAt == null
                    || Duration.between(lastRedisWarnAt, LocalDateTime.now()).toMinutes() >= 1) {
                log.warn("考试解析队列不可用（Redis 连接失败），本轮跳过: {}", e.getMessage());
                lastRedisWarnAt = LocalDateTime.now();
            }
        }
    }
}
