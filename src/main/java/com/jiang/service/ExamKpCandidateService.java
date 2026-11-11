package com.jiang.service;

import com.jiang.dto.KpCandidate;

import java.util.List;

/**
 * 知识点候选构造与缓存（design v4 §3.7 / tasks E1）
 *
 * <p>把"逐题知识点"前置到视觉解析：服务端构造候选清单注入 Prompt，模型逐题直出
 * {@code knowledgePointId}，再由白名单保证"只引用已启用知识点"（规则 #8 / 规则 #4 v4 前移）。</p>
 *
 * <p><b>分级上限（v4b 方案 a）</b>：parse 切片 ≤150（体积敏感，8 图 × 每图一次调用）、
 * attribution 切片 ≤500（只对未决题兜底调用）。两者基于<b>同一份 grade-filtered active 全集</b>排序，
 * 因此被 parse 截断的知识点仍会出现在兜底清单里（不存在"直出+兜底都不可达"的死区）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
public interface ExamKpCandidateService {

    /**
     * 构造候选快照（带 TTL 缓存，同一学生/科目/年级 10 分钟内复用，避免每图重复查库）
     *
     * @param tenantId     租户 ID
     * @param subjectId    科目 ID
     * @param studentId    学生 ID（用于"有掌握度记录的知识点优先"排序；可空则跳过该维度）
     * @param studentGrade 学生年级（1-12，可空表示不过滤）
     * @return 候选快照
     */
    CandidateSnapshot build(String tenantId, Long subjectId, Long studentId, Integer studentGrade);

    /**
     * 渲染 Prompt 用清单：每行 {@code - <ID> | <名称>}；空清单输出"（本卷无候选知识点）"
     */
    String renderForPrompt(List<KpCandidate> candidates);

    /**
     * 候选快照：同一全集的两种切片 + 截断统计
     */
    class CandidateSnapshot {

        /** parse 切片（≤150） */
        private final List<KpCandidate> parseCandidates;

        /** 兜底归因全集（≤500） */
        private final List<KpCandidate> attributionCandidates;

        /** 年级过滤后的候选总数 */
        private final int kpTotal;

        /** 被 parse 上限截断的条数（max(0, kpTotal - 150)） */
        private final int kpTruncated;

        /** 截断率（truncated / kpTotal，保留 4 位小数） */
        private final double truncationRate;

        /** 全部角色 ID 集合（白名单用；含 parse + attribution 切片） */
        private final java.util.Set<String> allowedIds;

        public CandidateSnapshot(List<KpCandidate> parseCandidates,
                                 List<KpCandidate> attributionCandidates,
                                 int kpTotal, int kpTruncated, double truncationRate) {
            this.parseCandidates = parseCandidates;
            this.attributionCandidates = attributionCandidates;
            this.kpTotal = kpTotal;
            this.kpTruncated = kpTruncated;
            this.truncationRate = truncationRate;
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            parseCandidates.forEach(c -> ids.add(c.knowledgePointId()));
            attributionCandidates.forEach(c -> ids.add(c.knowledgePointId()));
            this.allowedIds = ids;
        }

        public List<KpCandidate> getParseCandidates() {
            return parseCandidates;
        }

        public List<KpCandidate> getAttributionCandidates() {
            return attributionCandidates;
        }

        public int getKpTotal() {
            return kpTotal;
        }

        public int getKpTruncated() {
            return kpTruncated;
        }

        public double getTruncationRate() {
            return truncationRate;
        }

        public java.util.Set<String> getAllowedIds() {
            return allowedIds;
        }

        public boolean isAllowed(String knowledgePointId) {
            return knowledgePointId != null && allowedIds.contains(knowledgePointId);
        }
    }
}
