package com.jiang.service;

import com.jiang.config.SignalConfig;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.HomeworkSubmissionDetailMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.MasteryChangeLogMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.model.entity.LearningGoal;
import com.jiang.service.impl.SignalDetectorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SignalDetector 单元测试
 *
 * <p>重点覆盖 2026-11-09 的修复点：</p>
 * <ul>
 *   <li>SIG-2 的 MIN(delta) 为负数，必须取绝对值比较，否则信号永不触发</li>
 *   <li>SIG-2 / SIG-4 改为「学生 + 科目」维度查询（exam_paper 无 goal_id 列）</li>
 *   <li>goal 缺少 studentId / subjectId 时跳过，不抛异常打断其它信号</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignalDetector 单元测试")
class SignalDetectorTest {

    @Mock
    private LearningGoalMapper goalMapper;
    @Mock
    private StudentKnowledgeStateMapper knowledgeStateMapper;
    @Mock
    private HomeworkSubmissionDetailMapper submissionDetailMapper;
    @Mock
    private MasteryChangeLogMapper changeLogMapper;
    @Mock
    private DailyPlanMapper planMapper;

    @InjectMocks
    private SignalDetectorImpl signalDetector;

    private static final Long GOAL_ID = 100L;
    private static final Long STUDENT_ID = 1L;
    private static final Long SUBJECT_ID = 10L;
    private static final String TENANT_ID = "default";

