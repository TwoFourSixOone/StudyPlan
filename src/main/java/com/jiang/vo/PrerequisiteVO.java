package com.jiang.vo;

import lombok.Data;

/**
 * 先修知识点 VO
 * 修复 IMP-27：knowledgePointId 为 String 类型
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class PrerequisiteVO {

    /** 知识点 ID */
    private String knowledgePointId;

    /** 知识点名称 */
    private String knowledgePointName;

    /** 依赖类型：hard/soft */
    private String dependencyType;
}
