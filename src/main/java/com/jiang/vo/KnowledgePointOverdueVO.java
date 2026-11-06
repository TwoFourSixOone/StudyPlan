package com.jiang.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识点超期 VO
 * 修复 IMP-27：id 字段类型为 String
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class KnowledgePointOverdueVO {

    /** 知识点 ID（String 类型） */
    private String id;

    /** 知识点名称 */
    private String name;

    /** 科目名称 */
    private String subjectName;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 超期天数（IMP-13：自然日） */
    private Integer overdueDays;
}
