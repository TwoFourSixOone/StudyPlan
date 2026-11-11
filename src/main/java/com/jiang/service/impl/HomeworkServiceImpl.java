package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.HomeworkDegradationHandler;
import com.jiang.agent.QuestionGenAgent;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.ChoiceOptions;
import com.jiang.common.util.DailyPlanContentParser;
import com.jiang.common.util.GradeMatcher;
import com.jiang.common.util.LlmTextSanitizer;
import com.jiang.common.util.HomeworkQuotaCalculator;
import com.jiang.common.util.SimilarityDetector;
import com.jiang.dto.DailyPlanQuestionRef;
import com.jiang.dto.KnowledgePointWithMastery;
import com.jiang.dto.SubmitAnswerRequest;
import com.jiang.client.AgentScopeClient;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.ErrorQuestionMapper;
import com.jiang.mapper.GeneratedQuestionMapper;
import com.jiang.mapper.HomeworkSubmissionDetailMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.HomeworkSubmissionDetail;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.entity.Student;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.ErrorQuestionSourceType;
import com.jiang.model.enums.PlanStatus;
import com.jiang.security.StudentContext;
import com.jiang.service.ErrorQuestionService;
import com.jiang.service.GoalService;
import com.jiang.service.HomeworkService;
import com.jiang.service.MasteryService;
import com.jiang.service.SimilarQuestionService;
import com.jiang.vo.GradeResultVO;
import com.jiang.vo.GoalVO;
import com.jiang.vo.HomeworkVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * 每日作业 Service 实现
 * <p>
 * V1 Schema 适配：
 * - daily_plan 使用 goal_id（非 student_id），content JSON（非 question_ids）
 * - 无 version 字段，并发控制使用状态机校验（WHERE status = #{expectedStatus}）
 * - homework_submission_detail 包含 question_source、question_type、submitted_at
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Service
@Slf4j
public class HomeworkServiceImpl implements HomeworkService {

    private static final int MAX_AGENT_RETRIES = 2;

    /**
     * 题库抽题的跨天去重窗口（天）：排除近 N 天已排入计划的题库题。
     * 题库每知识点仅 4 道题，窗口过大会让第二天起几乎无题可抽。
     */
    private static final int BANK_DEDUPE_WINDOW_DAYS = 7;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TransactionTemplate transactionTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private GoalService goalService;

    @Autowired
    private MasteryService masteryService;

    @Autowired
    private DailyPlanMapper dailyPlanMapper;

    @Autowired
    private HomeworkSubmissionDetailMapper submissionMapper;

    /**
     * 题库抽题器（产品规则 #3：题库优先，不足由 Agent 补足）
     */
    @Autowired
    private BankQuestionPicker bankQuestionPicker;

    @Autowired
    private GeneratedQuestionMapper generatedQuestionMapper;

    @Autowired
    private QuestionBankMapper questionBankMapper;

    @Autowired
    private LearningGoalMapper learningGoalMapper;

    @Autowired
    private SubjectMapper subjectMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private StudentKnowledgeStateMapper stateMapper;

    @Autowired
    private QuestionGenAgent questionGenAgent;

    @Autowired
    private HomeworkDegradationHandler degradationHandler;

    @Autowired
    private DailyPlanContentParser contentParser;

    @Autowired
    private ErrorQuestionService errorQuestionService;

    @Autowired
    private SimilarQuestionService similarQuestionService;

    @Autowired
    private ErrorQuestionMapper errorQuestionMapper;

    @Autowired
    private StudentMapper studentMapper;

    @Autowired
    private AgentScopeClient agentScopeClient;

    @Autowired
    private ThreadPoolExecutor aiExecutor;

    @PostConstruct
    public void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ==================== generateDailyHomework ====================

