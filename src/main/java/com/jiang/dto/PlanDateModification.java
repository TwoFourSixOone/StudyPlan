package com.jiang.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 计划日期修改 DTO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class PlanDateModification {

    /** 目标日期 */
    private LocalDate date;

    /** 新内容（JSON） */
    private String newContent;

    /** 修改原因 */
    private String reason;
}
