package com.jiang.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.config.JwtConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtInterceptor 单元测试（P0-1：管理端角色隔离）
 *
 * <p>走真实 JWT 签发/验签（不 mock JwtUtil），覆盖：</p>
 * <ul>
 *   <li>学生 token / 旧的无 role token 访问管理端 → HTTP 403 + 统一响应体，且不进业务层</li>
 *   <li>admin token 访问管理端 → 放行，身份只落 {@link AdminContext}</li>
 *   <li>学生 token 访问学生端接口 → 行为不变（{@link StudentContext} 正常填充）</li>
 *   <li>admin 请求不污染 {@code StudentContext.studentId}（只复用 tenantId）</li>
 *   <li>{@code afterCompletion} 清理两个 ThreadLocal（覆盖线程复用）</li>
 *   <li>刷新 token 时保留原角色；CORS 预检不被 401 误伤</li>
 *   <li><b>R2 绕过回归</b>：matrix 参数 / percent 编码 / 重复斜杠 / 点段 / 尾斜杠等路径变体，
 *       按「已匹配 HandlerMethod 的 {@link AdminApi} 注解」判定时必须 403 且不执行 handler；
 *       非 HandlerMethod 场景走规范化路径兜底</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@DisplayName("JwtInterceptor 角色隔离")
class JwtInterceptorTest {

    private static final String SECRET = "this-is-a-test-secret-key-for-jwt-signing-256-bits";
    private static final String TENANT_ID = "default";
    private static final Long STUDENT_ID = 42L;
    private static final Long ADMIN_ID = 1L;

    private JwtUtil jwtUtil;
    private JwtInterceptor interceptor;
    private MockHttpServletResponse response;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        jwtUtil = newJwtUtil(7200000L, 300000L);

        interceptor = new JwtInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(interceptor, "objectMapper", new ObjectMapper());

        response = new MockHttpServletResponse();

        // 挂 Logback appender，验证 403 有 WARN 日志（含路径）
        Logger logger = (Logger) LoggerFactory.getLogger(JwtInterceptor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(JwtInterceptor.class);
        logger.detachAppender(logAppender);
        AdminContext.clear();
        StudentContext.clear();
    }

    // ==================== 403：学生 / 旧 token 访问管理端 ====================

