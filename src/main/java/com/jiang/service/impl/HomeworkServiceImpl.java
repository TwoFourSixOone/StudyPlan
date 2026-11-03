package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.HomeworkDegradationHandler;
import com.jiang.agent.QuestionGenAgent;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.DailyPlanContentParser;
import com.jiang.common.util.HomeworkQuotaCalculator;
import com.jiang.common.util.SimilarityDetector;
import com.jiang.dto.DailyPlanQuestionRef;
import com.jiang.dto.KnowledgePointWithMastery;
import com.jiang.dto.SubmitAnswerRequest;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.ErrorQuestionMapper;
import com.jiang.mapper.GeneratedQuestionMapper;
import com.jiang.mapper.HomeworkSubmissionDetailMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.HomeworkSubmissionDetail;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.QuestionBank;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        List<StudentKnowledgeState> states = stateMapper.selectActiveStatesWithKpInfo(studentId, tenantId, subjectName);

        // 4. 查询已启用知识点列表
        List<KnowledgePoint> activeKps = knowledgePointMapper.selectList(
                new LambdaQueryWrapper<KnowledgePoint>()
                        .eq(KnowledgePoint::getSubject, subjectName)
                        .eq(KnowledgePoint::getStatus, "active")
                        .eq(KnowledgePoint::getTenantId, tenantId)
        );

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
                    .masteryScore(state != null ? state.getMasteryScore() : null)
                    .confidence(state != null ? state.getConfidence() : null)
                    .build());
        }

        // 6. T-06 Part A: 查询到期错题（在 Agent 调用前）
        // BLOCK-H: 内部已按 knowledge_point_id 分组，每组 ≤ 3 道
        // IMP-AB: 返回 LinkedHashMap，key 顺序由 ORDER BY knowledge_point_id ASC 决定
        Map<String, List<com.jiang.vo.DueErrorQuestionVO>> dueErrorMap =
                errorQuestionService.getDueErrorQuestions(studentId, tenantId, planDate);

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
        int dueErrorCount = errorRefs.size();
        List<Map<String, Object>> activeErrorCounts = errorQuestionMapper.selectActiveErrorCountGroupByKp(studentId, tenantId);
        Map<String, Integer> kpErrorCountMap = activeErrorCounts.stream()
                .collect(Collectors.toMap(
                        m -> (String) m.get("knowledge_point_id"),
                        m -> ((Number) m.get("error_count")).intValue(),
                        (a, b) -> a));

        HomeworkQuotaCalculator.QuotaAllocation quota;
        try {
            quota = HomeworkQuotaCalculator.calculate(dueErrorCount, errorKpIds, kpMasteryMap, kpErrorCountMap);
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
                            sourceQuestion, targetCount,
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

        List<DailyPlanQuestionRef> reviewRefs = null;
        List<GeneratedQuestion> reviewGeneratedQuestions = null;
        int reviewQuota = quota.getReviewQuota();

        if (!reviewKpList.isEmpty() && reviewQuota > 0) {
            Exception lastAgentException = null;
            for (int attempt = 0; attempt < MAX_AGENT_RETRIES; attempt++) {
                try {
                    QuestionGenAgent.QuestionGenResult result = questionGenAgent.generate(reviewKpList, reviewQuota);
                    reviewRefs = result.getRefs();
                    reviewGeneratedQuestions = result.getGeneratedQuestions();
                    break;
                } catch (Exception e) {
                    log.warn("知识回顾 Agent 出题失败 (attempt {}/{}): {}", attempt + 1, MAX_AGENT_RETRIES, e.getMessage());
                    lastAgentException = e;
                }
            }

            if (reviewRefs == null) {
                log.info("知识回顾 Agent 重试耗尽，触发降级策略");
                degradationHandler.logDegradation(
                        "subjectId=" + subjectId + ", date=" + planDate + ", type=review",
                        lastAgentException != null ? lastAgentException.getClass().getSimpleName()
                                + ": " + lastAgentException.getMessage() : "unknown",
                        tenantId);
                try {
                    reviewRefs = degradationHandler.generateDefaultPlan(reviewKpList, tenantId);
                    reviewGeneratedQuestions = List.of();
                } catch (Exception e) {
                    log.error("知识回顾降级也失败: {}", e.getMessage());
                    reviewRefs = List.of();
                    reviewGeneratedQuestions = List.of();
                }
            }
        } else {
            reviewRefs = List.of();
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
        // 1. 获取 active goal
        GoalVO goal = getActiveGoalOrThrow(subjectId);
        Long goalId = goal.getGoalId();

        // 2. 查询 daily_plan
        DailyPlan plan = dailyPlanMapper.selectByGoalIdAndDate(goalId, tenantId, planDate);
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
            HomeworkVO vo = buildHomeworkVO(ref, bankMap, genMap, submissionMap);
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

        // 4. 构建题目答案查找表
        List<DailyPlanQuestionRef> refs = contentParser.parse(plan.getContent());

        Map<Long, QuestionBank> bankMap = questionBankMapper.selectBatchIds(
                refs.stream().filter(r -> "bank".equals(r.getQuestionSource()))
                        .map(DailyPlanQuestionRef::getQuestionId)
                        .collect(Collectors.toSet())
        ).stream().collect(Collectors.toMap(QuestionBank::getQuestionId, q -> q, (a, b) -> a));

        Map<Long, GeneratedQuestion> genMap = generatedQuestionMapper.selectBatchIds(
                refs.stream().filter(r -> "generated".equals(r.getQuestionSource()))
                        .map(DailyPlanQuestionRef::getQuestionId)
                        .collect(Collectors.toSet())
        ).stream().collect(Collectors.toMap(GeneratedQuestion::getGeneratedQuestionId, q -> q, (a, b) -> a));

        // 5. 逐题批改（基于去重后的 submissions）
        int correctCount = 0;
        List<Long> submissionIds = new ArrayList<>();
        Set<String> affectedKpIds = new LinkedHashSet<>();

        for (HomeworkSubmissionDetail sub : questionSubmissionMap.values()) {
            submissionIds.add(sub.getSubmissionId());
            if (sub.getKnowledgePointId() != null) {
                affectedKpIds.add(sub.getKnowledgePointId());
            }

            String correctAnswer = findCorrectAnswer(sub.getQuestionId(), sub.getQuestionSource(), bankMap, genMap);
            boolean isCorrect = sub.getStudentAnswer() != null
                    && sub.getStudentAnswer().trim().equalsIgnoreCase(
                    correctAnswer != null ? correctAnswer.trim() : "");

            sub.setIsCorrect(isCorrect ? 1 : 0);
            sub.setUpdateTime(LocalDateTime.now());
            submissionMapper.updateById(sub);

            if (isCorrect) {
                correctCount++;
            }
        }

        // 5.5 T-06 Part B: 错题处理（在批改后、掌握度更新前）
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

        // 6. BLOCK-3: 收集所有 submissionId，调用 MasteryService 更新掌握度
        // T-05: updateMasteryFromHomework 内部触发 syncPauseStatus
        if (!submissionIds.isEmpty()) {
            masteryService.updateMasteryFromHomework(studentId, tenantId, submissionIds);
        }

        // 7. 更新 daily_plan 状态 in_progress → completed（BLOCK-5: 状态机校验）
        int rows = dailyPlanMapper.updateStatusWithCheck(
                plan.getPlanId(), PlanStatus.COMPLETED.getCode(),
                PlanStatus.IN_PROGRESS.getCode(), tenantId);
        if (rows == 0) {
            throw new BusinessException(ErrorCodes.HOMEWORK_CONCURRENT_CONFLICT);
        }

        // 8. 返回 GradeResultVO（IMP-9: affectedKnowledgePoints 去重）
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
        GoalVO goal = goalService.getActiveGoal(subjectId);
        if (goal == null) {
            throw new BusinessException(ErrorCodes.LEARNING_GOAL_NOT_FOUND);
        }
        return goal;
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
    private HomeworkVO buildHomeworkVO(DailyPlanQuestionRef ref,
                                        Map<Long, QuestionBank> bankMap,
                                        Map<Long, GeneratedQuestion> genMap,
                                        Map<Long, HomeworkSubmissionDetail> submissionMap) {
        HomeworkVO.HomeworkVOBuilder builder = HomeworkVO.builder()
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
                // 解析选择题选项
                if (q.getChoices() != null) {
                    try {
                        List<String> choices = OBJECT_MAPPER.readValue(q.getChoices(),
                                new TypeReference<List<String>>() {});
                        builder.choices(choices);
                    } catch (Exception e) {
                        log.warn("解析 question_bank.choices JSON 失败: {}", e.getMessage());
                    }
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
                    if ("choice".equals(content.get("formatType")) && content.containsKey("choices")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, String>> choiceList = (List<Map<String, String>>) content.get("choices");
                        builder.choices(choiceList.stream()
                                .map(c -> c.get("label") + ". " + c.get("text"))
                                .collect(Collectors.toList()));
                    }
                } catch (Exception e) {
                    log.warn("解析 generated_question.content JSON 失败: {}", e.getMessage());
                }
            }
        }

        // 学生作答信息
        if (submission != null) {
            builder.studentAnswer(submission.getStudentAnswer())
                    .isCorrect(submission.getIsCorrect() != null && submission.getIsCorrect() == 1);
        }

        return builder.build();
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
}
