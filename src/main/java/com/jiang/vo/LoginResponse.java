package com.jiang.vo;

import lombok.Data;

/**
 * 登录响应 VO
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
public class LoginResponse {

    private String token;
    private StudentVO studentInfo;
}
