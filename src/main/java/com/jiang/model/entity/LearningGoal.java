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
     * 每日题量上限（1-15；NULL = 默认 15）
     *
     * <p>产品规则 #5：SIG-3（连续未完成作业）时由计划调整下调，跨天生效。</p>
     */
    private Integer dailyQuestionLimit;

    /**
     * 本轮暂缓的知识点 ID（JSON 数组字符串；NULL = 不暂缓）
     *
     * <p>产品规则 #5：SIG-4（周期内覆盖不完）时由计划调整写入，生成新题时排除。</p>
     */
    private String pausedKpIds;

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
