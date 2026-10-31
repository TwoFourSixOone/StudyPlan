package com.jiang.security;

/**
 * 学生上下文（ThreadLocal）
 * 在请求线程中存储当前登录学生信息
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
