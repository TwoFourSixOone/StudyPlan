package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 科目视图对象
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubjectVO {

    private Long subjectId;

    private String name;

    private String stage;            // 初中 / 高中

    private String description;

    private Integer activeKnowledgePointCount;  // 已启用知识点数量
}
