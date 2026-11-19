package com.jiang.common.handler;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jiang.model.entity.Student;
import com.jiang.security.AdminContext;
import com.jiang.security.StudentContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AutoFillHandler 单元测试（写路径租户一致性）
 *
 * <p>MyBatis-Plus 的租户插件对「INSERT 列里已有 tenant_id」不覆盖，因此实体落库的租户必须与
 * 读过滤同源（AdminContext → StudentContext → default），否则请求租户 t1 写入的行会落到 default，
 * 形成「写 default / 读 t1」的数据分割。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@DisplayName("AutoFillHandler 写路径租户填充")
class AutoFillHandlerTest {

    private final AutoFillHandler autoFillHandler = new AutoFillHandler();

    /**
     * 单测没有 MyBatis 容器：strictInsertFill 依赖实体 TableInfo（填充标记）
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("com.jiang.mapper.StudentMapper");
        TableInfoHelper.initTableInfo(assistant, Student.class);
    }

    @AfterEach
    void tearDown() {
        AdminContext.clear();
        StudentContext.clear();
    }

    @Test
    @DisplayName("学生请求：插入时 tenant_id 取 StudentContext（与读过滤同源）")
    void should_fillStudentContextTenant_when_insert() {
        StudentContext.setTenantId("tenant_a");
        Student student = new Student();

        autoFillHandler.insertFill(SystemMetaObject.forObject(student));

        assertEquals("tenant_a", student.getTenantId());
        assertNotNull(student.getCreateTime());
        assertNotNull(student.getUpdateTime());
    }

    @Test
    @DisplayName("管理端请求：插入时 tenant_id 取 AdminContext 租户")
    void should_fillAdminContextTenant_when_insert() {
        AdminContext.setTenantId("tenant_admin");
        Student entity = new Student();

        autoFillHandler.insertFill(SystemMetaObject.forObject(entity));

        assertEquals("tenant_admin", entity.getTenantId());
    }

    @Test
    @DisplayName("无请求上下文（定时任务/Worker）→ 回退 default，系统路径可写")
    void should_fillDefaultTenant_when_noContext() {
        Student entity = new Student();

        autoFillHandler.insertFill(SystemMetaObject.forObject(entity));

        assertEquals(TenantHandler.DEFAULT_TENANT_ID, entity.getTenantId());
        assertNotNull(entity.getCreateTime());
    }

    @Test
    @DisplayName("调用方显式指定租户（如 applyDiff 用 diff 自身租户）→ 不被覆盖")
    void should_notOverwriteExplicitTenant_when_insert() {
        StudentContext.setTenantId("tenant_a");
        Student entity = new Student();
        entity.setTenantId("tenant_explicit");

        autoFillHandler.insertFill(SystemMetaObject.forObject(entity));

        assertEquals("tenant_explicit", entity.getTenantId());
    }

    @Test
    @DisplayName("更新时只填 update_time，不动 tenant_id")
    void should_fillOnlyUpdateTime_when_update() {
        Student entity = new Student();
        entity.setTenantId("tenant_a");

        autoFillHandler.updateFill(SystemMetaObject.forObject(entity));

        assertNotNull(entity.getUpdateTime());
        assertEquals("tenant_a", entity.getTenantId());
        assertNull(entity.getCreateTime());
    }
}
