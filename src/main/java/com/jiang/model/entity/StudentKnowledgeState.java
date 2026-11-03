package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学生知识点掌握度状态实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("student_knowledge_state")
public class StudentKnowledgeState extends BaseEntity {

    /**
     * 状态 ID
     */
    @TableId("state_id")
    private Long stateId;

    /**
     * 学生 ID
     */
    private Long studentId;

    /**
     * 知识点 ID
     */
    private String knowledgePointId;

    /**
     * 掌握度（0.00-1.00）
     */
    private BigDecimal masteryScore;

    /**
     * 置信度（0.00-1.00）
     */
    private BigDecimal confidence;

    /**
     * 最近来源：homework / assessment / exam
     */
    private String source;

    /**
     * 最近评测时间
     */
    private LocalDateTime lastAssessedAt;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;

    // ===== 以下为非持久化字段，仅用于 JOIN 查询结果映射 =====

    /**
     * 知识点名称（JOIN knowledge_point 时使用）
     */
    @TableField(exist = false)
    private String kpName;

    /**
     * 知识点所属科目（JOIN knowledge_point 时使用）
     */
    @TableField(exist = false)
    private String kpSubject;

    /**
     * 知识点状态（JOIN knowledge_point 时使用）
     */
    @TableField(exist = false)
    private String kpStatus;
}
