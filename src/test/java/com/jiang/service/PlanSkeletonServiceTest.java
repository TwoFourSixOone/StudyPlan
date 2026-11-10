package com.jiang.service;

import com.jiang.agent.PlanningAgent;
import com.jiang.dto.PlanDraft;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.Subject;
import com.jiang.service.impl.PlanSkeletonServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PlanSkeletonServiceImpl 单元测试（产品规则 #1）
 *
 * <p>两段式：模板骨架同步落库；Agent 细化异步执行且失败静默。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("计划骨架生成")
class PlanSkeletonServiceTest {

    @Mock
    private LearningGoalMapper goalMapper;
    @Mock
    private SubjectMapper subjectMapper;
    @Mock
    private KnowledgePointMapper knowledgePointMapper;
    @Mock
    private StudentKnowledgeStateMapper stateMapper;
    @Mock
    private StudentMapper studentMapper;
    @Mock
    private DailyPlanMapper dailyPlanMapper;
    @Mock
    private PlanningAgent planningAgent;

    @InjectMocks
    private PlanSkeletonServiceImpl planSkeletonService;

    private static final Long GOAL_ID = 100L;
    private static final Long STUDENT_ID = 1L;
    private static final Long SUBJECT_ID = 10L;
    private static final String TENANT_ID = "default";
    private static final LocalDate START = LocalDate.of(2026, 11, 11);
    private static final LocalDate END = LocalDate.of(2026, 11, 12);

    private LearningGoal buildGoal() {
        LearningGoal goal = new LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setStudentId(STUDENT_ID);
        goal.setSubjectId(SUBJECT_ID);
        goal.setStartDate(START);
        goal.setEndDate(END);
        goal.setStatus("active");
        goal.setTenantId(TENANT_ID);
        return goal;
    }

    private KnowledgePoint buildKp(String id) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setKnowledgePointId(id);
        kp.setName("知识点" + id);
        kp.setSubject("数学");
        kp.setStatus("active");
        kp.setTenantId(TENANT_ID);
        return kp;
    }

    private void stubBase() {
        when(goalMapper.selectById(GOAL_ID)).thenReturn(buildGoal());
        Subject subject = new Subject();
        subject.setSubjectId(SUBJECT_ID);
        subject.setName("数学");
        when(subjectMapper.selectById(SUBJECT_ID)).thenReturn(subject);
        when(studentMapper.selectById(STUDENT_ID)).thenReturn(null);
        when(knowledgePointMapper.selectList(any())).thenReturn(List.of(buildKp("kp_001"), buildKp("kp_002")));
        when(stateMapper.selectActiveStatesWithKpInfo(anyLong(), eq(TENANT_ID), anyString(), eq(false)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("模板骨架：每个日期一行，秒级完成且不调用 Agent")
    void should_insertTemplateSkeleton_withoutAgent() {
        stubBase();
        when(dailyPlanMapper.selectByGoalIdAndDate(anyLong(), eq(TENANT_ID), any(LocalDate.class)))
                .thenReturn(null);

        int inserted = planSkeletonService.generateSkeleton(GOAL_ID, TENANT_ID);

        assertEquals(2, inserted, "11-11 ~ 11-12 共 2 天");
        verify(dailyPlanMapper, times(2)).insert(any(DailyPlan.class));
        verifyNoInteractions(planningAgent);
    }

    @Test
    @DisplayName("模板骨架：已存在的日期跳过")
    void should_skipExistingDates_when_insertingTemplate() {
        stubBase();
        when(dailyPlanMapper.selectByGoalIdAndDate(anyLong(), eq(TENANT_ID), any(LocalDate.class)))
                .thenReturn(new DailyPlan());

        int inserted = planSkeletonService.generateSkeleton(GOAL_ID, TENANT_ID);

        assertEquals(0, inserted);
        verify(dailyPlanMapper, never()).insert(any(DailyPlan.class));
    }

    @Test
    @DisplayName("Agent 细化：只更新待生成的计划")
    void should_refineExistingPendingPlan() {
        stubBase();
        PlanDraft.PlanDay day = new PlanDraft.PlanDay();
        day.setDate(START);
        day.setKpIds(List.of("kp_002"));
        day.setQuestionCount(8);
        PlanDraft draft = new PlanDraft();
        draft.setDays(List.of(day));
        when(planningAgent.generate(any())).thenReturn(draft);

        DailyPlan existing = new DailyPlan();
        existing.setPlanId(200L);
        existing.setStatus("pending_generation");
        when(dailyPlanMapper.selectByGoalIdAndDate(eq(GOAL_ID), eq(TENANT_ID), eq(START)))
                .thenReturn(existing);

        int updated = planSkeletonService.refineWithAgent(GOAL_ID, TENANT_ID);

        assertEquals(1, updated);
        ArgumentCaptor<DailyPlan> captor = ArgumentCaptor.forClass(DailyPlan.class);
        verify(dailyPlanMapper).updateById(captor.capture());
        assertEquals("[\"kp_002\"]", captor.getValue().getPlannedKpIds());
        assertEquals(8, captor.getValue().getPlannedQuestionCount());
    }

    @Test
    @DisplayName("Agent 细化失败：保留模板骨架，不抛异常")
    void should_swallowAgentFailure_when_refining() {
        stubBase();
        when(planningAgent.generate(any())).thenThrow(new RuntimeException("timeout"));

        int updated = planSkeletonService.refineWithAgent(GOAL_ID, TENANT_ID);

        assertEquals(0, updated);
        verify(dailyPlanMapper, never()).updateById(any(DailyPlan.class));
    }

    @Test
    @DisplayName("科目无已启用知识点 → 不生成骨架")
    void should_skip_when_noActiveKp() {
        when(goalMapper.selectById(GOAL_ID)).thenReturn(buildGoal());
        Subject subject = new Subject();
        subject.setSubjectId(SUBJECT_ID);
        subject.setName("数学");
        when(subjectMapper.selectById(SUBJECT_ID)).thenReturn(subject);
        when(knowledgePointMapper.selectList(any())).thenReturn(List.of());

        assertAll(
                () -> assertEquals(0, planSkeletonService.generateSkeleton(GOAL_ID, TENANT_ID)),
                () -> assertEquals(0, planSkeletonService.refineWithAgent(GOAL_ID, TENANT_ID))
        );
        verifyNoInteractions(planningAgent);
    }
}
