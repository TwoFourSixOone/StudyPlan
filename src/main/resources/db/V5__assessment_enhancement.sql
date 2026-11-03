-- V5__assessment_enhancement.sql
-- BLOCK-3/6/7 修复：subject_id 和 status 均为业务必填字段，不设 DEFAULT。
-- 执行前确认：SELECT COUNT(*) FROM assessment_result = 0
-- 若 > 0，改用 §2.1.1 的两步回填方案（本脚本不可直接执行）。

-- 1. 新增 subject_id 列（表为空，直接 NOT NULL 无 DEFAULT）
ALTER TABLE assessment_result
    ADD COLUMN `subject_id` BIGINT NOT NULL COMMENT '科目 ID（关联 subject.subject_id）'
    AFTER `student_id`;

-- 2. 新增 status 列（BLOCK-7 修复：无 DEFAULT，由 Service 显式写入 'pending'）
ALTER TABLE assessment_result
    ADD COLUMN `status` VARCHAR(20) NOT NULL COMMENT '状态：pending / graded'
    AFTER `total_score`;

-- 3. 存量数据回填（表为空时无需执行）
-- UPDATE assessment_result SET status = 'graded' WHERE assessed_at IS NOT NULL;

-- 4. assessed_at 改为可空（批改完成时才写入）
ALTER TABLE assessment_result
    MODIFY COLUMN `assessed_at` DATETIME DEFAULT NULL COMMENT '批改完成时间';

-- 5. 新增索引
ALTER TABLE assessment_result
    ADD INDEX `idx_student_subject` (`tenant_id`, `student_id`, `subject_id`),
    ADD INDEX `idx_status` (`tenant_id`, `status`);

-- 6. 新建 assessment_submission_detail 表
CREATE TABLE IF NOT EXISTS `assessment_submission_detail` (
    `detail_id`         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '答题明细 ID',
    `assessment_id`     BIGINT        NOT NULL COMMENT '所属评测 ID',
    `student_id`        BIGINT        NOT NULL COMMENT '学生 ID',
    `question_id`       BIGINT        NOT NULL COMMENT '题目 ID（关联 question_bank）',
    `knowledge_point_id` VARCHAR(64)  NOT NULL COMMENT '关联知识点',
    `student_answer`    TEXT          DEFAULT NULL COMMENT '学生答案',
    `is_correct`        TINYINT       NOT NULL DEFAULT 0 COMMENT '是否正确：0=错误, 1=正确',
    `difficulty`        VARCHAR(20)   NOT NULL COMMENT '题目难度（冗余）：basic/standard/challenge',
    `submitted_at`      DATETIME      NOT NULL COMMENT '提交时间',
    `tenant_id`         VARCHAR(50)   NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`detail_id`),
    INDEX `idx_assessment` (`tenant_id`, `assessment_id`),
    INDEX `idx_student_kp` (`tenant_id`, `student_id`, `knowledge_point_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='评测答题明细表（只读不删）';

-- ============================================
-- 回滚方案（注释形式，需人工审批后执行）
-- ============================================
-- 回滚前确认：assessment_result 中 status='pending' 且 assessed_at IS NULL 的记录需先删除
-- DELETE FROM assessment_result WHERE status = 'pending' AND assessed_at IS NULL;
-- DROP TABLE IF EXISTS assessment_submission_detail;
-- ALTER TABLE assessment_result DROP INDEX idx_student_subject;
-- ALTER TABLE assessment_result DROP INDEX idx_status;
-- ALTER TABLE assessment_result MODIFY COLUMN assessed_at DATETIME NOT NULL;
-- ALTER TABLE assessment_result DROP COLUMN status;
-- ALTER TABLE assessment_result DROP COLUMN subject_id;
