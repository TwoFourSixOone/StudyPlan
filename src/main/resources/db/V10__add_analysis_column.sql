-- =============================================
-- V10: homework_submission_detail 新增 analysis 列
-- 用途：存储 AI 生成的题目解析（批改时生成）
-- =============================================

ALTER TABLE `homework_submission_detail`
    ADD COLUMN `analysis` TEXT DEFAULT NULL COMMENT 'AI 生成的题目解析' AFTER `submitted_at`;
