package com.jiang.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDate;

/**
 * 学习目标创建完成事件
 *
 * <p>发布时机：目标与计划骨架都已落库（事务内发布，监听方在提交后处理），
 * 用于"目标从今天开始时立即生成今日作业"。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Getter
public class GoalCreatedEvent extends ApplicationEvent {

    private final Long goalId;
    private final Long studentId;
    private final Long subjectId;
    private final String tenantId;
    private final LocalDate startDate;

    public GoalCreatedEvent(Object source, Long goalId, Long studentId, Long subjectId,
                            String tenantId, LocalDate startDate) {
        super(source);
        this.goalId = goalId;
        this.studentId = studentId;
        this.subjectId = subjectId;
        this.tenantId = tenantId;
        this.startDate = startDate;
    }
}
