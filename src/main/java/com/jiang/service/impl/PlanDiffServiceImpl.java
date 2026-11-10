package com.jiang.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jiang.dto.PlanDateModification;
import com.jiang.dto.PlanDiffDTO;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.DailyPlanMapper;
import com.jiang.mapper.PlanAdjustmentDiffMapper;
import com.jiang.mapper.PlanAdjustmentLogMapper;
import com.jiang.model.entity.DailyPlan;
import com.jiang.model.entity.PlanAdjustmentDiff;
import com.jiang.model.entity.PlanAdjustmentLog;
import com.jiang.model.enums.PlanStatus;
import com.jiang.service.PlanDiffService;
import com.jiang.security.StudentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDiff(Long goalId, List<TriggerSignalDTO> signals, PlanDiffDTO diff) {
        String tenantId = StudentContext.getTenantId();

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
        LocalDate today = LocalDate.now();
        // 修复 Sev-3 补充：从 diff 对象获取 tenantId，避免依赖 StudentContext（定时任务场景无 HTTP 上下文）
        String tenantId = diff.getTenantId();

        // 修复 IMP-21：只更新未来日期的计划
        for (PlanDateModification mod : planDiff.getModifiedDates()) {
            if (mod.getDate().isBefore(today)) {
                log.warn("跳过过去日期的计划修改: date={}", mod.getDate());
                continue;
            }
            DailyPlan plan = planMapper.selectByGoalIdAndDate(diff.getGoalId(), tenantId, mod.getDate());
            if (plan != null) {
                plan.setContent(mod.getNewContent());
                // v2 模板契约：newContent 为空数组表示该天需安排新知识点，
                // 标记为 pending_generation 由出题 Agent 重新生成，避免学生看到空作业
                plan.setStatus(isBlankContent(mod.getNewContent())
                        ? PlanStatus.PENDING_GENERATION.getCode()
                        : PlanStatus.PENDING.getCode());
                planMapper.updateById(plan);
            }
        }

        diff.setStatus("confirmed");
        diff.setConfirmedAt(LocalDateTime.now());
        diffMapper.updateById(diff);

        saveAdjustmentLog(diff, "confirmed");
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
