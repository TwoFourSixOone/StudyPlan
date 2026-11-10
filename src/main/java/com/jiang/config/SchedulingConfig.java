package com.jiang.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务配置
 *
 * <p>启用 {@code @Scheduled}：每日 02:00 生成作业、02:00 清理过期 diff、03:00 知识点校对提醒、
 * 考试解析队列消费（1s 轮询）。</p>
 *
 * <p><b>为什么不在启动类上直接加 {@code @EnableScheduling}</b>：单元测试的
 * {@code @SpringBootTest} 会加载完整上下文，启用调度会让 {@code ExamAnalysisWorker}
 * 立即轮询 Redis、并触发外部依赖初始化，导致 49 个上下文测试失败。
 * 因此用 {@code @Profile("!test")} 在 test profile 下关闭调度，测试与生产各自干净。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
