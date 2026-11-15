package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识点覆盖口径工具测试（Change 2026-11-12_adjustment_signal_fix）
 *
 * @author StudyMate Team
 * @since 2026-11-12
 */
@DisplayName("KpCoverage / PausedKpIds 单元测试")
class KpCoverageTest {

    @Test
    @DisplayName("isUncovered: mastery 为 null 或 0 → 未覆盖；>0 → 已覆盖")
    void should_detectUncoveredByMastery() {
        assertTrue(KpCoverage.isUncovered(row("kp_1", "7", null)));
        assertTrue(KpCoverage.isUncovered(row("kp_2", "7", 0.0)));
        assertFalse(KpCoverage.isUncovered(row("kp_3", "7", 0.35)));
    }

    @Test
    @DisplayName("uncoveredRows: 年级过滤 + 未学过滤")
    void should_filterByGradeAndMastery() {
        List<Map<String, Object>> rows = List.of(
                row("kp_7", "7", null),
                row("kp_12", "12", null),
                row("kp_mastered", "7", 0.9));

        List<Map<String, Object>> grade7 = KpCoverage.uncoveredRows(rows, 7, Set.of());
        assertEquals(1, grade7.size());
        assertEquals("kp_7", grade7.get(0).get(KpCoverage.KEY_KP_ID));
    }

    @Test
    @DisplayName("uncoveredRows: 学生年级未知（null）→ 不按年级过滤")
    void should_notFilterGrade_when_studentGradeUnknown() {
        List<Map<String, Object>> rows = List.of(
                row("kp_7", "7", null),
                row("kp_12", "12", null));

        assertEquals(2, KpCoverage.uncoveredRows(rows, null, Set.of()).size());
    }

    @Test
    @DisplayName("uncoveredRows: 扣除暂缓知识点（2026-11-12 口径修正）")
    void should_excludePausedKps() {
        List<Map<String, Object>> rows = List.of(
                row("kp_1", "7", null),
                row("kp_2", "7", null),
                row("kp_3", "7", null));

        List<Map<String, Object>> result = KpCoverage.uncoveredRows(rows, 7, Set.of("kp_1", "kp_3"));

        assertEquals(1, result.size());
        assertEquals("kp_2", result.get(0).get(KpCoverage.KEY_KP_ID));
    }

    @Test
    @DisplayName("countUncovered: 与 uncoveredRows 同口径")
    void should_countSameAsUncoveredRows() {
        List<Map<String, Object>> rows = List.of(
                row("kp_1", "7", null),
                row("kp_2", "7", 0.5),
                row("kp_3", "7", 0.0));

        assertEquals(2, KpCoverage.countUncovered(rows, 7, Set.of()));
        assertEquals(1, KpCoverage.countUncovered(rows, 7, Set.of("kp_1")));
    }

    @Test
    @DisplayName("capacity: 4 天 × 3.0 × 0.8 = 9.6")
    void should_computeCapacity() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        assertEquals(9.6, KpCoverage.capacity(today, today.plusDays(4), 3.0, 0.8), 1e-9);
    }

    @Test
    @DisplayName("capacity: 日期倒挂 / 空值 → 0")
    void should_returnZero_when_dateInvalid() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        assertEquals(0d, KpCoverage.capacity(today, today, 3.0, 0.8), 1e-9);
        assertEquals(0d, KpCoverage.capacity(today, today.minusDays(1), 3.0, 0.8), 1e-9);
        assertEquals(0d, KpCoverage.capacity(null, today, 3.0, 0.8), 1e-9);
    }

    @Test
    @DisplayName("PausedKpIds: null / 空白 / 非法 JSON → 空集合，不抛异常")
    void should_returnEmpty_when_pausedJsonInvalid() {
        assertTrue(PausedKpIds.parse(null).isEmpty());
        assertTrue(PausedKpIds.parse("  ").isEmpty());
        assertTrue(PausedKpIds.parse("not-json").isEmpty());
        assertTrue(PausedKpIds.parseSet("{\"a\":1}").isEmpty());
    }

    @Test
    @DisplayName("PausedKpIds: 正常解析 + 去重 + 过滤空元素")
    void should_parseAndDedupe() {
        assertEquals(List.of("kp_1", "kp_2"), PausedKpIds.parse("[\"kp_1\",\"kp_2\"]"));
        assertEquals(Set.of("kp_1", "kp_2"), PausedKpIds.parseSet("[\"kp_1\",\"kp_1\",\"kp_2\",null]"));
    }

    private Map<String, Object> row(String kpId, String gradeRange, Double mastery) {
        Map<String, Object> row = new HashMap<>();
        row.put(KpCoverage.KEY_KP_ID, kpId);
        row.put("kpName", "知识点-" + kpId);
        row.put(KpCoverage.KEY_GRADE_RANGE, gradeRange);
        row.put(KpCoverage.KEY_MASTERY, mastery);
        return row;
    }
}
