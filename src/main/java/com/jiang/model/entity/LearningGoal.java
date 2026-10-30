package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 学习目标实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("learning_goal")
public class LearningGoal extends BaseEntity {

    /**
     * 目标 ID
     */
    @TableId("goal_id")
    private Long goalId;

    /**
     * 学生 ID
     */
    private Long studentId;

    /**
     * 科目 ID
     */
    private Long subjectId;

    /**
     * 目标分数
     */
    private Integer targetScore;

    /**
     * 学习方向描述
     */
    private String direction;

    /**
     * 开始日期
     */
    private LocalDate startDate;

    /**
     * 结束日期
     */
    private LocalDate endDate;

    /**
     * 状态：active / completed / expired
     */
    private String status;
}
