package com.jiang.client;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 全局 LLM 并发闸门（design D15 / S1）
 *
 * <p>线程池（examParseExecutor 3 + examAnalysisExecutor 3 + CallerRunsPolicy 的调用者线程）叠加后
 * 在途 LLM 调用可能远超 3，因此视觉解析、归因、判分三条链路共用同一个 {@link Semaphore}：
 * 无论调用来自哪个线程，<b>任一时刻在途 LLM 调用数 ≤ {@code exam.parse.grading-concurrency}</b>（默认 3）。</p>
 *
 * <p>死锁防护：闸门只在"单次 LLM 调用"这一最内层获取，不嵌套获取；等待时间受
 * "单图 60s / 单卷 180s / 判分 60s"预算约束（design §11.2）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Component
@Slf4j
public class LlmConcurrencyLimiter {

    /** 默认最大并发（与 design §11.2 的 exam.parse.grading-concurrency 保持一致） */
    public static final int DEFAULT_MAX_CONCURRENT = 3;

    private final Semaphore permits;

    private final int maxConcurrent;

    public LlmConcurrencyLimiter(
            @Value("${exam.parse.grading-concurrency:3}") int maxConcurrent) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        // 公平模式：避免高并发下个别调用线程长期饥饿
        this.permits = new Semaphore(this.maxConcurrent, true);
        log.info("LLM 全局并发闸门已创建: maxConcurrent={}", this.maxConcurrent);
    }

    /**
     * 在闸门内执行一次 LLM 调用
     *
     * @param action LLM 调用
     * @param <T>    返回类型
     * @return 调用结果
     */
    public <T> T call(Supplier<T> action) {
        long start = System.currentTimeMillis();
        acquire();
        long waitMs = System.currentTimeMillis() - start;
        // 排队可观测性（2026-11-12）：闸门被占满时，慢的是"等许可"而不是模型本身
        if (waitMs >= 1000) {
            log.warn("等待 LLM 并发许可 {}ms（闸门上限 {}，当前可用 {}）",
                    waitMs, maxConcurrent, permits.availablePermits());
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }

    /**
     * 获取一个许可（阻塞）。被中断时恢复中断标记并抛业务异常，不静默吞掉。
     */
    public void acquire() {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "等待 LLM 并发许可被中断");
        }
    }

    public void release() {
        permits.release();
    }

    /**
     * 当前可用许可数（≥0，观测用）
     */
    public int availablePermits() {
        return permits.availablePermits();
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }
}
