-- =============================================
-- 智学伴（StudyMate）数据库 V3_1 增量索引脚本
-- Change: 2026-11-02_error_question_management
-- 注意：此文件位于受保护目录，需人工审批后执行
-- 依赖：V3__error_question_table.sql 已 apply
-- 目的：补充 SQL 审查发现的 2 个性能索引缺口
-- =============================================

-- 1. mastery_change_log: 补充覆盖索引，优化 selectLatestByKpIds 自连接子查询
--    子查询: SELECT knowledge_point_id, MAX(log_id) FROM mastery_change_log
--            WHERE student_id=? AND tenant_id=? AND knowledge_point_id IN (...) GROUP BY knowledge_point_id
--    索引覆盖: WHERE 三列(student_id, tenant_id, knowledge_point_id) + 聚合列(log_id)
--    效果: 子查询从"索引过滤 + 回表取 log_id"优化为"索引内完成 MAX 聚合"
ALTER TABLE `mastery_change_log`
  ADD INDEX `idx_student_kp_log` (`student_id`, `tenant_id`, `knowledge_point_id`, `log_id`);

-- 2. question_bank: 补充覆盖索引，优化 selectAvgDifficultyByKp 聚合查询
--    查询: SELECT AVG(CASE difficulty ...) FROM question_bank
--          WHERE knowledge_point_id=? AND tenant_id=?
--    索引覆盖: WHERE 两列(knowledge_point_id, tenant_id) + 聚合列(difficulty)
--    效果: 查询从"索引过滤 + 回表取 difficulty"优化为"索引内完成 AVG 聚合"
ALTER TABLE `question_bank`
  ADD INDEX `idx_kp_difficulty` (`knowledge_point_id`, `tenant_id`, `difficulty`);

-- =============================================
-- 回滚方案（仅开发环境可执行，生产需人工审批）
-- =============================================
-- ALTER TABLE `question_bank` DROP INDEX `idx_kp_difficulty`;
-- ALTER TABLE `mastery_change_log` DROP INDEX `idx_student_kp_log`;
