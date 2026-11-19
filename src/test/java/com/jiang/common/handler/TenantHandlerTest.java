package com.jiang.common.handler;

import com.jiang.security.AdminContext;
import com.jiang.security.StudentContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * TenantHandler 单元测试（真实多租户接入）
 *
 * <p>租户来源优先级：AdminContext → StudentContext → {@code default}。
 * 无上下文（定时任务 / MQ / Worker / 启动期）必须回退默认租户，保证系统路径不被“空租户”打断。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@DisplayName("TenantHandler 租户解析")
class TenantHandlerTest {

    private static final String TENANT_ID = "tenant_a";

    private final TenantHandler tenantHandler = new TenantHandler();

    @AfterEach
    void tearDown() {
        AdminContext.clear();
        StudentContext.clear();
    }

    @Test
    @DisplayName("学生端请求：租户取自 StudentContext（JWT claims 写入）")
    void should_useStudentContextTenant_when_studentRequest() {
        StudentContext.setTenantId(TENANT_ID);

        assertEquals(TENANT_ID, TenantHandler.resolveTenantId());
        assertEquals(TENANT_ID, tenantValueOf(tenantHandler.getTenantId()));
    }

    @Test
    @DisplayName("管理端请求：租户优先取自 AdminContext（T2 写入管理端上下文）")
    void should_useAdminContextTenant_when_adminRequest() {
        // JwtInterceptor 对 admin 请求同时写 AdminContext.tenantId 与 StudentContext.tenantId
        AdminContext.setTenantId("tenant_admin");
        StudentContext.setTenantId("tenant_admin");

        assertEquals("tenant_admin", TenantHandler.resolveTenantId());

        // 仅管理端上下文也要生效（不依赖学生上下文）
        StudentContext.clear();
        assertEquals("tenant_admin", TenantHandler.resolveTenantId());
        assertEquals("tenant_admin", tenantValueOf(tenantHandler.getTenantId()));
    }

    @Test
    @DisplayName("无请求上下文（定时任务/Worker/启动期）→ 回退 default")
    void should_fallbackToDefault_when_noContext() {
        assertEquals(TenantHandler.DEFAULT_TENANT_ID, TenantHandler.resolveTenantId());
        assertEquals(TenantHandler.DEFAULT_TENANT_ID, tenantValueOf(tenantHandler.getTenantId()));
    }

    @Test
    @DisplayName("上下文租户为空白 → 视为无上下文，回退 default（不让空串污染过滤条件）")
    void should_fallbackToDefault_when_contextTenantBlank() {
        StudentContext.setTenantId("   ");
        assertEquals(TenantHandler.DEFAULT_TENANT_ID, TenantHandler.resolveTenantId());

        StudentContext.clear();
        AdminContext.setTenantId("");
        assertEquals(TenantHandler.DEFAULT_TENANT_ID, TenantHandler.resolveTenantId());
    }

    @Test
    @DisplayName("显式清理上下文（afterCompletion/remove 之后）→ 回退 default（线程复用安全）")
    void should_fallbackToDefault_afterContextCleared() {
        StudentContext.setTenantId(TENANT_ID);
        assertEquals(TENANT_ID, TenantHandler.resolveTenantId());

        StudentContext.clear();
        AdminContext.clear();

        assertEquals(TenantHandler.DEFAULT_TENANT_ID, TenantHandler.resolveTenantId(),
                "ThreadLocal 清理后必须回退默认租户，不能把上一个请求的租户带给下一个请求");
    }

    @Test
    @DisplayName("租户列名固定 tenant_id；业务表都不忽略租户过滤")
    void should_notIgnoreTenantFilter_forBusinessTables() {
        assertEquals("tenant_id", tenantHandler.getTenantIdColumn());
        assertFalse(tenantHandler.ignoreTable("student"));
        assertFalse(tenantHandler.ignoreTable("learning_goal"));
        assertFalse(tenantHandler.ignoreTable("daily_plan"));
        assertFalse(tenantHandler.ignoreTable("admin_user"));
    }

    private String tenantValueOf(Expression expression) {
        return ((StringValue) expression).getValue();
    }
}
