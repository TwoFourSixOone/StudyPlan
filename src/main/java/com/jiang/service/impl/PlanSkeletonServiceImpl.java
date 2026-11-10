package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.PlanningAgent;
import com.jiang.common.util.GradeMatcher;
import com.jiang.dto.PlanDraft;
import com.jiang.dto.PlanningContextDTO;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.Student;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.PlanStatus;
import com.jiang.service.PlanSkeletonService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日计划骨架服务实现（产品规则 #1）
 *
 * <p>两段式：模板骨架同步落库（秒级），规划 Agent 细化异步执行。
 * 这样即使 LLM 超时（PT2M），创建目标也不受影响。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Service
@Slf4j
public class PlanSkeletonServiceImpl implements PlanSkeletonService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 模板骨架每日题量
     */
    private static final int TEMPLATE_DAILY_QUESTION_COUNT = 10;

    /**
     * 单日最多安排知识点数
     */
    private static final int MAX_KP_PER_DAY = 2;

    /**
     * 送给规划 Agent 的知识点上限：全量清单会让 Prompt 过大、生成超时
     */
    private static final int MAX_KP_FOR_AGENT = 120;

    @Autowired
    private LearningGoalMapper goalMapper;

    @Autowired
    private SubjectMapper subjectMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private StudentKnowledgeStateMapper stateMapper;

    @Autowired
    private StudentMapper studentMapper;

    @Autowired
    private DailyPlanMapper dailyPlanMapper;

    @Autowired
    private PlanningAgent planningAgent;

    @Override
    public int generateSkeleton(Long goalId, String tenantId) {
        SkeletonContext ctx = loadContext(goalId, tenantId);
        if (ctx == null) {
            return 0;
        }
        PlanDraft draft = buildTemplateDraft(ctx);
        int inserted = persist(draft, goalId, tenantId, ctx.start, ctx.end);
        log.info("模板骨架已生成: goalId={}, 周期={} ~ {}, 新建计划={}", goalId, ctx.start, ctx.end, inserted);
        return inserted;
    }

    @Override
    public int refineWithAgent(Long goalId, String tenantId) {
        SkeletonContext ctx = loadContext(goalId, tenantId);
        if (ctx == null) {
            return 0;
        }
        PlanDraft draft;
        try {
            draft = planningAgent.generate(ctx.context);
        } catch (Exception e) {
            // 异步细化失败：模板骨架已在，无需补偿
            log.warn("规划 Agent 细化失败，保留模板骨架: goalId={}, error={}", goalId, e.getMessage());
            return 0;
        }
        if (draft.getDays() == null || draft.getDays().isEmpty()) {
            return 0;
        }
        int updated = updateExisting(draft, goalId, tenantId, ctx.start, ctx.end);
        log.info("规划 Agent 细化完成: goalId={}, days={}, 更新计划={}", goalId, draft.getDays().size(), updated);
        return updated;
    }

    // ==================== 上下文 ====================

    private SkeletonContext loadContext(Long goalId, String tenantId) {
        LearningGoal goal = goalMapper.selectById(goalId);
        if (goal == null) {
            log.warn("生成计划骨架失败：目标不存在 goalId={}", goalId);
            return null;
        }
        LocalDate start = goal.getStartDate();
        LocalDate end = goal.getEndDate();
        if (start == null || end == null || end.isBefore(start)) {
            log.warn("生成计划骨架失败：目标周期非法 goalId={}, start={}, end={}", goalId, start, end);
            return null;
        }

        Subject subject = subjectMapper.selectById(goal.getSubjectId());
        String subjectName = subject != null ? subject.getName() : null;

        List<KnowledgePoint> activeKps = knowledgePointMapper.selectList(
                new LambdaQueryWrapper<KnowledgePoint>()
                        .eq(KnowledgePoint::getStatus, "active")
                        .eq(KnowledgePoint::getTenantId, tenantId)
                        .eq(subjectName != null, KnowledgePoint::getSubject, subjectName)
        );
        if (activeKps.isEmpty()) {
            log.warn("生成计划骨架跳过：科目无已启用知识点 goalId={}, subject={}", goalId, subjectName);
            return null;
        }

        Student student = studentMapper.selectById(goal.getStudentId());
        Integer studentGrade = GradeMatcher.normalizeStudentGrade(student == null ? null : student.getGrade());
        if (studentGrade != null) {
            activeKps = activeKps.stream()
                    .filter(kp -> GradeMatcher.matchesRange(studentGrade, kp.getGradeRange()))
                    .toList();
        }
        if (activeKps.isEmpty()) {
            log.warn("生成计划骨架跳过：年级过滤后无知识点 goalId={}, grade={}", goalId, studentGrade);
            return null;
        }

        Map<String, Double> masteryMap = loadMastery(goal.getStudentId(), tenantId, subjectName);

        SkeletonContext ctx = new SkeletonContext();
        ctx.start = start;
        ctx.end = end;
        ctx.activeKps = activeKps;
        ctx.context = buildContext(goal, subjectName, studentGrade, activeKps, masteryMap);
        return ctx;
    }

    private PlanningContextDTO buildContext(LearningGoal goal, String subjectName, Integer studentGrade,
                                            List<KnowledgePoint> activeKps, Map<String, Double> masteryMap) {
        // 掌握度低的优先送审；限制数量，避免 Prompt 过大导致超时
        List<KnowledgePoint> forAgent = new ArrayList<>(activeKps);
        forAgent.sort(Comparator
                .comparing((KnowledgePoint kp) -> masteryMap.getOrDefault(kp.getKnowledgePointId(), 1.0))
                .thenComparing(KnowledgePoint::getKnowledgePointId));
        boolean truncated = forAgent.size() > MAX_KP_FOR_AGENT;
        if (truncated) {
            forAgent = forAgent.subList(0, MAX_KP_FOR_AGENT);
            log.info("知识点过多，仅送规划 Agent 前 {} 个（按掌握度升序）: goalId={}, 总数={}",
                    MAX_KP_FOR_AGENT, goal.getGoalId(), activeKps.size());
        }

        PlanningContextDTO context = new PlanningContextDTO();
        context.setGoalId(goal.getGoalId());
        context.setSubjectName(subjectName);
        context.setStartDate(goal.getStartDate());
        context.setEndDate(goal.getEndDate());
        context.setTargetScore(goal.getTargetScore());
        context.setDirection(goal.getDirection());
        context.setStudentGrade(studentGrade);

        List<PlanningContextDTO.KnowledgePointBrief> briefs = new ArrayList<>();
        for (KnowledgePoint kp : forAgent) {
            PlanningContextDTO.KnowledgePointBrief brief = new PlanningContextDTO.KnowledgePointBrief();
            brief.setKpId(kp.getKnowledgePointId());
            brief.setName(kp.getName());
            brief.setGradeRange(kp.getGradeRange());
            brief.setMastery(masteryMap.get(kp.getKnowledgePointId()));
            briefs.add(brief);
        }
        context.setKnowledgePoints(briefs);
        return context;
    }

    private Map<String, Double> loadMastery(Long studentId, String tenantId, String subjectName) {
        Map<String, Double> map = new HashMap<>();
        try {
            List<StudentKnowledgeState> states = stateMapper.selectActiveStatesWithKpInfo(
                    studentId, tenantId, subjectName, false);
            if (states != null) {
                for (StudentKnowledgeState state : states) {
                    if (state.getMasteryScore() != null) {
                        map.put(state.getKnowledgePointId(), state.getMasteryScore().doubleValue());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询掌握度失败，规划将按未测处理: {}", e.getMessage());
        }
        return map;
    }

    // ==================== 骨架构造与落库 ====================

    /**
     * 模板计划：按知识点 ID 顺序轮转，每天 MAX_KP_PER_DAY 个
     */
    private PlanDraft buildTemplateDraft(SkeletonContext ctx) {
        List<KnowledgePoint> ordered = ctx.activeKps.stream()
                .sorted(Comparator.comparing(KnowledgePoint::getKnowledgePointId))
                .toList();

        long totalDays = ChronoUnit.DAYS.between(ctx.start, ctx.end) + 1;
        List<PlanDraft.PlanDay> days = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < totalDays; i++) {
            PlanDraft.PlanDay day = new PlanDraft.PlanDay();
            day.setDate(ctx.start.plusDays(i));
            List<String> kpIds = new ArrayList<>();
            for (int j = 0; j < MAX_KP_PER_DAY && !ordered.isEmpty(); j++) {
                kpIds.add(ordered.get(cursor % ordered.size()).getKnowledgePointId());
                cursor++;
            }
            day.setKpIds(kpIds);
            day.setQuestionCount(TEMPLATE_DAILY_QUESTION_COUNT);
            day.setFocus("模板计划");
            days.add(day);
        }
        PlanDraft draft = new PlanDraft();
        draft.setDays(days);
        draft.setReason("模板骨架：按知识点顺序均匀分配");
        return draft;
    }

    private int persist(PlanDraft draft, Long goalId, String tenantId, LocalDate start, LocalDate end) {
        int inserted = 0;
        for (PlanDraft.PlanDay day : draft.getDays()) {
            LocalDate date = day.getDate();
            if (date == null || date.isBefore(start) || date.isAfter(end)) {
                continue;
            }
            if (dailyPlanMapper.selectByGoalIdAndDate(goalId, tenantId, date) != null) {
                continue;
            }
            DailyPlan plan = new DailyPlan();
            plan.setGoalId(goalId);
            plan.setPlanDate(date);
            plan.setStatus(PlanStatus.PENDING_GENERATION.getCode());
            plan.setContent("[]");
            plan.setTenantId(tenantId);
            plan.setCreateTime(LocalDateTime.now());
            plan.setUpdateTime(LocalDateTime.now());
            plan.setPlannedKpIds(toJson(day.getKpIds()));
            plan.setPlannedQuestionCount(day.getQuestionCount());
            try {
                dailyPlanMapper.insert(plan);
                inserted++;
            } catch (DuplicateKeyException e) {
                log.debug("计划已存在，跳过: goalId={}, date={}", goalId, date);
            }
        }
        return inserted;
    }

    /**
     * Agent 细化：只更新仍待生成的计划，已开始/已完成的日期不动
     */
    private int updateExisting(PlanDraft draft, Long goalId, String tenantId, LocalDate start, LocalDate end) {
        int updated = 0;
        for (PlanDraft.PlanDay day : draft.getDays()) {
            LocalDate date = day.getDate();
            if (date == null || date.isBefore(start) || date.isAfter(end)) {
                continue;
            }
            DailyPlan plan = dailyPlanMapper.selectByGoalIdAndDate(goalId, tenantId, date);
            if (plan == null || !PlanStatus.PENDING_GENERATION.getCode().equals(plan.getStatus())) {
                continue;
            }
            plan.setPlannedKpIds(toJson(day.getKpIds()));
            plan.setPlannedQuestionCount(day.getQuestionCount());
            plan.setUpdateTime(LocalDateTime.now());
            dailyPlanMapper.updateById(plan);
            updated++;
        }
        return updated;
    }

    private String toJson(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(ids);
        } catch (Exception e) {
            log.warn("知识点序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 内部上下文容器
     */
    private static class SkeletonContext {
        private LocalDate start;
        private LocalDate end;
        private List<KnowledgePoint> activeKps;
        private PlanningContextDTO context;
    }
}
