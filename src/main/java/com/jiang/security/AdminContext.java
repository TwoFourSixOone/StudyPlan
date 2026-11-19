package com.jiang.security;

/**
 * 管理端上下文（ThreadLocal）
 *
 * <p><b>与学生上下文隔离（P0-1）</b>：管理员 token 的 {@code sub} 是管理员 ID，
 * 不是学生 ID。此前 admin token 的 {@code sub=1} 会被写入 {@link StudentContext#setStudentId}，
 * 导致管理端操作可能被当成 {@code student_id=1} 的真实学生数据（越权/串数据）。</p>
 *
 * <p>现在 admin 请求只写本上下文 + {@link StudentContext#setTenantId}（管理端服务仍依赖租户过滤），
 * <b>不写</b> studentId / openid。</p>
 *
 * <p>线程复用：{@code JwtInterceptor#afterCompletion} 必须调用 {@link #clear()}，
 * 否则线程池复用会把上一个 admin 身份带到下一个请求。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
public class AdminContext {

    private static final ThreadLocal<Long> ADMIN_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> OPENID = new ThreadLocal<>();
    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    private AdminContext() {
    }

    public static void setAdminId(Long adminId) {
        ADMIN_ID.set(adminId);
    }

    public static Long getAdminId() {
        return ADMIN_ID.get();
    }

    public static void setOpenid(String openid) {
        OPENID.set(openid);
    }

    public static String getOpenid() {
        return OPENID.get();
    }

    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    /**
     * 清理全部管理端上下文（拦截器 afterCompletion 必须调用，避免线程复用串身份）
     */
    public static void clear() {
        ADMIN_ID.remove();
        OPENID.remove();
        TENANT_ID.remove();
    }
}
