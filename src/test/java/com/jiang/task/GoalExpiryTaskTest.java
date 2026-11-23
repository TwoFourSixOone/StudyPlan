package com.jiang.task;

import com.jiang.mapper.LearningGoalMapper;
import com.jiang.model.entity.LearningGoal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GoalExpiryTask 单元测试（Change: 2026-11-23_goal_expiry_and_homework_hint）
 *
 * <p>覆盖：有到期目标 → 调用批量 update 并返回行数；无到期目标 → 返回 0；
 * 底层异常 → 返回 0 且不抛出（定时任务不能拖垮调度线程）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoalExpiryTask 目标到期流转")
class GoalExpiryTaskTest {

    @Mock
    private LearningGoalMapper learningGoalMapper;

    private GoalExpiryTask buildTask() {
        GoalExpiryTask task = new GoalExpiryTask();
        ReflectionTestUtils.setField(task, "learningGoalMapper", learningGoalMapper);
        return task;
    }

    @Test
    @DisplayName("有过期 active 目标 → 批量置 expired 并返回更新行数")
    void should_expireOverdueGoals_when_exists() {
        when(learningGoalMapper.update(any(LearningGoal.class), any())).thenReturn(2);

        int updated = buildTask().expireOverdueGoals(LocalDate.of(2026, 11, 23));

        assertEquals(2, updated);
        verify(learningGoalMapper).update(any(LearningGoal.class), any());
    }

    @Test
    @DisplayName("无到期目标 → 返回 0（幂等，重复执行无副作用）")
    void should_returnZero_when_noOverdueGoal() {
        when(learningGoalMapper.update(any(LearningGoal.class), any())).thenReturn(0);

        assertEquals(0, buildTask().expireOverdueGoals(LocalDate.of(2026, 11, 23)));
    }

    @Test
    @DisplayName("底层异常 → 返回 0 且不抛出（不拖垮调度线程）")
    void should_swallowException_when_mapperFails() {
        when(learningGoalMapper.update(any(LearningGoal.class), any()))
                .thenThrow(new RuntimeException("db down"));

        assertEquals(0, buildTask().expireOverdueGoals(LocalDate.of(2026, 11, 23)));
    }
}
