package com.jiang.service;

import com.jiang.vo.LoginResponse;
import com.jiang.vo.StudentVO;

/**
 * 认证 Service 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
public interface AuthService {

    /**
     * 微信小程序登录
     *
     * @param code 微信登录凭证
     * @return 登录响应（含 JWT 与学生信息）
     */
    LoginResponse login(String code);

    /**
     * 获取当前登录学生信息
     *
     * @return 学生信息 VO
     */
    StudentVO getCurrentStudent();
}
