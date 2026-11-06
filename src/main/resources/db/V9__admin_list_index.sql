-- V9: 管理后端列表查询索引优化
-- 创建日期：2026-11-04
-- 说明：为管理后端列表查询添加复合索引
--
-- 修复 BLOCK-3：V9 仅依赖 V1~V5 既有表（question_bank、knowledge_point_dependency、
-- curriculum_standard、data_import_log、data_import_error_log），不依赖 V6-V8。
-- 若并行开发，V9 可独立 apply。

-- 1. 题目表索引（修复 IMP-1：列名为 type 而非 question_type）
CREATE INDEX idx_question_kp_difficulty ON question_bank(knowledge_point_id, difficulty, tenant_id);
CREATE INDEX idx_question_type ON question_bank(type, tenant_id);

-- 2. 先修关系表索引
CREATE INDEX idx_dependency_kp ON knowledge_point_dependency(knowledge_point_id, tenant_id);
CREATE INDEX idx_dependency_prereq ON knowledge_point_dependency(prerequisite_id, tenant_id);

-- 3. 课程标准表索引（修复 IMP-5：列名为 subject 而非 subject_id）
CREATE INDEX idx_standard_stage ON curriculum_standard(stage, tenant_id);
CREATE INDEX idx_standard_subject ON curriculum_standard(subject, tenant_id);

-- 4. 导入日志表索引
CREATE INDEX idx_import_log_type ON data_import_log(data_type, tenant_id);
CREATE INDEX idx_import_log_time ON data_import_log(imported_at, tenant_id);

-- 5. 导入错误记录表索引
CREATE INDEX idx_import_error_log ON data_import_error_log(import_id, tenant_id);

ALTER TABLE `student_knowledge_state`
    ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号'
        AFTER `last_assessed_at`;
