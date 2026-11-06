package com.jiang.vo;

import lombok.Data;

/**
 * 管理员登录响应 VO
 *
 * @author StudyMate Team
 * @since 2026-11-05
 */
@Data
public class AdminLoginResponse {

    /**
     * JWT Token
     */
    private String token;

    /**
     * 管理员信息
     */
    private AdminInfoVO adminInfo;
}
