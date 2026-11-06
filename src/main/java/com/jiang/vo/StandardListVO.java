package com.jiang.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程标准列表 VO
 * 修复 IMP-5：subjectName 字段直接来自 CurriculumStandard.subject（String 类型）
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class StandardListVO {

    /** 标准 ID */
    private Long id;

    /** 标准编码 */
    private String code;

    /** 标准描述 */
    private String description;

    /** 学段：初中 / 高中 */
    private String stage;

    /** 科目名称（IMP-5：直接取 CurriculumStandard.subject，String 类型） */
    private String subjectName;

    /** 创建时间 */
    private LocalDateTime createTime;
}
