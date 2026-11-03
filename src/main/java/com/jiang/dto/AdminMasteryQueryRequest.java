package com.jiang.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端掌握度查询请求 DTO
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminMasteryQueryRequest {

    /**
     * 学生 ID（可选）
     */
    private Long studentId;

    /**
     * 知识点 ID（可选）
     */
    private String knowledgePointId;

    /**
     * 科目（可选）
     */
    private String subject;

    /**
     * 页码，从 1 开始
     */
    @Builder.Default
    private Integer page = 1;

    /**
     * 每页条数
     */
    @Builder.Default
    private Integer size = 10;
}
