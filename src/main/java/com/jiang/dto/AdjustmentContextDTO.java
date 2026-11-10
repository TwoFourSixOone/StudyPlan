package com.jiang.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 计划调整 Agent 上下文 DTO
 *
 * <p>修复调整 Agent 信息不足问题：原 Prompt 只传入 goalId，
 * LLM 无法得知目标详情、未来计划与未覆盖知识点，导致输出凭空编造。
 * 由 Service 层组装真实数据后传入 Agent（对应产品规则 #8：Agent 只能引用已启用知识点）。
 *
 * @author StudyMate Team
 * @since 2026-11-09
 */
@Data
@Builder
public class AdjustmentContextDTO {

    /** 学习目标 ID */
    private Long goalId;

    /** 学生 ID */
    private Long studentId;

    /** 科目 ID */
    private Long subjectId;

    /** 科目名称 */
    private String subjectName;

    /** 目标分数 */
    private Integer targetScore;

    /** 方向描述 */
    private String direction;

    /** 目标开始日期 */
    private LocalDate startDate;

    /** 目标结束日期 */
    private LocalDate endDate;

    /** 未来计划（最近 N 天真实 daily_plan，含原 content 供 LLM 基于其修改） */
    private List<FuturePlanItem> futurePlans;

    /** 未覆盖知识点清单（ID + 名称，截断到最近需要覆盖的数量） */
    private List<KpItem> uncoveredKps;

    /**
     * 未来计划条目
     */
    @Data
    @Builder
    public static class FuturePlanItem {

        /** 计划日期 */
        private LocalDate date;

        /** 计划状态（pending/pending_generation/in_progress/completed/cancelled） */
        private String status;

        /** 原计划内容 JSON（List&lt;DailyPlanQuestionRef&gt; 序列化） */
        private String content;
    }

    /**
     * 未覆盖知识点条目
     */
    @Data
    @Builder
    public static class KpItem {

        /** 知识点 ID */
        private String kpId;

        /** 知识点名称 */
        private String name;
    }
}
