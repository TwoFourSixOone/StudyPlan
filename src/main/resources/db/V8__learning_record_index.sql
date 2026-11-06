-- V8: 学习记录查询索引优化
-- 创建日期：2026-11-04
-- 修复 IMP-19：仅添加 V5 未覆盖的索引，避免重复
-- 注意：Flyway 保证脚本只执行一次

-- 依赖：V1~V7 已 apply
-- 被依赖：V9（admin_list_index）

-- ============================================
-- 1. mastery_change_log 索引（V5 未覆盖）
-- ============================================

-- 按知识点+学生查询掌握度变更历史（用于学习记录详情）
CREATE INDEX idx_mastery_log_kp_student ON mastery_change_log(
    knowledge_point_id, 
    student_id, 
    create_time, 
    tenant_id
);

-- 按来源查询掌握度变更（用于关联某次作业/考试导致的掌握度变化）
CREATE INDEX idx_mastery_log_source ON mastery_change_log(
    source_id, 
    change_type, 
    tenant_id
);

-- ============================================
-- 注意：以下索引已在 V5（assessment_system Change）中添加，此处不重复
-- ============================================
-- homework_submission_detail: idx_student_date (student_id, tenant_id, submitted_at)
-- assessment_result: idx_student_date (student_id, tenant_id, assessed_at)
-- exam_analysis_result: idx_student_date (student_id, tenant_id, analyzed_at)

-- ============================================
-- 回滚方案（注释形式，需人工审批后执行）
-- ============================================
-- DROP INDEX idx_mastery_log_kp_student ON mastery_change_log;
-- DROP INDEX idx_mastery_log_source ON mastery_change_log;
