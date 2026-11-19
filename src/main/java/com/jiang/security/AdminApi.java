package com.jiang.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理端接口标记（P0-1 角色隔离的**权威判定**依据）
 *
 * <p>R2 复核发现的真实绕过：拦截器曾用原始 {@code requestURI} 字符串前缀判断
 * {@code /api/admin}，但 Spring MVC 路由会做 percent 解码并剥离 matrix 参数，
 * 于是 {@code /api/admin;x=1/goal/1}、{@code /api/%61dmin/goal/1} 等能命中管理端 handler
 * 却绕过角色门禁。</p>
 *
 * <p>现在改为：<b>已匹配到的 {@code HandlerMethod} 的 bean 类型是否标注本注解</b>即是否为管理端接口，
 * 与 Spring 实际路由结果天然一致（不再依赖任何路径字符串推断）。
 * 因此<b>每个管理端 Controller 都必须标注本注解</b>；新增管理端 Controller 时若漏标，
 * 会由 {@code AdminGoalControllerTest} 里的「管理端映射注解覆盖」用例兜住
 * （扫描所有映射到 {@code /api/admin} 的 handler 并断言其 bean 类型带本注解）。</p>
 *
 * <p>路径匹配只作为兜底：仅当 handler 不是 {@code HandlerMethod}（静态资源等）时才使用，
 * 且必须用 Spring 解码/规范化后的路径（{@code UrlPathHelper#getPathWithinApplication}）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminApi {
}
