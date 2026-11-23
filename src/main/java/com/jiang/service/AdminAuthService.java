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

    /**
     * 修改当前管理员密码（校验原密码，成功后更新 PBKDF2 哈希）
     *
     * <p>管理员身份只从服务端上下文取（{@code AdminContext#getAdminId()}），不接受客户端传入。</p>
     *
     * @param adminId     当前登录管理员 ID（服务端上下文）
     * @param oldPassword 原密码（明文，仅用于校验）
     * @param newPassword 新密码（明文，落库前哈希）
     * @throws com.jiang.common.exception.BusinessException 原密码不正确（1194）/ 新旧密码相同（1195）/
     *                                                       账号不存在或已停用（401）
     */
    void changePassword(Long adminId, String oldPassword, String newPassword);
}
