-- V6: 试卷上传与异步解析系统
-- 创建日期：2026-11-04
-- 说明：新增试卷主表、题目表、解析任务表
-- 注意：Flyway 保证脚本只执行一次

-- 依赖：V1~V5 已 apply（knowledge_point、subject 等表已存在）
-- 被依赖：V7（plan_adjustment）、V8（learning_record_index）、V9（admin_list_index）

-- ============================================
-- 1. exam_paper（试卷主表）
-- ============================================
CREATE TABLE exam_paper (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '试卷ID',
    student_id      BIGINT        NOT NULL COMMENT '学生ID',
    subject_id      BIGINT        NOT NULL COMMENT '科目ID',
    exam_date       DATE          NOT NULL COMMENT '考试日期',
    total_score     DECIMAL(5,2)  DEFAULT NULL COMMENT '总分（学生录入后回填）',
    paper_type      VARCHAR(20)   NOT NULL DEFAULT 'midterm' COMMENT '试卷类型：midterm/final/other',
    image_urls      JSON          NOT NULL COMMENT '试卷图片URL列表（JSON数组）',
    status          VARCHAR(20)   NOT NULL DEFAULT 'pending' COMMENT '状态：pending/processing/completed/failed',
    grade_status    VARCHAR(20)   NOT NULL DEFAULT 'ungraded' COMMENT '批改状态：ungraded/graded',
    remark          VARCHAR(500)  DEFAULT NULL COMMENT '备注',
    tenant_id       VARCHAR(50)   NOT NULL DEFAULT 'default' COMMENT '租户ID',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    -- 修复 BLOCK-4：唯一索引改为 (student_id, subject_id, exam_date, tenant_id)
    -- 语义：同一学生同一科目同一天只能传一份试卷（防重复上传）
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_exam (student_id, subject_id, exam_date, tenant_id),
    INDEX idx_student_status (student_id, status, tenant_id),
    INDEX idx_subject (subject_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='试卷主表';

-- ============================================
-- 2. exam_question（试卷题目表）
-- ============================================
CREATE TABLE exam_question (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '题目ID',
    exam_paper_id       BIGINT        NOT NULL COMMENT '试卷ID',
    question_index      INT           NOT NULL COMMENT '题目序号',
    question_content    TEXT          NOT NULL COMMENT '题目内容（OCR识别结果）',
    question_image_url  VARCHAR(500)  DEFAULT NULL COMMENT '题目图片URL（可选）',
    -- NOTE-1：knowledge_point_id 类型为 VARCHAR(64)，与 knowledge_point.knowledge_point_id 一致
    knowledge_point_id  VARCHAR(64)   DEFAULT NULL COMMENT '归因的知识点ID（待人工归因时为NULL）',
    -- 修复 IMP-8：DDL 注释明确 pending_manual_review 时 knowledge_point_id 允许 NULL
    attribution_status  VARCHAR(30)   NOT NULL DEFAULT 'pending' COMMENT '归因状态：pending/manual_review/completed',
    student_answer      TEXT          DEFAULT NULL COMMENT '学生答案（学生手动录入）',
    is_correct          TINYINT(1)    DEFAULT NULL COMMENT '是否正确：0-错误，1-正确，NULL-未录入',
    score               DECIMAL(5,2)  DEFAULT NULL COMMENT '得分（学生手动录入）',
    full_score          DECIMAL(5,2)  DEFAULT NULL COMMENT '满分（学生手动录入）',
    question_type       VARCHAR(20)   DEFAULT NULL COMMENT '题型：choice/numeric/short',
    difficulty          VARCHAR(20)   DEFAULT NULL COMMENT '难度：basic/standard/challenge',
    tenant_id           VARCHAR(50)   NOT NULL DEFAULT 'default' COMMENT '租户ID',
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    FOREIGN KEY (exam_paper_id) REFERENCES exam_paper(id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_point_id) REFERENCES knowledge_point(knowledge_point_id),
    INDEX idx_paper_index (exam_paper_id, question_index, tenant_id),
    INDEX idx_knowledge_point (knowledge_point_id, tenant_id),
    INDEX idx_attribution_status (attribution_status, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='试卷题目表';

-- ============================================
-- 3. exam_analysis_task（解析任务表）
-- ============================================
CREATE TABLE exam_analysis_task (
    id                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    exam_paper_id         BIGINT        NOT NULL COMMENT '试卷ID',
    task_type             VARCHAR(30)   NOT NULL DEFAULT 'ocr_attribution' COMMENT '任务类型：ocr_attribution/ocr_only/attribution_only',
    status                VARCHAR(20)   NOT NULL DEFAULT 'pending' COMMENT '状态：pending/processing/completed/failed',
    ocr_retry_count       INT           NOT NULL DEFAULT 0 COMMENT 'OCR重试次数',
    attribution_retry_count INT         NOT NULL DEFAULT 0 COMMENT '归因重试次数',
    error_message         TEXT          DEFAULT NULL COMMENT '错误信息',
    started_at            DATETIME      DEFAULT NULL COMMENT '开始处理时间',
    completed_at          DATETIME      DEFAULT NULL COMMENT '完成时间',
    tenant_id             VARCHAR(50)   NOT NULL DEFAULT 'default' COMMENT '租户ID',
    create_time           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    FOREIGN KEY (exam_paper_id) REFERENCES exam_paper(id) ON DELETE CASCADE,
    INDEX idx_paper_status (exam_paper_id, status, tenant_id),
    INDEX idx_status (status, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='试卷解析任务表';

-- ============================================
-- 回滚方案（注释形式，需人工审批后执行）
-- ============================================
-- DROP TABLE IF EXISTS exam_analysis_task;
-- DROP TABLE IF EXISTS exam_question;
-- DROP TABLE IF EXISTS exam_paper;
