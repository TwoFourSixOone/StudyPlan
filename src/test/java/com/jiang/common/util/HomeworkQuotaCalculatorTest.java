package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HomeworkQuotaCalculator 单元测试
 * 覆盖：P0/P1/P2 优先级体系、极端模式、降级模式、BLOCK-5 防御、M-3 eligibleKpCount
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
class HomeworkQuotaCalculatorTest {

    // ==================== 常量验证 ====================

    @Test
    @DisplayName("常量值验证")
    void should_haveCorrectConstants() {
        assertEquals(15, HomeworkQuotaCalculator.DAILY_MAX);
        assertEquals(4, HomeworkQuotaCalculator.KP_MAX);
        assertEquals(1, HomeworkQuotaCalculator.SIMILAR_MIN_PER_KP);
        assertEquals(3, HomeworkQuotaCalculator.SIMILAR_MAX_PER_KP);
    }

    // ==================== 极端模式 ====================

    @Nested
    @DisplayName("极端模式（dueErrorCount >= DAILY_MAX）")
    class ExtremeModeTests {

        @Test
        @DisplayName("dueErrorCount = 15 → 极端模式，错题独占")
        void should_extremeMode_when_dueErrorCountEqualsMax() {
            var result = HomeworkQuotaCalculator.calculate(
                    15, Set.of("kp1"), Map.of(), Map.of("kp1", 4));
            assertTrue(result.isExtremeMode());
            assertTrue(result.isDegradedMode());
            assertEquals(15, result.getErrorQuota());
            assertEquals(0, result.getSimilarQuota());
            assertEquals(0, result.getReviewQuota());
        }

        @Test
        @DisplayName("dueErrorCount = 20（超过上限）→ 极端模式")
        void should_extremeMode_when_dueErrorCountExceedsMax() {
            var result = HomeworkQuotaCalculator.calculate(
                    20, Set.of("kp1"), Map.of(), Map.of("kp1", 4));
            assertTrue(result.isExtremeMode());
            assertEquals(15, result.getErrorQuota());
            assertEquals(0, result.getSimilarQuota());
            assertEquals(0, result.getReviewQuota());
        }
    }

    // ==================== BLOCK-5 防御 ====================

    @Nested
    @DisplayName("BLOCK-5：kpErrorCountMap 防御性校验")
    class Block5DefenseTests {

        @Test
        @DisplayName("kpErrorCountMap 值 > KP_MAX → 抛 IllegalArgumentException")
        void should_throw_when_errorCountExceedsKpMax() {
            assertThrows(IllegalArgumentException.class, () ->
                    HomeworkQuotaCalculator.calculate(
                            5, Set.of("kp1"),
                            Map.of("kp1", new BigDecimal("0.5")),
                            Map.of("kp1", 5))); // 5 > KP_MAX(4)
        }

        @Test
        @DisplayName("kpErrorCountMap 值 = KP_MAX → 正常（不抛异常）")
        void should_notThrow_when_errorCountEqualsKpMax() {
            assertDoesNotThrow(() ->
                    HomeworkQuotaCalculator.calculate(
                            4, Set.of("kp1"),
                            Map.of("kp1", new BigDecimal("0.5")),
                            Map.of("kp1", 4)));
        }
    }

    // ==================== P0 错题重做 ====================

    @Nested
    @DisplayName("P0：错题重做配额")
    class P0ErrorQuotaTests {

        @Test
        @DisplayName("无错题 → errorQuota=0")
        void should_zeroErrorQuota_when_noErrors() {
            var result = HomeworkQuotaCalculator.calculate(
                    0, Collections.emptySet(), Map.of(), Map.of());
            assertEquals(0, result.getErrorQuota());
            assertFalse(result.isExtremeMode());
        }

        @Test
        @DisplayName("3 道错题 → errorQuota=3")
        void should_errorQuotaEqualsDueCount() {
            var result = HomeworkQuotaCalculator.calculate(
                    3, Set.of("kp1", "kp2"),
                    Map.of("kp1", new BigDecimal("0.3"), "kp2", new BigDecimal("0.6")),
                    Map.of("kp1", 2, "kp2", 1));
            assertEquals(3, result.getErrorQuota());
        }
    }

    // ==================== P1 举一反三 ====================

    @Nested
    @DisplayName("P1：有错题知识点举一反三配额")
    class P1SimilarQuotaTests {

