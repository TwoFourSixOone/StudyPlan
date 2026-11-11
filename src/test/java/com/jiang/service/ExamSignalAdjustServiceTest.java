package com.jiang.service;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.PlanAdjustmentLogMapper;
import com.jiang.model.entity.LearningGoal;
import com.jiang.service.impl.ExamSignalAdjustServiceImpl;
import com.jiang.vo.AdjustTriggerVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 试卷结算后信号检测与自动调整测试（design §8）
 *
 * <p>覆盖：无 active goal 跳过、12h 去重（查 action='confirmed'）、无信号跳过、
 * 命中即 applyDiff、PLAN_ADJUST_CONFLICT 跳过且不抛异常。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamSignalAdjustService 结算后信号调整")
class ExamSignalAdjustServiceTest {

    private static final Long PAPER_ID = 10L;
    private static final Long STUDENT_ID = 100L;
    private static final Long SUBJECT_ID = 5L;
    private static final Long GOAL_ID = 900L;
    private static final String TENANT = "t1";

    private LearningGoalMapper learningGoalMapper;
    private PlanAdjustmentLogMapper planAdjustmentLogMapper;
    private SignalDetector signalDetector;
    private PlanAdjustmentService planAdjustmentService;
    private PlanDiffService planDiffService;
    private ThreadPoolExecutor aiExecutor;
    private ExamSignalAdjustServiceImpl service;

    @BeforeEach
    void setUp() {
        learningGoalMapper = mock(LearningGoalMapper.class);
        planAdjustmentLogMapper = mock(PlanAdjustmentLogMapper.class);
        signalDetector = mock(SignalDetector.class);
        planAdjustmentService = mock(PlanAdjustmentService.class);
        planDiffService = mock(PlanDiffService.class);
        aiExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);