    @BeforeEach
    void setUp() {
        // SignalConfig 是纯配置对象，不 mock，直接用默认值（sig2=0.15、sig3=2、sig4=3.0/0.8）
        ReflectionTestUtils.setField(signalDetector, "signalConfig", new SignalConfig());

        // SIG-1 / SIG-3 的「无数据」基线，按用例覆盖
        lenient().when(submissionDetailMapper.selectRecentAccuracy(anyLong(), anyInt(), anyString()))
                .thenReturn(List.of());
        lenient().when(planMapper.selectIncompleteDays(anyLong(), anyString()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("SIG-2：掌握度下降 -0.30（负数）→ 取绝对值后命中")
    void should_detectSIG2_when_dropIsNegativeBeyondThreshold() {
        when(goalMapper.selectById(GOAL_ID))
                .thenReturn(buildGoal(STUDENT_ID, SUBJECT_ID, LocalDate.now().plusDays(30)));
        when(changeLogMapper.selectMaxMasteryDropByStudentAndSubject(STUDENT_ID, SUBJECT_ID, TENANT_ID))
                .thenReturn(-0.30);

        List<TriggerSignalDTO> signals = signalDetector.detectSignals(GOAL_ID, TENANT_ID);

        assertTrue(hasSignal(signals, "SIG-2"), "MIN(delta) 为负数时应取绝对值与阈值比较");
    }

    @Test
    @DisplayName("SIG-2：掌握度下降 -0.10（未达 0.15 阈值）→ 不命中")
    void should_notDetectSIG2_when_dropWithinThreshold() {
        when(goalMapper.selectById(GOAL_ID))
                .thenReturn(buildGoal(STUDENT_ID, SUBJECT_ID, LocalDate.now().plusDays(30)));
        when(changeLogMapper.selectMaxMasteryDropByStudentAndSubject(STUDENT_ID, SUBJECT_ID, TENANT_ID))
                .thenReturn(-0.10);

        List<TriggerSignalDTO> signals = signalDetector.detectSignals(GOAL_ID, TENANT_ID);

        assertFalse(hasSignal(signals, "SIG-2"));
    }

    @Test
    @DisplayName("SIG-2 / SIG-4：goal 无 subjectId → 跳过，且不发起按科目查询")
    void should_skipSIG2AndSIG4_when_goalHasNoSubject() {
        when(goalMapper.selectById(GOAL_ID))
                .thenReturn(buildGoal(STUDENT_ID, null, LocalDate.now().plusDays(30)));

        List<TriggerSignalDTO> signals = signalDetector.detectSignals(GOAL_ID, TENANT_ID);

        assertFalse(hasSignal(signals, "SIG-2"));
        assertFalse(hasSignal(signals, "SIG-4"));
        verify(changeLogMapper, never())
                .selectMaxMasteryDropByStudentAndSubject(anyLong(), anyLong(), anyString());
        verify(knowledgeStateMapper, never())
                .countUncoveredByStudentAndSubject(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("SIG-4：剩余容量 7.2 < 未覆盖 10 → 命中")
    void should_detectSIG4_when_remainingCapacityInsufficient() {
        // 剩余 3 天 × capDay 3.0 × eff 0.8 = 7.2
        when(goalMapper.selectById(GOAL_ID))
                .thenReturn(buildGoal(STUDENT_ID, SUBJECT_ID, LocalDate.now().plusDays(3)));
        // changeLog 未 stub → 返回 null，SIG-2 自然不命中
        when(knowledgeStateMapper.countUncoveredByStudentAndSubject(STUDENT_ID, SUBJECT_ID, TENANT_ID))
                .thenReturn(10L);

        List<TriggerSignalDTO> signals = signalDetector.detectSignals(GOAL_ID, TENANT_ID);

        assertTrue(hasSignal(signals, "SIG-4"));
    }

    @Test
    @DisplayName("SIG-4：剩余容量充足 → 不命中")
    void should_notDetectSIG4_when_capacityIsEnough() {
        when(goalMapper.selectById(GOAL_ID))
                .thenReturn(buildGoal(STUDENT_ID, SUBJECT_ID, LocalDate.now().plusDays(30)));
        when(knowledgeStateMapper.countUncoveredByStudentAndSubject(STUDENT_ID, SUBJECT_ID, TENANT_ID))
                .thenReturn(5L);

        List<TriggerSignalDTO> signals = signalDetector.detectSignals(GOAL_ID, TENANT_ID);

        assertFalse(hasSignal(signals, "SIG-4"));
    }

    @Test
    @DisplayName("SIG-3：连续 2 天未完成作业 → 命中")
    void should_detectSIG3_when_twoConsecutiveIncompleteDays() {
        LocalDate today = LocalDate.now();
        when(planMapper.selectIncompleteDays(GOAL_ID, TENANT_ID))
                .thenReturn(List.of(today.minusDays(1), today));

        List<TriggerSignalDTO> signals = signalDetector.detectSignals(GOAL_ID, TENANT_ID);

        assertTrue(hasSignal(signals, "SIG-3"));
    }

    @Test
    @DisplayName("全部信号均未命中 → 返回空列表")
    void should_returnEmptySignals_when_nothingDetected() {
        when(goalMapper.selectById(GOAL_ID))
                .thenReturn(buildGoal(STUDENT_ID, SUBJECT_ID, LocalDate.now().plusDays(30)));
        when(changeLogMapper.selectMaxMasteryDropByStudentAndSubject(STUDENT_ID, SUBJECT_ID, TENANT_ID))
                .thenReturn(-0.02);
        when(knowledgeStateMapper.countUncoveredByStudentAndSubject(STUDENT_ID, SUBJECT_ID, TENANT_ID))
                .thenReturn(1L);

        List<TriggerSignalDTO> signals = signalDetector.detectSignals(GOAL_ID, TENANT_ID);

        assertTrue(signals.isEmpty());
    }

    // ==================== 辅助方法 ====================

    private LearningGoal buildGoal(Long studentId, Long subjectId, LocalDate endDate) {
        LearningGoal goal = new LearningGoal();
        goal.setGoalId(GOAL_ID);
        goal.setStudentId(studentId);
        goal.setSubjectId(subjectId);
        goal.setStartDate(LocalDate.now().minusDays(10));
        goal.setEndDate(endDate);
        goal.setStatus("active");
        return goal;
    }

    private boolean hasSignal(List<TriggerSignalDTO> signals, String type) {
        return signals.stream().anyMatch(s -> type.equals(s.getSignalType()));
    }
}
