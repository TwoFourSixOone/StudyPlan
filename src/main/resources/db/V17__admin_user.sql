-- V17：管理员账号落库（2026-11-19，上线加固：admin 账号入库）
-- 背景：AdminAuthServiceImpl 此前把管理员用户名/密码硬编码在源码里，且复用学生 JWT 结构；
--       上线前必须做到「账号入库 + 密码只存哈希 + 首账号由环境变量初始化」。
-- 密码：仅存 PBKDF2WithHmacSHA256 哈希，格式 pbkdf2$<iterations>$<saltBase64>$<hashBase64>；
--       源码与本迁移中**不写任何明文密码或固定哈希**，首个账号由
--       ADMIN_BOOTSTRAP_USERNAME（默认 admin）/ ADMIN_BOOTSTRAP_PASSWORD 环境变量在启动时创建。
-- 说明：username 在租户内唯一（多租户下同一登录名可在不同租户各存在一份）。
CREATE TABLE `admin_user` (
    `admin_id`      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '管理员 ID',
    `username`      VARCHAR(64)  NOT NULL COMMENT '登录名（租户内唯一）',
    `password_hash` VARCHAR(255) NOT NULL COMMENT 'PBKDF2 密码哈希：pbkdf2$迭代次数$盐Base64$哈希Base64',
    `role`          VARCHAR(32)  NOT NULL DEFAULT 'admin' COMMENT '角色：admin',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态：active=启用 / disabled=停用',
    `tenant_id`     VARCHAR(50)  NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`admin_id`),
    UNIQUE KEY `uk_username` (`username`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='管理员账号表';

-- ============================================
-- 回滚方案（注释形式，需人工审批后执行）
-- ============================================
-- DROP TABLE IF EXISTS `admin_user`;
