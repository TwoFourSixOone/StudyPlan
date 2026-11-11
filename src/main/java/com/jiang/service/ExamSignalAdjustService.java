package com.jiang.service;

/**
 * 试卷结算后的信号检测与自动调整（design §8 / tasks C1）
 *
 * <p>时机：结算 TX-B 提交之后异步执行（不占解析池、不阻塞任务收尾）。
 * 顺序：定位 active 目标 → 12h 去重（查 {@code plan_adjustment_log.action='confirmed'}）→
 * detectSignals → triggerAdjustment → applyDiff；无目标/无信号/冲突均静默跳过，
 * 任何失败都不影响试卷状态与已写入的掌握度/错题。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
public interface ExamSignalAdjustService {

    /**
     * 触发一次结算后的信号调整（内部异步执行，调用方无需等待）
     *
     * @param paperId   试卷 ID（日志追溯）
     * @param studentId 学生 ID（显式入参，Worker 不读 ThreadLocal）
     * @param subjectId 科目 ID（定位该科目的 active 目标）
     * @param tenantId  租户 ID
     */
    void triggerAfterSettlement(Long paperId, Long studentId, Long subjectId, String tenantId);
}
