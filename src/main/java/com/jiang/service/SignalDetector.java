package com.jiang.service;

import com.jiang.dto.TriggerSignalDTO;

import java.util.List;

/**
 * 信号检测服务接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface SignalDetector {

    /**
     * 检测指定目标是否触发调整信号
     * 修复 SQL-H：增加 tenantId 参数，传递到各 SIG 检测方法的 SQL 查询中
     *
     * @param goalId   学习目标 ID
     * @param tenantId 租户 ID
     * @return 触发信号列表（空表示无需调整）
     */
    List<TriggerSignalDTO> detectSignals(Long goalId, String tenantId);
}
