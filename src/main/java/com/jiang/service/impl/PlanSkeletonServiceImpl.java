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
 * <p>流程：查询真实数据 → 调规划 Agent → 落 {@code daily_plan} 骨架。
 * Agent 失败时回退为"按章节顺序均匀分配"的模板计划（规则 #6 降级策略）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Service
@Slf4j
public class PlanSkeletonServiceImpl implements PlanSkeletonService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 模板降级时每日题量
     */
    private static final int TEMPLATE_DAILY_QUESTION_COUNT = 10;

    /**
     * 单日最多安排知识点数
     */
    private static final int MAX_KP_PER_DAY = 2;

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
        LearningGoal goal = goalMapper.selectById(goalId);
        if (goal == null) {
            log.warn("生成计划骨架失败：目标不存在 goalId={}", goalId);
            return 0;
        }

        LocalDate start = goal.getStartDate();
        LocalDate end = goal.getEndDate();
        if (start == null || end == null || end.isBefore(start)) {
            log.warn("生成计划骨架失败：目标周期非法 goalId={}, start={}, end={}", goalId, start, end);
            return 0;
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
            return 0;
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
            return 0;
        }

        Map<String, Double> masteryMap = loadMastery(goal.getStudentId(), tenantId, subjectName);

        PlanningContextDTO context = new PlanningContextDTO();
        context.setGoalId(goalId);
        context.setSubjectName(subjectName);
        context.setStartDate(start);
        context.setEndDate(end);
        context.setTargetScore(goal.getTargetScore());
        context.setDirection(goal.getDirection());
        context.setStudentGrade(studentGrade);
        List<PlanningContextDTO.KnowledgePointBrief> briefs = new ArrayList<>();
        for (KnowledgePoint kp : activeKps) {
            PlanningContextDTO.KnowledgePointBrief brief = new PlanningContextDTO.KnowledgePointBrief();
            brief.setKpId(kp.getKnowledgePointId());
            brief.setName(kp.getName());
            brief.setGradeRange(kp.getGradeRange());
            brief.setMastery(masteryMap.get(kp.getKnowledgePointId()));
            briefs.add(brief);
        }
        context.setKnowledgePoints(briefs);

        PlanDraft draft;
        try {
            draft = planningAgent.generate(context);
            log.info("规划 Agent 成功: goalId={}, days={}", goalId, draft.getDays() == null ? 0 : draft.getDays().size());
        } catch (Exception e) {
            log.warn("规划 Agent 失败，回退模板计划: goalId={}, error={}", goalId, e.getMessage());
            draft = buildTemplateDraft(context, activeKps);
        }

        int inserted = persist(draft, goalId, tenantId, start, end);
        log.info("计划骨架生成完成: goalId={}, 周期={} ~ {}, 新建计划={}", goalId, start, end, inserted);
        return inserted;
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

    /**
     * 模板降级：按章节顺序把知识点均匀铺到每一天（规则 #6）
     */
    private PlanDraft buildTemplateDraft(PlanningContextDTO context, List<KnowledgePoint> kps) {
        List<KnowledgePoint> ordered = kps.stream()
                .sorted(Comparator.comparing(KnowledgePoint::getKnowledgePointId))
                .toList();

        long totalDays = ChronoUnit.DAYS.between(context.getStartDate(), context.getEndDate()) + 1;
        List<PlanDraft.PlanDay> days = new ArrayList<>();
        int kpCursor = 0;
        for (int i = 0; i < totalDays; i++) {
            PlanDraft.PlanDay day = new PlanDraft.PlanDay();
            day.setDate(context.getStartDate().plusDays(i));
            List<String> kpIds = new ArrayList<>();
            for (int j = 0; j < MAX_KP_PER_DAY && !ordered.isEmpty(); j++) {
                kpIds.add(ordered.get(kpCursor % ordered.size()).getKnowledgePointId());
                kpCursor++;
            }
            day.setKpIds(kpIds);
            day.setQuestionCount(TEMPLATE_DAILY_QUESTION_COUNT);
            day.setFocus("模板计划：" + ordered.get(kpCursor % ordered.size()).getName());
            days.add(day);
        }
        PlanDraft draft = new PlanDraft();
        draft.setDays(days);
        draft.setReason("规划 Agent 不可用，按知识点顺序均匀分配（模板降级）");
        return draft;
    }

    private int persist(PlanDraft draft, Long goalId, String tenantId, LocalDate start, LocalDate end) {
        if (draft.getDays() == null || draft.getDays().isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (PlanDraft.PlanDay day : draft.getDays()) {
            LocalDate date = day.getDate();
            if (date == null || date.isBefore(start) || date.isAfter(end)) {
                log.warn("跳过周期外的计划日: goalId={}, date={}", goalId, date);
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
            if (day.getKpIds() != null && !day.getKpIds().isEmpty()) {
                try {
                    plan.setPlannedKpIds(OBJECT_MAPPER.writeValueAsString(day.getKpIds()));
                } catch (Exception e) {
                    log.warn("知识点序列化失败，跳过骨架知识点: date={}", date);
                }
            }
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
}
