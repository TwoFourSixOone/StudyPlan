-- =============================================
-- 智学伴（StudyMate）数据库 V3 变更脚本
-- Change: 2026-11-02_error_question_management
-- 注意：此文件位于受保护目录，需人工审批后执行
-- 依赖：V2__homework_uk_index.sql 已 apply
-- =============================================

-- 1. 创建错题队列表（艾宾浩斯周期调度）
CREATE TABLE `error_question` (
    `error_question_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '错题 ID',
    `student_id` BIGINT NOT NULL COMMENT '学生 ID',
    `question_id` BIGINT NOT NULL COMMENT '关联 question_bank.question_id',
    `knowledge_point_id` VARCHAR(64) NOT NULL COMMENT '关联知识点 ID',
    `source_type` VARCHAR(20) NOT NULL COMMENT '来源类型：homework/assessment/exam',
    `source_id` BIGINT DEFAULT NULL COMMENT '来源 ID（语义由 source_type 决定）',
    `first_wrong_date` DATE NOT NULL COMMENT '首次答错日期（艾宾浩斯起算点，BLOCK-F: 只在 INSERT 时写入）',
    `last_wrong_date` DATE NOT NULL COMMENT '最近一次答错日期（周期重置基准）',
    `current_stage` INT NOT NULL DEFAULT 1 COMMENT '当前阶段（1-5）',
    `next_due_date` DATE NOT NULL COMMENT '下次到期日期',
    `status` VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active/paused/completed',
    `completed_date` DATE DEFAULT NULL COMMENT '完成日期（stage > 5 时记录）',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`error_question_id`),
    -- BLOCK-4 修复：唯一索引防止并发重复入队
    UNIQUE INDEX `uk_student_question` (`student_id`, `question_id`, `tenant_id`),
    -- BLOCK-H 修复：支撑 selectDueKnowledgePointIds + selectDueQuestionsByKnowledgePoint
    INDEX `idx_student_status_due` (`student_id`, `tenant_id`, `status`, `next_due_date`),
    -- 支撑 batchPause/batchReactivate + selectPageWithDetail WHERE 筛选
    INDEX `idx_student_kp_status` (`student_id`, `tenant_id`, `knowledge_point_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='错题队列表（艾宾浩斯周期调度）';

-- IMP-U 说明：selectPageWithDetail 的 ORDER BY first_wrong_date 无法被索引覆盖，会触发 filesort
-- 预估单学生错题上限 2000 条，filesort < 20ms 可接受
-- T-01 EXPLAIN 验证后若 filesort 行数 > 5000 则补索引：
--   idx_student_first_wrong (student_id, tenant_id, first_wrong_date)
--   补索引后需验证 5 种查询组合：① student_id+tenant_id；② +knowledgePointId；③ +status；④ +两者；⑤ 深分页 offset=1500

-- =============================================
-- 回滚方案（仅开发环境可执行，生产需数据迁移）
-- =============================================
-- DROP TABLE IF EXISTS `error_question`;
