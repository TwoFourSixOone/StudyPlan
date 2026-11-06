package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 课程标准实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("curriculum_standard")
public class CurriculumStandard extends BaseEntity {

    /**
     * 标准 ID
     */
    @TableId(value = "standard_id", type = IdType.AUTO)
    private Long standardId;

    /**
     * 科目
     */
    private String subject;

    /**
     * 学段：初中 / 高中
     */
    private String stage;

    /**
     * 标准编码
     */
    private String code;

    /**
     * 标准描述
     */
    private String description;
}
