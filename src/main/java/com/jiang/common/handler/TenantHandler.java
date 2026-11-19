package com.jiang.common.handler;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.jiang.security.AdminContext;
import com.jiang.security.StudentContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.stereotype.Component;

/**
 * 租户处理器（真实多租户接入）
 *
 * <p>租户来源优先级（{@link #resolveTenantId()}）：</p>
 * <ol>
 *   <li>管理端请求：{@link AdminContext#getTenantId()}（T2 引入，admin 身份与请求租户）</li>
 *   <li>学生端请求：{@link StudentContext#getTenantId()}（JwtInterceptor 从 JWT claims 写入）</li>
 *   <li>无请求上下文（定时任务 / MQ / Worker 线程 / 启动期）→ {@link #DEFAULT_TENANT_ID}，
 *       保证 01:30 自动调整、考试结算等系统路径照常可用</li>
 * </ol>
 *
 * <p>本类只负责「读/写 SQL 自动追加的租户条件」。实体落库时的租户填充由
 * {@code AutoFillHandler} 负责，二者必须同源（都走 {@link #resolveTenantId()}），
 * 否则会出现「读按请求租户过滤、写固定 default」的跨租户串数据。</p>
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Component
public class TenantHandler implements TenantLineHandler {

    /**
     * 默认租户 ID（无请求上下文时回退；Demo 阶段的单租户数据也在此租户下）
     */
    public static final String DEFAULT_TENANT_ID = "default";

    /**
     * 解析当前租户：管理端上下文 → 学生端上下文 → 默认租户
     *
     * <p>空串/空白一律视为「无上下文」，回退默认租户（JWT claims 缺 tenantId 时不会污染过滤条件）。</p>
     *
     * @return 当前请求租户；无上下文时 {@link #DEFAULT_TENANT_ID}
     */
    public static String resolveTenantId() {
        String adminTenantId = AdminContext.getTenantId();
        if (hasText(adminTenantId)) {
            return adminTenantId;
        }
        String studentTenantId = StudentContext.getTenantId();
        if (hasText(studentTenantId)) {
            return studentTenantId;
        }
        return DEFAULT_TENANT_ID;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public Expression getTenantId() {
        return new StringValue(resolveTenantId());
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    /**
     * 是否忽略某张表的租户过滤
     *
     * <p><b>当前没有任何表忽略</b>：业务表（student / learning_goal / daily_plan / admin_user …）
     * 全部带 {@code tenant_id}，必须按租户过滤。若后续引入**无 tenant_id 的全局表**
     * （如字典表、全局配置表），在此按表名 return true，并写明原因与影响面，
     * 不要用「所有表都忽略」的方式绕过。</p>
     */
    @Override
    public boolean ignoreTable(String tableName) {
        return false;
    }
}