    @Test
    @DisplayName("学生 token 访问 /api/admin/** → 403 + 统一响应体，且不写任何上下文")
    void should_return403_when_studentTokenCallsAdminApi() throws Exception {
        MockHttpServletRequest request = adminRequest(jwtUtil.generateToken("student_openid", STUDENT_ID, TENANT_ID));

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed, "学生 token 不得进入管理端业务层");
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        Map<?, ?> body = new ObjectMapper().readValue(response.getContentAsString(), Map.class);
        assertEquals(ErrorCodes.FORBIDDEN.getCode(), body.get("code"));
        assertEquals(ErrorCodes.FORBIDDEN.getMessage(), body.get("message"));
        // 拒绝发生在落上下文之前：不残留学生/管理员身份
        assertNull(StudentContext.getStudentId());
        assertNull(StudentContext.getTenantId());
        assertNull(AdminContext.getAdminId());
        // 排障日志：WARN + 路径 + openid（不打印 token）
        assertTrue(logAppender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("/api/admin/goal")
                        && e.getFormattedMessage().contains("student_openid")),
                "403 必须有 WARN 日志记录路径与 openid");
    }

    @Test
    @DisplayName("旧 token（无 role claim）按 student 处理：管理端 403（不强制重新登录）")
    void should_return403_when_legacyTokenWithoutRoleCallsAdminApi() throws Exception {
        MockHttpServletRequest request = adminRequest(legacyTokenWithoutRole());

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        assertNull(StudentContext.getStudentId());
    }

    @Test
    @DisplayName("/api/admin 与 /api/admin/xxx 都算管理端路径（无子路径也拦截）")
    void should_return403_when_studentTokenCallsAdminRootPath() throws Exception {
        String token = jwtUtil.generateToken("student_openid", STUDENT_ID, TENANT_ID);

        for (String path : new String[]{"/api/admin", "/api/admin/", "/api/admin/mastery/dashboard"}) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            boolean allowed = interceptor.preHandle(request(path, token), resp, new Object());
            assertFalse(allowed, path + " 必须被拒绝");
            assertEquals(HttpServletResponse.SC_FORBIDDEN, resp.getStatus(), path);
        }
    }

    // ==================== 放行：admin token ====================

    @Test
    @DisplayName("admin token 访问 /api/admin/** → 放行，身份只落 AdminContext（不写 studentId）")
    void should_allowAndKeepStudentContextClean_when_adminTokenCallsAdminApi() throws Exception {
        MockHttpServletRequest request = adminRequest(jwtUtil.generateAdminToken("admin_admin", ADMIN_ID, TENANT_ID));

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals(ADMIN_ID, AdminContext.getAdminId());
        assertEquals("admin_admin", AdminContext.getOpenid());
        assertEquals(TENANT_ID, AdminContext.getTenantId());
        // 管理端服务依赖租户过滤 → 只复用 tenantId；studentId/openid 不得被污染
        assertEquals(TENANT_ID, StudentContext.getTenantId());
        assertNull(StudentContext.getStudentId(), "admin 请求不得把管理员 ID 写成 studentId");
        assertNull(StudentContext.getOpenid());
    }

    // ==================== 学生端零回归 ====================

    @Test
    @DisplayName("学生 token 访问学生端接口 → 放行且学生上下文正常（含旧 token）")
    void should_allow_when_studentOrLegacyTokenCallsStudentApi() throws Exception {
        String studentToken = jwtUtil.generateToken("student_openid", STUDENT_ID, TENANT_ID);
        String legacyToken = legacyTokenWithoutRole();
        String[] paths = {
                "/api/student/goals",
                "/api/homework/today",
                "/api/error-questions/list",
                "/api/assessment/submit"
        };

        for (String path : paths) {
            // 学生 token（role=student）
            MockHttpServletRequest req = request(path, studentToken);
            assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()), path);
            assertEquals(STUDENT_ID, StudentContext.getStudentId(), path);
            assertEquals("student_openid", StudentContext.getOpenid(), path);
            assertEquals(TENANT_ID, StudentContext.getTenantId(), path);
            assertNull(AdminContext.getAdminId(), path);
            StudentContext.clear();

            // 旧 token（无 role claim）按 student 处理 → 学生接口仍可用
            MockHttpServletRequest legacyReq = request(path, legacyToken);
            assertTrue(interceptor.preHandle(legacyReq, new MockHttpServletResponse(), new Object()),
                    path + " 旧 token 不应被强制踢出");
            assertEquals(STUDENT_ID, StudentContext.getStudentId(), path);
            StudentContext.clear();
        }
    }

    // ==================== 上下文清理 / 刷新 / 预检 / 401 ====================

    @Test
    @DisplayName("afterCompletion 清理 AdminContext 与 StudentContext（覆盖线程复用）")
    void should_clearBothContexts_when_afterCompletion() throws Exception {
        MockHttpServletRequest request = adminRequest(jwtUtil.generateAdminToken("admin_admin", ADMIN_ID, TENANT_ID));
        interceptor.preHandle(request, response, new Object());
        // 模拟线程复用前的残留
        AdminContext.setOpenid("stale_admin");
        StudentContext.setOpenid("stale_student");

        interceptor.afterCompletion(request, response, new Object(), null);

        assertNull(AdminContext.getAdminId());
        assertNull(AdminContext.getOpenid());
        assertNull(AdminContext.getTenantId());
        assertNull(StudentContext.getStudentId());
        assertNull(StudentContext.getOpenid());
        assertNull(StudentContext.getTenantId());
    }

    @Test
    @DisplayName("admin token 临近过期刷新 → 新 token 仍是 admin（不降级为学生）")
    void should_keepAdminRole_when_adminTokenRefreshed() throws Exception {
        // R2-F2：不要再用 1000ms 过期（JWT exp 秒级截断 → 实际有效期 0~1s，偶发“已过期”）。
        // 60s 过期仍远小于 refreshThreshold=300s → shouldRefresh 必然命中，且不受秒截断影响。
        String nearExpiryAdminToken = newJwtUtil(60_000L, 300_000L)
                .generateAdminToken("admin_admin", ADMIN_ID, TENANT_ID);
        MockHttpServletRequest request = adminRequest(nearExpiryAdminToken);

        assertTrue(interceptor.preHandle(request, response, new Object()));

        String refreshed = response.getHeader("X-Refresh-Token");
        assertNotNull(refreshed, "临近过期应下发刷新 token");
        assertTrue(JwtUtil.isAdmin(jwtUtil.parseToken(refreshed)), "刷新后必须仍是管理员角色");
        assertEquals(String.valueOf(ADMIN_ID), jwtUtil.parseToken(refreshed).getSubject());
    }

    @Test
    @DisplayName("CORS 预检（OPTIONS + Origin）不带 token 也放行，避免跨域预检被 401 误伤")
    void should_allow_when_corsPreflightWithoutToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/admin/goal");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "GET");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNull(StudentContext.getStudentId());
        assertNull(AdminContext.getAdminId());
    }

    @Test
    @DisplayName("缺少 token / token 非法 → 抛 UNAUTHORIZED（HTTP 401，既有行为不变）")
    void should_throwUnauthorized_when_tokenMissingOrInvalid() {
        BusinessException missing = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request("/api/student/goals", null), response, new Object()));
        assertEquals(ErrorCodes.UNAUTHORIZED.getCode(), missing.getCode());

        BusinessException invalid = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request("/api/student/goals", "not-a-jwt"), response, new Object()));
        assertEquals(ErrorCodes.UNAUTHORIZED.getCode(), invalid.getCode());
    }

    // ==================== R2 绕过回归：以「已匹配 HandlerMethod 的 @AdminApi 注解」为权威 ====================

    @Test
    @DisplayName("R2-F1 回归（权威判定）：矩阵参数/percent 编码/重复斜杠/点段/尾斜杠路径变体命中 @AdminApi handler → 学生 token 403 且不执行 handler")
    void should_return403_when_studentTokenReachesAdminHandlerViaPathVariants() throws Exception {
        String studentToken = jwtUtil.generateToken("student_openid", STUDENT_ID, TENANT_ID);
        HandlerMethod adminHandler = handlerOf(FakeAdminController.class);

        List<String> rawUris = List.of(
                "/api/admin;x=1/goal/1",   // matrix 参数（Spring 剥离后命中 /api/admin/goal/1）
                "/api/%61dmin/goal/1",     // percent 编码（Spring 解码后命中）
                "//api/admin/goal/1",      // 重复斜杠
                "/api/./admin/goal/1",     // 点段
                "/api/admin/goal/1/",      // 尾斜杠
                "/api/admin/goal/1"        // 明文基线
        );

        for (String rawUri : rawUris) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            boolean allowed = interceptor.preHandle(rawRequest(rawUri, studentToken), resp, adminHandler);
            assertFalse(allowed, rawUri + " 必须被拒绝（不得进入 admin handler）");
            assertEquals(HttpServletResponse.SC_FORBIDDEN, resp.getStatus(), rawUri);
            // 拒绝发生在落上下文之前
            assertNull(StudentContext.getStudentId(), rawUri);
            assertNull(AdminContext.getAdminId(), rawUri);
            // FakeAdminController#handle 一旦被执行会抛 AssertionError（若执行则用例直接失败）
        }
    }

    @Test
    @DisplayName("R2-F1 回归（权威判定）：admin token + 同组路径变体 → 放行（管理端仍正常工作）")
    void should_allow_when_adminTokenReachesAdminHandlerViaPathVariants() throws Exception {
        String adminToken = jwtUtil.generateAdminToken("admin_admin", ADMIN_ID, TENANT_ID);
        HandlerMethod adminHandler = handlerOf(FakeAdminController.class);

        for (String rawUri : List.of("/api/admin;x=1/goal/1", "/api/%61dmin/goal/1",
                "//api/admin/goal/1", "/api/./admin/goal/1")) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(rawRequest(rawUri, adminToken), resp, adminHandler), rawUri);
            assertEquals(ADMIN_ID, AdminContext.getAdminId(), rawUri);
            AdminContext.clear();
            StudentContext.clear();
        }
    }

    @Test
    @DisplayName("权威判定：未标注 @AdminApi 的 handler 即使路径像管理端也放行（路径不参与 HandlerMethod 判定）")
    void should_allow_when_studentTokenReachesNonAdminHandlerMethod() throws Exception {
        String studentToken = jwtUtil.generateToken("student_openid", STUDENT_ID, TENANT_ID);
        MockHttpServletRequest request = request("/api/student/whatever", studentToken);

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(),
                handlerOf(FakeStudentController.class));

        assertTrue(allowed);
        assertEquals(STUDENT_ID, StudentContext.getStudentId());
        assertNull(AdminContext.getAdminId());
    }

    @Test
    @DisplayName("兜底（非 HandlerMethod）：规范化后是管理端路径 → 学生 token 403（matrix/percent/重复斜杠/点段都覆盖）")
    void should_return403_when_nonHandlerMethodWithNormalizedAdminPath() throws Exception {
        String studentToken = jwtUtil.generateToken("student_openid", STUDENT_ID, TENANT_ID);

        for (String rawUri : List.of("/api/admin;x=1/goal/1", "/api/%61dmin/goal/1",
                "//api/admin/goal/1", "/api/./admin/goal/1", "/api/admin", "/api/admin/")) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            boolean allowed = interceptor.preHandle(rawRequest(rawUri, studentToken), resp, new Object());
            assertFalse(allowed, rawUri + " 兜底路径判定必须拒绝");
            assertEquals(HttpServletResponse.SC_FORBIDDEN, resp.getStatus(), rawUri);
        }
    }

    @Test
    @DisplayName("路径规范化：matrix 参数/percent 编码/重复斜杠/点段/尾斜杠都归一为 /api/admin/goal/1（与 Spring 路由语义一致）")
    void should_normalizePathVariants_when_resolvingPath() {
        assertEquals("/api/admin/goal/1", JwtInterceptor.resolveNormalizedPath(rawRequest("/api/admin;x=1/goal/1", null)));
        assertEquals("/api/admin/goal/1", JwtInterceptor.resolveNormalizedPath(rawRequest("/api/%61dmin/goal/1", null)));
        assertEquals("/api/admin/goal/1", JwtInterceptor.resolveNormalizedPath(rawRequest("//api/admin/goal/1", null)));
        assertEquals("/api/admin/goal/1", JwtInterceptor.resolveNormalizedPath(rawRequest("/api/./admin/goal/1", null)));
        assertEquals("/api/admin/goal/1", JwtInterceptor.sanitizePath("/api/admin/goal/1/"));

        assertTrue(JwtInterceptor.isAdminPath("/api/admin"));
        assertTrue(JwtInterceptor.isAdminPath("/api/admin/"));
        assertTrue(JwtInterceptor.isAdminPath("/api/admin/goal/1"));
        // 不能误伤相似前缀（避免把学生接口拦掉）
        assertFalse(JwtInterceptor.isAdminPath("/api/administrator"));
        assertFalse(JwtInterceptor.isAdminPath("/api/student/admin"));
        assertFalse(JwtInterceptor.isAdminPath("/api/homework/today"));
        assertFalse(JwtInterceptor.isAdminPath(""));
        assertFalse(JwtInterceptor.isAdminPath(null));
    }

    // ==================== 辅助方法 ====================

    /**
     * 测试用「管理端 controller」：带 {@link AdminApi}，handler 一旦被执行即抛错（证明 403 前未执行）
     */
    @AdminApi
    static class FakeAdminController {
        public void handle() {
            throw new AssertionError("管理端 handler 不应被执行");
        }
    }

    /**
     * 测试用「学生端 controller」：不带 {@link AdminApi}
     */
    static class FakeStudentController {
        public void handle() {
            // 学生端 handler 允许执行
        }
    }

    private static HandlerMethod handlerOf(Class<?> controllerClass) {
        try {
            return new HandlerMethod(controllerClass.getDeclaredConstructor().newInstance(),
                    controllerClass.getMethod("handle"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("构造 HandlerMethod 失败: " + controllerClass, e);
        }
    }

    /**
     * 构造「原始 URI 不被 MockMvc 再编码」的请求（percent 编码等变体必须用 setRequestURI 直接构造）
     */
    private MockHttpServletRequest rawRequest(String rawUri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", rawUri);
        request.setRequestURI(rawUri);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return request;
    }

    private MockHttpServletRequest adminRequest(String token) {
        return request("/api/admin/goal", token);
    }

    private MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return request;
    }

    private JwtUtil newJwtUtil(long expiration, long refreshThreshold) {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "jwtConfig", newJwtConfig(expiration, refreshThreshold));
        return util;
    }

    private JwtConfig newJwtConfig(long expiration, long refreshThreshold) {
        JwtConfig config = new JwtConfig();
        config.setSecret(SECRET);
        config.setExpiration(expiration);
        config.setRefreshThreshold(refreshThreshold);
        return config;
    }

    /**
     * 构造改造前签发的旧 token（无 role claim），用于验证兼容策略
     */
    private String legacyTokenWithoutRole() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(STUDENT_ID))
                .claim("openid", "legacy_openid")
                .claim("tenantId", TENANT_ID)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 600000L))
                .signWith(key)
                .compact();
    }
}
