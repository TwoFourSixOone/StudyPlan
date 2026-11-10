-- =============================================
-- 智学伴（StudyMate）数据库 V11 变更脚本
-- Change: 2026-11-05_grade_level_filtering
-- 注意：此文件位于受保护目录，需人工审批后执行
-- 依赖：V10__add_analysis_column.sql 已 apply
-- 说明：知识点与题库增加年级维度（对应产品规则 #11）
-- =============================================

-- 1. 知识点：适用年级范围（字符串，支持单值/区间/列表）
--    "3"     → 仅三年级
--    "7-9"   → 初中全段
--    "1,2,3" → 一、二、三年级
--    NULL    → 全年级通用
ALTER TABLE `knowledge_point`
    ADD COLUMN `grade_range` VARCHAR(20) DEFAULT NULL
    COMMENT '适用年级范围："3" / "7-9" / "1,2,3"；NULL=全年级通用';

-- 2. 题库：题目所属年级（单值，NULL 表示继承知识点 grade_range）
ALTER TABLE `question_bank`
    ADD COLUMN `grade` TINYINT DEFAULT NULL
    COMMENT '题目所属年级 1-12；NULL=继承知识点 grade_range';

-- 说明：不新增索引。
-- grade_range 用于 Java 层过滤知识点列表（单科目下数量有限）；
-- question_bank.grade 的过滤发生在"按 knowledge_point_id 已命中索引"之后，属内存过滤。

-- =============================================
-- 回滚方案（人工审批后执行，按创建逆序）
-- =============================================
-- ALTER TABLE `question_bank` DROP COLUMN `grade`;
-- ALTER TABLE `knowledge_point` DROP COLUMN `grade_range`;
