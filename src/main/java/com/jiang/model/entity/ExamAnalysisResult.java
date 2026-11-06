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
import java.time.LocalDateTime;

/**
 * 试卷分析结果实体（只读不删）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "exam_analysis_result", autoResultMap = true)
public class ExamAnalysisResult extends BaseEntity {

    /**
     * 分析 ID
     */
    @TableId("analysis_id")
    private Long analysisId;

    /**
     * 学生 ID
     */
    private Long studentId;

    /**
     * 科目 ID
     */
    private Long subjectId;

    /**
     * 考试名称
     */
    private String examName;

    /**
     * 试卷图片 URL 列表
     */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String imageUrls;

    /**
     * 题目分析结果
     */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String questions;

    /**
     * 总分
     */
    private BigDecimal totalScore;

    /**
     * 用户得分
     */
    private BigDecimal userScore;

    /**
     * 状态：pending / processing / completed / failed
     */
    private String status;

    /**
     * 分析完成时间
     */
    private LocalDateTime analyzedAt;
}