    @Override
    public void generateDailyHomework(Long studentId, String tenantId, Long subjectId, LocalDate planDate) {
        // 1. 获取 active goal（OBS-3: GoalService 内部从 StudentContext 获取 studentId/tenantId）
        GoalVO goal = getActiveGoalOrThrow(subjectId);
        Long goalId = goal.getGoalId();

        // 1.1 日期必须在目标周期内：避免手动生成周期外的作业（产品规则 #1）
        if (goal.getStartDate() != null && planDate.isBefore(goal.getStartDate())
                || goal.getEndDate() != null && planDate.isAfter(goal.getEndDate())) {
            log.warn("作业日期不在目标周期内: goalId={}, planDate={}, 周期={} ~ {}",
                    goalId, planDate, goal.getStartDate(), goal.getEndDate());
            throw new BusinessException(ErrorCodes.HOMEWORK_DATE_OUT_OF_RANGE);
        }

        // 2. 查询 daily_plan
        DailyPlan existingPlan = dailyPlanMapper.selectByGoalIdAndDate(goalId, tenantId, planDate);
        if (existingPlan != null) {
            String status = existingPlan.getStatus();
            if (PlanStatus.IN_PROGRESS.getCode().equals(status) || PlanStatus.COMPLETED.getCode().equals(status)) {
                throw new BusinessException(ErrorCodes.HOMEWORK_ALREADY_EXISTS);
            }
            // pending_generation / pending → 允许覆盖
        }

        // 3. 查询学生掌握度状态（复用 StudentKnowledgeStateMapper JOIN 查询，直接获取 mastery 数据）
        String subjectName = getSubjectName(goal.getSubjectId());
        List<StudentKnowledgeState> states = stateMapper.selectActiveStatesWithKpInfo(studentId, tenantId, subjectName, false);

        // 4. 查询已启用知识点列表
        List<KnowledgePoint> activeKps = knowledgePointMapper.selectList(
                new LambdaQueryWrapper<KnowledgePoint>()
                        .eq(KnowledgePoint::getSubject, subjectName)
                        .eq(KnowledgePoint::getStatus, "active")
                        .eq(KnowledgePoint::getTenantId, tenantId)
        );

        // 产品规则 #1 边界 / #8：待校对的知识点不参与出题。
        // 若该科目没有已启用知识点，必须显式报错——否则会静默写入一条 content=[] 的空作业
        if (activeKps.isEmpty()) {
            log.warn("科目无已启用知识点，无法生成作业: subjectId={}, subjectName={}, tenantId={}",
                    subjectId, subjectName, tenantId);
            throw new BusinessException(ErrorCodes.SUBJECT_NO_ACTIVE_KP);
        }

        // 5. 构造 Agent 输入：合并掌握度状态 + 知识点信息
        Map<String, StudentKnowledgeState> stateMap = states.stream()
                .collect(Collectors.toMap(StudentKnowledgeState::getKnowledgePointId, s -> s, (a, b) -> a));

        List<KnowledgePointWithMastery> kpList = new ArrayList<>();
        for (KnowledgePoint kp : activeKps) {
            StudentKnowledgeState state = stateMap.get(kp.getKnowledgePointId());
            kpList.add(KnowledgePointWithMastery.builder()
                    .knowledgePointId(kp.getKnowledgePointId())
                    .name(kp.getName())
                    .subject(kp.getSubject())
                    .gradeRange(kp.getGradeRange())
                    .masteryScore(state != null ? state.getMasteryScore() : null)
                    .confidence(state != null ? state.getConfidence() : null)
                    .build());
        }

        // 5.1 年级过滤（产品规则 #11）：严格优先 + 整体降级
        // 只收窄内容池，不改配额算法；整体为空时关闭过滤，保证当日仍能出题
        Integer studentGrade = GradeMatcher.normalizeStudentGrade(getStudentGrade(studentId));
        if (studentGrade != null) {
            List<KnowledgePointWithMastery> gradeFiltered = kpList.stream()
                    .filter(kp -> GradeMatcher.matchesRange(studentGrade, kp.getGradeRange()))
                    .collect(Collectors.toList());
            if (gradeFiltered.isEmpty() && !kpList.isEmpty()) {
                log.warn("年级过滤后知识点为空，整体降级为不过滤: studentId={}, grade={}, 原始知识点数={}",
                        studentId, studentGrade, kpList.size());
            } else {
                log.info("年级过滤生效: studentId={}, grade={}, 知识点 {} -> {}",
                        studentId, studentGrade, kpList.size(), gradeFiltered.size());
                kpList = gradeFiltered;
            }
        }
        // 5.1.1 计划骨架（产品规则 #1）：当天若已有规划知识点，按其收窄
        List<String> plannedKpIds = parsePlannedKpIds(existingPlan);
        if (!plannedKpIds.isEmpty()) {
            List<KnowledgePointWithMastery> plannedFiltered = kpList.stream()
                    .filter(kp -> plannedKpIds.contains(kp.getKnowledgePointId()))
                    .collect(Collectors.toList());
            if (plannedFiltered.isEmpty()) {
                log.warn("骨架知识点与当前可用知识点无交集，忽略骨架: goalId={}, plannedCount={}",
                        goalId, plannedKpIds.size());
            } else {
                log.info("按计划骨架收窄知识点: goalId={}, {} -> {}", goalId, kpList.size(), plannedFiltered.size());
                kpList = plannedFiltered;
            }
        }

        // 5.2 调整策略（产品规则 #5）：目标上的"暂缓知识点"与"日题量上限"
        LearningGoal goalEntity = learningGoalMapper.selectById(goalId);
        Integer dailyQuestionLimit = goalEntity == null ? null : goalEntity.getDailyQuestionLimit();
        List<String> pausedKpIds = parsePausedKpIds(goalEntity == null ? null : goalEntity.getPausedKpIds());
        if (!pausedKpIds.isEmpty()) {
            List<KnowledgePointWithMastery> afterPaused = kpList.stream()
                    .filter(kp -> !pausedKpIds.contains(kp.getKnowledgePointId()))
                    .collect(Collectors.toList());
            if (afterPaused.isEmpty()) {
                // 暂缓后无题可出 → 忽略暂缓（与年级过滤的整体降级策略一致）
                log.warn("暂缓知识点导致无可出题知识点，忽略暂缓: goalId={}, paused={}", goalId, pausedKpIds);
            } else {
                log.info("暂缓知识点生效: goalId={}, 知识点 {} -> {}", goalId, kpList.size(), afterPaused.size());
                kpList = afterPaused;
            }
        }

        Set<String> gradeKpIds = kpList.stream()
                .map(KnowledgePointWithMastery::getKnowledgePointId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 5.2 题库优先（产品规则 #3）：准备知识点年级范围映射 + 学生已做过的题库题
        Map<String, String> kpGradeRangeMap = new HashMap<>();
        for (KnowledgePointWithMastery kp : kpList) {
            kpGradeRangeMap.put(kp.getKnowledgePointId(), kp.getGradeRange());
        }
        Set<Long> usedBankQuestionIds = new LinkedHashSet<>();
        try {
            List<Long> answered = submissionMapper.selectAnsweredBankQuestionIds(studentId, tenantId);
            if (answered != null) {
                usedBankQuestionIds.addAll(answered);
            }
        } catch (Exception e) {
            // 查询失败不阻塞出题：退化为仅本次计划内去重
            log.error("查询学生已做题库题失败，仅按本次计划去重: studentId={}, error={}", studentId, e.getMessage());
        }

        // 5.3 排除近 7 天已排进计划、但还未作答的题库题
        // 否则连续两天生成（中间没做作业）会抽到同一道题
        try {
            LocalDate windowStart = planDate.minusDays(BANK_DEDUPE_WINDOW_DAYS);
            List<DailyPlan> recentPlans = dailyPlanMapper.selectList(
                    new LambdaQueryWrapper<DailyPlan>()
                            .eq(DailyPlan::getTenantId, tenantId)
                            .eq(DailyPlan::getGoalId, goalId)
                            .ge(DailyPlan::getPlanDate, windowStart)
            );
            int parsedPlans = 0;
            for (DailyPlan recent : recentPlans) {
                if (recent.getContent() == null || recent.getContent().isEmpty()) {
                    continue;
                }
                List<DailyPlanQuestionRef> refs = contentParser.parse(recent.getContent());
                if (refs == null) {
                    continue;
                }
                for (DailyPlanQuestionRef ref : refs) {
                    if ("bank".equals(ref.getQuestionSource()) && ref.getQuestionId() != null) {
                        usedBankQuestionIds.add(ref.getQuestionId());
                    }
                }
                parsedPlans++;
            }
            if (parsedPlans > 0) {
                log.info("近 {} 天计划已占用 {} 道题库题，本次抽题将排除", BANK_DEDUPE_WINDOW_DAYS, usedBankQuestionIds.size());
            }
        } catch (Exception e) {
            log.warn("查询近 {} 天计划失败，题库去重退化为仅按已作答题目: {}", BANK_DEDUPE_WINDOW_DAYS, e.getMessage());
        }

        // 6. T-06 Part A: 查询到期错题（在 Agent 调用前）
        // BLOCK-H: 内部已按 knowledge_point_id 分组，每组 ≤ 3 道
        // IMP-AB: 返回 LinkedHashMap，key 顺序由 ORDER BY knowledge_point_id ASC 决定
        Map<String, List<com.jiang.vo.DueErrorQuestionVO>> dueErrorMap = new java.util.LinkedHashMap<>(
                errorQuestionService.getDueErrorQuestions(studentId, tenantId, planDate));

        // 6.1 错题必须属于当前科目：getDueErrorQuestions 只按学生查询，
        // 不过滤会把其他科目的错题塞进本作业（联调实测英语作业里出现数学题）
        Set<String> subjectKpIds = activeKps.stream()
                .map(KnowledgePoint::getKnowledgePointId)
                .collect(Collectors.toSet());
        int dueKpBeforeFilter = dueErrorMap.size();
        dueErrorMap.keySet().removeIf(kpId -> !subjectKpIds.contains(kpId));
        if (dueErrorMap.size() < dueKpBeforeFilter) {
            log.info("错题科目过滤: 到期知识点 {} -> {}（已排除其他科目）",
                    dueKpBeforeFilter, dueErrorMap.size());
        }

        // 组装错题引用列表（taskType=redo, questionSource=bank）
        List<DailyPlanQuestionRef> errorRefs = new ArrayList<>();
        Set<Long> occupiedQuestionIds = new LinkedHashSet<>();
        for (List<com.jiang.vo.DueErrorQuestionVO> voList : dueErrorMap.values()) {
            for (com.jiang.vo.DueErrorQuestionVO vo : voList) {
                errorRefs.add(new DailyPlanQuestionRef(
                        vo.getQuestionId(), "bank", "redo"));
                occupiedQuestionIds.add(vo.getQuestionId());
            }
        }

        // ===== 5. 配额计算（Change 8: HomeworkQuotaCalculator）=====
        // 构建 kpMasteryMap（kpId → masteryScore）
        Map<String, BigDecimal> kpMasteryMap = kpList.stream()
                .collect(Collectors.toMap(
                        KnowledgePointWithMastery::getKnowledgePointId,
                        kp -> kp.getMasteryScore() != null ? kp.getMasteryScore() : BigDecimal.ZERO,
                        (a, b) -> a));

        // 构建 kpErrorCountMap（kpId → 活跃错题总数，用于配额计算）
        Set<String> errorKpIds = dueErrorMap.keySet();
        // 年级过滤后，被排除的知识点不参与「举一反三」配额（仍保留其原题重做）
        Set<String> quotaErrorKpIds = errorKpIds.stream()
                .filter(gradeKpIds::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (quotaErrorKpIds.size() != errorKpIds.size()) {
            log.info("年级过滤：{} 个有错题知识点不参与举一反三配额（仅保留原题重做）",
                    errorKpIds.size() - quotaErrorKpIds.size());
        }
        int dueErrorCount = errorRefs.size();
        List<Map<String, Object>> activeErrorCounts = errorQuestionMapper.selectActiveErrorCountGroupByKp(studentId, tenantId);
        Map<String, Integer> kpErrorCountMap = activeErrorCounts.stream()
                .collect(Collectors.toMap(
                        m -> (String) m.get("knowledge_point_id"),
                        m -> ((Number) m.get("error_count")).intValue(),
                        (a, b) -> a));

        HomeworkQuotaCalculator.QuotaAllocation quota;
        try {
            Integer plannedCount = existingPlan == null ? null : existingPlan.getPlannedQuestionCount();
         int dailyLimit = plannedCount != null
                 ? plannedCount
                 : (dailyQuestionLimit == null ? HomeworkQuotaCalculator.DAILY_MAX : dailyQuestionLimit);
         quota = HomeworkQuotaCalculator.calculate(dueErrorCount, quotaErrorKpIds, kpMasteryMap, kpErrorCountMap, dailyLimit);
        } catch (IllegalArgumentException e) {
            log.error("配额计算入参非法: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.BAD_REQUEST,
                    "配额计算失败: " + e.getMessage());
        }
        log.info("配额计算结果: errorQuota={}, similarQuota={}, reviewQuota={}, extremeMode={}, degradedMode={}",
                quota.getErrorQuota(), quota.getSimilarQuota(), quota.getReviewQuota(),
                quota.isExtremeMode(), quota.isDegradedMode());

        // ===== 6. 组装错题引用（taskType=redo）— 已在步骤 4 完成 =====

        // ===== 7. 举一反三新题生成（按配额为每个有错题知识点生成）=====
        // T-07: 构建活跃知识点 ID 集合，用于过滤停用知识点
        Set<String> activeKpIds = activeKps.stream()
                .map(KnowledgePoint::getKnowledgePointId)
                .collect(Collectors.toSet());

        List<DailyPlanQuestionRef> similarRefs = new ArrayList<>();
        List<GeneratedQuestion> allGeneratedQuestions = new ArrayList<>();

        if (!quota.isExtremeMode()) {
            for (Map.Entry<String, Integer> entry : quota.getSimilarQuotaPerKp().entrySet()) {
                String kpId = entry.getKey();
                int targetCount = entry.getValue();
                if (targetCount <= 0) continue;

                // T-07: 知识点停用检查——disabled 知识点跳过举一反三
                if (!activeKpIds.contains(kpId)) {
                    log.info("知识点 {} 已停用，跳过举一反三生成", kpId);
                    continue;
                }

                // 产品规则 #11：被年级过滤的知识点只保留原题重做，不生成新题
                if (!gradeKpIds.contains(kpId)) {
                    log.info("知识点 {} 被年级过滤，跳过举一反三（仅保留原题重做）", kpId);
                    continue;
                }

                // 产品规则 #3：优先从题库抽题（排除本次已选 + 历史已做），不足部分再由 Agent 生成
                List<QuestionBank> bankPicked = bankQuestionPicker.pick(
                        kpId, tenantId, targetCount, studentGrade,
                        kpGradeRangeMap.get(kpId), usedBankQuestionIds);
                for (QuestionBank picked : bankPicked) {
                    similarRefs.add(new DailyPlanQuestionRef(picked.getQuestionId(), "bank", "similar"));
                    usedBankQuestionIds.add(picked.getQuestionId());
                }
                int remaining = targetCount - bankPicked.size();
                if (remaining <= 0) {
                    log.info("知识点 {} 举一反三全部命中题库（{} 道）", kpId, bankPicked.size());
                    continue;
                }

                // 查询代表错题（最早到期的 1 道）
                QuestionBank sourceQuestion = errorQuestionMapper.selectSourceQuestionForSimilar(
                        studentId, tenantId, kpId, planDate);
                if (sourceQuestion == null) {
                    log.warn("知识点 {} 无可用源题，跳过举一反三", kpId);
                    continue;
                }

                // 查询已有新题内容（IMP-15: LIMIT 100）
                List<String> existingContents = generatedQuestionMapper.selectCheckedContentsByKp(kpId, tenantId);

                try {
                    List<GeneratedQuestion> similarQuestions = similarQuestionService.generateWithValidation(
                            studentId, tenantId, kpId,
                            kpMasteryMap.getOrDefault(kpId, BigDecimal.ZERO),
                            sourceQuestion, remaining,
                            sourceQuestion.getContent(), existingContents);

                    for (GeneratedQuestion gq : similarQuestions) {
                        similarRefs.add(new DailyPlanQuestionRef(0L, "generated", "similar"));
                        allGeneratedQuestions.add(gq);
                    }
                } catch (Exception e) {
                    log.error("知识点 {} 举一反三生成失败: {}", kpId, e.getMessage());
                    // 不阻塞其他知识点
                }
            }
        }

        // ===== 8. 知识回顾新题生成（使用配额计算结果 reviewQuota）=====
        // 过滤出无错题的知识点，按掌握度从低到高排序
        List<KnowledgePointWithMastery> reviewKpList = kpList.stream()
                .filter(kp -> !errorKpIds.contains(kp.getKnowledgePointId()))
                .sorted(Comparator.comparing(
                        kp -> kp.getMasteryScore() != null ? kp.getMasteryScore() : BigDecimal.ZERO))
                .collect(Collectors.toList());

        List<DailyPlanQuestionRef> reviewRefs;
        List<GeneratedQuestion> reviewGeneratedQuestions;
        int reviewQuota = quota.getReviewQuota();

        // 产品规则 #3：知识回顾同样题库优先——按掌握度升序逐知识点取题，直到配额用尽
        List<DailyPlanQuestionRef> reviewBankRefs = new ArrayList<>();
        if (!reviewKpList.isEmpty() && reviewQuota > 0) {
            int remainingReviewQuota = reviewQuota;
            for (KnowledgePointWithMastery kp : reviewKpList) {
                if (remainingReviewQuota <= 0) {
                    break;
                }
                List<QuestionBank> picked = bankQuestionPicker.pick(
                        kp.getKnowledgePointId(), tenantId, remainingReviewQuota, studentGrade,
                        kp.getGradeRange(), usedBankQuestionIds);
                for (QuestionBank qb : picked) {
                    reviewBankRefs.add(new DailyPlanQuestionRef(qb.getQuestionId(), "bank", "review"));
                    usedBankQuestionIds.add(qb.getQuestionId());
                    remainingReviewQuota--;
                }
            }
            if (!reviewBankRefs.isEmpty()) {
                log.info("知识回顾题库命中 {} 道，剩余 {} 道由 Agent 生成",
                        reviewBankRefs.size(), Math.max(0, reviewQuota - reviewBankRefs.size()));
            }
        }

        int agentReviewQuota = reviewQuota - reviewBankRefs.size();
        if (!reviewKpList.isEmpty() && reviewQuota > 0 && agentReviewQuota > 0) {
            Exception lastAgentException = null;
            List<DailyPlanQuestionRef> agentRefs = null;
            List<GeneratedQuestion> agentGenerated = null;
            for (int attempt = 0; attempt < MAX_AGENT_RETRIES; attempt++) {
                try {
                    QuestionGenAgent.QuestionGenResult result = questionGenAgent.generate(reviewKpList, agentReviewQuota);
                    agentRefs = result.getRefs();
                    agentGenerated = result.getGeneratedQuestions();
                    break;
                } catch (Exception e) {
                    log.warn("知识回顾 Agent 出题失败 (attempt {}/{}): {}", attempt + 1, MAX_AGENT_RETRIES, e.getMessage());
                    lastAgentException = e;
                }
            }

            if (agentRefs == null) {
                log.info("知识回顾 Agent 重试耗尽，触发降级策略");
                degradationHandler.logDegradation(
                        "subjectId=" + subjectId + ", date=" + planDate + ", type=review",
                        lastAgentException != null ? lastAgentException.getClass().getSimpleName()
                                + ": " + lastAgentException.getMessage() : "unknown",
                        tenantId);
                try {
                    agentRefs = degradationHandler.generateDefaultPlan(reviewKpList, tenantId, studentGrade);
                    agentGenerated = List.of();
                } catch (Exception e) {
                    log.error("知识回顾降级也失败: {}", e.getMessage());
                    agentRefs = List.of();
                    agentGenerated = List.of();
                }
            }

            reviewRefs = new ArrayList<>(reviewBankRefs);
            reviewRefs.addAll(agentRefs);
            reviewGeneratedQuestions = new ArrayList<>(agentGenerated);
        } else {
            reviewRefs = new ArrayList<>(reviewBankRefs);
            reviewGeneratedQuestions = List.of();
        }

        // ===== 9. 合并所有引用（错题 + 举一反三 + 知识回顾）=====
        List<DailyPlanQuestionRef> allRefs = new ArrayList<>(errorRefs);
        allRefs.addAll(similarRefs);
        if (reviewRefs != null) {
            allRefs.addAll(reviewRefs);
        }

        List<GeneratedQuestion> allNewQuestions = new ArrayList<>(allGeneratedQuestions);
        if (reviewGeneratedQuestions != null) {
            allNewQuestions.addAll(reviewGeneratedQuestions);
        }

        // ===== 10. 事务内执行写操作：创建/更新计划 + 落题 + 状态机更新 =====
        final List<DailyPlanQuestionRef> finalRefs = allRefs;
        final List<GeneratedQuestion> finalGeneratedQuestions = allNewQuestions;

        transactionTemplate.executeWithoutResult(status -> {
            DailyPlan plan = existingPlan;
            if (plan == null) {
                plan = new DailyPlan();
                plan.setGoalId(goalId);
                plan.setPlanDate(planDate);
                plan.setStatus(PlanStatus.PENDING_GENERATION.getCode());
                plan.setTenantId(tenantId);
                plan.setCreateTime(LocalDateTime.now());
                plan.setUpdateTime(LocalDateTime.now());
                try {
                    dailyPlanMapper.insert(plan);
                } catch (DuplicateKeyException e) {
                    plan = dailyPlanMapper.selectByGoalIdAndDate(goalId, tenantId, planDate);
                    if (plan == null) {
                        throw new BusinessException(ErrorCodes.HOMEWORK_CONCURRENT_CONFLICT,
                                "并发冲突：计划记录异常");
                    }
                    String planStatus = plan.getStatus();
                    if (PlanStatus.IN_PROGRESS.getCode().equals(planStatus)
                            || PlanStatus.COMPLETED.getCode().equals(planStatus)) {
                        throw new BusinessException(ErrorCodes.HOMEWORK_ALREADY_EXISTS);
                    }
                }
            }
            persistHomeworkResult(plan, finalRefs, finalGeneratedQuestions, tenantId);
        });
    }

    // ==================== getHomeworkByDate ====================

    @Override
    public List<HomeworkVO> getHomeworkByDate(Long studentId, String tenantId, Long subjectId, LocalDate planDate) {
        // 1. 直接根据 subjectId 和日期查询 daily_plan（不依赖活跃目标，支持查看历史作业）
        DailyPlan plan = dailyPlanMapper.selectBySubjectAndDate(subjectId, tenantId, planDate);
        if (plan == null) {
            return List.of();
        }

        // 3. IMP-B: 解析 content JSON
        List<DailyPlanQuestionRef> refs = contentParser.parse(plan.getContent());
        if (refs.isEmpty()) {
            return List.of();
        }

        // 4. NEW-1 双来源查询：按 questionSource 分组，批量查询（IMP-M: selectBatchIds 避免 N+1）
        Set<Long> bankIds = new LinkedHashSet<>();
        Set<Long> generatedIds = new LinkedHashSet<>();
        for (DailyPlanQuestionRef ref : refs) {
            if ("bank".equals(ref.getQuestionSource())) {
                bankIds.add(ref.getQuestionId());
            } else if ("generated".equals(ref.getQuestionSource())) {
                generatedIds.add(ref.getQuestionId());
            }
        }

        Map<Long, QuestionBank> bankMap = bankIds.isEmpty() ? Map.of() :
                questionBankMapper.selectBatchIds(bankIds).stream()
                        .collect(Collectors.toMap(QuestionBank::getQuestionId, q -> q, (a, b) -> a));

        Map<Long, GeneratedQuestion> genMap = generatedIds.isEmpty() ? Map.of() :
                generatedQuestionMapper.selectBatchIds(generatedIds).stream()
                        .collect(Collectors.toMap(GeneratedQuestion::getGeneratedQuestionId, q -> q, (a, b) -> a));

        // 5. 查询学生作答记录
        List<HomeworkSubmissionDetail> submissions = submissionMapper.selectByPlanId(plan.getPlanId(), tenantId);
        Map<Long, HomeworkSubmissionDetail> submissionMap = submissions.stream()
                .collect(Collectors.toMap(HomeworkSubmissionDetail::getQuestionId, s -> s, (a, b) -> a));

        // 6. 组装 HomeworkVO 列表
        List<HomeworkVO> result = new ArrayList<>();
        for (DailyPlanQuestionRef ref : refs) {
            HomeworkVO vo = buildHomeworkVO(plan.getPlanId(), ref, bankMap, genMap, submissionMap);
            result.add(vo);
        }

        return result;
    }

    // ==================== submitAnswer ====================

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void submitAnswer(Long studentId, String tenantId, SubmitAnswerRequest request) {
        // 1. OBS-2: 用 planId 直接查询 daily_plan
        DailyPlan plan = validatePlanAndGet(studentId, tenantId, request.getPlanId());

        // 2. 校验 status=pending 或 in_progress（允许重复提交覆盖）
        if (!PlanStatus.PENDING.getCode().equals(plan.getStatus())
                && !PlanStatus.IN_PROGRESS.getCode().equals(plan.getStatus())) {
            throw new BusinessException(ErrorCodes.HOMEWORK_NOT_SUBMITTED,
                    "作业状态不允许提交: " + plan.getStatus());
        }

        // 3. 校验题目属于当日作业
        List<DailyPlanQuestionRef> refs = contentParser.parse(plan.getContent());
        boolean questionInPlan = refs.stream()
                .anyMatch(ref -> ref.getQuestionId().equals(request.getQuestionId())
                        && ref.getQuestionSource().equals(request.getQuestionSource()));
        if (!questionInPlan) {
            throw new BusinessException(ErrorCodes.QUESTION_NOT_FOUND,
                    "题目不属于当日作业");
        }

        // 4. 写入 homework_submission_detail（IMP-3: insertOrUpdate 覆盖语义）
        HomeworkSubmissionDetail detail = new HomeworkSubmissionDetail();
        detail.setPlanId(plan.getPlanId());
        detail.setQuestionId(request.getQuestionId());
        detail.setQuestionSource(request.getQuestionSource());
        detail.setQuestionType(request.getTaskType()); // NEW-6: taskType → questionType 跨层映射
        detail.setStudentAnswer(request.getStudentAnswer());
        detail.setIsCorrect(null); // 批改时填充
        detail.setSubmittedAt(LocalDateTime.now());
        detail.setStudentId(studentId);
        detail.setTenantId(tenantId);
        detail.setCreateTime(LocalDateTime.now());
        detail.setUpdateTime(LocalDateTime.now());

        // 从题目关联知识点
        String kpId = findKnowledgePointId(request, refs);
        detail.setKnowledgePointId(kpId);

        submissionMapper.insertOrUpdate(detail);

        // 5. 更新 daily_plan 状态 pending → in_progress（BLOCK-5: 状态机校验）
        if (PlanStatus.PENDING.getCode().equals(plan.getStatus())) {
            int rows = dailyPlanMapper.updateStatusWithCheck(
                    plan.getPlanId(), PlanStatus.IN_PROGRESS.getCode(),
                    PlanStatus.PENDING.getCode(), tenantId);
            if (rows == 0) {
                throw new BusinessException(ErrorCodes.HOMEWORK_CONCURRENT_CONFLICT);
            }
        }
    }

    // ==================== gradeHomework ====================

    /**
     * 批改作业
     *
     * <p>事务范围说明：当前 @Transactional 覆盖全流程（查询 → 批改 → 错题处理 → 掌握度更新 → 状态变更）。
     * 单学生单日 ≤ 15 题，事务宽度可接受。若后续题量增长，建议参照 generateDailyHomework
     * 使用 TransactionTemplate 缩窄事务边界，将步骤 3/4 的查询移出事务。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public GradeResultVO gradeHomework(Long studentId, String tenantId, Long subjectId, LocalDate planDate) {
        // 1. OBS-1: 通过 subjectId 获取 goal_id
        GoalVO goal = getActiveGoalOrThrow(subjectId);
        Long goalId = goal.getGoalId();

        // 2. 查询 daily_plan
        DailyPlan plan = dailyPlanMapper.selectByGoalIdAndDate(goalId, tenantId, planDate);
        if (plan == null) {
            throw new BusinessException(ErrorCodes.HOMEWORK_NOT_FOUND);
        }

        // IMP-2: 幂等性检查
        if (PlanStatus.COMPLETED.getCode().equals(plan.getStatus())) {
            throw new BusinessException(ErrorCodes.HOMEWORK_ALREADY_GRADED);
        }
        if (!PlanStatus.IN_PROGRESS.getCode().equals(plan.getStatus())) {
            throw new BusinessException(ErrorCodes.HOMEWORK_NOT_SUBMITTED);
        }

        // 3. 查询当日所有提交记录
        List<HomeworkSubmissionDetail> rawSubmissions = submissionMapper.selectByPlanId(plan.getPlanId(), tenantId);

        // IMP-AD: 优先按 create_time ASC, submission_id ASC 排序（避免雪花算法 submissionId 非严格递增）
        // IMP-W: 排序保证“同一 questionId 多条 redo 时取最后一条”语义稳定
        rawSubmissions.sort(Comparator.comparing(
                HomeworkSubmissionDetail::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(HomeworkSubmissionDetail::getSubmissionId));

        // IMP-Q: LinkedHashMap + redo 优先去重——同一 questionId 若同时存在 redo 和 non-redo，优先处理 redo
        Map<Long, HomeworkSubmissionDetail> questionSubmissionMap = new LinkedHashMap<>();
        for (HomeworkSubmissionDetail sub : rawSubmissions) {
            Long qId = sub.getQuestionId();
            HomeworkSubmissionDetail existing = questionSubmissionMap.get(qId);
            if (existing == null || "redo".equals(sub.getQuestionType())) {
                questionSubmissionMap.put(qId, sub);
            } else {
                log.warn("[gradeHomework] 重复 questionId={}（非 redo 被跳过）", qId);
            }
        }

        // 4. 校验所有题目均已提交
        List<DailyPlanQuestionRef> refs = contentParser.parse(plan.getContent());
        Set<Long> planQuestionIds = refs.stream()
                .map(DailyPlanQuestionRef::getQuestionId)
                .collect(Collectors.toSet());
        Set<Long> submittedQuestionIds = questionSubmissionMap.keySet();

        if (!planQuestionIds.equals(submittedQuestionIds)) {
            // 找出未提交的题目
            Set<Long> missingIds = new LinkedHashSet<>(planQuestionIds);
            missingIds.removeAll(submittedQuestionIds);
            throw new BusinessException(ErrorCodes.HOMEWORK_NOT_SUBMITTED,
                    String.format("还有 %d 道题未提交，无法批改: %s",
                            missingIds.size(), missingIds.toString()));
        }

        // 5. 构建题目答案查找表

        Set<Long> bankIds = refs.stream().filter(r -> "bank".equals(r.getQuestionSource()))
                .map(DailyPlanQuestionRef::getQuestionId).collect(Collectors.toSet());
        Map<Long, QuestionBank> bankMap = bankIds.isEmpty() ? Map.of()
                : questionBankMapper.selectBatchIds(bankIds).stream()
                .collect(Collectors.toMap(QuestionBank::getQuestionId, q -> q, (a, b) -> a));

        Set<Long> genIds = refs.stream().filter(r -> "generated".equals(r.getQuestionSource()))
                .map(DailyPlanQuestionRef::getQuestionId).collect(Collectors.toSet());
        Map<Long, GeneratedQuestion> genMap = genIds.isEmpty() ? Map.of()
                : generatedQuestionMapper.selectBatchIds(genIds).stream()
                .collect(Collectors.toMap(GeneratedQuestion::getGeneratedQuestionId, q -> q, (a, b) -> a));

        // 4.5 查询学生年级 + 科目名称（AI 解析用）
        String studentGrade = getStudentGrade(studentId);
        String subjectName = getSubjectName(goal.getSubjectId());

        // 6. 逐题批改（基于去重后的 submissions）
        // Phase 1: 收集所有题目信息 + 选择题快速批改
        int correctCount = 0;
        List<Long> submissionIds = new ArrayList<>();
        Set<String> affectedKpIds = new LinkedHashSet<>();

        // 预计算所有题目的基础信息（非 AI 操作，快速完成）
        List<HomeworkSubmissionDetail> submissions = new ArrayList<>(questionSubmissionMap.values());
        String[] correctAnswers = new String[submissions.size()];
        String[] questionContents = new String[submissions.size()];
        String[] questionFormatTypes = new String[submissions.size()];
        boolean[] isCorrectFlags = new boolean[submissions.size()];

        for (int i = 0; i < submissions.size(); i++) {
            HomeworkSubmissionDetail sub = submissions.get(i);
            submissionIds.add(sub.getSubmissionId());
            if (sub.getKnowledgePointId() != null) {
                affectedKpIds.add(sub.getKnowledgePointId());
            }

            correctAnswers[i] = findCorrectAnswer(sub.getQuestionId(), sub.getQuestionSource(), bankMap, genMap);
            questionContents[i] = getQuestionContent(sub.getQuestionId(), sub.getQuestionSource(), bankMap, genMap);
            questionFormatTypes[i] = getQuestionFormatType(sub.getQuestionId(), sub.getQuestionSource(), bankMap, genMap);

            if ("choice".equals(questionFormatTypes[i])) {
                // 选择题：字符串精确比较（快速，无需 AI）
                isCorrectFlags[i] = sub.getStudentAnswer() != null
                        && sub.getStudentAnswer().trim().equalsIgnoreCase(
                        correctAnswers[i] != null ? correctAnswers[i].trim() : "");
            }
            // 非选择题：isCorrectFlags[i] 保持 false，后续由 AI 并行批改填充
        }

        // Phase 2: 并行 AI 调用（非选择题批改 + 所有题目解析生成）
        CompletableFuture<?>[] futures = new CompletableFuture[submissions.size()];
        for (int i = 0; i < submissions.size(); i++) {
            final int idx = i;
            HomeworkSubmissionDetail sub = submissions.get(idx);

            if ("choice".equals(questionFormatTypes[idx])) {
                // 选择题：只需生成解析
                futures[idx] = CompletableFuture.supplyAsync(() -> {
                    try {
                        return generateAnalysis(questionContents[idx], correctAnswers[idx],
                                sub.getStudentAnswer(), subjectName, studentGrade);
                    } catch (Exception e) {
                        log.warn("AI 生成解析失败 (choice): questionId={}", sub.getQuestionId());
                        return null;
                    }
                }, aiExecutor).thenAccept(analysis -> sub.setAnalysis(analysis));
            } else {
                // 非选择题：需要 AI 批改 + 生成解析（两个独立的 AI 调用并行）
                CompletableFuture<Boolean> gradeFuture = CompletableFuture.supplyAsync(() ->
                        aiGradeQuestion(questionContents[idx], correctAnswers[idx],
                                sub.getStudentAnswer(), subjectName, studentGrade), aiExecutor);

                CompletableFuture<String> analysisFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return generateAnalysis(questionContents[idx], correctAnswers[idx],
                                sub.getStudentAnswer(), subjectName, studentGrade);
                    } catch (Exception e) {
                        log.warn("AI 生成解析失败 (non-choice): questionId={}", sub.getQuestionId());
                        return null;
                    }
                }, aiExecutor);

                futures[idx] = CompletableFuture.allOf(gradeFuture, analysisFuture)
                        .thenAccept(v -> {
                            isCorrectFlags[idx] = gradeFuture.join();
                            sub.setAnalysis(analysisFuture.join());
                        });
            }
        }

        // 等待所有 AI 调用完成
        CompletableFuture.allOf(futures).join();

        // Phase 3: 设置批改结果 + 批量写 DB
        for (int i = 0; i < submissions.size(); i++) {
            HomeworkSubmissionDetail sub = submissions.get(i);
            sub.setIsCorrect(isCorrectFlags[i] ? 1 : 0);
            sub.setUpdateTime(LocalDateTime.now());
            submissionMapper.updateById(sub);

            if (isCorrectFlags[i]) {
                correctCount++;
            }
        }

        // 6.5 T-06 Part B: 错题处理（在批改后、掌握度更新前）
        // BLOCK-E: 批改是错题入队的唯一触发点
        // IMP-8: 插入在步骤 5（批改）之后、步骤 6（掌握度更新）之前
        // 注意：此处 recordWrongAnswer/advanceStage/resetStage 的写操作与步骤 6 的
        // batchFlushStates→syncPauseStatus 在同一事务内，执行顺序为：
        // ① 错题入队/推进/重置 → ② 掌握度写入 → ③ syncPauseStatus（幂等，无副作用）
        for (HomeworkSubmissionDetail sub : questionSubmissionMap.values()) {
            boolean isCorrect = sub.getIsCorrect() != null && sub.getIsCorrect() == 1;
            boolean isRedo = "redo".equals(sub.getQuestionType());

            if (isRedo) {
                // 错题重做：答对 → advanceStage；答错 → resetStage
                if (isCorrect) {
                    errorQuestionService.advanceStage(studentId, tenantId,
                            sub.getQuestionId(), planDate);
                } else {
                    errorQuestionService.resetStage(studentId, tenantId,
                            sub.getQuestionId(), planDate);
                }
            } else {
                // 非 redo 题（bank/generated）：答错 → recordWrongAnswer 入队
                if (!isCorrect) {
                    errorQuestionService.recordWrongAnswer(
                            studentId, tenantId,
                            sub.getQuestionId(),
                            sub.getKnowledgePointId(),
                            ErrorQuestionSourceType.HOMEWORK,
                            sub.getSubmissionId(),
                            planDate);
                }
            }
        }

        // 7. BLOCK-3: 收集所有 submissionId，调用 MasteryService 更新掌握度
        // T-05: updateMasteryFromHomework 内部触发 syncPauseStatus
        if (!submissionIds.isEmpty()) {
            masteryService.updateMasteryFromHomework(studentId, tenantId, submissionIds);
        }

        // 8. 更新 daily_plan 状态 in_progress → completed（BLOCK-5: 状态机校验）
        int rows = dailyPlanMapper.updateStatusWithCheck(
                plan.getPlanId(), PlanStatus.COMPLETED.getCode(),
                PlanStatus.IN_PROGRESS.getCode(), tenantId);
        if (rows == 0) {
            throw new BusinessException(ErrorCodes.HOMEWORK_CONCURRENT_CONFLICT);
        }

        // 9. 返回 GradeResultVO（IMP-9: affectedKnowledgePoints 去重）
        int total = questionSubmissionMap.size();
        return GradeResultVO.builder()
                .totalQuestions(total)
                .correctCount(correctCount)
                .incorrectCount(total - correctCount)
                .accuracy(total > 0
                        ? BigDecimal.valueOf(correctCount).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .affectedKnowledgePoints(new ArrayList<>(affectedKpIds))
                .build();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 事务内执行：创建/更新计划 + 落题 + 状态机更新
     * 由 generateDailyHomework 的 TransactionTemplate 回调调用
     */
    private void persistHomeworkResult(DailyPlan plan,
                                        List<DailyPlanQuestionRef> refs,
                                        List<GeneratedQuestion> generatedQuestions,
                                        String tenantId) {
        // 1. 生成题 → 写入 generated_question 表（Change 8: 捕获 DuplicateKeyException）
        List<GeneratedQuestion> persistedQuestions = new ArrayList<>();
        if (generatedQuestions != null && !generatedQuestions.isEmpty()) {
            for (GeneratedQuestion gq : generatedQuestions) {
                gq.setDailyPlanId(plan.getPlanId());
                gq.setTenantId(tenantId);
                gq.setCreateTime(LocalDateTime.now());
                gq.setUpdateTime(LocalDateTime.now());
                // 防御性兜底：content_hash 为 NOT NULL 列，确保唯一索引对所有来源的生成题生效
                // （举一反三新题由 SimilarQuestionService 显式写入 SimHash；知识回顾等其他来源默认为空串）
                if (gq.getContentHash() == null) {
                    gq.setContentHash("");
                }
                try {
                    generatedQuestionMapper.insert(gq);
                    persistedQuestions.add(gq);
                } catch (DuplicateKeyException e) {
                    // IMP-4: uk_kp_content_hash 唯一索引冲突 → 内容重复，跳过该题
                    log.warn("生成题内容重复，跳过: kpId={}, contentHash={}",
                            gq.getKnowledgePointId(), gq.getContentHash());
                }
            }

            // H-3 修复：使用引用相等（==）对比原始列表与持久化结果，避免 DuplicateKeyException 跳题时 ID 回填错位
            // 原理：persistedQuestions 仅包含 insert 成功的对象引用；
            //   遍历 generatedQuestions（原始顺序），遇到 persisted 中的引用时消费下一个 generated ref
            java.util.Iterator<GeneratedQuestion> persistedIter = persistedQuestions.iterator();
            GeneratedQuestion currentPersisted = persistedIter.hasNext() ? persistedIter.next() : null;
            for (GeneratedQuestion original : generatedQuestions) {
                if (original == currentPersisted) {
                    // 该题持久化成功，回填对应的 ref
                    for (DailyPlanQuestionRef ref : refs) {
                        if ("generated".equals(ref.getQuestionSource()) && ref.getQuestionId() == 0L) {
                            ref.setQuestionId(original.getGeneratedQuestionId());
                            break;
                        }
                    }
                    currentPersisted = persistedIter.hasNext() ? persistedIter.next() : null;
                }
                // 该题持久化失败（original != currentPersisted），对应 ref 保持 questionId=0，继续下一题
            }
        }

        // 2. 序列化 refs → daily_plan.content
        String contentJson = contentParser.serialize(refs);

        // 3. 更新 daily_plan.status = pending（IMP-I: 带状态机校验）
        // 允许覆盖的场景：pending_generation（首次生成）或 pending（重新生成）
        String expectedStatus = plan.getStatus();
        int rows = dailyPlanMapper.updateContentAndStatus(
                plan.getPlanId(), contentJson,
                PlanStatus.PENDING.getCode(),
                expectedStatus,
                tenantId);
        if (rows == 0) {
            throw new BusinessException(ErrorCodes.HOMEWORK_CONCURRENT_CONFLICT);
        }
    }

    /**
     * 获取 active goal，不存在则抛 LEARNING_GOAL_NOT_FOUND
     */
    private GoalVO getActiveGoalOrThrow(Long subjectId) {
        List<GoalVO> goals = goalService.getActiveGoals(subjectId);
        if (goals == null || goals.isEmpty()) {
            throw new BusinessException(ErrorCodes.LEARNING_GOAL_NOT_FOUND);
        }
        // 返回第一个活跃目标（或可以根据业务逻辑选择最新的一个）
        return goals.get(0);
    }

    /**
     * 获取科目名称
     */
    private String getSubjectName(Long subjectId) {
        Subject subject = subjectMapper.selectById(subjectId);
        return subject != null ? subject.getName() : null;
    }

    /**
     * 校验 daily_plan 归属 + 状态，返回 plan
     * OBS-2: 通过 daily_plan.goal_id 反查 learning_goal，校验 goal.studentId == 当前 studentId
     */
    private DailyPlan validatePlanAndGet(Long studentId, String tenantId, Long planId) {
        DailyPlan plan = dailyPlanMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ErrorCodes.HOMEWORK_NOT_FOUND);
        }

        // 归属校验：通过 goal_id 反查 learning_goal
        LearningGoal goal = learningGoalMapper.selectById(plan.getGoalId());
        if (goal == null || !studentId.equals(goal.getStudentId()) || !tenantId.equals(plan.getTenantId())) {
            throw new BusinessException(ErrorCodes.STUDENT_IDENTITY_MISMATCH);
        }

        return plan;
    }

