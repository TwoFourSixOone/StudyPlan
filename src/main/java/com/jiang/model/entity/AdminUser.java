package com.jiang.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 管理员账号实体（上线加固：管理端账号落库，取代源码硬编码账号）
 *
 * <p>密码只存 PBKDF2 哈希（格式 {@code pbkdf2$<iterations>$<saltBase64>$<hashBase64>}），
 * 表结构见 {@code db/V17__admin_user.sql}。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("admin_user")
public class AdminUser extends BaseEntity {

    /**
     * 管理员 ID
     */
    @TableId("admin_id")
    private Long adminId;

    /**
     * 登录名（租户内唯一）
     */
    private String username;

    /**
     * 密码哈希：{@code pbkdf2$<iterations>$<saltBase64>$<hashBase64>}（禁止明文）
     */
    private String passwordHash;

    /**
     * 角色：admin
     */
    private String role;

    /**
     * 状态：active=启用 / disabled=停用
     */
    private String status;
}
