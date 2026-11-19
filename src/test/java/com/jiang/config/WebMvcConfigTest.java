package com.jiang.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebMvcConfig CORS 白名单单元测试（上线收敛）
 *
 * <p>安全目标：从 {@code allowedOriginPatterns("*")} + 携带凭证，改为**可配置白名单**；
 * 白名单为空时不得退化为 {@code "*"}（仅同源放行），并记录一次 WARN。</p>
 *
 * <p>断言方式：调用真实的 {@link WebMvcConfig#addCorsMappings}（用 {@link CorsRegistry}），
 * 再从注册结果里取 {@link CorsConfiguration}，用 Spring 自己的 {@code checkOrigin} 判定，
 * 不自己重写匹配逻辑。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@DisplayName("WebMvcConfig CORS 白名单")
class WebMvcConfigTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(WebMvcConfig.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    // ==================== 白名单解析 ====================

    @Test
    @DisplayName("解析：逗号分隔 + 去空格 + 丢弃空项")
    void should_parseOrigins_when_commaSeparatedWithSpaces() {
        String[] origins = WebMvcConfig.parseAllowedOrigins(
                " http://localhost:5173 , http://127.0.0.1:5173 ,,  https://admin.example.com  ");

        assertEquals(3, origins.length);
        assertEquals("http://localhost:5173", origins[0]);
        assertEquals("http://127.0.0.1:5173", origins[1]);
        assertEquals("https://admin.example.com", origins[2]);
    }

    @Test
    @DisplayName("解析：null / 空串 / 纯空白 / 只有逗号 → 空数组（不产出 \"*\"）")
    void should_returnEmptyOrigins_when_blankOrNull() {
        assertEquals(0, WebMvcConfig.parseAllowedOrigins(null).length);
        assertEquals(0, WebMvcConfig.parseAllowedOrigins("").length);
        assertEquals(0, WebMvcConfig.parseAllowedOrigins("   ").length);
        assertEquals(0, WebMvcConfig.parseAllowedOrigins(" , ,, ").length);
    }

    // ==================== 默认白名单 ====================

    @Test
    @DisplayName("默认配置：只放行本地开发来源（localhost/127.0.0.1），外网来源被拒")
    void should_allowOnlyLocalDevOrigins_when_defaultWhitelist() {
        CorsConfiguration cors = corsConfigurationOf(WebMvcConfig.DEFAULT_ALLOWED_ORIGINS);

        assertEquals("http://localhost:5173", cors.checkOrigin("http://localhost:5173"));
        assertEquals("http://127.0.0.1:5173", cors.checkOrigin("http://127.0.0.1:5173"));
        // 通配端点（本地 H5 调试换端口）
        assertEquals("http://localhost:8080", cors.checkOrigin("http://localhost:8080"));
        // 外网来源不得放行
        assertNull(cors.checkOrigin("https://evil.example.com"));
        assertNull(cors.checkOrigin("http://localhost.evil.example.com"));

        // 仍支持携带凭证 + 暴露刷新 token 头
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());
        assertTrue(cors.getExposedHeaders().contains("X-Refresh-Token"));
        assertTrue(cors.getAllowedMethods().contains("OPTIONS"));
    }

    // ==================== 空白名单（安全默认） ====================

    @Test
    @DisplayName("白名单为空 → 不放行任何跨域来源（不得退化为 *），且只 WARN 一次")
    void should_notFallbackToWildcard_when_whitelistEmpty() {
        CorsConfiguration cors = corsConfigurationOf("");

        // 关键：allowedOrigins 里不能残留 Spring 默认的 "*"
        assertTrue(cors.getAllowedOrigins() == null || cors.getAllowedOrigins().isEmpty(),
                "空白名单不得保留 allowedOrigins 通配: " + cors.getAllowedOrigins());
        assertTrue(cors.getAllowedOriginPatterns() == null || cors.getAllowedOriginPatterns().isEmpty());

        assertNull(cors.checkOrigin("http://localhost:5173"), "空白名单不得放行本地来源");
        assertNull(cors.checkOrigin("https://evil.example.com"));
        // 同源请求不经过 CORS 校验，不受影响；这里仅确认凭证配置未被破坏
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());

        List<ILoggingEvent> warns = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("app.cors.allowed-origins"))
                .toList();
        assertEquals(1, warns.size(), "空白名单应记录一次 WARN 提示如何配置");
    }

    @Test
    @DisplayName("自定义白名单（生产域名）→ 放行配置来源，其它来源仍被拒")
    void should_allowConfiguredOrigins_when_customWhitelist() {
        CorsConfiguration cors = corsConfigurationOf("https://admin.example.com, https://h5.example.com");

        assertEquals("https://admin.example.com", cors.checkOrigin("https://admin.example.com"));
        assertEquals("https://h5.example.com", cors.checkOrigin("https://h5.example.com"));
        assertNull(cors.checkOrigin("https://evil.example.com"));
        assertFalse(logAppender.list.stream()
                        .anyMatch(event -> event.getLevel() == Level.WARN),
                "配置非空时不应产生空白名单 WARN");
    }

    // ==================== 辅助方法 ====================

    /**
     * 调用真实的 {@link WebMvcConfig#addCorsMappings}，取回 /api/** 的 CORS 配置
     *
     * <p>{@link CorsRegistry} 不暴露内部注册结果，测试侧反射读取
     * （Spring 6.1：{@code private final List<CorsRegistration> registrations}）。</p>
     */
    private CorsConfiguration corsConfigurationOf(String rawAllowedOrigins) {
        WebMvcConfig webMvcConfig = new WebMvcConfig();
        ReflectionTestUtils.setField(webMvcConfig, "allowedOrigins", rawAllowedOrigins);

        CorsRegistry registry = new CorsRegistry();
        webMvcConfig.addCorsMappings(registry);

        @SuppressWarnings("unchecked")
        List<CorsRegistration> registrations =
                (List<CorsRegistration>) ReflectionTestUtils.getField(registry, "registrations");
        assertNotNull(registrations, "CorsRegistry 内部结构变化，需同步调整测试");

        CorsRegistration registration = registrations.stream()
                .filter(item -> WebMvcConfig.CORS_PATH_PATTERN.equals(
                        ReflectionTestUtils.getField(item, "pathPattern")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 " + WebMvcConfig.CORS_PATH_PATTERN + " 的 CORS 配置"));

        CorsConfiguration configuration =
                (CorsConfiguration) ReflectionTestUtils.getField(registration, "config");
        assertNotNull(configuration);
        return configuration;
    }
}
