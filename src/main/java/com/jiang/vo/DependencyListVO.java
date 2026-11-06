package com.jiang.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 先修关系列表 VO
 * 修复 IMP-5：知识点 ID 为 String 类型
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class DependencyListVO {

    /** 关系 ID */
    private Long id;

    /** 知识点名称（IMP-5：批量查询） */
    private String knowledgePointName;

    /** 先修知识点名称（IMP-5：批量查询） */
    private String prerequisiteName;

    /** 依赖类型：hard / soft */
    private String dependencyType;

    /** 创建时间 */
    private LocalDateTime createTime;
}
