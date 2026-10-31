-- =============================================
-- 补丁脚本：为系统日志表补全 update_time 列
-- 原因：BaseEntity 定义了 updateTime 字段（FieldFill.INSERT_UPDATE），
--       但以下 3 个只读日志表的 DDL 缺少 update_time 列，导致 INSERT 时报
--       "Unknown column 'update_time' in 'field list'"
-- 注意：本脚本与 data_import 变更无关，需独立审批执行
-- =============================================

-- 1. mastery_change_log（掌握度变更日志表）
ALTER TABLE `mastery_change_log`
    ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER `create_time`;

-- 2. agent_degradation_log（Agent 降级日志表）
ALTER TABLE `agent_degradation_log`
    ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER `create_time`;

-- 3. pending_review_alert（校对超期提醒表）
ALTER TABLE `pending_review_alert`
    ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER `create_time`;
