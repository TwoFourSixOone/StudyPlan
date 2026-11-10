package com.jiang.listener;

import com.jiang.event.GoalCreatedEvent;
import com.jiang.service.HomeworkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.LocalDate;
import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GoalCreatedHomeworkListener 单元测试（目标今天开始 → 立即生成今日作业）
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("目标创建后即时生成今日作业")
class GoalCreatedHomeworkListenerTest {

    @Mock
    private HomeworkService homeworkService;

    @Mock
    private ThreadPoolExecutor planningExecutor;

    @InjectMocks
    private GoalCreatedHomeworkListener listener;

    private static final Long GOAL_ID = 100L;
    private static final Long STUDENT_ID = 1L;
    private static final Long SUBJECT_ID = 10L;
    private static final String TENANT_ID = "default";

    private GoalCreatedEvent event(LocalDate startDate) {
        return new GoalCreatedEvent(this, GOAL_ID, STUDENT_ID, SUBJECT_ID, TENANT_ID, startDate);
    }

    @Test
    @DisplayName("开始日期是今天 → 提交即时出题任务")
    void should_generateTodayHomework_when_startDateIsToday() {
        listener.onGoalCreated(event(LocalDate.now()));

        verify(planningExecutor).execute(any());
    }

    @Test
    @DisplayName("开始日期不是今天 → 不提交任务")
    void should_skip_when_startDateIsNotToday() {
        listener.onGoalCreated(event(LocalDate.now().plusDays(1)));

        verifyNoInteractions(planningExecutor, homeworkService);
    }

    @Test
    @DisplayName("任务体：设置上下文后调用生成今日作业")
    void should_callHomeworkService_when_taskRuns() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        listener.onGoalCreated(event(LocalDate.now()));
        verify(planningExecutor).execute(captor.capture());

        captor.getValue().run();

        verify(homeworkService).generateDailyHomework(eq(STUDENT_ID), eq(TENANT_ID), eq(SUBJECT_ID),
                any(LocalDate.class));
    }

    @Test
    @DisplayName("生成失败 → 不抛异常（由定时任务/手动补偿）")
    void should_swallowFailure() {
        doThrow(new RuntimeException("agent down")).when(homeworkService)
                .generateDailyHomework(anyLong(), anyString(), anyLong(), any(LocalDate.class));
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        listener.onGoalCreated(event(LocalDate.now()));
        verify(planningExecutor).execute(captor.capture());

        captor.getValue().run();

        verify(homeworkService).generateDailyHomework(anyLong(), anyString(), anyLong(), any(LocalDate.class));
    }
}
