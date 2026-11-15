package com.jiang.service.impl;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.BusinessDate;
import com.jiang.dto.TriggerSignalDTO;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.PlanAdjustmentLogMapper;
import com.jiang.model.entity.LearningGoal;
import com.jiang.service.ExamSignalAdjustService;
import com.jiang.service.PlanAdjustmentService;
import com.jiang.service.PlanDiffService;
import com.jiang.service.SignalDetector;
import com.jiang.vo.AdjustTriggerVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 试卷结算后信号检测与自动调整实现（design §8.1/§8.2/§8.3）
 *
 * <p>线程与身份：异步走 {@code aiExecutor}（不占解析池）；Worker 线程没有 HTTP 上下文，
 * 因此本类是<b>纯显式身份入参</b>——不读写 {@code StudentContext}（design §8.2），
 * 租户随 {@code triggerAdjustment(goalId, studentId, tenantId)} 与 {@code saveDiff(..., tenantId)}
 * 一路显式传递，异常也不会污染线程。</p>
 *
 * <p>去重（B2）：12h 窗口查 {@code plan_adjustment_log.action='confirmed'}——applyDiff 实际写的是
 * confirmed（不新增 'applied'，D12）；同学生同科目同日第二次上传已被 1131 拦，这里的去重针对"信号调整"。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Service
@Slf4j
public class ExamSignalAdjustServiceImpl implements ExamSignalAdjustService {

    @Autowired
    private LearningGoalMapper learningGoalMapper;

    @Autowired
    private PlanAdjustmentLogMapper planAdjustmentLogMapper;

    @Autowired
    private SignalDetector signalDetector;

    @Autowired
    private PlanAdjustmentService planAdjustmentService;

    @Autowired
    private PlanDiffService planDiffService;

    @Autowired
    @Qualifier("aiExecutor")
    private ThreadPoolExecutor aiExecutor;

    /** 信号调整去重窗口（小时） */
    @Value("${exam.adjust.dedup-hours:12}")
    private int dedupHours;

    @Override
    public void triggerAfterSettlement(Long paperId, Long studentId, Long subjectId, String tenantId) {
        try {
            aiExecutor.submit(() -> doTrigger(paperId, studentId, subjectId, tenantId));
        } catch (Exception e) {
            // 线程池拒绝等异常不能影响试卷状态（结算已完成）
            log.warn("[ExamAdjust] 信号检测任务提交失败，忽略: paperId={}, error={}", paperId, e.getMessage());
        }
    }

    /**
     * 实际检测与调整（异步线程内执行，全程 try/catch）
     *
     * <p>public 仅为可单测性：生产路径统一经 {@link #triggerAfterSettlement} 异步提交。</p>
     */
    public void doTrigger(Long paperId, Long studentId, Long subjectId, String tenantId) {
        try {
            // 1. 定位 active 且覆盖今天的目标
            LearningGoal goal = learningGoalMapper.selectActiveByStudentSubject(
                    studentId, tenantId, subjectId, BusinessDate.today());
            if (goal == null) {
                log.info("[ExamAdjust] 无进行中目标，跳过: paperId={}, studentId={}, subjectId={}",
                        paperId, studentId, subjectId);
                return;
            }

            // 2. 12h 去重（查 action='confirmed'，避免与 01:30 定时任务同日双调）
            int windowHours = Math.max(1, dedupHours);
            int confirmed = planAdjustmentLogMapper.countConfirmedSince(
                    goal.getGoalId(), tenantId, LocalDateTime.now().minusHours(windowHours));
            if (confirmed > 0) {
                log.info("[ExamAdjust] {}h 内已有确认的调整，跳过: goalId={}", windowHours, goal.getGoalId());
                return;
            }

            // 3. 信号检测（无信号不调用调整 Agent）
            List<TriggerSignalDTO> signals = signalDetector.detectSignals(goal.getGoalId(), tenantId);
            if (signals == null || signals.isEmpty()) {
                log.info("[ExamAdjust] 无信号，跳过: goalId={}", goal.getGoalId());
                return;
            }

            // 4. 调整 Agent（内部已对 Agent 异常降级为 noAdjustment）
            // design §8.2：Worker/异步线程不使用 StudentContext，身份全部显式传参
            // （saveDiff/applyDiff 的租户由 triggerAdjustment 的 tenantId 与 diff 自身携带）
            AdjustTriggerVO vo = planAdjustmentService.triggerAdjustment(
                    goal.getGoalId(), studentId, tenantId);
            if (vo == null || !vo.isAdjusted() || vo.getDiffId() == null) {
                log.info("[ExamAdjust] 未生成调整方案，跳过: goalId={}, message={}",
                        goal.getGoalId(), vo == null ? null : vo.getMessage());
                return;
            }

            // 5. 自动生效（规则 #5 自动模式）
            planDiffService.applyDiff(vo.getDiffId());
            log.info("[ExamAdjust] 考试结算触发自动调整完成: goalId={}, diffId={}, signals={}",
                    goal.getGoalId(), vo.getDiffId(), signals.size());
        } catch (BusinessException e) {
            // PLAN_ADJUST_CONFLICT（已有 pending diff）等业务异常：跳过，不影响试卷
            if (ErrorCodes.PLAN_ADJUST_CONFLICT.getCode().equals(e.getCode())
                    || ErrorCodes.PLAN_ADJUST_COOLDOWN.getCode().equals(e.getCode())) {
                log.info("[ExamAdjust] 已有进行中/去重窗口内的调整，跳过: paperId={}", paperId);
            } else {
                log.warn("[ExamAdjust] 业务异常，跳过: paperId={}, error={}", paperId, e.getMessage());
            }
        } catch (Exception e) {
            log.error("[ExamAdjust] 信号检测/自动调整失败（不影响试卷状态）: paperId={}", paperId, e);
        }
    }
}
