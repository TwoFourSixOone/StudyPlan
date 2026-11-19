package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 管理员账号 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {

    /**
     * 按登录名查询管理员账号（租户条件由 MyBatis-Plus 租户插件自动追加）
     *
     * <p>登录接口在 {@code /api/admin/auth/login}（免鉴权、无请求上下文），
     * 因此实际按默认租户查询；多租户场景下同一登录名可在不同租户各自存在。</p>
     *
     * @param username 登录名
     * @return 管理员账号；不存在时 {@code null}
     */
    @Select("SELECT admin_id, username, password_hash, role, status, tenant_id, create_time, update_time " +
            "FROM admin_user WHERE username = #{username}")
    AdminUser selectByUsername(@Param("username") String username);
}
