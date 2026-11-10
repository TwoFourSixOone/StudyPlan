package com.jiang.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 规划线程池
 *
 * <p>创建目标时先用模板骨架保证「秒级可计划」，再由该线程池异步调用规划 Agent 细化，
 * 避免 LLM 超时（PT2M）阻塞目标创建接口。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Configuration
public class PlanningConfig {

    /**
     * 规划细化线程池：单线程足够（同一时刻极少有多个目标同时创建），队列兜底
     */
    @Bean(name = "planningExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor planningExecutor() {
        AtomicInteger seq = new AtomicInteger(1);
        return new ThreadPoolExecutor(
                1, 2,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(20),
                r -> {
                    Thread t = new Thread(r, "planning-refine-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
