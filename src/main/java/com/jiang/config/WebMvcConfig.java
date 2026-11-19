package com.jiang.config;

import com.jiang.security.JwtInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * MVC 配置（注册拦截器 + CORS）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** CORS 作用路径 */
    static final String CORS_PATH_PATTERN = "/api/**";

    /**
     * 默认放行来源（仅本地开发）：微信小程序走原生请求、不存在浏览器 CORS；
     * 这里只为 admin-web（Vite 5173）与本地 H5 调试放行，生产必须用配置覆盖。
     *
     * <p>生产覆盖方式（不改仓库内 yml，任选其一）：</p>
     * <ul>
     *   <li>环境变量：{@code APP_CORS_ALLOWED_ORIGINS=https://admin.example.com,https://h5.example.com}</li>
     *   <li>启动参数：{@code --app.cors.allowed-origins=https://admin.example.com}</li>
     * </ul>
     * <p>支持通配子域/端口（Spring {@code allowedOriginPatterns} 语法，如 {@code http://localhost:*}）；
     * 带凭证（{@code allowCredentials=true}）时**不允许** {@code "*"}，白名单为空即只允许同源。</p>
     */
    static final String DEFAULT_ALLOWED_ORIGINS =
            "http://localhost:5173,http://127.0.0.1:5173,http://localhost:*";

    @Autowired
    private JwtInterceptor jwtInterceptor;

    /**
     * CORS 白名单（逗号分隔，来自 {@code app.cors.allowed-origins}）
     *
     * <p>未配置时使用本地开发默认值；显式配置为空串表示「不放行任何跨域来源」（仅同源）。</p>
     */
    @Value("${app.cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}")
    private String allowedOrigins;

    /**
     * 注册 JWT 拦截器
     *
     * <p>P0-1 角色隔离：管理端接口的「必须 role=admin」断言统一在 {@link JwtInterceptor#preHandle}
     * 内完成，判定口径为：</p>
     * <ul>
     *   <li><b>权威判定</b>：Spring 已路由到 {@code HandlerMethod} 时，看其 bean 类型是否标注
     *       {@code @AdminApi}（com.jiang.security.AdminApi）——与真实路由语义天然一致，
     *       不受 matrix 参数（{@code ;x=1}）、percent 编码（{@code %61}）、重复斜杠、点段等
     *       路径变体影响；</li>
     *   <li><b>兜底判定</b>：仅当 handler 不是 {@code HandlerMethod}（静态资源等）时才用路径匹配，
     *       且路径取 {@code UrlPathHelper#getPathWithinApplication} 解码 + 剥离 matrix 参数后的
     *       规范路径（再折叠重复斜杠、去 {@code .}/{@code ..} 段），用预编译 {@code PathPattern}
     *       匹配 {@code /api/admin} 与 {@code /api/admin/**}。</li>
     * </ul>
     *
     * <p>非管理员（学生 token / 旧的无 role token）访问管理端时，由拦截器直接写
     * HTTP 403 + 统一响应体（不走业务异常映射，且不执行 handler），
     * 因此这里无需再注册第二个拦截器；两个登录路径继续免鉴权。</p>
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/admin/auth/login"  // 管理员登录接口免鉴权
                );
    }

    /**
     * CORS 配置（上线收敛：从「放行任意来源」改为可配置白名单）
     *
     * <p><b>安全默认</b>：白名单为空时**不得**退化为 {@code "*"}，而是不放行任何跨域来源
     * （同源请求不经过 CORS 校验，仍可正常访问）；此时启动日志 WARN 一次提示如何配置。</p>
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = parseAllowedOrigins(allowedOrigins);
        if (origins.length == 0) {
            log.warn("app.cors.allowed-origins 未配置任何来源 → 仅允许同源访问（不会退化为 \"*\"）；"
                    + "请通过环境变量 APP_CORS_ALLOWED_ORIGINS 或启动参数 --app.cors.allowed-origins 配置白名单");
        }
        registry.addMapping(CORS_PATH_PATTERN)
                // CorsRegistration 默认带 allowedOrigins("*")，必须显式清空：
                // 否则空白名单 + allowCredentials(true) 会退化为「任意来源 + 携带凭证」
                .allowedOrigins(new String[0])
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Refresh-Token")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 解析白名单配置：逗号分隔，逐项 trim，丢弃空项（容忍多余逗号/空格/空串）
     *
     * @param raw 原始配置值（可为 null）
     * @return 非空白名单；无有效项时返回空数组
     */
    static String[] parseAllowedOrigins(String raw) {
        if (raw == null) {
            return new String[0];
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }
}
