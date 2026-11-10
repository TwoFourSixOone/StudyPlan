package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jiang.agent.AdjustmentAgent;
import com.jiang.common.exception.AdjustmentException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.dto.AdjustmentContextDTO;
import com.jiang.dto.PlanDiffDTO;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.AgentDegradationLogMapper;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.ErrorQuestionMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.PlanAdjustmentDiffMapper;
import com.jiang.mapper.PlanAdjustmentLogMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.AgentDegradationLog;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.ErrorQuestion;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.PlanAdjustmentDiff;
import com.jiang.model.entity.PlanAdjustmentLog;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.AgentType;
import com.jiang.security.StudentContext;
import com.jiang.service.PlanAdjustmentService;
import com.jiang.service.PlanDiffService;
import com.jiang.service.SignalDetector;
import com.jiang.vo.AdjustTriggerVO;
import com.jiang.vo.AdjustmentHistoryVO;
import com.jiang.vo.PlanDiffDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 计划调整服务实现
 * 修复 BLOCK-B：应用层防重
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Service
@Slf4j
public class PlanAdjustmentServiceImpl implements PlanAdjustmentService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            // 修复反序列化失败：PlanDiffDTO 含 LocalDate，裸 ObjectMapper 缺 JSR310 模块
            // （convertToDetailVO/convertToHistoryVO 读取 diffContent 时会炸）
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 调整 Agent 上下文：未来计划窗口（天） */
    private static final int FUTURE_PLAN_DAYS = 7;
    /** 调整 Agent 上下文：未覆盖知识点清单截断条数（避免 Prompt 膨胀） */
    private static final int UNCOVERED_KP_LIMIT = 30;
    /** 降级日志字段最大长度（隐性约定 #12：截断至 500 字符） */
    private static final int MAX_LOG_LENGTH = 500;

    @Autowired
    private PlanAdjustmentDiffMapper diffMapper;
    @Autowired
    private PlanAdjustmentLogMapper logMapper;
    @Autowired
    private SignalDetector signalDetector;
    @Autowired
    private AdjustmentAgent adjustmentAgent;
    @Autowired
    private PlanDiffService planDiffService;
    @Autowired
    private ErrorQuestionMapper errorQuestionMapper;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private LearningGoalMapper goalMapper;
    @Autowired
    private SubjectMapper subjectMapper;
    @Autowired
    private DailyPlanMapper planMapper;
    @Autowired
    private StudentKnowledgeStateMapper knowledgeStateMapper;
    @Autowired
    private AgentDegradationLogMapper degradationLogMapper;

    /**
     * 修复 BLOCK-B：应用层保证“同一 goal 同时只允许一个 pending diff”
     * 因 plan_adjustment_diff 已去掉唯一索引，需在业务层查重
     * 修复 Sev-3：移除 @Transactional，Agent 调用在事务外，仅 saveDiff 在事务内
     * 修复 Sev-4：捕获 AdjustmentException，执行降级策略
     */
    @Override
    public AdjustTriggerVO triggerAdjustment(Long goalId) {
        String tenantId = StudentContext.getTenantId();
    
        // 1. 应用层防重：检查是否已有 pending diff
        int pendingCount = diffMapper.countByGoalIdAndStatus(goalId, "pending", tenantId);
        if (pendingCount > 0) {
            throw new BusinessException(ErrorCodes.PLAN_ADJUST_CONFLICT);
        }
    
        // 2. 检测触发信号
        List<TriggerSignalDTO> signals = signalDetector.detectSignals(goalId, tenantId);
        if (signals.isEmpty()) {
            return AdjustTriggerVO.noAdjustment();
        }
    
        // 3. 修复 Issue-3：在 Service 层查询超 30 天错题，作为 Agent 输入传入
        List<ErrorQuestion> criticalErrors = errorQuestionMapper.selectCriticalErrors(
                LocalDate.now().minusDays(30), tenantId);
        List<String> criticalErrorIds = criticalErrors.stream()
                .map(e -> String.valueOf(e.getQuestionId()))
                .collect(Collectors.toList());
    
        // 3.5 组装调整 Agent 真实数据上下文（目标详情 + 未来 7 天计划 + 未覆盖知识点清单）
        AdjustmentContextDTO context = buildAdjustmentContext(goalId, tenantId);
    
        // 4. 调用调整 Agent（事务外，避免长事务占用 DB 连接）
        PlanDiffDTO diff;
        try {
            diff = adjustmentAgent.generateDiff(signals, context, criticalErrorIds);
        } catch (AdjustmentException e) {
            // 修复 Sev-4：Agent 异常降级，返回空调整方案
            log.warn("调整 Agent 异常，降级处理: goalId={}", goalId, e);
            // 隐性约定 #12：所有降级事件必须记录 agent_degradation_log（写入前脱敏）
            recordDegradation(goalId, tenantId, signals, criticalErrorIds, e);
            return AdjustTriggerVO.noAdjustment();
        }
    
        // 5. 保存 diff（事务内）
        Long diffId;
        try {
            diffId = transactionTemplate.execute(status -> {
                return planDiffService.saveDiff(goalId, signals, diff);
            });
        } catch (Exception e) {
            log.warn("saveDiff 失败，用户可重新触发: goalId={}", goalId, e);
            throw e;
        }
    
        return AdjustTriggerVO.of(diffId, signals);
    }

    /**
     * 组装调整 Agent 上下文：目标详情 + 未来 7 天真实计划 + 未覆盖知识点清单
     * <p>
     * 修复调整 Agent 信息不足：原 Prompt 仅传 goalId，LLM 只能凭空编造知识点/日期/题目，
     * 确认后幻觉数据写入 daily_plan.content（产品规则 #8：Agent 只能引用已启用知识点）
     */
    private AdjustmentContextDTO buildAdjustmentContext(Long goalId, String tenantId) {
        LearningGoal goal = goalMapper.selectById(goalId);
        if (goal == null) {
            throw new BusinessException(ErrorCodes.LEARNING_GOAL_NOT_FOUND);
        }

        String subjectName = null;
        if (goal.getSubjectId() != null) {
            Subject subject = subjectMapper.selectById(goal.getSubjectId());
            if (subject != null) {
                subjectName = subject.getName();
            }
        }

        // 未来 7 天真实计划（含原 content，LLM 调整的唯一基础）
        LocalDate today = LocalDate.now();
        List<DailyPlan> plans = planMapper.selectPlansBetween(
                goalId, tenantId, today, today.plusDays(FUTURE_PLAN_DAYS - 1));
        List<AdjustmentContextDTO.FuturePlanItem> futurePlans = plans.stream()
                .map(p -> AdjustmentContextDTO.FuturePlanItem.builder()
                        .date(p.getPlanDate())
                        .status(p.getStatus())
                        .content(p.getContent())
                        .build())
                .collect(Collectors.toList());

        // 未覆盖知识点清单（截断，防止 Prompt 膨胀）
        List<Map<String, Object>> uncoveredRows = knowledgeStateMapper.selectUncoveredKpList(
                goal.getStudentId(), goal.getSubjectId(), tenantId, UNCOVERED_KP_LIMIT);
        List<AdjustmentContextDTO.KpItem> uncoveredKps = uncoveredRows.stream()
                .map(r -> AdjustmentContextDTO.KpItem.builder()
                        .kpId(String.valueOf(r.get("kpId")))
                        .name(String.valueOf(r.get("kpName")))
                        .build())
                .collect(Collectors.toList());

        return AdjustmentContextDTO.builder()
                .goalId(goalId)
                .studentId(goal.getStudentId())
                .subjectId(goal.getSubjectId())
                .subjectName(subjectName)
                .targetScore(goal.getTargetScore())
                .direction(goal.getDirection())
                .startDate(goal.getStartDate())
                .endDate(goal.getEndDate())
                .futurePlans(futurePlans)
                .uncoveredKps(uncoveredKps)
                .build();
    }

    /**
     * 记录调整 Agent 降级日志（隐性约定 #12）
     * <p>
     * 写入前统一脱敏：移除 API Key / token / secret / studentId，并截断至 500 字符。
     * 日志写入失败不影响主流程，仅记录错误——不能因为审计失败而让用户拿不到降级提示。
     */
    private void recordDegradation(Long goalId, String tenantId,
                                   List<TriggerSignalDTO> signals,
                                   List<String> criticalErrorIds,
                                   Exception cause) {
        try {
            AgentDegradationLog entry = new AgentDegradationLog();
            entry.setAgentType(AgentType.ADJUSTMENT.getCode());
            entry.setInputSummary(sanitize(buildDegradationInput(goalId, signals, criticalErrorIds)));
            entry.setFailureReason(sanitize(cause.getClass().getSimpleName() + ": " + cause.getMessage()));
            entry.setDegradationAction("return_empty_diff");
            entry.setTenantId(tenantId);
            entry.setCreateTime(LocalDateTime.now());
            entry.setUpdateTime(LocalDateTime.now());
            degradationLogMapper.insert(entry);
        } catch (Exception e) {
            // 降级日志失败不能阻断"返回空 diff"这条主流程
            log.error("调整 Agent 降级日志写入失败: goalId={}, error={}", goalId, e.getMessage());
        }
    }

    /**
     * 组装降级日志的输入摘要（只记信号类型与错题数量，不记学生标识与题目内容）
     */
    private String buildDegradationInput(Long goalId, List<TriggerSignalDTO> signals,
                                         List<String> criticalErrorIds) {
        String signalTypes = signals == null ? "" : signals.stream()
                .map(TriggerSignalDTO::getSignalType)
                .collect(Collectors.joining(","));
        int criticalCount = criticalErrorIds == null ? 0 : criticalErrorIds.size();
        return String.format("goalId=%d, signals=[%s], criticalErrorCount=%d",
                goalId, signalTypes, criticalCount);
    }

    /**
     * 脱敏处理：截断至 {@link #MAX_LOG_LENGTH} 字符，移除敏感信息
     */
    private String sanitize(String input) {
        if (input == null) {
            return "";
        }
        String sanitized = input
                .replaceAll("(?i)(api[-_]?key|token|secret)[=:]\\s*\\S+", "$1=***")
                .replaceAll("studentId[=:]\\s*\\d+", "studentId=***");
        if (sanitized.length() > MAX_LOG_LENGTH) {
            sanitized = sanitized.substring(0, MAX_LOG_LENGTH) + "...[truncated]";
        }
        return sanitized;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmDiff(Long diffId) {
        String tenantId = StudentContext.getTenantId();
        PlanAdjustmentDiff diff = diffMapper.selectById(diffId);

        if (diff == null || !tenantId.equals(diff.getTenantId())) {
            throw new BusinessException(ErrorCodes.PLAN_ADJUST_NOT_FOUND);
        }
        if ("confirmed".equals(diff.getStatus())) {
            throw new BusinessException(ErrorCodes.PLAN_DIFF_ALREADY_CONFIRMED);
        }
        if ("rejected".equals(diff.getStatus())) {
            throw new BusinessException(ErrorCodes.PLAN_DIFF_ALREADY_REJECTED);
        }
        if ("expired".equals(diff.getStatus())) {
            throw new BusinessException(ErrorCodes.PLAN_DIFF_EXPIRED);
        }
        if (!"pending".equals(diff.getStatus())) {
            throw new BusinessException(ErrorCodes.PLAN_DIFF_NOT_FOUND_OR_INVALID);
        }

        // 应用 diff
        planDiffService.applyDiff(diffId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectDiff(Long diffId) {
        String tenantId = StudentContext.getTenantId();
        PlanAdjustmentDiff diff = diffMapper.selectById(diffId);

        if (diff == null || !tenantId.equals(diff.getTenantId())) {
            throw new BusinessException(ErrorCodes.PLAN_ADJUST_NOT_FOUND);
        }
        if (!"pending".equals(diff.getStatus())) {
            throw new BusinessException(ErrorCodes.PLAN_DIFF_NOT_FOUND_OR_INVALID);
        }

        diff.setStatus("rejected");
        diff.setRejectedAt(LocalDateTime.now());
        diffMapper.updateById(diff);

        // 记录日志
        PlanAdjustmentLog logEntry = new PlanAdjustmentLog();
        logEntry.setGoalId(diff.getGoalId());
        logEntry.setDiffId(diff.getId());
        logEntry.setTriggerSignals(diff.getTriggerSignals());
        logEntry.setDiffContent(diff.getDiffContent());
        logEntry.setAction("rejected");
        logEntry.setActionAt(LocalDateTime.now());
        logEntry.setTenantId(tenantId);
        logMapper.insert(logEntry);
    }

    @Override
    public PlanDiffDetailVO getDiffDetail(Long diffId) {
        String tenantId = StudentContext.getTenantId();
        PlanAdjustmentDiff diff = diffMapper.selectById(diffId);

        if (diff == null || !tenantId.equals(diff.getTenantId())) {
            throw new BusinessException(ErrorCodes.PLAN_ADJUST_NOT_FOUND);
        }

        return convertToDetailVO(diff);
    }

    @Override
    public PageResult<AdjustmentHistoryVO> getAdjustmentHistory(Integer page, Integer size) {
        String tenantId = StudentContext.getTenantId();

        // 修复 M-1：统一使用 MyBatis-Plus Page 分页，避免 wrapper.last() 字符串拼接
        LambdaQueryWrapper<PlanAdjustmentLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlanAdjustmentLog::getTenantId, tenantId)
                .orderByDesc(PlanAdjustmentLog::getActionAt);

        Page<PlanAdjustmentLog> pageParam = new Page<>(page, size);
        Page<PlanAdjustmentLog> result = logMapper.selectPage(pageParam, wrapper);

        List<AdjustmentHistoryVO> records = result.getRecords().stream()
                .map(this::convertToHistoryVO)
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, records);
    }

    private PlanDiffDetailVO convertToDetailVO(PlanAdjustmentDiff diff) {
        PlanDiffDetailVO.PlanDiffDetailVOBuilder builder = PlanDiffDetailVO.builder()
                .diffId(diff.getId())
                .goalId(diff.getGoalId())
                .status(diff.getStatus())
                .expireAt(diff.getExpireAt())
                .confirmedAt(diff.getConfirmedAt())
                .rejectedAt(diff.getRejectedAt())
                .createTime(diff.getCreateTime());

        try {
            List<TriggerSignalDTO> signals = OBJECT_MAPPER.readValue(
                    diff.getTriggerSignals(), new TypeReference<List<TriggerSignalDTO>>() {});
            builder.signals(signals);

            PlanDiffDTO planDiff = OBJECT_MAPPER.readValue(
                    diff.getDiffContent(), PlanDiffDTO.class);
            builder.modifiedDates(planDiff.getModifiedDates());
            builder.reason(planDiff.getReason());
        } catch (Exception e) {
            log.error("JSON deserialization failed: diffId={}", diff.getId(), e);
        }

        return builder.build();
    }

    private AdjustmentHistoryVO convertToHistoryVO(PlanAdjustmentLog logEntry) {
        AdjustmentHistoryVO.AdjustmentHistoryVOBuilder builder = AdjustmentHistoryVO.builder()
                .logId(logEntry.getId())
                .goalId(logEntry.getGoalId())
                .diffId(logEntry.getDiffId())
                .action(logEntry.getAction())
                .actionAt(logEntry.getActionAt())
                .createTime(logEntry.getCreateTime());

        try {
            PlanDiffDTO planDiff = OBJECT_MAPPER.readValue(
                    logEntry.getDiffContent(), PlanDiffDTO.class);
            builder.reason(planDiff.getReason());
        } catch (Exception e) {
            log.error("JSON deserialization failed: logId={}", logEntry.getId(), e);
        }

        return builder.build();
    }
}