        @Test
        @DisplayName("单知识点、配额充足 → 分配到 SIMILAR_MAX_PER_KP")
        void should_allocateMaxPerKp_when_quotaSufficient() {
            // dueErrorCount=2, 剩余 13，kp1 errorCount=1, dynamicMax=3
            var result = HomeworkQuotaCalculator.calculate(
                    2, Set.of("kp1"),
                    Map.of("kp1", new BigDecimal("0.3")),
                    Map.of("kp1", 1));
            assertEquals(2, result.getErrorQuota());
            // SIMILAR_MAX_PER_KP=3, dynamicMax=KP_MAX(4)-1=3, 可分配到 3
            assertEquals(3, result.getSimilarQuota());
            assertEquals(3, result.getSimilarQuotaPerKp().get("kp1"));
        }

        @Test
        @DisplayName("知识点错题数=KP_MAX → 该知识点无法分配举一反三（dynamicMax<=0）")
        void should_skipKp_when_dynamicMaxZero() {
            // dueErrorCount=0, kp1 errorCount=4 → dynamicMax=0
            var result = HomeworkQuotaCalculator.calculate(
                    0, Set.of("kp1"),
                    Map.of("kp1", new BigDecimal("0.5")),
                    Map.of("kp1", 4));
            assertEquals(0, result.getSimilarQuota());
            assertTrue(result.getSimilarQuotaPerKp().isEmpty());
            // 全部配额给 P2 知识回顾
            assertEquals(15, result.getReviewQuota());
        }

        @Test
        @DisplayName("多知识点按掌握度从低到高排序分配")
        void should_prioritizeLowMasteryKp() {
            // kp1 mastery=0.2, kp2 mastery=0.8 → kp1 优先分配
            var result = HomeworkQuotaCalculator.calculate(
                    0, Set.of("kp1", "kp2"),
                    Map.of("kp1", new BigDecimal("0.2"), "kp2", new BigDecimal("0.8")),
                    Map.of("kp1", 0, "kp2", 0));
            // 两个知识点各得 SIMILAR_MIN_PER_KP=1，然后扩展
            assertTrue(result.getSimilarQuotaPerKp().containsKey("kp1"));
            assertTrue(result.getSimilarQuotaPerKp().containsKey("kp2"));
            // kp1 掌握度低，扩展配额应 ≥ kp2
            assertTrue(result.getSimilarQuotaPerKp().get("kp1") >= result.getSimilarQuotaPerKp().get("kp2"));
        }

        @Test
        @DisplayName("最低保障：每个 eligible 知识点至少分配 SIMILAR_MIN_PER_KP")
        void should_ensureMinPerKp() {
            // 3 个知识点，dueErrorCount=12 → 剩余 3，每知识点至少 1
            var result = HomeworkQuotaCalculator.calculate(
                    12, Set.of("kp1", "kp2", "kp3"),
                    Map.of("kp1", new BigDecimal("0.1"), "kp2", new BigDecimal("0.2"), "kp3", new BigDecimal("0.3")),
                    Map.of("kp1", 1, "kp2", 1, "kp3", 1));
            // 剩余 3，3 个 eligible KP，每个 dynamicMax=3
            assertEquals(3, result.getSimilarQuota());
            assertEquals(1, result.getSimilarQuotaPerKp().get("kp1"));
            assertEquals(1, result.getSimilarQuotaPerKp().get("kp2"));
            assertEquals(1, result.getSimilarQuotaPerKp().get("kp3"));
        }
    }

    // ==================== M-3 degradedMode ====================

    @Nested
    @DisplayName("M-3：degradedMode 降级模式判断")
    class DegradedModeTests {

        @Test
        @DisplayName("配额紧张时：仅部分 KP 被处理，degradedMode 为防御性标记")
        void should_partialAllocation_when_quotaTight() {
            // 5 个 KP，dueErrorCount=13 → 剩余 2
            // 只有前 2 个 KP 被处理（按掌握度排序），各分 1 道后 remainingQuota=0
            var result = HomeworkQuotaCalculator.calculate(
                    13, Set.of("kp1", "kp2", "kp3", "kp4", "kp5"),
                    Map.of("kp1", new BigDecimal("0.1"), "kp2", new BigDecimal("0.2"),
                            "kp3", new BigDecimal("0.3"), "kp4", new BigDecimal("0.4"), "kp5", new BigDecimal("0.5")),
                    Map.of("kp1", 1, "kp2", 1, "kp3", 1, "kp4", 1, "kp5", 1));
            // 仅 2 个 KP 实际被处理，各获 1 道
            assertEquals(2, result.getSimilarQuota());
            assertEquals(2, result.getSimilarQuotaPerKp().size());
            // 每个 eligible KP 至少获得 MIN_PER_KP=1，故 similarQuota >= eligibleKpCount
            // degradedMode 在当前分配逻辑下为防御性标记（始终 false）
            assertFalse(result.isDegradedMode());
        }

