package com.jiang.security;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.config.JwtConfig;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Slf4j
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JwtConfig jwtConfig;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 从 Header 中获取 JWT（兼容 "Bearer <token>" 和直接 "<token>" 两种格式）
        String token = request.getHeader("Authorization");
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED);
        }

        // 2. 去除 "Bearer " 前缀（如有）
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // 3. 一次性解析 JWT（校验 + 提取 Claims，避免重复 HMAC-SHA256 验签）
        Claims claims = jwtUtil.parseAndValidateToken(token);

        // 4. 从 Claims 中提取学生信息并存入 ThreadLocal（先校验再 set，防止异常时泄露数据）
        Long studentId = Long.parseLong(claims.getSubject());
        String openid = claims.get("openid", String.class);
        String tenantId = claims.get("tenantId", String.class);

        StudentContext.setStudentId(studentId);
        StudentContext.setOpenid(openid);
        StudentContext.setTenantId(tenantId);

        // 5. 判断是否需要刷新（边界：exp - now <= refreshThreshold && exp - now > 0）
        if (jwtUtil.shouldRefresh(token)) {
            String newToken = jwtUtil.generateToken(openid, studentId, tenantId);
            response.setHeader("X-Refresh-Token", newToken);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 清除 ThreadLocal，避免内存泄漏
        StudentContext.clear();
    }
}
