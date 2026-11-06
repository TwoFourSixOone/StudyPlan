package com.jiang.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识点详情 VO
 * 修复 IMP-27：id 字段类型为 String
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class KnowledgePointDetailVO {

    /** 知识点 ID（String 类型） */
    private String id;

    /** 知识点名称 */
    private String name;

    /** 科目名称 */
    private String subjectName;

    /** 状态 */
    private String status;

    /** 描述 */
    private String description;

    /** 层级 */
    private Integer level;

    /** 父节点 ID */
    private String parentId;

    /** 先修知识点列表 */
    private List<PrerequisiteVO> prerequisites;

    /** 被依赖知识点列表 */
    private List<PrerequisiteVO> dependents;

    /** 关联题目数量 */
    private Integer questionCount;

    /** 学习该知识点的学生数量 */
    private Integer studentCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
