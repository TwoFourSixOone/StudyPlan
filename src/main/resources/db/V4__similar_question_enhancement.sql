-- =============================================
-- 智学伴（StudyMate）数据库 V4 增量变更脚本
-- Change: 2026-11-03_similar_question_generation
-- 注意：此文件位于受保护目录，需人工审批后执行
-- 依赖：V3_1__add_performance_indexes.sql 已 apply
-- 目的：generated_question 表新增 content_hash 字段 + 唯一索引
-- =============================================

-- 1. generated_question 表新增 content_hash 字段（SimHash，用于快速相似度比较）
-- 注意：NOT NULL DEFAULT '' 保证唯一索引对所有行生效（MySQL UNIQUE 索引中 NULL 不参与唯一性约束）
--       举一反三新题由 Service 层显式写入 SimHash 值；存量/其他来源记录自动填充空串
ALTER TABLE `generated_question`
    ADD COLUMN `content_hash` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '题干内容 SimHash（相似度快速比较）'
    AFTER `similarity_checked`;

-- 2. 唯一索引：防止同一知识点下重复写入相同内容的新题
--    复合列：knowledge_point_id + content_hash + tenant_id
--    配合 DuplicateKeyException 捕获实现幂等写入
ALTER TABLE `generated_question`
    ADD UNIQUE INDEX `uk_kp_content_hash` (`knowledge_point_id`, `content_hash`, `tenant_id`);

-- =============================================
-- 回滚方案（仅开发环境可执行，生产需人工审批）
-- =============================================
-- ALTER TABLE `generated_question` DROP INDEX `uk_kp_content_hash`;
-- ALTER TABLE `generated_question` DROP COLUMN `content_hash`;
