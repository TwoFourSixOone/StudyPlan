package com.jiang.common.handler;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.stereotype.Component;

/**
 * 租户处理器
 * Demo 阶段固定 tenant_id = 'default'
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Component
public class TenantHandler implements TenantLineHandler {

    /**
     * 默认租户 ID
     */
    public static final String DEFAULT_TENANT_ID = "default";

    @Override
    public Expression getTenantId() {
        return new StringValue(DEFAULT_TENANT_ID);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // 所有表都需要 tenant_id
        return false;
    }
}
