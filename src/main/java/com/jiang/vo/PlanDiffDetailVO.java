package com.jiang.vo;

import com.jiang.dto.PlanDateModification;
import com.jiang.dto.TriggerSignalDTO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计划 diff 详情 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@Builder
public class PlanDiffDetailVO {

    /** diff ID */
    private Long diffId;

    /** 目标 ID */
    private Long goalId;

    /** 状态：pending/confirmed/rejected/expired */
    private String status;

    /** 触发信号 */
    private List<TriggerSignalDTO> signals;

    /** 修改日期列表 */
    private List<PlanDateModification> modifiedDates;

    /** 调整原因 */
    /**
     * 日题量上限调整（null = 未调整；产品规则 #5）
     */
    private Integer dailyQuestionLimit;

    /**
     * 暂缓知识点 ID（null = 未调整；空数组 = 清空暂缓）
     */
    private List<String> pausedKpIds;
    private String reason;

    /** 过期时间 */
    private LocalDateTime expireAt;

    /** 确认时间 */
    private LocalDateTime confirmedAt;

    /** 拒绝时间 */
    private LocalDateTime rejectedAt;

    /** 创建时间 */
    private LocalDateTime createTime;
}