        service = new ExamSignalAdjustServiceImpl();
        ReflectionTestUtils.setField(service, "learningGoalMapper", learningGoalMapper);
        ReflectionTestUtils.setField(service, "planAdjustmentLogMapper", planAdjustmentLogMapper);
        ReflectionTestUtils.setField(service, "signalDetector", signalDetector);
        ReflectionTestUtils.setField(service, "planAdjustmentService", planAdjustmentService);
        ReflectionTestUtils.setField(service, "planDiffService", planDiffService);
        ReflectionTestUtils.setField(service, "aiExecutor", aiExecutor);
        ReflectionTestUtils.setField(service, "dedupHours", 12);
    }

    @AfterEach
    void tearDown() {
        aiExecutor.shutdownNow();
    }

    private LearningGoal activeGoal() {
        LearningGoal goal = new LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setStudentId(STUDENT_ID);
        goal.setSubjectId(SUBJECT_ID);
        goal.setStatus("active");
        goal.setStartDate(LocalDate.now().minusDays(10));
        goal.setEndDate(LocalDate.now().plusDays(10));
        return goal;
    }

    private TriggerSignalDTO signal() {
        TriggerSignalDTO dto = new TriggerSignalDTO();
        dto.setSignalType("SIG-2");
        dto.setDescription("掌握度下降");
        return dto;
    }

    @Test
    @DisplayName("无 active goal → 跳过（不检测信号）")
    void should_skip_when_noActiveGoal() {
        when(learningGoalMapper.selectActiveByStudentSubject(eq(STUDENT_ID), eq(TENANT), eq(SUBJECT_ID), any()))
                .thenReturn(null);

        service.doTrigger(PAPER_ID, STUDENT_ID, SUBJECT_ID, TENANT);

        verify(signalDetector, never()).detectSignals(anyLong(), anyString());
        verify(planAdjustmentService, never()).triggerAdjustment(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("12h 内已有 confirmed 调整 → 跳过（B2）")
    void should_skip_when_dedupHit() {
        when(learningGoalMapper.selectActiveByStudentSubject(any(), any(), any(), any())).thenReturn(activeGoal());
        when(planAdjustmentLogMapper.countConfirmedSince(eq(GOAL_ID), eq(TENANT), any())).thenReturn(1);

        service.doTrigger(PAPER_ID, STUDENT_ID, SUBJECT_ID, TENANT);

        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(planAdjustmentLogMapper).countConfirmedSince(eq(GOAL_ID), eq(TENANT), sinceCaptor.capture());
        // 窗口约为 now-12h
        long minutes = java.time.Duration.between(sinceCaptor.getValue(), LocalDateTime.now()).toMinutes();
        assertTrue(minutes >= 715 && minutes <= 725, "12h 去重窗口, actual=" + minutes);
        verify(signalDetector, never()).detectSignals(anyLong(), anyString());
    }

    @Test
    @DisplayName("无信号 → 跳过（不调用调整 Agent）")
    void should_skip_when_noSignals() {
        when(learningGoalMapper.selectActiveByStudentSubject(any(), any(), any(), any())).thenReturn(activeGoal());
        when(planAdjustmentLogMapper.countConfirmedSince(any(), any(), any())).thenReturn(0);
        when(signalDetector.detectSignals(GOAL_ID, TENANT)).thenReturn(List.of());

        service.doTrigger(PAPER_ID, STUDENT_ID, SUBJECT_ID, TENANT);

        verify(planAdjustmentService, never()).triggerAdjustment(anyLong(), anyLong(), anyString());
        verify(planDiffService, never()).applyDiff(anyLong());
    }

    @Test
    @DisplayName("有信号且生成 diff → applyDiff 自动生效")
    void should_applyDiff_when_adjusted() {
        when(learningGoalMapper.selectActiveByStudentSubject(any(), any(), any(), any())).thenReturn(activeGoal());
        when(planAdjustmentLogMapper.countConfirmedSince(any(), any(), any())).thenReturn(0);
        when(signalDetector.detectSignals(GOAL_ID, TENANT)).thenReturn(List.of(signal()));
        when(planAdjustmentService.triggerAdjustment(GOAL_ID, STUDENT_ID, TENANT))
                .thenReturn(AdjustTriggerVO.of(77L, List.of(signal())));

        service.doTrigger(PAPER_ID, STUDENT_ID, SUBJECT_ID, TENANT);

        verify(planDiffService).applyDiff(77L);
    }

    @Test
    @DisplayName("PLAN_ADJUST_CONFLICT（已有 pending diff）→ 跳过且不抛异常")
    void should_swallowConflict() {
        when(learningGoalMapper.selectActiveByStudentSubject(any(), any(), any(), any())).thenReturn(activeGoal());
        when(planAdjustmentLogMapper.countConfirmedSince(any(), any(), any())).thenReturn(0);
        when(signalDetector.detectSignals(GOAL_ID, TENANT)).thenReturn(List.of(signal()));
        when(planAdjustmentService.triggerAdjustment(GOAL_ID, STUDENT_ID, TENANT))
                .thenThrow(new BusinessException(ErrorCodes.PLAN_ADJUST_CONFLICT));

        assertDoesNotThrow(() -> service.doTrigger(PAPER_ID, STUDENT_ID, SUBJECT_ID, TENANT));
        verify(planDiffService, never()).applyDiff(anyLong());
    }

    @Test
    @DisplayName("任意异常都不外抛（不影响试卷状态）")
    void should_swallowAnyException() {
        when(learningGoalMapper.selectActiveByStudentSubject(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.doTrigger(PAPER_ID, STUDENT_ID, SUBJECT_ID, TENANT));
    }

    @Test
    @DisplayName("异步入口：提交后立即返回，不阻塞 Worker")
    void should_submitToAiExecutor_when_triggerAfterSettlement() throws Exception {
        when(learningGoalMapper.selectActiveByStudentSubject(any(), any(), any(), any())).thenReturn(null);

        service.triggerAfterSettlement(PAPER_ID, STUDENT_ID, SUBJECT_ID, TENANT);
        aiExecutor.shutdown();
        assertTrue(aiExecutor.awaitTermination(2, TimeUnit.SECONDS), "任务应提交到 aiExecutor");

        verify(learningGoalMapper).selectActiveByStudentSubject(eq(STUDENT_ID), eq(TENANT), eq(SUBJECT_ID), any());
        assertEquals(0, aiExecutor.getActiveCount());
    }
}
