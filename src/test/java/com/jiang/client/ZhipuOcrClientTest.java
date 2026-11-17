package com.jiang.client;

import com.jiang.dto.OcrPageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GLM-OCR 响应解析测试（2026-11-17）
 */
@DisplayName("ZhipuOcrClient 响应解析")
class ZhipuOcrClientTest {

    @Test
    @DisplayName("正常响应：md_results + image bbox 归一化")
    void should_parseMarkdownAndImageRects() {
        String json = """
                {"id":"task_1","model":"GLM-OCR",
                 "md_results":"# 第1题\\n已知集合A={1,2,3}，求A∩B。",
                 "layout_details":[[
                   {"index":1,"label":"text","bbox_2d":[0.05,0.05,0.95,0.20],"content":"已知集合","width":500,"height":700},
                   {"index":2,"label":"image","bbox_2d":[0.50,0.40,0.90,0.70],"content":"https://x/img.png"},
                   {"index":3,"label":"table","bbox_2d":[0.10,0.75,0.90,0.95],"content":"<table></table>"}
                 ]],
                 "usage":{"total_tokens":123}}
                """;
        OcrPageResult result = ZhipuOcrClient.parseResponse(json);
        assertNotNull(result);
        assertTrue(result.markdown().contains("集合"));
        assertEquals(1, result.imageRects().size());
        Map<String, Double> rect = result.imageRects().get(0);
        assertEquals(0.5, rect.get("x"), 1e-6);
        assertEquals(0.4, rect.get("y"), 1e-6);
        assertEquals(0.4, rect.get("width"), 1e-6);
        assertEquals(0.3, rect.get("height"), 1e-6);
    }

    @Test
    @DisplayName("只有文本没有图片元素 → imageRects 为空")
    void should_parseWithoutImages() {
        String json = """
                {"md_results":"纯文本页","layout_details":[[{"index":1,"label":"text","bbox_2d":[0,0,1,0.1],"content":"纯文本"}]]}
                """;
        OcrPageResult result = ZhipuOcrClient.parseResponse(json);
        assertNotNull(result);
        assertTrue(result.imageRects().isEmpty());
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("空结果/非法 JSON/错误体 → null（调用方降级）")
    void should_returnNull_forEmptyOrInvalid() {
        assertNull(ZhipuOcrClient.parseResponse(null));
        assertNull(ZhipuOcrClient.parseResponse(" "));
        assertNull(ZhipuOcrClient.parseResponse("{\"md_results\":\"\",\"layout_details\":[]}"));
        assertNull(ZhipuOcrClient.parseResponse("{\"error\":{\"code\":\"1211\",\"message\":\"bad\"}}"));
        assertNull(ZhipuOcrClient.parseResponse("not json"));
    }
}
