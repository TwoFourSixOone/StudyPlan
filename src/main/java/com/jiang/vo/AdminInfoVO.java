package com.jiang.vo;

import lombok.Data;

/**
 * 管理员信息 VO
 *
 * @author StudyMate Team
 * @since 2026-11-05
 */
@Data
public class AdminInfoVO {

    /**
     * 管理员 ID
     */
    private Long adminId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 角色
     */
    private String role;
}
