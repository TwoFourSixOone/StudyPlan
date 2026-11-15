package com.jiang.common.util;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识点覆盖口径（产品规则 #5 · SIG-4）
 *
 * <p>系统内「未覆盖知识点」只有这一套判定，禁止各处自行实现：</p>
 * <ol>
 *   <li><b>未学</b>：{@code masteryScore} 为 {@code null}（无状态行）或 {@code 0}</li>
 *   <li><b>年级内</b>：{@code gradeRange} 必须命中学生年级（学生年级未知时不过滤）</li>
 *   <li><b>未暂缓</b>：{@code learning_goal.paused_kp_ids} 中的知识点不算未覆盖——它们本轮不出题，
 *       既不出题又计入压力会导致 SIG-4 每天重复命中、每天重新暂缓一批（2026-11-12 日志实测）</li>
 * </ol>
 *
 * <p>行字段键与 {@code StudentKnowledgeStateMapper.selectActiveKpsWithMastery} 的别名一致：
 * {@code kpId} / {@code gradeRange} / {@code masteryScore}。</p>
 *
 * <p>纯静态实现：不依赖 Spring，便于单测与跨层复用（信号检测、调整上下文、目标创建预检）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-12
 */
public final class KpCoverage {

    /** 知识点 ID 列别名 */
    public static final String KEY_KP_ID = "kpId";

    /** 年级范围列别名 */
    public static final String KEY_GRADE_RANGE = "gradeRange";

    /** 掌握度列别名 */
    public static final String KEY_MASTERY = "masteryScore";

    private KpCoverage() {
    }

    /**
     * 是否「未学」：无掌握度记录或掌握度为 0
     */
    public static boolean isUncovered(Map<String, Object> row) {
        if (row == null) {
            return false;
        }
        Object mastery = row.get(KEY_MASTERY);
        return mastery == null || ((Number) mastery).doubleValue() == 0d;
    }

    /**
     * 过滤出「年级内 + 未学 + 未暂缓」的知识点行（保持原顺序，不截断）
     *
     * @param rows          {@code selectActiveKpsWithMastery} 的结果
     * @param studentGrade  学生年级（null = 不按年级过滤）
     * @param pausedKpIds   目标上本轮暂缓的知识点（null/空 = 不扣除）
     * @return 未覆盖行，可直接用于计数或截断后交给调整 Agent
     */
    public static List<Map<String, Object>> uncoveredRows(List<Map<String, Object>> rows,
                                                          Integer studentGrade,
                                                          Set<String> pausedKpIds) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> uncovered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!isUncovered(row)) {
                continue;
            }
            if (studentGrade != null
                    && !GradeMatcher.matchesRange(studentGrade, (String) row.get(KEY_GRADE_RANGE))) {
                continue;
            }
            Object kpId = row.get(KEY_KP_ID);
            if (kpId != null && pausedKpIds != null && pausedKpIds.contains(String.valueOf(kpId))) {
                continue;
            }
            uncovered.add(row);
        }
        return uncovered;
    }

    /**
     * 未覆盖知识点数量（口径同 {@link #uncoveredRows}）
     */
    public static long countUncovered(List<Map<String, Object>> rows,
                                      Integer studentGrade,
                                      Set<String> pausedKpIds) {
        return uncoveredRows(rows, studentGrade, pausedKpIds).size();
    }

    /**
     * 周期内可覆盖的知识点容量：{@code max(0, 剩余天数) × 每日容量 × 效率}
     *
     * <p>与 {@code SignalDetectorImpl.detectSIG4} 的公式保持一致；日期倒挂（endDate ≤ from）返回 0。</p>
     */
    public static double capacity(LocalDate from, LocalDate endDate, double capDay, double efficiency) {
        if (from == null || endDate == null) {
            return 0d;
        }
        long days = ChronoUnit.DAYS.between(from, endDate);
        if (days <= 0) {
            return 0d;
        }
        return days * capDay * efficiency;
    }
}
