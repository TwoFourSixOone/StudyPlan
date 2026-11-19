package com.jiang.security;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Slf4j
@Component
public class JwtUtil {

    /** 角色 claim 名（P0-1：学生 / 管理员角色隔离） */
    public static final String CLAIM_ROLE = "role";

    /** 角色：学生（默认角色；**缺失 role claim 的旧 token 也按此处理**，避免已登录用户被强制踢出） */
    public static final String ROLE_STUDENT = "student";

    /** 角色：管理员（只有该角色可访问 /api/admin/**） */
    public static final String ROLE_ADMIN = "admin";

    @Autowired
    private JwtConfig jwtConfig;

    /**
     * 签发学生 JWT（兼容旧签名，claim 带 {@code role=student}）
     *
     * @param openid    微信 openid
     * @param studentId 学生 ID
     * @param tenantId  租户 ID
     * @return JWT 字符串
     */
    public String generateToken(String openid, Long studentId, String tenantId) {
        return generateToken(openid, studentId, tenantId, ROLE_STUDENT);
    }

    /**
     * 签发带角色的 JWT
     *
     * @param openid    身份标识（学生 openid / 管理员 {@code admin_<username>}）
     * @param subjectId 主体 ID（学生 ID 或管理员 ID，写入 {@code sub}）
     * @param tenantId  租户 ID
     * @param role      角色：{@link #ROLE_STUDENT} / {@link #ROLE_ADMIN}；空值按 student 处理
     * @return JWT 字符串
     */
    public String generateToken(String openid, Long subjectId, String tenantId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtConfig.getExpiration());

        SecretKey key = Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .subject(String.valueOf(subjectId))
                .claim("openid", openid)
                .claim("tenantId", tenantId)
                .claim(CLAIM_ROLE, normalizeRole(role))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * 签发管理员 JWT（{@code role=admin}），仅供 AdminAuthService 登录使用
     *
     * @param openid  管理员标识（{@code admin_<username>}）
     * @param adminId 管理员 ID（写入 {@code sub}）
     * @param tenantId 租户 ID
     * @return JWT 字符串
     */
    public String generateAdminToken(String openid, Long adminId, String tenantId) {
        return generateToken(openid, adminId, tenantId, ROLE_ADMIN);
    }

    /**
     * 解析 JWT
     *
     * @param token JWT 字符串
     * @return Claims 对象
     * @throws ExpiredJwtException   token 已过期
     * @throws io.jsonwebtoken.UnsupportedJwtException token 格式不支持
     * @throws io.jsonwebtoken.MalformedJwtException   token 结构不完整
     * @throws io.jsonwebtoken.SignatureException      签名校验失败
     * @throws IllegalArgumentException                token 为空或 null
     */
    public Claims parseToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验 JWT 是否有效
     * 内部捕获所有 jjwt 异常，统一返回 false
     *
     * @param token JWT 字符串
     * @return true=有效，false=无效或过期或格式错误
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            log.warn("JWT 校验失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 判断 JWT 是否需要刷新
     * 边界条件：exp - now <= refreshThreshold && exp - now > 0
     * 内部捕获 ExpiredJwtException，已过期则返回 false，无需前置校验
     *
     * @param token JWT 字符串
     * @return true=需要刷新（过期前 5 分钟内，且尚未过期）；已过期返回 false
     */
    public boolean shouldRefresh(String token) {
        try {
            Claims claims = parseToken(token);
            long now = System.currentTimeMillis();
            long exp = claims.getExpiration().getTime();
            long diff = exp - now;
            return diff > 0 && diff <= jwtConfig.getRefreshThreshold();
        } catch (ExpiredJwtException e) {
            // 已过期 = 不需要刷新
            return false;
        } catch (Exception e) {
            // 其他异常（格式错误等）= 不需要刷新
            return false;
        }
    }

    /**
     * 校验并解析 JWT，返回 Claims；校验失败抛出 BusinessException
     *
     * @param token JWT 字符串
     * @return Claims 对象
     * @throws BusinessException token 无效、过期或格式错误
     */
    public Claims parseAndValidateToken(String token) {
        try {
            return parseToken(token);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "JWT 已过期");
        } catch (Exception e) {
            log.warn("JWT 解析失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "JWT 无效");
        }
    }

    /**
     * 从 JWT 中提取 openid
     */
    public String getOpenidFromToken(String token) {
        return parseToken(token).get("openid", String.class);
    }

    /**
     * 从 JWT 中提取 studentId
     */
    public Long getStudentIdFromToken(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    /**
     * 从 JWT 中提取 tenantId
     */
    public String getTenantIdFromToken(String token) {
        return parseToken(token).get("tenantId", String.class);
    }

    /**
     * 从 JWT 中提取角色（缺失/非法 claim 按 student 处理）
     *
     * @param token JWT 字符串
     * @return {@link #ROLE_ADMIN} 或 {@link #ROLE_STUDENT}
     */
    public String getRoleFromToken(String token) {
        return resolveRole(parseToken(token));
    }

    /**
     * 解析 Claims 中的角色
     *
     * <p><b>兼容旧 token</b>：角色 claim 缺失（本次改造前签发）或取值不在白名单内时，
     * 一律按 {@link #ROLE_STUDENT} 处理——已登录学生不会被强制踢出，
     * 但旧 token（含旧 admin token）也**不能**再访问 /api/admin/**。</p>
     *
     * @param claims JWT Claims
     * @return {@link #ROLE_ADMIN} 或 {@link #ROLE_STUDENT}
     */
    public static String resolveRole(Claims claims) {
        if (claims == null) {
            return ROLE_STUDENT;
        }
        return normalizeRole(claims.get(CLAIM_ROLE, String.class));
    }

    /**
     * 是否管理员 token（缺失 role claim 的旧 token 视为学生）
     *
     * @param claims JWT Claims
     * @return true=管理员
     */
    public static boolean isAdmin(Claims claims) {
        return ROLE_ADMIN.equals(resolveRole(claims));
    }

    /**
     * 角色归一：只认 {@link #ROLE_ADMIN}，其余（null / 空 / 未知 / 大小写混杂）统一按 student
     */
    private static String normalizeRole(String role) {
        if (role == null) {
            return ROLE_STUDENT;
        }
        return ROLE_ADMIN.equalsIgnoreCase(role.trim()) ? ROLE_ADMIN : ROLE_STUDENT;
    }
}
