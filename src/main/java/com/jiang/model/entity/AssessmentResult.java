package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 专项评测结果实体（只读不删）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "assessment_result", autoResultMap = true)
public class AssessmentResult extends BaseEntity {

    /**
     * 评测 ID
     */
    @TableId("assessment_id")
    private Long assessmentId;

    /**
     * 学生 ID
     */
    private Long studentId;

    /**
     * 涉及的知识点 ID 列表
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String knowledgePointIds;

    /**
     * 得分（百分制，0-100）
     */
    private BigDecimal score;

    /**
     * 总分（百分制，通常为 100）
     */
    private BigDecimal totalScore;

    /**
     * 详细结果（含各知识点正确率）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String results;

    /**
     * 评测时间
     */
    private LocalDateTime assessedAt;
}
