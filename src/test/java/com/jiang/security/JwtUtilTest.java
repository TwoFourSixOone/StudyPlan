package com.jiang.security;

import com.jiang.config.JwtConfig;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
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
}
