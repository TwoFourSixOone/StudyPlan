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
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private LearningGoal buildGoal() {
        LearningGoal goal = new LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setStudentId(STUDENT_ID);
        goal.setSubjectId(SUBJECT_ID);
        goal.setStartDate(TODAY);
        goal.setEndDate(TODAY);
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
        when(knowledgePointMapper.selectList(any())).thenReturn(List.of(buildKp("kp_001"), buildKp("kp_002")));
        when(stateMapper.selectActiveStatesWithKpInfo(anyLong(), eq(TENANT_ID), anyString(), eq(false)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("规划 Agent 成功 → 按草稿落骨架（知识点 + 题量）")
    void should_persistAgentDraft() {
        stubBase();
        PlanDraft.PlanDay day = new PlanDraft.PlanDay();
        day.setDate(TODAY);
        day.setKpIds(List.of("kp_001", "kp_002"));
        day.setQuestionCount(8);
        PlanDraft draft = new PlanDraft();
        draft.setDays(List.of(day));
        draft.setReason("test");
        when(planningAgent.generate(any())).thenReturn(draft);
        when(dailyPlanMapper.selectByGoalIdAndDate(eq(GOAL_ID), eq(TENANT_ID), eq(TODAY))).thenReturn(null);

        int inserted = planSkeletonService.generateSkeleton(GOAL_ID, TENANT_ID);

        assertEquals(1, inserted);
        ArgumentCaptor<DailyPlan> captor = ArgumentCaptor.forClass(DailyPlan.class);
        verify(dailyPlanMapper).insert(captor.capture());
        DailyPlan plan = captor.getValue();
        assertEquals("pending_generation", plan.getStatus());
        assertEquals("[\"kp_001\",\"kp_002\"]", plan.getPlannedKpIds());
        assertEquals(8, plan.getPlannedQuestionCount());
    }

    @Test
    @DisplayName("规划 Agent 失败 → 模板降级仍生成骨架")
    void should_fallbackToTemplate_when_agentFails() {
        stubBase();
        when(planningAgent.generate(any())).thenThrow(new RuntimeException("agent down"));
        when(dailyPlanMapper.selectByGoalIdAndDate(eq(GOAL_ID), eq(TENANT_ID), eq(TODAY))).thenReturn(null);

        int inserted = planSkeletonService.generateSkeleton(GOAL_ID, TENANT_ID);

        assertEquals(1, inserted, "Agent 失败也应生成模板骨架");
        verify(dailyPlanMapper).insert(any(DailyPlan.class));
    }

    @Test
    @DisplayName("当日计划已存在 → 跳过（幂等）")
    void should_skip_when_planExists() {
        stubBase();
        PlanDraft.PlanDay day = new PlanDraft.PlanDay();
        day.setDate(TODAY);
        day.setKpIds(List.of("kp_001"));
        day.setQuestionCount(10);
        PlanDraft draft = new PlanDraft();
        draft.setDays(List.of(day));
        when(planningAgent.generate(any())).thenReturn(draft);
        when(dailyPlanMapper.selectByGoalIdAndDate(eq(GOAL_ID), eq(TENANT_ID), eq(TODAY)))
                .thenReturn(new DailyPlan());

        int inserted = planSkeletonService.generateSkeleton(GOAL_ID, TENANT_ID);

        assertEquals(0, inserted);
        verify(dailyPlanMapper, never()).insert(any(DailyPlan.class));
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

        int inserted = planSkeletonService.generateSkeleton(GOAL_ID, TENANT_ID);

        assertEquals(0, inserted);
        verifyNoInteractions(planningAgent);
    }
}
