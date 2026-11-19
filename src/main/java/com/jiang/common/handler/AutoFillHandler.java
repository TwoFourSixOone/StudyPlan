package com.jiang.common.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 自动填充处理器
 *
 * <p>租户填充必须与读过滤**同源**（{@link TenantHandler#resolveTenantId()}：
 * AdminContext → StudentContext → {@code default}）：MyBatis-Plus 的租户插件对
 * 「列已存在」的 INSERT 不再追加/覆盖 tenant_id，若这里继续写死 {@code default}，
 * 会出现「读按请求租户过滤、写固定 default」的数据分割（请求租户 t1 写入的行 t1 自己读不到）。</p>
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Component
public class AutoFillHandler implements MetaObjectHandler {

    /**
     * 插入时自动填充
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime::now, LocalDateTime.class);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime::now, LocalDateTime.class);
        // 租户取当前上下文（无上下文回退默认租户，保证定时任务/Worker 线程可用）
        this.strictInsertFill(metaObject, "tenantId", TenantHandler::resolveTenantId, String.class);
    }

    /**
     * 更新时自动填充
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime::now, LocalDateTime.class);
    }
}
