package com.jiang.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.DailyPlanQuestionRef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * daily_plan.content JSON 解析工具类
 * 统一封装 JSON 解析/序列化逻辑，避免各处重复用 Jackson 解析
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Component
public class DailyPlanContentParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 解析 content JSON 为题目引用列表
     * 内部捕获 JsonProcessingException，包装为 BusinessException(CONTENT_PARSE_ERROR)
     */
    public List<DailyPlanQuestionRef> parse(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(contentJson,
                    new TypeReference<List<DailyPlanQuestionRef>>() {});
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.CONTENT_PARSE_ERROR,
                    "daily_plan.content JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 序列化为 JSON 字符串
     * 内部捕获 JsonProcessingException，包装为 BusinessException(CONTENT_SERIALIZE_ERROR)
     */
    public String serialize(List<DailyPlanQuestionRef> refs) {
        try {
            return OBJECT_MAPPER.writeValueAsString(refs);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.CONTENT_SERIALIZE_ERROR,
                    "daily_plan.content JSON 序列化失败: " + e.getMessage());
        }
    }
}
