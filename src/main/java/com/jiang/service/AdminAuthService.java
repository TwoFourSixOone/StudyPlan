package com.jiang.service;

import com.jiang.vo.AdminLoginResponse;

/**
 * 管理员认证 Service 接口
 *
 * @author StudyMate Team
 * @since 2026-11-05
 */
public interface AdminAuthService {

    /**
     * 管理员登录（用户名 + 密码）
     *
     * @param username 用户名
     * @param password 密码
     * @return 登录响应（含 JWT 与管理员信息）
     */
    AdminLoginResponse login(String username, String password);
}