        @Test
        @DisplayName("similarQuota >= eligibleKpCount → degradedMode=false")
        void should_notDegraded_when_similarQuotaSufficient() {
            var result = HomeworkQuotaCalculator.calculate(
                    0, Set.of("kp1"),
                    Map.of("kp1", new BigDecimal("0.5")),
                    Map.of("kp1", 1));
            // 剩余 15，1 个 eligible KP → 可分到 3 道（SIMILAR_MAX）
            assertFalse(result.isDegradedMode());
            assertEquals(3, result.getSimilarQuota());
        }

        @Test
        @DisplayName("无 eligible KP → degradedMode=false")
        void should_notDegraded_when_noEligibleKp() {
            var result = HomeworkQuotaCalculator.calculate(
                    0, Set.of("kp1"),
                    Map.of("kp1", new BigDecimal("0.5")),
                    Map.of("kp1", 4)); // errorCount=4=KP_MAX → dynamicMax=0 → not eligible
            assertFalse(result.isDegradedMode());
        }
    }

    // ==================== P2 知识回顾 ====================

    @Nested
    @DisplayName("P2：知识回顾配额（剩余配额）")
    class P2ReviewQuotaTests {

        @Test
        @DisplayName("无错题 → reviewQuota = DAILY_MAX - similarQuota")
        void should_reviewQuotaFillRemaining() {
            var result = HomeworkQuotaCalculator.calculate(
                    0, Collections.emptySet(), Map.of(), Map.of());
            // 无错题 → 无 P1 分配 → 全部 15 道给 P2
            assertEquals(15, result.getReviewQuota());
            assertEquals(0, result.getSimilarQuota());
        }

        @Test
        @DisplayName("有错题 + 有举一反三 → reviewQuota = 剩余")
        void should_reviewQuotaIsRemainder() {
            var result = HomeworkQuotaCalculator.calculate(
                    3, Set.of("kp1"),
                    Map.of("kp1", new BigDecimal("0.5")),
                    Map.of("kp1", 1));
            // errorQuota=3, similarQuota=3(KP_MAX-1=3, SIMILAR_MAX=3)
            // reviewQuota = 15 - 3 - 3 = 9
            assertEquals(3, result.getErrorQuota());
            assertEquals(3, result.getSimilarQuota());
            assertEquals(9, result.getReviewQuota());
        }
    }

    // ==================== 边界场景 ====================

    @Nested
    @DisplayName("边界场景")
    class BoundaryTests {

        @Test
        @DisplayName("空输入 → 全部配额给 P2")
        void should_allReview_when_emptyInput() {
            var result = HomeworkQuotaCalculator.calculate(
                    0, Collections.emptySet(), Map.of(), Map.of());
            assertEquals(0, result.getErrorQuota());
            assertEquals(0, result.getSimilarQuota());
            assertEquals(15, result.getReviewQuota());
            assertFalse(result.isExtremeMode());
            assertFalse(result.isDegradedMode());
        }

        @Test
        @DisplayName("kpMasteryMap 缺少知识点 → 默认 mastery=1.0（排最后）")
        void should_defaultMasteryOne_when_kpMissing() {
            // kp1 在 errorKpIds 但不在 kpMasteryMap → 默认 1.0
            var result = HomeworkQuotaCalculator.calculate(
                    0, Set.of("kp1"),
                    Map.of(), // 空 mastery map
                    Map.of("kp1", 1));
            assertTrue(result.getSimilarQuotaPerKp().containsKey("kp1"));
        }

        @Test
        @DisplayName("配额总量守恒：errorQuota + similarQuota + reviewQuota = DAILY_MAX")
        void should_quotaSumEqualsDailyMax() {
            var result = HomeworkQuotaCalculator.calculate(
                    5, Set.of("kp1", "kp2"),
                    Map.of("kp1", new BigDecimal("0.3"), "kp2", new BigDecimal("0.6")),
                    Map.of("kp1", 2, "kp2", 1));
            assertEquals(15, result.getErrorQuota() + result.getSimilarQuota() + result.getReviewQuota());
        }
    }
}
