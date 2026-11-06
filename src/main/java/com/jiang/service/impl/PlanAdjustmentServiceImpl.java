package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.AdjustmentAgent;
import com.jiang.common.exception.AdjustmentException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.dto.PlanDiffDTO;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.ErrorQuestionMapper;
import com.jiang.mapper.PlanAdjustmentDiffMapper;
import com.jiang.mapper.PlanAdjustmentLogMapper;
import com.jiang.model.entity.ErrorQuestion;
import com.jiang.model.entity.PlanAdjustmentDiff;
import com.jiang.model.entity.PlanAdjustmentLog;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    
        // 4. 调用调整 Agent（事务外，避免长事务占用 DB 连接）
        PlanDiffDTO diff;
        try {
            diff = adjustmentAgent.generateDiff(signals, goalId, criticalErrorIds);
        } catch (AdjustmentException e) {
            // 修复 Sev-4：Agent 异常降级，返回空调整方案
            log.warn("调整 Agent 异常，降级处理: goalId={}", goalId, e);
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
