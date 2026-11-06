package com.jiang.dto;

import lombok.Data;

/**
 * 触发信号 DTO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class TriggerSignalDTO {

    /** 信号类型：SIG-1/SIG-2/SIG-3/SIG-4 */
    private String signalType;

    /** 信号描述 */
    private String description;

    /** 信号详情（JSON） */
    private String detail;
}
