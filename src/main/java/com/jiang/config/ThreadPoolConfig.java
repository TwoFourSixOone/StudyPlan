package com.jiang.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池统一管理配置
 * <p>
 * 所有业务线程池在此集中定义，避免在各 Service/Worker 中分散创建。
 * 统一命名、统一异常处理。
 * <p>
 * 关键设计决策：
 * - 使用有界队列 + CallerRunsPolicy，防止 OOM
 * - 非守护线程，确保 JVM 退出前任务能完成
 * - 统一 UncaughtExceptionHandler，防止异常静默吞掉
 * - 两个线程池相互独立，无任务依赖关系
 * - 关闭逻辑由 ExecutorShutdownManager 统一管理，配置类不自引用
 *
 * @author StudyMate Team
 * @since 2026-11-06
 */
@Configuration
@Slf4j
public class ThreadPoolConfig {

    // ==================== AI 调用线程池 ====================

    @Value("${thread-pool.ai.core-size:4}")
    private int aiCoreSize;

    @Value("${thread-pool.ai.queue-capacity:50}")
    private int aiQueueCapacity;

    /**
     * AI 调用线程池
     * 用于作业批改中的 AI 并行调用（解析生成 + 非选择题批改）
     * <p>
     * 拒绝策略：CallerRunsPolicy
     * - Web 请求线程同步执行 AI 调用，响应时间可能延长
     * - 业务可接受此背压行为（AI 调用慢时自然限流）
     */
    @Bean(name = "aiExecutor", destroyMethod = "")
    public ThreadPoolExecutor aiExecutor() {
        return createExecutor("ai-worker", aiCoreSize, aiCoreSize, aiQueueCapacity);
    }

    // ==================== 试卷解析任务线程池 ====================

    @Value("${thread-pool.exam-analysis.core-size:3}")
    private int examAnalysisCoreSize;

    @Value("${thread-pool.exam-analysis.queue-capacity:30}")
    private int examAnalysisQueueCapacity;

    /**
     * 试卷解析任务线程池
     * 用于 ExamAnalysisWorker 消费 Redis 队列中的解析任务
     * <p>
     * 拒绝策略：CallerRunsPolicy
     * - Worker 调度线程同步执行解析任务，消费节奏可能变慢
     * - 消息未 ACK 会重投，配合监控告警可及时发现积压
     */
    @Bean(name = "examAnalysisExecutor", destroyMethod = "")
    public ThreadPoolExecutor examAnalysisExecutor() {
        return createExecutor("exam-analysis-worker", examAnalysisCoreSize, examAnalysisCoreSize,
                examAnalysisQueueCapacity);
    }

    // ==================== 优雅关闭管理器 ====================

    /**
     * 创建线程池关闭管理器
     * 通过参数注入绑定"池引用 + 等待秒数"，避免字段散落配对错误
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorShutdownManager executorShutdownManager(
            @Qualifier("aiExecutor") ThreadPoolExecutor aiExecutor,
            @Qualifier("examAnalysisExecutor") ThreadPoolExecutor examAnalysisExecutor,
            @Value("${thread-pool.ai.await-terminate-seconds:30}") int aiWaitSec,
            @Value("${thread-pool.exam-analysis.await-terminate-seconds:60}") int examWaitSec) {
        return new ExecutorShutdownManager(Map.of(
                "aiExecutor", new PoolRef(aiExecutor, aiWaitSec),
                "examAnalysisExecutor", new PoolRef(examAnalysisExecutor, examWaitSec)));
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建有界线程池
     *
     * @param namePrefix    线程名前缀
     * @param coreSize      核心线程数
     * @param maxSize       最大线程数
     * @param queueCapacity 有界队列容量
     */
    private ThreadPoolExecutor createExecutor(String namePrefix, int coreSize, int maxSize, int queueCapacity) {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreSize, maxSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, namePrefix + "-" + counter.incrementAndGet());
                    // 非守护线程：确保 JVM 退出前任务能完成
                    t.setDaemon(false);
                    // 统一未捕获异常处理
                    t.setUncaughtExceptionHandler((thread, ex) ->
                            log.error("线程池任务未捕获异常: thread={}, pool={}", thread.getName(), namePrefix, ex));
                    return t;
                },
                // 队列满时由调用者线程执行，防止任务丢失 + 背压
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        // 保留核心线程常驻，避免反复创建销毁开销
        // executor.allowCoreThreadTimeOut(true); // 已禁用
        log.info("线程池已创建: prefix={}, coreSize={}, max={}, queueCapacity={}",
                namePrefix, coreSize, maxSize, queueCapacity);
        return executor;
    }

    // ==================== 内部类 ====================

    /**
     * 线程池引用 + 等待秒数的绑定结构
     */
    record PoolRef(ThreadPoolExecutor executor, int awaitSeconds) {}

    /**
     * 线程池关闭管理器
     * 负责在容器关闭时优雅终止所有注册的线程池
     */
    static class ExecutorShutdownManager {

        private final Map<String, PoolRef> pools;

        ExecutorShutdownManager(Map<String, PoolRef> pools) {
            this.pools = pools;
        }

        @PreDestroy
        public void shutdown() {
            for (Map.Entry<String, PoolRef> entry : pools.entrySet()) {
                String name = entry.getKey();
                ThreadPoolExecutor executor = entry.getValue().executor();
                int waitSec = entry.getValue().awaitSeconds();

                log.info("开始关闭线程池: {}, 活跃线程={}, 队列任务={}",
                        name, executor.getActiveCount(), executor.getQueue().size());

                executor.shutdown();
                try {
                    if (!executor.awaitTermination(waitSec, TimeUnit.SECONDS)) {
                        log.warn("线程池 {} 在 {}s 内未完全终止，强制关闭", name, waitSec);
                        executor.shutdownNow();
                        // shutdownNow 后通常不会再有任务完成，无需二次等待
                    } else {
                        log.info("线程池 {} 已优雅关闭", name);
                    }
                } catch (InterruptedException e) {
                    log.error("线程池 {} 关闭被中断", name, e);
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
