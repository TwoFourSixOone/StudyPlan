package com.jiang.task;

import com.jiang.service.ExamPaperService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 试卷解析 Worker 拉取节流测试（design §11.2 / tasks A3，fix t23）
 *
 * <p>核心：{@code activeCount >= coreSize} 时不得继续 {@code pop+submit}，
 * 否则队列元素会被 CallerRunsPolicy 拉到调度线程上跑长任务。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamAnalysisWorker 拉取节流")
class ExamAnalysisWorkerTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ListOperations<String, Object> listOperations;
    private ExamPaperService examPaperService;
    private ThreadPoolExecutor executor;
    private ExamAnalysisWorker worker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        listOperations = mock(ListOperations.class);
        examPaperService = mock(ExamPaperService.class);
        executor = mock(ThreadPoolExecutor.class);
        worker = new ExamAnalysisWorker();
        ReflectionTestUtils.setField(worker, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(worker, "examPaperService", examPaperService);
        ReflectionTestUtils.setField(worker, "executor", executor);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
    }

    @Test
    @DisplayName("线程池已满（active >= core）→ 不拉取队列（留待下一轮）")
    void should_notPop_when_poolSaturated() {
        when(executor.getCorePoolSize()).thenReturn(3);
        when(executor.getActiveCount()).thenReturn(3);

        worker.processTasks();

        verify(listOperations, never()).rightPop(anyString());
        verify(executor, never()).submit(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    @DisplayName("有空闲线程（active < core）→ 正常拉取并提交")
    void should_popAndSubmit_when_capacityAvailable() {
        when(executor.getCorePoolSize()).thenReturn(3);
        when(executor.getActiveCount()).thenReturn(1);
        when(listOperations.rightPop("exam_analysis_queue")).thenReturn("42").thenReturn(null);

        worker.processTasks();

        verify(listOperations, org.mockito.Mockito.times(2)).rightPop("exam_analysis_queue");
        verify(executor).submit(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    @DisplayName("队列为空 → 立即结束（不空转）")
    void should_stop_when_queueEmpty() {
        when(executor.getCorePoolSize()).thenReturn(3);
        when(executor.getActiveCount()).thenReturn(0);
        when(listOperations.rightPop("exam_analysis_queue")).thenReturn(null);

        worker.processTasks();

        verify(listOperations).rightPop("exam_analysis_queue");
        verify(executor, never()).submit(org.mockito.ArgumentMatchers.any(Runnable.class));
    }
}
