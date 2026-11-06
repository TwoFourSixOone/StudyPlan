-- V7: 计划调整系统
-- 创建日期：2026-11-04
-- 说明：新增计划调整 diff 表和调整日志表
-- 注意：Flyway 保证脚本只执行一次

-- 依赖：V1~V6 已 apply（learning_goal、exam_paper 等表已存在）
-- 被依赖：V8（learning_record_index）、V9（admin_list_index）

-- ============================================
-- 1. plan_adjustment_diff（计划调整 diff 表）
-- 修复 BLOCK-B：去掉 uk_goal_diff (goal_id, id, tenant_id)
-- 原因：id 是自增主键，包含主键的唯一索引无防重意义
-- "同一 goal 同时只允许一个 pending diff"由应用层保证
-- ============================================
CREATE TABLE plan_adjustment_diff (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT 'diff ID',
    goal_id         BIGINT        NOT NULL COMMENT '学习目标 ID',
    status          VARCHAR(20)   NOT NULL DEFAULT 'pending' COMMENT '状态：pending/confirmed/rejected/expired',
    trigger_signals JSON          NOT NULL COMMENT '触发信号列表（JSON 数组）',
    diff_content    JSON          NOT NULL COMMENT '调整内容（JSON）',
    expire_at       DATETIME      NOT NULL COMMENT '过期时间',
    confirmed_at    DATETIME      DEFAULT NULL COMMENT '确认时间',
    rejected_at     DATETIME      DEFAULT NULL COMMENT '拒绝时间',
    tenant_id       VARCHAR(50)   NOT NULL DEFAULT 'default' COMMENT '租户ID',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    FOREIGN KEY (goal_id) REFERENCES learning_goal(goal_id),
    INDEX idx_goal_status (goal_id, status, tenant_id),
    INDEX idx_expire (expire_at, status, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计划调整 diff 表';

-- ============================================
-- 2. plan_adjustment_log（计划调整日志表）
-- 保留 uk_goal_diff：一个 diff 只能有一条 log 记录（diff_id 不是本表主键，有防重意义）
-- ============================================
CREATE TABLE plan_adjustment_log (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '日志 ID',
    goal_id         BIGINT        NOT NULL COMMENT '学习目标 ID',
    diff_id         BIGINT        NOT NULL COMMENT '关联 diff ID',
    trigger_signals JSON          NOT NULL COMMENT '触发信号列表（冗余）',
    diff_content    JSON          NOT NULL COMMENT '调整内容（冗余）',
    action          VARCHAR(20)   NOT NULL COMMENT '操作：confirmed/rejected/expired',
    action_at       DATETIME      NOT NULL COMMENT '操作时间',
    tenant_id       VARCHAR(50)   NOT NULL DEFAULT 'default' COMMENT '租户ID',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    FOREIGN KEY (goal_id) REFERENCES learning_goal(goal_id),
    FOREIGN KEY (diff_id) REFERENCES plan_adjustment_diff(id),
    UNIQUE KEY uk_goal_diff (goal_id, diff_id, tenant_id),
    INDEX idx_goal (goal_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计划调整日志表';

-- ============================================
-- 回滚方案（注释形式，需人工审批后执行）
-- ============================================
-- DROP TABLE IF EXISTS plan_adjustment_log;
-- DROP TABLE IF EXISTS plan_adjustment_diff;
