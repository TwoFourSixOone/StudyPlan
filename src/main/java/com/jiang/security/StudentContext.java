package com.jiang.security;

/**
 * 学生上下文（ThreadLocal）
 * 在请求线程中存储当前登录学生信息
 *
 * <p><b>只有学生 token 才能写入 studentId / openid（P0-1）</b>：管理员 token 的 {@code sub}
 * 是管理员 ID，写入这里会被当成 {@code student_id} 参与业务过滤（串数据）。
 * admin 请求由 {@link AdminContext} 承载身份，仅复用本上下文的 {@code tenantId}
 * （管理端服务依赖租户过滤）。</p>
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
public class StudentContext {

    private static final ThreadLocal<Long> STUDENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> OPENID = new ThreadLocal<>();
    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    public static void setStudentId(Long studentId) {
        STUDENT_ID.set(studentId);
    }

    public static Long getStudentId() {
        return STUDENT_ID.get();
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

    public static void clear() {
        STUDENT_ID.remove();
        OPENID.remove();
        TENANT_ID.remove();
    }
}
