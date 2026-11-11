package com.jiang.dto;

/**
 * 知识点候选（解析直出用，design v4 §3.7）
 *
 * @param knowledgePointId 知识点 ID（knowledge_point.knowledge_point_id）
 * @param name             知识点名称
 * @author StudyMate Team
 * @since 2026-11-11
 */
public record KpCandidate(String knowledgePointId, String name) {
}
