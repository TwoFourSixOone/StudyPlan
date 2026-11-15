package com.jiang.service.impl;

import com.jiang.agent.AgentDegradationLogger;
import com.jiang.common.util.GradeMatcher;
import com.jiang.common.util.LlmTextSanitizer;
import com.jiang.dto.KpCandidate;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.service.ExamKpCandidateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识点候选构造与缓存实现（design v4 §3.7 / tasks E1）
 *
 * <p>候选来源：{@code tenant + subject + status='active'}（显式列名），按
 * {@link GradeMatcher#matchesRangeAtOrBelow} 做年级过滤（**同一学段且不高于学生年级**：高三可含高一/高二，
 * 但不含初中；空 grade_range 保留；学生年级空不过滤），
 * 再按「精确年级匹配 → 有掌握度记录 → kpId 升序」排序，最后切出 parse 300 / attribution 500 两个切片。</p>
 *
 * <p>2026-11-12 修订：原按 {@code matchesRange} 只取学生所在年级，导致高考跨年级考点（集合/复数等，
 * 标注在高一/初一）永远不在候选清单里，只能待人工归因；同时 parse 上限 150 → 300 以容纳跨年级候选。</p>
 *
 * <p>缓存：{@code ConcurrentHashMap} + TTL（默认 600s），不引入新依赖；缓存 key 额外带上 studentId
 * （排序依赖该学生的掌握度记录，若共用 grade 维度会把 A 的排序结果错给 B）。知识点导入/治理后最多
 * TTL 生效，属已知延迟。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Service
@Slf4j
public class ExamKpCandidateServiceImpl implements ExamKpCandidateService {

    /** parse 切片上限（体积敏感：8 图 × 每图一次调用）。2026-11-12 由 150 提到 300，容纳跨年级候选 */
    @Value("${exam.parse.kp-candidates.max:300}")
    private int parseMax = 300;

    /** 兜底归因切片上限（只对未决题调用，通常 0–1 批） */
    @Value("${exam.parse.kp-candidates.attribution-max:500}")
    private int attributionMax = 500;

    /** 缓存 TTL（秒） */
    @Value("${exam.parse.kp-candidates.ttl-seconds:600}")
    private long ttlSeconds = 600L;

    /** 超过该总数才可能出现"兜底不可达"（分片数 > 2） */
    static final int KP_UNREACHABLE_THRESHOLD = 1000;

    private static final String EMPTY_PROMPT_TEXT = "（本卷无候选知识点）";

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private StudentKnowledgeStateMapper stateMapper;

    @Autowired
    private com.jiang.mapper.SubjectMapper subjectMapper;

    @Autowired
    private AgentDegradationLogger degradationLogger;

    private final ConcurrentHashMap<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    @Override
    public CandidateSnapshot build(String tenantId, Long subjectId, Long studentId, Integer studentGrade) {
        String cacheKey = tenantId + "|" + subjectId + "|" + studentId + "|" + studentGrade;
        CachedSnapshot cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.createdAt < ttlSeconds * 1000L) {
            return cached.snapshot;
        }

        CandidateSnapshot snapshot = buildUncached(tenantId, subjectId, studentId, studentGrade);
        cache.put(cacheKey, new CachedSnapshot(snapshot, now));
        return snapshot;
    }

    /**
     * 实际构造（无缓存）：1 条 kp 查询 + 至多 1 条掌握度查询
     */
    private CandidateSnapshot buildUncached(String tenantId, Long subjectId, Long studentId,
                                             Integer studentGrade) {
        String subjectName = resolveSubjectName(tenantId, subjectId);
        List<KnowledgePoint> rows = (subjectName == null || subjectName.isBlank())
                ? List.of()
                : knowledgePointMapper.selectActiveBySubject(tenantId, subjectName);

        // 1. 年级过滤（2026-11-12：同一学段且不高于学生年级；空 grade_range 保留；学生年级空不过滤）
        List<KnowledgePoint> filtered = new ArrayList<>();
        for (KnowledgePoint row : rows) {
            if (studentGrade == null || row.getGradeRange() == null || row.getGradeRange().isBlank()
                    || GradeMatcher.matchesRangeAtOrBelow(studentGrade, row.getGradeRange())) {
                filtered.add(row);
            }
        }

        // 2. 有掌握度记录的知识点优先（1 条 IN 查询，禁 N+1）
        Set<String> withState = new LinkedHashSet<>();
        if (studentId != null && !filtered.isEmpty()) {
            List<String> kpIds = filtered.stream()
                    .map(KnowledgePoint::getKnowledgePointId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            if (!kpIds.isEmpty()) {
                List<StudentKnowledgeState> states = stateMapper.selectByStudentAndKpIds(studentId, tenantId, kpIds);
                if (states != null) {
                    states.forEach(state -> {
                        if (state.getKnowledgePointId() != null) {
                            withState.add(state.getKnowledgePointId());
                        }
                    });
                }
            }
        }

        // 3. 排序：精确年级匹配 → 有掌握度 → kpId 升序（稳定可复现）
        filtered.sort(Comparator
                .comparing((KnowledgePoint row) -> studentGrade != null
                        && GradeMatcher.matchesExactGrade(studentGrade, row.getGradeRange()) ? 0 : 1)
                .thenComparing(row -> withState.contains(row.getKnowledgePointId()) ? 0 : 1)
                .thenComparing(row -> row.getKnowledgePointId() == null ? "" : row.getKnowledgePointId()));

        List<KpCandidate> all = new ArrayList<>(filtered.size());
        for (KnowledgePoint row : filtered) {
            all.add(new KpCandidate(row.getKnowledgePointId(),
                    LlmTextSanitizer.sanitize(row.getName())));
        }

        int total = all.size();
        int parseLimit = Math.max(1, parseMax);
        int attributionLimit = Math.max(parseLimit, attributionMax);
        List<KpCandidate> parseSlice = new ArrayList<>(all.subList(0, Math.min(parseLimit, total)));
        List<KpCandidate> attributionSlice = new ArrayList<>(
                all.subList(0, Math.min(attributionLimit, total)));

        int truncated = Math.max(0, total - parseLimit);
        double rate = total == 0 ? 0d
                : BigDecimal.valueOf(truncated)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP).doubleValue();

        if (truncated > 0) {
            log.warn("[ExamKp] 候选截断: subjectId={}, grade={}, total={}, injected={}, rate={}",
                    subjectId, studentGrade, total, parseSlice.size(), rate);
            degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                    "subjectId=" + subjectId + ", grade=" + studentGrade + ", total=" + total
                            + ", injected=" + parseSlice.size() + ", rate=" + rate,
                    "知识点候选超过 parse 上限 " + parseLimit + "，按排序截断（兜底清单仍含被截断项）",
                    "truncate_kp_candidates", tenantId);
        }
        if (total > KP_UNREACHABLE_THRESHOLD) {
            log.warn("[ExamKp] 候选总量 {} 超过兜底可达上限 {}，可能出现不可归因", total, KP_UNREACHABLE_THRESHOLD);
            degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                    "subjectId=" + subjectId + ", grade=" + studentGrade + ", total=" + total,
                    "知识点候选总量超过 " + KP_UNREACHABLE_THRESHOLD + "，兜底可能不可达",
                    "kp_unreachable", tenantId);
        }

        log.info("[ExamKp] 候选快照: subjectId={}, grade={}, total={}, parse={}, attribution={}",
                subjectId, studentGrade, total, parseSlice.size(), attributionSlice.size());
        return new CandidateSnapshot(parseSlice, attributionSlice, total, truncated, rate);
    }

    @Override
    public String renderForPrompt(List<KpCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return EMPTY_PROMPT_TEXT;
        }
        StringBuilder sb = new StringBuilder(candidates.size() * 40);
        for (KpCandidate candidate : candidates) {
            String name = candidate.name() == null ? "" : candidate.name().replaceAll("\\s+", " ").trim();
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(candidate.knowledgePointId()).append(" | ").append(name);
        }
        return sb.toString();
    }

    /**
     * 科目定位（A7）：按 {@code subjectId + tenantId} 查科目表（禁止按名称裸查/跨租户命中）；
     * {@code knowledge_point} 只有 subject 名称列（零 DDL），因此先用 subjectId 校验归属，
     * 再用该名称查知识点——名称仅作连接键与 Prompt 展示，不再作为外部输入。
     */
    /**
     * 科目定位（A7）：按 {@code subjectId + tenantId} 查科目表（禁止按名称裸查/跨租户命中）；
     * {@code knowledge_point} 只有 subject 名称列（零 DDL），因此先用 subjectId 校验归属，
     * 再用该名称查知识点——名称仅作连接键与 Prompt 展示，不再作为外部输入。
     */
    private String resolveSubjectName(String tenantId, Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        com.jiang.model.entity.Subject subject = subjectMapper.selectByIdAndTenant(subjectId, tenantId);
        return subject == null ? null : subject.getName();
    }

    /**
     * 清空缓存（知识点导入/治理后可主动失效；单测用）
     */
    public void clearCache() {
        cache.clear();
    }

    private record CachedSnapshot(CandidateSnapshot snapshot, long createdAt) {
    }
}
