package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 科目实体
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("subject")
public class Subject extends BaseEntity {

    /**
     * 科目 ID
     */
    @TableId("subject_id")
    private Long subjectId;

    /**
     * 科目名称
     */
    private String name;

    /**
     * 学段：初中 / 高中
     */
    private String stage;

    /**
     * 科目描述
     */
    private String description;
}
