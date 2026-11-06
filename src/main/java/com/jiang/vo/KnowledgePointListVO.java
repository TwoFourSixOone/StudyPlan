package com.jiang.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识点列表 VO
 * 修复 IMP-27：id 字段类型为 String
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class KnowledgePointListVO {

    /** 知识点 ID（String 类型） */
    private String id;

    /** 知识点名称 */
    private String name;

    /** 科目名称 */
    private String subjectName;

    /** 状态 */
    private String status;

    /** 关联题目数量（IMP-12：LEFT JOIN 一次查出） */
    private Integer questionCount;

    /** 先修关系数量 */
    private Integer dependencyCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
