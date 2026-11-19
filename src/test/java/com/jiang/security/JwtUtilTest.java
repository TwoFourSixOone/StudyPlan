package com.jiang.security;

import com.jiang.config.JwtConfig;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具类单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        // 48 字节，满足 >= 32 字节要求
        config.setSecret("this-is-a-test-secret-key-for-jwt-signing-256-bits");
        config.setExpiration(7200000L);
        config.setRefreshThreshold(300000L);

        jwtUtil = new JwtUtil();
        // 通过反射注入 jwtConfig
        try {
            var field = JwtUtil.class.getDeclaredField("jwtConfig");
            field.setAccessible(true);
            field.set(jwtUtil, config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void should_generateValidToken_when_validParams() {
        String token = jwtUtil.generateToken("test_openid", 1L, "default");
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void should_parseTokenSuccessfully_when_validToken() {
        String token = jwtUtil.generateToken("test_openid", 1L, "default");
        var claims = jwtUtil.parseToken(token);
        assertEquals("1", claims.getSubject());
        assertEquals("test_openid", claims.get("openid"));
        assertEquals("default", claims.get("tenantId"));
    }

    @Test
    void should_returnTrue_when_tokenIsValid() {
        String token = jwtUtil.generateToken("test_openid", 1L, "default");
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    void should_returnFalse_when_tokenIsInvalid() {
        assertFalse(jwtUtil.validateToken("invalid.token.string"));
    }

    @Test
    void should_returnFalse_when_tokenIsNull() {
        assertFalse(jwtUtil.validateToken(null));
    }

    @Test
    void should_returnFalse_when_tokenIsEmpty() {
        assertFalse(jwtUtil.validateToken(""));
    }

    @Test
    void should_returnTrue_when_shouldRefreshAndNearExpiry() {
        // 生成一个即将过期的 token（通过手动设置短过期时间）
        JwtConfig shortConfig = new JwtConfig();
        shortConfig.setSecret("this-is-a-test-secret-key-for-jwt-signing-256-bits");
        shortConfig.setExpiration(1000L); // 1 秒过期
        shortConfig.setRefreshThreshold(300000L); // 5 分钟刷新阈值

        JwtUtil shortJwtUtil = new JwtUtil();
        try {
            var field = JwtUtil.class.getDeclaredField("jwtConfig");
            field.setAccessible(true);
            field.set(shortJwtUtil, shortConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String token = shortJwtUtil.generateToken("test_openid", 1L, "default");
        // token 1 秒后过期，在 5 分钟刷新阈值内，应该返回 true
        assertTrue(jwtUtil.shouldRefresh(token));
    }

    @Test
    void should_returnFalse_when_shouldRefreshAndNotNearExpiry() {
        String token = jwtUtil.generateToken("test_openid", 1L, "default");
        // token 2 小时后过期，不在 5 分钟刷新阈值内
        assertFalse(jwtUtil.shouldRefresh(token));
    }

    @Test
    void should_returnFalse_when_shouldRefreshAndExpired() {
        // 构造一个已过期的 token
        JwtConfig expiredConfig = new JwtConfig();
        expiredConfig.setSecret("this-is-a-test-secret-key-for-jwt-signing-256-bits");
        expiredConfig.setExpiration(-1000L); // 负数过期时间，立即过期
        expiredConfig.setRefreshThreshold(300000L);

        JwtUtil expiredJwtUtil = new JwtUtil();
        try {
            var field = JwtUtil.class.getDeclaredField("jwtConfig");
            field.setAccessible(true);
            field.set(expiredJwtUtil, expiredConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String token = expiredJwtUtil.generateToken("test_openid", 1L, "default");
        // 已过期 token，shouldRefresh 返回 false
        assertFalse(jwtUtil.shouldRefresh(token));
    }

    @Test
    void should_extractOpenid_when_validToken() {
        String token = jwtUtil.generateToken("test_openid", 1L, "default");
        assertEquals("test_openid", jwtUtil.getOpenidFromToken(token));
    }

    @Test
    void should_extractStudentId_when_validToken() {
        String token = jwtUtil.generateToken("test_openid", 42L, "default");
        assertEquals(42L, jwtUtil.getStudentIdFromToken(token));
    }

    @Test
    void should_extractTenantId_when_validToken() {
        String token = jwtUtil.generateToken("test_openid", 1L, "my_tenant");
        assertEquals("my_tenant", jwtUtil.getTenantIdFromToken(token));
    }

    @Test
    void should_throwExpiredJwtException_when_parseExpiredToken() {
        JwtConfig expiredConfig = new JwtConfig();
        expiredConfig.setSecret("this-is-a-test-secret-key-for-jwt-signing-256-bits");
        expiredConfig.setExpiration(-1000L);
        expiredConfig.setRefreshThreshold(300000L);

        JwtUtil expiredJwtUtil = new JwtUtil();
        try {
            var field = JwtUtil.class.getDeclaredField("jwtConfig");
            field.setAccessible(true);
            field.set(expiredJwtUtil, expiredConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String token = expiredJwtUtil.generateToken("test_openid", 1L, "default");
        assertThrows(ExpiredJwtException.class, () -> jwtUtil.parseToken(token));
    }

    @Test
    void should_returnFalseFromValidate_when_expiredToken() {
        JwtConfig expiredConfig = new JwtConfig();
        expiredConfig.setSecret("this-is-a-test-secret-key-for-jwt-signing-256-bits");
        expiredConfig.setExpiration(-1000L);
        expiredConfig.setRefreshThreshold(300000L);

        JwtUtil expiredJwtUtil = new JwtUtil();
        try {
            var field = JwtUtil.class.getDeclaredField("jwtConfig");
            field.setAccessible(true);
            field.set(expiredJwtUtil, expiredConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String token = expiredJwtUtil.generateToken("test_openid", 1L, "default");
        assertFalse(jwtUtil.validateToken(token));
    }

    @Test
    void should_throwMalformedJwtException_when_parseMalformedToken() {
        assertThrows(MalformedJwtException.class, () -> jwtUtil.parseToken("malformed"));
    }

    // ==================== P0-1：角色 claim 与旧 token 兼容 ====================

    @Test
    @DisplayName("默认签发（旧签名）→ role=student")
    void should_carryStudentRole_when_generateDefaultToken() {
        String token = jwtUtil.generateToken("test_openid", 1L, "default");

        assertEquals(JwtUtil.ROLE_STUDENT, jwtUtil.parseToken(token).get(JwtUtil.CLAIM_ROLE, String.class));
        assertFalse(JwtUtil.isAdmin(jwtUtil.parseToken(token)));
        assertEquals(JwtUtil.ROLE_STUDENT, jwtUtil.getRoleFromToken(token));
    }

    @Test
    @DisplayName("generateAdminToken → role=admin 且 sub=管理员ID")
    void should_carryAdminRole_when_generateAdminToken() {
        String token = jwtUtil.generateAdminToken("admin_admin", 1L, "default");

        var claims = jwtUtil.parseToken(token);
        assertEquals(JwtUtil.ROLE_ADMIN, claims.get(JwtUtil.CLAIM_ROLE, String.class));
        assertEquals("1", claims.getSubject());
        assertEquals("admin_admin", claims.get("openid", String.class));
        assertTrue(JwtUtil.isAdmin(claims));
        assertEquals(JwtUtil.ROLE_ADMIN, jwtUtil.getRoleFromToken(token));
    }

    @Test
    @DisplayName("旧 token（无 role claim）→ 按 student 处理（不强制重新登录）")
    void should_treatAsStudent_when_roleClaimMissing() {
        String legacyToken = legacyTokenWithoutRole();

        var claims = jwtUtil.parseToken(legacyToken);
        assertNull(claims.get(JwtUtil.CLAIM_ROLE, String.class));
        assertEquals(JwtUtil.ROLE_STUDENT, JwtUtil.resolveRole(claims));
        assertFalse(JwtUtil.isAdmin(claims));
        assertEquals(JwtUtil.ROLE_STUDENT, jwtUtil.getRoleFromToken(legacyToken));
    }

    @Test
    @DisplayName("未知 / 大小写混杂的 role 取值 → 只认 admin（其余按 student）")
    void should_normalizeRole_when_roleClaimIsUnknownOrMixedCase() {
        assertEquals(JwtUtil.ROLE_STUDENT, JwtUtil.resolveRole(claimsWithRole("teacher")));
        assertEquals(JwtUtil.ROLE_STUDENT, JwtUtil.resolveRole(claimsWithRole("")));
        assertEquals(JwtUtil.ROLE_ADMIN, JwtUtil.resolveRole(claimsWithRole("ADMIN")));
        assertEquals(JwtUtil.ROLE_ADMIN, JwtUtil.resolveRole(claimsWithRole(" admin ")));
        // null Claims 也不能 NPE
        assertEquals(JwtUtil.ROLE_STUDENT, JwtUtil.resolveRole(null));
    }

    /**
     * 构造改造前签发的旧 token（无 role claim）
     */
    private String legacyTokenWithoutRole() {
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                "this-is-a-test-secret-key-for-jwt-signing-256-bits"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.Date now = new java.util.Date();
        return io.jsonwebtoken.Jwts.builder()
                .subject("1")
                .claim("openid", "legacy_openid")
                .claim("tenantId", "default")
                .issuedAt(now)
                .expiration(new java.util.Date(now.getTime() + 600000L))
                .signWith(key)
                .compact();
    }

    /**
     * 用给定 role 取值构造 Claims（模拟各类历史/异常取值）
     */
    private io.jsonwebtoken.Claims claimsWithRole(String role) {
        io.jsonwebtoken.Claims claims = org.mockito.Mockito.mock(io.jsonwebtoken.Claims.class);
        org.mockito.Mockito.when(claims.get(JwtUtil.CLAIM_ROLE, String.class)).thenReturn(role);
        return claims;
    }
}
