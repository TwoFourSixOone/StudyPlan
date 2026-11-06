package com.jiang.service.impl;

import com.jiang.config.SignalConfig;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.*;
import com.jiang.model.entity.LearningGoal;
import com.jiang.service.SignalDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 信号检测服务实现
 * 修复 BLOCK-14：SIG-4 配置化 + 语义修正
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Service
@Slf4j
public class SignalDetectorImpl implements SignalDetector {

    @Autowired
    private SignalConfig signalConfig;
    @Autowired
    private LearningGoalMapper goalMapper;
    @Autowired
    private StudentKnowledgeStateMapper knowledgeStateMapper;
    @Autowired
    private HomeworkSubmissionDetailMapper submissionDetailMapper;
    @Autowired
    private MasteryChangeLogMapper changeLogMapper;
    @Autowired
    private DailyPlanMapper planMapper;

    @Override
    public List<TriggerSignalDTO> detectSignals(Long goalId, String tenantId) {
        List<TriggerSignalDTO> signals = new ArrayList<>();

        // SIG-1：窗口内连续正确率低于阈值
        detectSIG1(goalId, tenantId).ifPresent(signals::add);

        // SIG-2：掌握度单次大幅下降
        detectSIG2(goalId, tenantId).ifPresent(signals::add);

        // SIG-3：连续多天未完成作业
        detectSIG3(goalId, tenantId).ifPresent(signals::add);

        // SIG-4：剩余时间不足以覆盖未学习知识点
        detectSIG4(goalId, tenantId).ifPresent(signals::add);

        return signals;
    }

    private java.util.Optional<TriggerSignalDTO> detectSIG1(Long goalId, String tenantId) {
        int windowDays = signalConfig.getSig1().getWindowDays();
        double threshold = signalConfig.getSig1().getThreshold();
        int consecutiveCount = signalConfig.getSig1().getConsecutiveCount();

        // 查询最近 N 天的作业提交正确率
        List<Double> recentAccuracy = submissionDetailMapper.selectRecentAccuracy(
                goalId, windowDays, tenantId);

        if (recentAccuracy.size() < consecutiveCount) {
            return java.util.Optional.empty();
        }

        // 检查是否连续 N 次低于阈值
        boolean consecutiveLow = true;
        for (int i = recentAccuracy.size() - consecutiveCount; i < recentAccuracy.size(); i++) {
            if (recentAccuracy.get(i) >= threshold) {
                consecutiveLow = false;
                break;
            }
        }

        if (consecutiveLow) {
            TriggerSignalDTO signal = new TriggerSignalDTO();
            signal.setSignalType("SIG-1");
            signal.setDescription(String.format("最近 %d 次作业正确率均低于 %.0f%%", consecutiveCount, threshold * 100));
            return java.util.Optional.of(signal);
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<TriggerSignalDTO> detectSIG2(Long goalId, String tenantId) {
        double masteryDropThreshold = signalConfig.getSig2().getMasteryDrop();

        // 查询最近一次考试导致的掌握度下降
        Double maxDrop = changeLogMapper.selectMaxMasteryDropByGoalId(goalId, tenantId);

        if (maxDrop != null && maxDrop > masteryDropThreshold) {
            TriggerSignalDTO signal = new TriggerSignalDTO();
            signal.setSignalType("SIG-2");
            signal.setDescription(String.format("掌握度单次下降 %.2f，超过阈值 %.2f", maxDrop, masteryDropThreshold));
            return java.util.Optional.of(signal);
        }
        return java.util.Optional.empty();
    }

    /**
     * 修复 Sev-6：从日期列表计算最大连续未完成天数
     */
    private java.util.Optional<TriggerSignalDTO> detectSIG3(Long goalId, String tenantId) {
        int consecutiveDays = signalConfig.getSig3().getConsecutiveDays();

        // 查询最近 30 天内未完成的日期列表
        List<LocalDate> incompleteDays = planMapper.selectIncompleteDays(goalId, tenantId);

        if (incompleteDays.isEmpty()) {
            return java.util.Optional.empty();
        }

        // 计算最大连续天数
        int maxConsecutive = 1;
        int currentConsecutive = 1;
        for (int i = 1; i < incompleteDays.size(); i++) {
            LocalDate prev = incompleteDays.get(i - 1);
            LocalDate curr = incompleteDays.get(i);
            if (curr.equals(prev.plusDays(1))) {
                currentConsecutive++;
                maxConsecutive = Math.max(maxConsecutive, currentConsecutive);
            } else {
                currentConsecutive = 1;
            }
        }

        if (maxConsecutive >= consecutiveDays) {
            TriggerSignalDTO signal = new TriggerSignalDTO();
            signal.setSignalType("SIG-3");
            signal.setDescription(String.format("连续 %d 天未完成作业，超过阈值 %d 天", maxConsecutive, consecutiveDays));
            return java.util.Optional.of(signal);
        }
        return java.util.Optional.empty();
    }

    /**
     * 修复 BLOCK-14：kUncovered = 从未学习过的知识点（masteryScore IS NULL 或 0）
     * 而非 masteryScore < 0.8
     */
    private java.util.Optional<TriggerSignalDTO> detectSIG4(Long goalId, String tenantId) {
        LearningGoal goal = goalMapper.selectById(goalId);
        if (goal == null) {
            return java.util.Optional.empty();
        }

        long dRemain = ChronoUnit.DAYS.between(LocalDate.now(), goal.getEndDate());
        if (dRemain <= 0) {
            return java.util.Optional.empty();
        }

        // 从配置读取 capDay 和 eff（不再硬编码）
        double capDay = signalConfig.getSig4().getCapDay();
        double eff = signalConfig.getSig4().getEfficiency();

        // 修复 BLOCK-14：kUncovered = 从未学习过（masteryScore IS NULL）
        long kUncovered = knowledgeStateMapper.countUncoveredByGoalId(goalId, tenantId);

        if (dRemain * capDay * eff < kUncovered) {
            TriggerSignalDTO signal = new TriggerSignalDTO();
            signal.setSignalType("SIG-4");
            signal.setDescription(String.format(
                    "剩余 %.1f 天 × 容量 %.1f × 效率 %.2f = %.1f < 未覆盖知识点 %d",
                    (double) dRemain, capDay, eff, dRemain * capDay * eff, kUncovered));
            return java.util.Optional.of(signal);
        }
        return java.util.Optional.empty();
    }
}
