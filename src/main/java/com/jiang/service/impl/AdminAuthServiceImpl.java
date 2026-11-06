package com.jiang.service.impl;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.security.JwtUtil;
import com.jiang.service.AdminAuthService;
import com.jiang.vo.AdminInfoVO;
import com.jiang.vo.AdminLoginResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 管理员认证 Service 实现（Demo 阶段硬编码）
 * 
 * TODO: 后续 change 引入 admin 表，持久化管理员账号
 *
 * @author StudyMate Team
 * @since 2026-11-05
 */
@Slf4j
@Service
public class AdminAuthServiceImpl implements AdminAuthService {

    @Autowired
    private JwtUtil jwtUtil;

    // Demo 阶段硬编码（后续改为数据库查询）
    private static final String DEMO_USERNAME = "admin";
    private static final String DEMO_PASSWORD = "smileday159";
    private static final Long DEMO_ADMIN_ID = 1L;

    @Override
    public AdminLoginResponse login(String username, String password) {
        // 1. 校验用户名密码（Demo 阶段硬编码）
        if (!DEMO_USERNAME.equals(username) || !DEMO_PASSWORD.equals(password)) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "用户名或密码错误");
        }

        // 2. 生成 JWT（复用学生 JWT 结构，openid 使用 "admin_{username}"）
        String openid = "admin_" + username;
        String tenantId = "default";
        String token = jwtUtil.generateToken(openid, DEMO_ADMIN_ID, tenantId);

        // 3. 构造响应
        AdminLoginResponse response = new AdminLoginResponse();
        response.setToken(token);

        AdminInfoVO adminInfo = new AdminInfoVO();
        adminInfo.setAdminId(DEMO_ADMIN_ID);
        adminInfo.setUsername(username);
        adminInfo.setNickname("管理员");
        adminInfo.setRole("admin");
        response.setAdminInfo(adminInfo);

        log.info("管理员登录成功：username={}, adminId={}", username, DEMO_ADMIN_ID);

        return response;
    }
}
