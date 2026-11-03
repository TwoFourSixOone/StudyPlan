package com.jiang.common.util;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 每日作业配额计算器
 * 实现规则 #3 的完整配额逻辑（P0/P1/P2 优先级体系）
 * <p>
 * Change: 2026-11-03_similar_question_generation
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
public class HomeworkQuotaCalculator {

    /** 单日总量上限 */
    public static final int DAILY_MAX = 15;
    /** 单知识点每日出题上限（错题 + 举一反三合计） */
    public static final int KP_MAX = 4;
    /** 每个有错题知识点至少分配的举一反三新题数 */
    public static final int SIMILAR_MIN_PER_KP = 1;
    /** 每个有错题知识点最多分配的举一反三新题数 */
    public static final int SIMILAR_MAX_PER_KP = 3;

    /**
     * 计算配额分配方案
     *
     * @param dueErrorCount   当日到期错题总数
     * @param errorKpIds      有错题的知识点 ID 集合
     * @param kpMasteryMap    知识点掌握度 Map（kpId → masteryScore）
     * @param kpErrorCountMap 每个知识点的错题数 Map（kpId → errorCount）
     * @return 配额方案
     * @throws IllegalArgumentException 若 kpErrorCountMap 中某值 > KP_MAX（防御性校验）
     */
    public static QuotaAllocation calculate(int dueErrorCount,
                                             Set<String> errorKpIds,
                                             Map<String, BigDecimal> kpMasteryMap,
                                             Map<String, Integer> kpErrorCountMap) {
        // 前置防御（BLOCK-5）：校验 kpErrorCountMap 每个值 ≤ KP_MAX
        for (Map.Entry<String, Integer> entry : kpErrorCountMap.entrySet()) {
            if (entry.getValue() > KP_MAX) {
                throw new IllegalArgumentException(
                        "知识点 " + entry.getKey() + " 错题数 " + entry.getValue() + " 超过 KP_MAX(" + KP_MAX + ")");
            }
        }

        Map<String, Integer> similarQuotaPerKp = new HashMap<>();
        Map<String, Integer> reviewQuotaPerKp = new HashMap<>();
        int errorQuota;
        int similarQuota;
        int reviewQuota;
        boolean extremeMode;
        boolean degradedMode;

        // 1. 判断极端模式
        if (dueErrorCount >= DAILY_MAX) {
            // 极端模式：错题独占全部配额
            extremeMode = true;
            degradedMode = true;
            errorQuota = DAILY_MAX;
            similarQuota = 0;
            reviewQuota = 0;
            return new QuotaAllocation(errorQuota, similarQuota, reviewQuota,
                    similarQuotaPerKp, reviewQuotaPerKp, extremeMode, degradedMode);
        }

        extremeMode = false;
        errorQuota = dueErrorCount;
        int remainingQuota = DAILY_MAX - errorQuota;

        // 2. P1 — 有错题知识点的举一反三
        // 按掌握度从低到高排序（优先分配薄弱知识点）
        java.util.List<String> sortedErrorKps = new java.util.ArrayList<>(errorKpIds);
        sortedErrorKps.sort(Comparator.comparing(kpId ->
                kpMasteryMap.getOrDefault(kpId, BigDecimal.ONE),
                Comparator.naturalOrder()));

        int similarTotal = 0;
        int eligibleKpCount = 0; // M-3 修复：统计“实际可获得最低保障”的知识点数（dynamicMax > 0）
        for (String kpId : sortedErrorKps) {
            if (remainingQuota <= 0) {
                break;
            }

            int errorCount = kpErrorCountMap.getOrDefault(kpId, 0);
            // 动态举一反三上限 = KP_MAX - 该知识点错题数
            int dynamicMax = KP_MAX - errorCount;
            if (dynamicMax <= 0) {
                // KP_MAX 硬上限 > 最低保障，该知识点无法分配举一反三
                continue;
            }
            eligibleKpCount++;

            // 先分配最低保障 1 道
            int allocated = Math.min(SIMILAR_MIN_PER_KP, Math.min(dynamicMax, remainingQuota));
            similarQuotaPerKp.put(kpId, allocated);
            similarTotal += allocated;
            remainingQuota -= allocated;
        }

        // P1 扩展：配额充足时，按掌握度从低到高扩展至 SIMILAR_MAX_PER_KP 或 dynamicMax
        if (remainingQuota > 0) {
            for (String kpId : sortedErrorKps) {
                if (remainingQuota <= 0) break;

                int currentAlloc = similarQuotaPerKp.getOrDefault(kpId, 0);
                int errorCount = kpErrorCountMap.getOrDefault(kpId, 0);
                int dynamicMax = KP_MAX - errorCount;
                int maxExpand = Math.min(SIMILAR_MAX_PER_KP, dynamicMax);
                int canExpand = maxExpand - currentAlloc;

                if (canExpand > 0) {
                    int expand = Math.min(canExpand, remainingQuota);
                    similarQuotaPerKp.put(kpId, currentAlloc + expand);
                    similarTotal += expand;
                    remainingQuota -= expand;
                }
            }
        }

        similarQuota = similarTotal;

        // 判断配额极度紧张：实际分配的举一反三数 < 可获得保障的知识点数
        degradedMode = similarQuota < eligibleKpCount && eligibleKpCount > 0;

        // 3. P2 — 无错题知识点的知识回顾
        // 剩余配额全部分给知识回顾（此处不展开，由调用方根据 kpMasteryMap 分配）
        reviewQuota = remainingQuota;

        return new QuotaAllocation(errorQuota, similarQuota, reviewQuota,
                similarQuotaPerKp, reviewQuotaPerKp, extremeMode, degradedMode);
    }

    /**
     * 配额分配方案
     */
    public static class QuotaAllocation {
        private final int errorQuota;
        private final int similarQuota;
        private final int reviewQuota;
        /** 只包含配额 > 0 的知识点 */
        private final Map<String, Integer> similarQuotaPerKp;
        /** 只包含配额 > 0 的知识点 */
        private final Map<String, Integer> reviewQuotaPerKp;
        private final boolean extremeMode;
        /** 仅用于日志/监控，调用方遍历 Map 即可，无需分支判断（IMP-17） */
        private final boolean degradedMode;

        public QuotaAllocation(int errorQuota, int similarQuota, int reviewQuota,
                                Map<String, Integer> similarQuotaPerKp,
                                Map<String, Integer> reviewQuotaPerKp,
                                boolean extremeMode, boolean degradedMode) {
            this.errorQuota = errorQuota;
            this.similarQuota = similarQuota;
            this.reviewQuota = reviewQuota;
            this.similarQuotaPerKp = similarQuotaPerKp;
            this.reviewQuotaPerKp = reviewQuotaPerKp;
            this.extremeMode = extremeMode;
            this.degradedMode = degradedMode;
        }

        public int getErrorQuota() { return errorQuota; }
        public int getSimilarQuota() { return similarQuota; }
        public int getReviewQuota() { return reviewQuota; }
        public Map<String, Integer> getSimilarQuotaPerKp() { return similarQuotaPerKp; }
        public Map<String, Integer> getReviewQuotaPerKp() { return reviewQuotaPerKp; }
        public boolean isExtremeMode() { return extremeMode; }
        public boolean isDegradedMode() { return degradedMode; }
    }
}
