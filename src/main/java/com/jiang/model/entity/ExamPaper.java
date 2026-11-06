package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jiang.common.handler.JsonStringTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 试卷主表实体
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "exam_paper", autoResultMap = true)
public class ExamPaper extends BaseEntity {

    /**
     * 试卷 ID
     */
    @TableId("id")
    private Long id;

    /**
     * 学生 ID
     */
    private Long studentId;

    /**
     * 科目 ID
     */
    private Long subjectId;

    /**
     * 考试日期
     */
    private LocalDate examDate;

    /**
     * 总分（学生录入后回填）
     */
    private BigDecimal totalScore;

    /**
     * 试卷类型：midterm/final/other
     */
    private String paperType;

    /**
     * 试卷图片 URL 列表（JSON 数组）
     */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String imageUrls;

    /**
     * 状态：pending/processing/completed/failed
     */
    private String status;

    /**
     * 批改状态：ungraded/graded
     */
    private String gradeStatus;

    /**
     * 备注
     */
    private String remark;
}
