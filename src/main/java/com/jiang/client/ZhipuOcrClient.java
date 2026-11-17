package com.jiang.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.util.JsonPayloadExtractor;
import com.jiang.dto.OcrPageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GLM-OCR 客户端（2026-11-17，智谱 layout_parsing）
 *
 * <p>用途：对试卷每页做高精度 OCR，把 Markdown 文本与图片 bbox 作为"文本真值"喂给视觉解析，
 * 降低小字号误读（√7→√3）、编题、跨页表格丢失等问题。</p>
 *
 * <p><b>降级策略（重要）</b>：</p>
 * <ul>
 *   <li>未配置 API Key / 开关关闭 → {@link #isEnabled()} 返回 false，调用方完全不启用 OCR 通道</li>
 *   <li>超时 / 非 200 / 响应非法 → 返回 null（该页跳过，不影响视觉解析）</li>
 *   <li>首次尝试 data URI，失败再用裸 base64（兼容不同网关对 file 字段的要求）</li>
 *   <li>所有异常都吞掉并记日志，绝不向上抛，保证解析主流程可用</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@Slf4j
@Component
public class ZhipuOcrClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 显式配置的 Key（优先级高于环境变量） */
    @Value("${exam.parse.ocr.api-key:}")
    private String configuredApiKey;

    /** OCR 通道开关（默认开；没有 Key 时自动关闭） */
    @Value("${exam.parse.ocr.enabled:true}")
    private boolean enabled;

    /** 布局解析接口 */
    @Value("${exam.parse.ocr.base-url:https://open.bigmodel.cn/api/paas/v4/layout_parsing}")
    private String baseUrl;

    /** 单页超时（秒） */
    @Value("${exam.parse.ocr.timeout-seconds:30}")
    private int timeoutSeconds;

    private volatile HttpClient httpClient;

    private volatile boolean missingKeyWarned;

    /**
     * OCR 通道是否可用（开关 + Key 都存在）
     */
    public boolean isEnabled() {
        if (!enabled) {
            return false;
        }
        String key = apiKey();
        if (key == null) {
            if (!missingKeyWarned) {
                missingKeyWarned = true;
                log.warn("GLM-OCR 未配置 API Key（环境变量 ZHIPU_API_KEY 或 exam.parse.ocr.api-key），OCR 通道已关闭");
            }
            return false;
        }
        return true;
    }

    /**
     * 解析单页图片
     *
     * @param imageBytes 图片字节（≤10MB）
     * @param mediaType  媒体类型（如 image/jpeg）
     * @return 解析结果；失败返回 null（调用方跳过该页）
     */
    public OcrPageResult parse(byte[] imageBytes, String mediaType) {
        if (!isEnabled() || imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String type = mediaType == null || mediaType.isBlank() ? "image/jpeg" : mediaType;
        OcrPageResult result = doParse("data:" + type + ";base64," + base64);
        if (result == null) {
            result = doParse(base64);
        }
        return result;
    }

    private OcrPageResult doParse(String fileValue) {
        for (int attempt = 0; attempt <= 1; attempt++) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", "glm-ocr");
                body.put("file", fileValue);
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                        .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                        .header("Authorization", "Bearer " + apiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                        .build();
                HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    OcrPageResult parsed = parseResponse(response.body());
                    if (parsed != null && !parsed.isEmpty()) {
                        return parsed;
                    }
                    log.warn("GLM-OCR 返回空结果: attempt={}", attempt);
                } else {
                    log.warn("GLM-OCR 调用失败: status={}, body={}", response.statusCode(), abbreviate(response.body()));
                }
            } catch (Exception e) {
                log.warn("GLM-OCR 调用异常: attempt={}, reason={}", attempt, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 解析 layout_parsing 响应（单图 → 单页）
     */
    static OcrPageResult parseResponse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(JsonPayloadExtractor.extract(json));
            if (root == null || !root.isObject()) {
                return null;
            }
            String markdown = root.path("md_results").asText("");
            List<Map<String, Double>> rects = new ArrayList<>();
            JsonNode details = root.get("layout_details");
            if (details != null && details.isArray()) {
                for (JsonNode page : details) {
                    if (page == null || !page.isArray()) {
                        continue;
                    }
                    for (JsonNode element : page) {
                        if (!"image".equals(element.path("label").asText())) {
                            continue;
                        }
                        JsonNode bbox = element.get("bbox_2d");
                        if (bbox == null || !bbox.isArray() || bbox.size() != 4) {
                            continue;
                        }
                        double x1 = bbox.get(0).asDouble();
                        double y1 = bbox.get(1).asDouble();
                        double x2 = bbox.get(2).asDouble();
                        double y2 = bbox.get(3).asDouble();
                        Map<String, Double> rect = new LinkedHashMap<>();
                        rect.put("x", Math.max(0d, Math.min(1d, x1)));
                        rect.put("y", Math.max(0d, Math.min(1d, y1)));
                        rect.put("width", Math.max(0.01d, Math.min(1d, x2) - Math.max(0d, Math.min(1d, x1))));
                        rect.put("height", Math.max(0.01d, Math.min(1d, y2) - Math.max(0d, Math.min(1d, y1))));
                        rects.add(rect);
                    }
                }
            }
            OcrPageResult result = new OcrPageResult(markdown, rects);
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.warn("GLM-OCR 响应解析失败: {}", e.getMessage());
            return null;
        }
    }

    private HttpClient client() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    private String apiKey() {
        if (configuredApiKey != null && !configuredApiKey.isBlank()) {
            return configuredApiKey.trim();
        }
        String env = System.getenv("ZHIPU_API_KEY");
        return env == null || env.isBlank() ? null : env.trim();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
