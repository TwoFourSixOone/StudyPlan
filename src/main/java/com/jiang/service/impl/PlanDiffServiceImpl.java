package com.jiang.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jiang.dto.PlanDateModification;
import com.jiang.dto.PlanDiffDTO;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.PlanAdjustmentDiffMapper;
import com.jiang.mapper.PlanAdjustmentLogMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.PlanAdjustmentDiff;
import com.jiang.model.entity.PlanAdjustmentLog;
import com.jiang.model.enums.PlanStatus;
import com.jiang.service.PlanDiffService;
import com.jiang.security.StudentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jiang.common.util.BusinessDate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 计划 diff 服务实现
 * 修复 BLOCK-13 + IMP-21
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Service
@Slf4j
public class PlanDiffServiceImpl implements PlanDiffService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            // 修复序列化失败：PlanDiffDTO 含 LocalDate，裸 ObjectMapper 缺 JSR310 模块
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    // 修复 Issue-5：定时任务无 StudentContext，使用显式常量（Demo 阶段固定租户）
    private static final String DEFAULT_TENANT_ID = "default";

    @Autowired
    private PlanAdjustmentDiffMapper diffMapper;
    @Autowired
    private PlanAdjustmentLogMapper logMapper;
    @Autowired
    private DailyPlanMapper planMapper;
    @Autowired
    private LearningGoalMapper goalMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDiff(Long goalId, List<TriggerSignalDTO> signals, PlanDiffDTO diff) {
        return saveDiff(goalId, signals, diff, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDiff(Long goalId, List<TriggerSignalDTO> signals, PlanDiffDTO diff, String tenantId) {
        // design §8.2：异步线程不读 StudentContext；调用方显式传租户时优先使用
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = StudentContext.getTenantId();
        }

        PlanAdjustmentDiff entity = new PlanAdjustmentDiff();
        entity.setGoalId(goalId);
        entity.setStatus("pending");
        try {
            entity.setTriggerSignals(OBJECT_MAPPER.writeValueAsString(signals));
            entity.setDiffContent(OBJECT_MAPPER.writeValueAsString(diff));
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
        entity.setExpireAt(LocalDateTime.now().plusDays(7)); // 7 天过期
        entity.setTenantId(tenantId);

        diffMapper.insert(entity);
        return entity.getId();
    }

    /**
     * 修复 IMP-21：只更新 plan_date >= today 的未来计划
     * 已完成的历史计划不重置为 pending
     *
     * <p><b>归属校验由调用方负责（P0-2）</b>：本方法是「系统入口」，同时被
     * {@code PlanAdjustmentServiceImpl#confirmDiff}（学生确认）、定时任务
     * {@code AutoAdjustmentTask} 与考试结算 {@code ExamSignalAdjustServiceImpl} 复用；
     * 后两者**没有 HTTP 上下文 / 没有学生身份**，因此这里不读 {@code StudentContext}、
     * 也不做 studentId 校验（硬要求身份会直接打断系统路径）。</p>
     *
     * <p>租户从 diff 自身 {@code tenantId} 取（同样不依赖 ThreadLocal）。
     * 学生端确认入口必须先经 {@code PlanAdjustmentServiceImpl#requireOwnedDiff} 完成归属校验。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyDiff(Long diffId) {
        PlanAdjustmentDiff diff = diffMapper.selectById(diffId);
        if (diff == null || !"pending".equals(diff.getStatus())) {
            throw new com.jiang.common.exception.BusinessException(
                    com.jiang.common.exception.ErrorCodes.PLAN_DIFF_NOT_FOUND_OR_INVALID);
        }

        PlanDiffDTO planDiff;
        try {
            planDiff = OBJECT_MAPPER.readValue(diff.getDiffContent(), PlanDiffDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
        LocalDate today = BusinessDate.today();
        // 修复 Sev-3 补充：从 diff 对象获取 tenantId，避免依赖 StudentContext（定时任务场景无 HTTP 上下文）
        String tenantId = diff.getTenantId();

        // 产品规则 #5（B 方案）：策略字段先落到目标上——未来日期此时还没有 daily_plan 行，
        // 只有目标能跨天承载"日题量上限 / 暂缓知识点"，由 02:00 每日生成读取生效
        applyGoalStrategy(diff.getGoalId(), planDiff, tenantId);

        // 修复 IMP-21：只更新未来日期、且尚未开始的计划
        int applied = 0;
        int skippedNoPlan = 0;
        int skippedStarted = 0;
        int skippedPast = 0;
        List<PlanDateModification> mods = planDiff.getModifiedDates() == null
                ? List.of() : planDiff.getModifiedDates();
        for (PlanDateModification mod : mods) {
            if (mod.getDate() == null) {
                continue;
            }
            if (mod.getDate().isBefore(today)) {
                skippedPast++;
                log.warn("跳过过去日期的计划修改: date={}", mod.getDate());
                continue;
            }
            DailyPlan plan = planMapper.selectByGoalIdAndDate(diff.getGoalId(), tenantId, mod.getDate());
            if (plan == null) {
                // 不再静默跳过：该日尚无计划，重排意图由目标策略在生成时承接
                skippedNoPlan++;
                log.info("该日期尚无计划，调整意图将在生成时生效: date={}, reason={}",
                        mod.getDate(), mod.getReason());
                continue;
            }
            // 学生已开始/已完成的计划不动（规则 #9：历史只读）
            String status = plan.getStatus();
            if (PlanStatus.IN_PROGRESS.getCode().equals(status)
                    || PlanStatus.COMPLETED.getCode().equals(status)) {
                skippedStarted++;
                log.info("跳过已开始/已完成的计划: date={}, status={}", mod.getDate(), status);
                continue;
            }
            plan.setContent(mod.getNewContent());
            // v2 模板契约：newContent 为空数组表示该天需安排新知识点，
            // 标记为 pending_generation 由出题 Agent 重新生成，避免学生看到空作业
            plan.setStatus(isBlankContent(mod.getNewContent())
                    ? PlanStatus.PENDING_GENERATION.getCode()
                    : PlanStatus.PENDING.getCode());
            planMapper.updateById(plan);
            applied++;
        }

        log.info("调整应用完成: goalId={}, 已更新={}, 无计划跳过={}, 已开始跳过={}, 过去日期跳过={}",
                diff.getGoalId(), applied, skippedNoPlan, skippedStarted, skippedPast);

        diff.setStatus("confirmed");
        diff.setConfirmedAt(LocalDateTime.now());
        diffMapper.updateById(diff);

        saveAdjustmentLog(diff, "confirmed");
    }

    /**
     * 把策略字段写入目标（产品规则 #5）
     *
     * <ul>
     *   <li>dailyQuestionLimit：1-15，写入后由每日生成作为硬上限</li>
     *   <li>pausedKpIds：全量替换；空数组表示清空暂缓</li>
     * </ul>
     */
    private void applyGoalStrategy(Long goalId, PlanDiffDTO planDiff, String tenantId) {
        if (planDiff.getDailyQuestionLimit() == null && planDiff.getPausedKpIds() == null) {
            return;
        }
        LearningGoal goal = goalMapper.selectById(goalId);
        if (goal == null || !tenantId.equals(goal.getTenantId())) {
            log.warn("调整目标不存在或租户不匹配，跳过策略写入: goalId={}, tenantId={}", goalId, tenantId);
            return;
        }
        if (planDiff.getDailyQuestionLimit() != null) {
            goal.setDailyQuestionLimit(planDiff.getDailyQuestionLimit());
        }
        if (planDiff.getPausedKpIds() != null) {
            try {
                goal.setPausedKpIds(planDiff.getPausedKpIds().isEmpty()
                        ? null
                        : OBJECT_MAPPER.writeValueAsString(planDiff.getPausedKpIds()));
            } catch (Exception e) {
                throw new RuntimeException("暂缓知识点序列化失败", e);
            }
        }
        goalMapper.updateById(goal);
        log.info("调整策略已写入目标: goalId={}, dailyQuestionLimit={}, pausedKpCount={}",
                goalId, planDiff.getDailyQuestionLimit(),
                planDiff.getPausedKpIds() == null ? 0 : planDiff.getPausedKpIds().size());
    }

    /**
     * 判断 newContent 是否为空（null / 空白 / 空数组），表示该天无可复用题目引用
     */
    private boolean isBlankContent(String newContent) {
        return newContent == null || newContent.isBlank() || "[]".equals(newContent.trim());
    }

    /**
     * 修复 BLOCK-13：批量更新过期 diff，避免长事务
     * 使用单条 UPDATE 语句批量修改状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanExpiredDiffs() {
        LocalDateTime now = LocalDateTime.now();
        String tenantId = DEFAULT_TENANT_ID;

        // 1. 批量更新过期 diff 状态（单条 SQL）
        int expiredCount = diffMapper.batchExpire(now, "pending", DEFAULT_TENANT_ID);
        if (expiredCount == 0) return 0;

        // 2. 查询本次过期的 diff（用于写日志）
        List<PlanAdjustmentDiff> expiredDiffs = diffMapper.selectByExpireAtBefore(now, "expired", tenantId);

        // 3. 批量插入日志（batchInsert）
        List<PlanAdjustmentLog> logs = expiredDiffs.stream()
                .map(d -> buildLog(d, "expired"))
                .collect(Collectors.toList());
        if (!logs.isEmpty()) {
            logMapper.batchInsert(logs);
        }

        return expiredCount;
    }

    private void saveAdjustmentLog(PlanAdjustmentDiff diff, String action) {
        PlanAdjustmentLog log = buildLog(diff, action);
        logMapper.insert(log);
    }

    private PlanAdjustmentLog buildLog(PlanAdjustmentDiff diff, String action) {
        PlanAdjustmentLog log = new PlanAdjustmentLog();
        log.setGoalId(diff.getGoalId());
        log.setDiffId(diff.getId());
        log.setTriggerSignals(diff.getTriggerSignals());
        log.setDiffContent(diff.getDiffContent());
        log.setAction(action);
        log.setActionAt(LocalDateTime.now());
        log.setTenantId(diff.getTenantId());
        return log;
    }
}
