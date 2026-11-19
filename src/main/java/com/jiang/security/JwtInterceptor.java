package com.jiang.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.Result;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * JWT 拦截器
 *
 * <p>职责（按顺序）：</p>
 * <ol>
 *   <li>解析并校验 token（无效/过期 → 401，交全局异常处理器）</li>
 *   <li><b>角色隔离（P0-1）</b>：管理端接口只允许 {@code role=admin}；学生 token 与
 *       <b>缺失 role claim 的旧 token</b> 一律 403（直接写 HTTP 状态码 + 统一响应体，
 *       不走业务异常映射——{@code GlobalExceptionHandler} 只把 UNAUTHORIZED 映射为 HTTP 状态码）。
 *       判定口径见 {@link #isAdminRequest(Object, String)}：<b>已匹配 {@code HandlerMethod} 的
 *       bean 类型是否标注 {@link AdminApi}</b>（权威），路径仅作非 HandlerMethod 场景的兜底</li>
 *   <li>身份落 ThreadLocal：学生 → {@link StudentContext}；管理员 → {@link AdminContext}
 *       + 仅复用 {@link StudentContext#setTenantId}（管理端服务依赖租户过滤），<b>不写</b> studentId</li>
 *   <li>临近过期时按<b>原角色</b>签发刷新 token（admin 刷新后仍是 admin）</li>
 * </ol>
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Slf4j
@Component
public class JwtInterceptor implements HandlerInterceptor {

    /** 管理端路径根（无子路径也算） */
    static final String ADMIN_PATH = "/api/admin";

    /**
     * 管理端路径模式（**仅兜底**：handler 不是 HandlerMethod 时使用）
     *
     * <p>用 {@link PathPattern} 而不是字符串前缀：PathPattern 与 Spring MVC 路由同一套匹配语义
     * （percent 解码、matrix 参数剥离由 {@link #resolveNormalizedPath} 先行处理）。</p>
     */
    private static final PathPattern ADMIN_ROOT_PATTERN = new PathPatternParser().parse(ADMIN_PATH);
    private static final PathPattern ADMIN_SUB_PATTERN = new PathPatternParser().parse(ADMIN_PATH + "/**");

    /**
     * Spring 的「解码 + 剥离 matrix 参数」工具（禁止直接用 {@code request.getRequestURI()}：
     * Tomcat 返回未解码未归一的原始 URI）
     */
    private static final UrlPathHelper URL_PATH_HELPER = new UrlPathHelper();

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 0. CORS 预检请求不带 Authorization，交 Spring 的 PreFlightHandler 处理（否则跨域预检被 401 误伤）
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }

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

        String openid = claims.get("openid", String.class);
        String tenantId = claims.get("tenantId", String.class);
        String subject = claims.getSubject();
        // 缺失/非法 role claim 按 student 处理（兼容改造前签发的旧 token）
        boolean admin = JwtUtil.isAdmin(claims);

        // 4. 角色隔离：管理端接口只有管理员可访问（学生 / 旧 token → 403，不进业务层、不执行 handler）
        String path = resolveNormalizedPath(request);
        if (!admin && isAdminRequest(handler, path)) {
            // 面向排障：记录规范化路径与 openid，不打印 token
            log.warn("拒绝非管理员访问管理端接口: path={}, openid={}, role={}, handler={}",
                    path, openid, JwtUtil.resolveRole(claims),
                    handler == null ? "null" : handler.getClass().getName());
            writeForbidden(response);
            return false;
        }

        // 5. 身份落 ThreadLocal（先校验再 set，防止异常时泄露数据）
        Long subjectId = parseSubjectId(subject);
        if (admin) {
            // admin 身份只写管理端上下文；租户仍写 StudentContext（管理端服务按 tenant_id 过滤）
            AdminContext.setAdminId(subjectId);
            AdminContext.setOpenid(openid);
            AdminContext.setTenantId(tenantId);
            StudentContext.setTenantId(tenantId);
        } else {
            StudentContext.setStudentId(subjectId);
            StudentContext.setOpenid(openid);
            StudentContext.setTenantId(tenantId);
        }

        // 6. 判断是否需要刷新（边界：exp - now <= refreshThreshold && exp - now > 0），刷新保持原角色
        if (jwtUtil.shouldRefresh(token)) {
            String newToken = admin
                    ? jwtUtil.generateAdminToken(openid, subjectId, tenantId)
                    : jwtUtil.generateToken(openid, subjectId, tenantId);
            response.setHeader("X-Refresh-Token", newToken);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 清除 ThreadLocal，避免线程复用串身份与内存泄漏
        AdminContext.clear();
        StudentContext.clear();
    }

    /**
     * 是否为管理端请求（P0-1 权威判定）
     *
     * <ol>
     *   <li><b>handler 是 {@code HandlerMethod}（Spring 已路由到某个 controller 方法）</b>：
     *       只看该 controller 的 bean 类型是否标注 {@link AdminApi}。
     *       这与 Spring 实际匹配语义天然一致，不会因为 matrix 参数、percent 编码、
     *       重复斜杠、点段等路径变体而漏判</li>
     *   <li><b>非 HandlerMethod（静态资源等）</b>：只用规范化路径兜底（{@link #isAdminPath(String)}）</li>
     * </ol>
     */
    private boolean isAdminRequest(Object handler, String normalizedPath) {
        if (handler instanceof HandlerMethod handlerMethod) {
            // 合并注解查询：兼容代理类/元注解
            return AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), AdminApi.class);
        }
        return isAdminPath(normalizedPath);
    }

    /**
     * 兜底：规范化路径是否命中管理端（{@code /api/admin} 或 {@code /api/admin/**}）
     */
    static boolean isAdminPath(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return false;
        }
        PathContainer container = PathContainer.parsePath(normalizedPath);
        return ADMIN_ROOT_PATTERN.matches(container) || ADMIN_SUB_PATTERN.matches(container);
    }

    /**
     * 取 Spring 语义下的「应用内规范化路径」：percent 解码 + 剥离 matrix 参数 + 折叠重复斜杠 + 去点段
     *
     * @param request 请求（不用原始 {@code requestURI}，Tomcat 不保证解码/归一）
     * @return 以 {@code /} 开头的规范化路径（如 {@code /api/admin;x=1/goal/1} → {@code /api/admin/goal/1}）
     */
    static String resolveNormalizedPath(HttpServletRequest request) {
        // UrlPathHelper：解码 + 移除 matrix 参数（默认 urlDecode=true / removeSemicolonContent=true）
        String path = URL_PATH_HELPER.getPathWithinApplication(request);
        return sanitizePath(path);
    }

    /**
     * 折叠重复斜杠、去掉 {@code .} 段与 {@code ..} 段（与 Spring 对 HTTP path 的规范化等价）
     */
    static String sanitizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : rawPath.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            segments.addLast(segment);
        }
        return "/" + String.join("/", segments);
    }

    private Long parseSubjectId(String subject) {
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            // subject 非法与签名失败同口径：401（不泄漏解析细节）
            throw new BusinessException(ErrorCodes.UNAUTHORIZED);
        }
    }

    /**
     * 写 403（HTTP 状态码 + {@link Result} 统一响应体，与 401 的响应格式一致）
     */
    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(ErrorCodes.FORBIDDEN)));
    }
}