    /**
     * 组装单个 HomeworkVO
     */
    private HomeworkVO buildHomeworkVO(Long planId, DailyPlanQuestionRef ref,
                                        Map<Long, QuestionBank> bankMap,
                                        Map<Long, GeneratedQuestion> genMap,
                                        Map<Long, HomeworkSubmissionDetail> submissionMap) {
        HomeworkVO.HomeworkVOBuilder builder = HomeworkVO.builder()
                .planId(planId)
                .questionId(ref.getQuestionId())
                .questionSource(ref.getQuestionSource())
                .taskType(ref.getTaskType());

        HomeworkSubmissionDetail submission = submissionMap.get(ref.getQuestionId());

        if ("bank".equals(ref.getQuestionSource())) {
            QuestionBank q = bankMap.get(ref.getQuestionId());
            if (q != null) {
                builder.questionFormatType(q.getType())
                        .questionContent(q.getContent())
                        .difficulty(q.getDifficulty())
                        .knowledgePointId(q.getKnowledgePointId());
                // 解析选择题选项（隐性约定 #25：题库存 {key,text}，出参统一为纯文本，label 由前端按序号渲染）
                List<String> choices = resolveChoiceTexts(q.getChoices());
                if (!choices.isEmpty()) {
                    builder.choices(choices);
                } else if ("choice".equals(q.getType())) {
                    log.error("题库选择题 questionId={} 缺少有效选项，学生将无法作答", q.getQuestionId());
                }
            }
        } else if ("generated".equals(ref.getQuestionSource())) {
            GeneratedQuestion gq = genMap.get(ref.getQuestionId());
            if (gq != null) {
                builder.difficulty(gq.getDifficulty())
                        .knowledgePointId(gq.getKnowledgePointId());
                // 解析 content JSON
                try {
                    Map<String, Object> content = OBJECT_MAPPER.readValue(gq.getContent(),
                            new TypeReference<Map<String, Object>>() {});
                    builder.questionContent((String) content.get("stem"))
                            .questionFormatType((String) content.get("formatType"));
                    if ("choice".equals(content.get("formatType"))) {
                        List<String> choices = resolveChoiceTexts(content.get("choices"));
                        if (choices.isEmpty()) {
                            log.error("生成选择题 questionId={} 缺少有效选项，学生将无法作答", gq.getGeneratedQuestionId());
                        } else {
                            builder.choices(choices);
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析 generated_question.content JSON 失败: {}", e.getMessage());
                }
            }
        }

        // 学生作答信息
        if (submission != null) {
            builder.studentAnswer(submission.getStudentAnswer());
            // isCorrect: null=未批改, 1=正确, 0=错误
            if (submission.getIsCorrect() != null) {
                builder.isCorrect(submission.getIsCorrect() == 1);
                // 批改后返回标准答案
                String correctAnswer = findCorrectAnswer(ref.getQuestionId(), ref.getQuestionSource(), bankMap, genMap);
                builder.correctAnswer(correctAnswer);
                // 批改后返回 AI 解析
                builder.analysis(submission.getAnalysis());
            }
        }

        return builder.build();
    }

    /**
     * 解析选择题选项为纯文本列表（隐性约定 #25）
     *
     * <p>输入可能是：JSON 字符串（可能被双重序列化）、{@code List}、{@code Map}，
     * 元素可能是 {@code {label,text}}、{@code {key,text}} 或纯字符串；统一交给
     * {@link ChoiceOptions#normalize(Object)} 归一化后只保留文本，label 由前端按序号渲染。</p>
     */
    private List<String> resolveChoiceTexts(Object rawOptions) {
        if (rawOptions == null) {
            return List.of();
        }
        try {
            Object parsed = rawOptions;
            if (rawOptions instanceof String raw) {
                if (raw.isBlank()) {
                    return List.of();
                }
                try {
                    parsed = OBJECT_MAPPER.readValue(raw, Object.class);
                    // 双重序列化：外层仍是字符串
                    if (parsed instanceof String inner && !inner.isBlank()) {
                        parsed = OBJECT_MAPPER.readValue(inner, Object.class);
                    }
                } catch (Exception ignore) {
                    parsed = raw;
                }
            }
            return ChoiceOptions.normalize(parsed).stream()
                    .map(option -> option.get("text"))
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("解析选择题选项失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从题目关联知识点 ID
     */
    private String findKnowledgePointId(SubmitAnswerRequest request, List<DailyPlanQuestionRef> refs) {
        // 从 question_bank 或 generated_question 获取 knowledge_point_id
        if ("bank".equals(request.getQuestionSource())) {
            QuestionBank q = questionBankMapper.selectById(request.getQuestionId());
            return q != null ? q.getKnowledgePointId() : null;
        } else {
            GeneratedQuestion gq = generatedQuestionMapper.selectById(request.getQuestionId());
            return gq != null ? gq.getKnowledgePointId() : null;
        }
    }

    /**
     * 查找题目标准答案
     * IMP-D: bank 题从 question_bank.answer 取；generated 题从 content JSON 的 answer 字段取
     */
    private String findCorrectAnswer(Long questionId, String questionSource,
                                      Map<Long, QuestionBank> bankMap,
                                      Map<Long, GeneratedQuestion> genMap) {
        if ("bank".equals(questionSource)) {
            QuestionBank q = bankMap.get(questionId);
            return q != null ? q.getAnswer() : null;
        } else {
            GeneratedQuestion gq = genMap.get(questionId);
            if (gq == null || gq.getContent() == null) {
                return null;
            }
            try {
                Map<String, Object> content = OBJECT_MAPPER.readValue(gq.getContent(),
                        new TypeReference<Map<String, Object>>() {});
                return (String) content.get("answer");
            } catch (Exception e) {
                log.warn("解析 generated_question.content JSON 失败: {}", e.getMessage());
                return null;
            }
        }
    }

    /**
     * 获取题目内容（题干）
     */
    private String getQuestionContent(Long questionId, String questionSource,
                                       Map<Long, QuestionBank> bankMap,
                                       Map<Long, GeneratedQuestion> genMap) {
        if ("bank".equals(questionSource)) {
            QuestionBank q = bankMap.get(questionId);
            return q != null ? q.getContent() : null;
        } else {
            GeneratedQuestion gq = genMap.get(questionId);
            if (gq == null || gq.getContent() == null) {
                return null;
            }
            try {
                Map<String, Object> content = OBJECT_MAPPER.readValue(gq.getContent(),
                        new TypeReference<Map<String, Object>>() {});
                return (String) content.get("stem");
            } catch (Exception e) {
                log.warn("解析 generated_question.content JSON 失败: {}", e.getMessage());
                return null;
            }
        }
    }

    /**
     * 获取学生年级
     */
    private String getStudentGrade(Long studentId) {
        Student student = studentMapper.selectById(studentId);
        return student != null ? student.getGrade() : null;
    }

    /**
     * 解析计划骨架指定的知识点（产品规则 #1）
     */
    private List<String> parsePlannedKpIds(DailyPlan plan) {
        if (plan == null || plan.getPlannedKpIds() == null || plan.getPlannedKpIds().isBlank()) {
            return List.of();
        }
        try {
            List<String> ids = OBJECT_MAPPER.readValue(plan.getPlannedKpIds(),
                    new TypeReference<List<String>>() {});
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.warn("解析计划骨架知识点失败，忽略: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析目标上的暂缓知识点 JSON 数组（产品规则 #5）
     *
     * <p>解析失败时返回空列表：暂缓是优化项，不能因此阻塞出题。</p>
     */
    private List<String> parsePausedKpIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> ids = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.warn("解析暂缓知识点失败，忽略: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * AI 生成题目解析
     * 根据学生年级生成适合其理解能力的解析，失败时返回 null 不影响批改流程
     */
    /**
     * Prompt 中展示的年级：转成中文名（"12" → "高三"），未设置时返回"当前"
     *
     * <p>LLM 对"12"可能按"12 年级"（美式学制）理解，中文名称更准确（隐性约定 #23）。</p>
     */
    private String analysisGradeLabel(String grade) {
        if (grade == null || grade.isBlank()) {
            return "当前";
        }
        String label = GradeMatcher.displayName(grade);
        return label.isEmpty() ? grade : label;
    }

    private String generateAnalysis(String questionContent, String correctAnswer,
                                     String studentAnswer, String subject, String grade) {        if (questionContent == null || questionContent.isBlank()) {
            return null;
        }
        try {
            String promptTemplate = loadAnalysisPromptTemplate();
            String prompt = promptTemplate
                    .replace("{subject}", subject != null ? subject : "")
                    .replace("{grade}", analysisGradeLabel(grade))
                    .replace("{questionContent}", questionContent)
                    .replace("{correctAnswer}", correctAnswer != null ? correctAnswer : "暂无")
                    .replace("{studentAnswer}", studentAnswer != null ? studentAnswer : "暂无");

            // 隐性约定 #24：解析以纯文本渲染，需清洗 LaTeX / Markdown 标记
            return LlmTextSanitizer.sanitize(agentScopeClient.call(prompt));
        } catch (Exception e) {
            log.warn("AI 生成题目解析失败: questionId={}, error={}", questionContent, e.getMessage());
            return null;
        }
    }

    /**
     * 获取题目格式类型（choice / numeric / short / self）
     */
    private String getQuestionFormatType(Long questionId, String questionSource,
                                          Map<Long, QuestionBank> bankMap,
                                          Map<Long, GeneratedQuestion> genMap) {
        if ("bank".equals(questionSource)) {
            QuestionBank q = bankMap.get(questionId);
            return q != null ? q.getType() : null;
        } else {
            GeneratedQuestion gq = genMap.get(questionId);
            if (gq == null || gq.getContent() == null) {
                return null;
            }
            try {
                Map<String, Object> content = OBJECT_MAPPER.readValue(gq.getContent(),
                        new TypeReference<Map<String, Object>>() {});
                return (String) content.get("formatType");
            } catch (Exception e) {
                log.warn("解析 generated_question.content JSON 失败: {}", e.getMessage());
                return null;
            }
        }
    }

    /**
     * AI 批改填空/解答/主观题
     * 返回 true=正确, false=错误；AI 调用失败时降级为字符串比较
     */
    private boolean aiGradeQuestion(String questionContent, String correctAnswer,
                                     String studentAnswer, String subject, String grade) {
        if (studentAnswer == null || studentAnswer.isBlank()) {
            return false;
        }
        // 降级：如果题目内容或正确答案缺失，回退到字符串比较
        if (questionContent == null || questionContent.isBlank()
                || correctAnswer == null || correctAnswer.isBlank()) {
            return studentAnswer.trim().equalsIgnoreCase(correctAnswer != null ? correctAnswer.trim() : "");
        }
        try {
            String promptTemplate = loadGradingPromptTemplate();
            String prompt = promptTemplate
                    .replace("{subject}", subject != null ? subject : "")
                    .replace("{grade}", analysisGradeLabel(grade))
                    .replace("{questionContent}", questionContent)
                    .replace("{correctAnswer}", correctAnswer)
                    .replace("{studentAnswer}", studentAnswer);

            String result = agentScopeClient.call(prompt);
            if (result != null) {
                String trimmed = result.trim();
                // AI 只应返回"正确"或"错误"
                if (trimmed.contains("正确")) {
                    return true;
                } else if (trimmed.contains("错误")) {
                    return false;
                }
            }
            // AI 返回内容无法解析，降级为字符串比较
            log.warn("AI 批改结果无法解析: {}", result);
            return studentAnswer.trim().equalsIgnoreCase(correctAnswer.trim());
        } catch (Exception e) {
            log.warn("AI 批改失败，降级为字符串比较: questionId={}, error={}", questionContent, e.getMessage());
            return studentAnswer.trim().equalsIgnoreCase(correctAnswer.trim());
        }
    }

    /**
     * 从 classpath 加载批改 Prompt 模板
     */
    private String loadGradingPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/versions/grading_v1.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        } catch (IOException e) {
            log.error("加载批改 Prompt 模板失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "批改 Prompt 模板加载失败");
        }
    }

    /**
     * 从 classpath 加载解析生成 Prompt 模板
     */
    private String loadAnalysisPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/versions/analysis_gen_v1.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        } catch (IOException e) {
            log.error("加载解析生成 Prompt 模板失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "解析生成 Prompt 模板加载失败");
        }
    }
}
