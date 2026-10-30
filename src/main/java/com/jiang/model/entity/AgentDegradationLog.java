package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Agent 降级日志实体（只读不删）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_degradation_log")
public class AgentDegradationLog extends BaseEntity {

    /**
     * 日志 ID
     */
    @TableId("log_id")
    private Long logId;

    /**
     * Agent 类型：planning / question_gen / attribution / adjustment
     */
    private String agentType;

    /**
     * 输入摘要
     */
    private String inputSummary;

    /**
     * 失败原因
     */
    private String failureReason;

    /**
     * 降级处理动作
     */
    private String degradationAction;
}
