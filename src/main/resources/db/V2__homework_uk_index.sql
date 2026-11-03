-- =============================================
-- 智学伴（StudyMate）数据库 V2 变更脚本
-- Change: 2026-11-02_homework_core_flow
-- 注意：此文件位于受保护目录，需人工审批后执行
-- =============================================

-- 1. 为 homework_submission_detail 新增 student_id 列（用于归属校验）
ALTER TABLE `homework_submission_detail`
  ADD COLUMN `student_id` BIGINT DEFAULT NULL COMMENT '学生 ID（V2 补充，用于归属校验）'
  AFTER `submission_id`;

-- 2. 为 homework_submission_detail 新增唯一索引（支持 ON DUPLICATE KEY UPDATE 覆盖语义）
ALTER TABLE `homework_submission_detail`
  ADD UNIQUE INDEX `uk_plan_question` (`plan_id`, `question_id`, `tenant_id`);

-- 3. 为 student_id 添加索引（加速归属查询）
ALTER TABLE `homework_submission_detail`
  ADD INDEX `idx_student` (`tenant_id`, `student_id`);

-- 4. 为 daily_plan 新增唯一索引（防止并发重复插入同一 goal_id + plan_date）
ALTER TABLE `daily_plan`
  ADD UNIQUE INDEX `uk_goal_date` (`goal_id`, `plan_date`, `tenant_id`);

-- =============================================
-- 回滚方案
-- =============================================
-- ALTER TABLE `daily_plan` DROP INDEX `uk_goal_date`;
-- ALTER TABLE `homework_submission_detail` DROP INDEX `idx_student`;
-- ALTER TABLE `homework_submission_detail` DROP INDEX `uk_plan_question`;
-- ALTER TABLE `homework_submission_detail` DROP COLUMN `student_id`;
