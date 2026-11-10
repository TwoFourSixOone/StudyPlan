package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HomeworkQuotaCalculator 单元测试：日题量上限（产品规则 #5 调整策略）
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@DisplayName("HomeworkQuotaCalculator 日题量上限")
class HomeworkQuotaCalculatorTest {

    @Test
    @DisplayName("默认上限 15：无错题时全部配额给知识回顾")
    void should_allocateFifteen_when_defaultLimit() {
        HomeworkQuotaCalculator.QuotaAllocation quota = HomeworkQuotaCalculator.calculate(
                0, Set.of(), Map.of(), Map.of());

        assertEquals(15, quota.getReviewQuota());
        assertEquals(0, quota.getErrorQuota());
    }

    @Test
    @DisplayName("策略下调到 10：总配额随上限收缩")
    void should_shrink_when_dailyLimitLowered() {
        HomeworkQuotaCalculator.QuotaAllocation quota = HomeworkQuotaCalculator.calculate(
                0, Set.of(), Map.of(), Map.of(), 10);

        assertEquals(10, quota.getReviewQuota());
    }

    @Test
    @DisplayName("错题数 >= 调整后的上限 → 极端模式，错题独占配额")
    void should_enterExtremeMode_when_errorsReachLoweredLimit() {
        HomeworkQuotaCalculator.QuotaAllocation quota = HomeworkQuotaCalculator.calculate(
                8, Set.of("kp_001"), Map.of("kp_001", new BigDecimal("0.5")),
                Map.of("kp_001", 3), 8);

        assertTrue(quota.isExtremeMode(), "错题数达到下调后的上限即进入极端模式");
        assertEquals(8, quota.getErrorQuota());
        assertEquals(0, quota.getReviewQuota());
    }

    @Test
    @DisplayName("上限越界被夹到 [1, 15]")
    void should_clampLimit() {
        assertEquals(15, HomeworkQuotaCalculator.calculate(0, Set.of(), Map.of(), Map.of(), 99)
                .getReviewQuota(), "超过 15 仍按 15");
        assertEquals(1, HomeworkQuotaCalculator.calculate(0, Set.of(), Map.of(), Map.of(), 0)
                .getReviewQuota(), "小于 1 按 1");
    }
}
