package com.jiang.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 目标上「本轮暂缓知识点」的解析工具（产品规则 #5 · `learning_goal.paused_kp_ids`）
 *
 * <p>暂缓是优化项：解析失败必须降级为空集合，不能因此阻塞出题或信号检测。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-12
 */
@Slf4j
public final class PausedKpIds {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PausedKpIds() {
    }

    /**
     * 解析暂缓知识点 JSON 数组
     *
     * @param json `learning_goal.paused_kp_ids` 原文（NULL 表示不暂缓）
     * @return 知识点 ID 列表；null / 空白 / 解析失败返回空列表
     */
    public static List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> ids = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.warn("解析暂缓知识点失败，忽略: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析为去重集合（过滤 null 元素，保持插入顺序）
     */
    public static Set<String> parseSet(String json) {
        List<String> ids = parse(json);
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                set.add(id);
            }
        }
        return set;
    }
}
