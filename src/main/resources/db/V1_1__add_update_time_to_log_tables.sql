-- =============================================
-- 补丁脚本：为数据导入相关日志表补全 update_time 列
-- Change: 2026-10-31_data_import
-- 原因：BaseEntity 定义了 updateTime 字段（FieldFill.INSERT_UPDATE），
--       但以下 2 个表的 DDL 缺少 update_time 列，导致 INSERT 时报
--       "Unknown column 'update_time' in 'field list'"
-- =============================================

-- 1. data_import_log（数据导入日志表）
ALTER TABLE `data_import_log`
    ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER `create_time`;

-- 2. data_import_error_log（数据导入错误记录表）
ALTER TABLE `data_import_error_log`
    ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER `create_time`;
