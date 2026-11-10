package com.jiang.task;

import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.LearningGoal;
import com.jiang.service.HomeworkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DailyHomeworkGenerationTask 单元测试
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("每日作业定时生成任务")
class DailyHomeworkGenerationTaskTest {

    @Mock
    private LearningGoalMapper learningGoalMapper;

    @Mock
    private DailyPlanMapper dailyPlanMapper;

    @Mock
    private HomeworkService homeworkService;

    @InjectMocks
    private DailyHomeworkGenerationTask task;

    private static final String TENANT_ID = "default";

    private LearningGoal buildGoal(long goalId, long studentId, long subjectId) {
        LearningGoal goal = new LearningGoal();
        goal.setGoalId(goalId);
        goal.setStudentId(studentId);
        goal.setSubjectId(subjectId);
        goal.setTenantId(TENANT_ID);
        goal.setStatus("active");
        return goal;
    }

    @Test
    @DisplayName("无进行中目标 → 不生成")
    void should_doNothing_when_noActiveGoal() {
        when(learningGoalMapper.selectList(any())).thenReturn(List.of());

        task.generateTodayHomework();

        verifyNoInteractions(homeworkService);
    }

    @Test
    @DisplayName("当日计划已生成题目 → 跳过，不覆盖")
    void should_skip_when_planAlreadyGenerated() {
        when(learningGoalMapper.selectList(any())).thenReturn(List.of(buildGoal(10L, 1L, 100L)));
        DailyPlan generated = new DailyPlan();
        generated.setStatus("pending");
        generated.setContent("[{\"questionId\":1}]");
        when(dailyPlanMapper.selectByGoalIdAndDate(eq(10L), eq(TENANT_ID), any(LocalDate.class)))
                .thenReturn(generated);

        task.generateTodayHomework();

        verifyNoInteractions(homeworkService);
    }

    @Test
    @DisplayName("当日只有计划骨架（pending_generation）→ 继续出题")
    void should_generate_when_onlySkeletonExists() {
        when(learningGoalMapper.selectList(any())).thenReturn(List.of(buildGoal(10L, 1L, 100L)));
        DailyPlan skeleton = new DailyPlan();
        skeleton.setStatus("pending_generation");
        skeleton.setContent("[]");
        when(dailyPlanMapper.selectByGoalIdAndDate(eq(10L), eq(TENANT_ID), any(LocalDate.class)))
                .thenReturn(skeleton);

        task.generateTodayHomework();

        verify(homeworkService).generateDailyHomework(eq(1L), eq(TENANT_ID), eq(100L), any(LocalDate.class));
    }

    @Test
    @DisplayName("当日无计划 → 为每个目标生成一次")
    void should_generate_when_noPlanExists() {
        when(learningGoalMapper.selectList(any()))
                .thenReturn(List.of(buildGoal(10L, 1L, 100L), buildGoal(11L, 2L, 101L)));
        when(dailyPlanMapper.selectByGoalIdAndDate(anyLong(), eq(TENANT_ID), any(LocalDate.class)))
                .thenReturn(null);

        task.generateTodayHomework();

        verify(homeworkService).generateDailyHomework(eq(1L), eq(TENANT_ID), eq(100L), any(LocalDate.class));
        verify(homeworkService).generateDailyHomework(eq(2L), eq(TENANT_ID), eq(101L), any(LocalDate.class));
    }

    @Test
    @DisplayName("单个目标生成失败 → 不影响后续目标")
    void should_continue_when_oneGoalFails() {
        when(learningGoalMapper.selectList(any()))
                .thenReturn(List.of(buildGoal(10L, 1L, 100L), buildGoal(11L, 2L, 101L)));
        when(dailyPlanMapper.selectByGoalIdAndDate(anyLong(), eq(TENANT_ID), any(LocalDate.class)))
                .thenReturn(null);
        doThrow(new RuntimeException("agent down"))
                .when(homeworkService).generateDailyHomework(eq(1L), anyString(), anyLong(), any(LocalDate.class));

        task.generateTodayHomework();

        verify(homeworkService).generateDailyHomework(eq(1L), anyString(), anyLong(), any(LocalDate.class));
        verify(homeworkService).generateDailyHomework(eq(2L), eq(TENANT_ID), eq(101L), any(LocalDate.class));
    }
}
