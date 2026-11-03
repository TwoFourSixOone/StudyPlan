-- =============================================
-- 测试环境 Schema（H2 兼容，仅创建本次测试所需表）
-- =============================================

CREATE TABLE IF NOT EXISTS `student` (
    `student_id` BIGINT NOT NULL AUTO_INCREMENT,
    `openid` VARCHAR(100) NOT NULL,
    `nickname` VARCHAR(100) DEFAULT NULL,
    `avatar` VARCHAR(500) DEFAULT NULL,
    `grade` VARCHAR(20) DEFAULT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`student_id`),
    UNIQUE INDEX `uk_openid` (`openid`, `tenant_id`)
);

-- 知识点表
CREATE TABLE IF NOT EXISTS `knowledge_point` (
    `knowledge_point_id` VARCHAR(64) NOT NULL,
    `subject` VARCHAR(50) NOT NULL,
    `name` VARCHAR(200) NOT NULL,
    `level` TINYINT NOT NULL,
    `parent_id` VARCHAR(64) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending_review',
    `standards` JSON DEFAULT NULL,
    `mastery_criteria` JSON DEFAULT NULL,
    `diagnostic_question` JSON DEFAULT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`knowledge_point_id`)
);

-- 题库表
CREATE TABLE IF NOT EXISTS `question_bank` (
    `question_id` BIGINT NOT NULL AUTO_INCREMENT,
    `knowledge_point_id` VARCHAR(64) NOT NULL,
    `content` TEXT NOT NULL,
    `choices` JSON DEFAULT NULL,
    `answer` VARCHAR(500) NOT NULL,
    `analysis` TEXT DEFAULT NULL,
    `difficulty` VARCHAR(20) NOT NULL,
    `type` VARCHAR(20) NOT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`question_id`),
    INDEX `idx_kp_difficulty` (`knowledge_point_id`, `tenant_id`, `difficulty`)
);

-- 先修关系表
CREATE TABLE IF NOT EXISTS `knowledge_point_dependency` (
    `dependency_id` BIGINT NOT NULL AUTO_INCREMENT,
    `knowledge_point_id` VARCHAR(64) NOT NULL,
    `prerequisite_id` VARCHAR(64) NOT NULL,
    `dependency_type` VARCHAR(20) NOT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`dependency_id`)
);

-- 课程标准表
CREATE TABLE IF NOT EXISTS `curriculum_standard` (
    `standard_id` BIGINT NOT NULL AUTO_INCREMENT,
    `subject` VARCHAR(50) NOT NULL,
    `stage` VARCHAR(50) NOT NULL,
    `code` VARCHAR(100) NOT NULL,
    `description` TEXT DEFAULT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`standard_id`),
    UNIQUE INDEX `uk_subject_code` (`subject`, `code`, `tenant_id`)
);

-- 导入日志表
CREATE TABLE IF NOT EXISTS `data_import_log` (
    `import_id` BIGINT NOT NULL AUTO_INCREMENT,
    `data_type` VARCHAR(50) NOT NULL,
    `file_source` VARCHAR(500) DEFAULT NULL,
    `success_count` INT NOT NULL DEFAULT 0,
    `fail_count` INT NOT NULL DEFAULT 0,
    `skip_count` INT NOT NULL DEFAULT 0,
    `imported_at` DATETIME NOT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`import_id`)
);

-- 导入错误记录表
CREATE TABLE IF NOT EXISTS `data_import_error_log` (
    `error_id` BIGINT NOT NULL AUTO_INCREMENT,
    `import_id` BIGINT NOT NULL,
    `record_id` VARCHAR(100) DEFAULT NULL,
    `error_reason` TEXT DEFAULT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`error_id`)
);

-- 科目表
CREATE TABLE IF NOT EXISTS `subject` (
    `subject_id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(50) NOT NULL,
    `stage` VARCHAR(50) NOT NULL,
    `description` VARCHAR(500) DEFAULT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`subject_id`)
);

-- 学习目标表
CREATE TABLE IF NOT EXISTS `learning_goal` (
    `goal_id` BIGINT NOT NULL AUTO_INCREMENT,
    `student_id` BIGINT NOT NULL,
    `subject_id` BIGINT NOT NULL,
    `target_score` INT DEFAULT NULL,
    `direction` VARCHAR(500) DEFAULT NULL,
    `start_date` DATE NOT NULL,
    `end_date` DATE NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'active',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`goal_id`)
);

-- 学生知识点掌握度状态表
CREATE TABLE IF NOT EXISTS `student_knowledge_state` (
    `state_id` BIGINT NOT NULL AUTO_INCREMENT,
    `student_id` BIGINT NOT NULL,
    `knowledge_point_id` VARCHAR(64) NOT NULL,
    `mastery_score` DECIMAL(5,4) NOT NULL DEFAULT 0.50,
    `confidence` DECIMAL(5,4) NOT NULL DEFAULT 0.10,
    `source` VARCHAR(20) DEFAULT NULL,
    `last_assessed_at` DATETIME DEFAULT NULL,
    `version` INT NOT NULL DEFAULT 0,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`state_id`),
    UNIQUE INDEX `uk_student_kp` (`student_id`, `knowledge_point_id`, `tenant_id`)
);

-- 掌握度变更日志表
CREATE TABLE IF NOT EXISTS `mastery_change_log` (
    `log_id` BIGINT NOT NULL AUTO_INCREMENT,
    `student_id` BIGINT NOT NULL,
    `knowledge_point_id` VARCHAR(64) NOT NULL,
    `change_type` VARCHAR(20) NOT NULL,
    `source_id` BIGINT DEFAULT NULL,
    `delta` DECIMAL(5,4) DEFAULT NULL,
    `mastery_before` DECIMAL(5,4) DEFAULT NULL,
    `mastery_after` DECIMAL(5,4) DEFAULT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`log_id`),
    INDEX `idx_student_kp` (`tenant_id`, `student_id`, `knowledge_point_id`),
    INDEX `idx_student_kp_log` (`student_id`, `tenant_id`, `knowledge_point_id`, `log_id`)
);

-- 作业提交明细表
CREATE TABLE IF NOT EXISTS `homework_submission_detail` (
    `submission_id` BIGINT NOT NULL AUTO_INCREMENT,
    `student_id` BIGINT NOT NULL,
    `plan_id` BIGINT NOT NULL,
    `question_id` BIGINT NOT NULL,
    `question_source` VARCHAR(20) NOT NULL,
    `question_type` VARCHAR(20) DEFAULT NULL,
    `student_answer` TEXT DEFAULT NULL,
    `is_correct` TINYINT DEFAULT NULL,
    `knowledge_point_id` VARCHAR(64) DEFAULT NULL,
    `submitted_at` DATETIME DEFAULT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`submission_id`),
    UNIQUE INDEX `uk_plan_question` (`plan_id`, `question_id`, `tenant_id`)
);

-- 每日计划表
CREATE TABLE IF NOT EXISTS `daily_plan` (
    `plan_id` BIGINT NOT NULL AUTO_INCREMENT,
    `goal_id` BIGINT NOT NULL,
    `plan_date` DATE NOT NULL,
    `content` JSON DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`plan_id`),
    UNIQUE INDEX `uk_goal_date` (`goal_id`, `plan_date`, `tenant_id`)
);

-- Agent 降级日志表
CREATE TABLE IF NOT EXISTS `agent_degradation_log` (
    `log_id` BIGINT NOT NULL AUTO_INCREMENT,
    `agent_type` VARCHAR(50) NOT NULL,
    `input_summary` TEXT DEFAULT NULL,
    `failure_reason` TEXT DEFAULT NULL,
    `degradation_action` TEXT DEFAULT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`log_id`)
);

-- Agent 生成新题表
CREATE TABLE IF NOT EXISTS `generated_question` (
    `generated_question_id` BIGINT NOT NULL AUTO_INCREMENT,
    `daily_plan_id` BIGINT DEFAULT NULL,
    `knowledge_point_id` VARCHAR(64) DEFAULT NULL,
    `source_question_id` BIGINT DEFAULT NULL,
    `content` TEXT DEFAULT NULL,
    `difficulty` VARCHAR(20) DEFAULT NULL,
    `similarity_checked` TINYINT DEFAULT 0,
    `content_hash` VARCHAR(64) NOT NULL DEFAULT '',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`generated_question_id`),
    UNIQUE INDEX `uk_kp_content_hash` (`knowledge_point_id`, `content_hash`, `tenant_id`)
);

-- 错题队列表（艾宾浩斯周期调度）
CREATE TABLE IF NOT EXISTS `error_question` (
    `error_question_id` BIGINT NOT NULL AUTO_INCREMENT,
    `student_id` BIGINT NOT NULL,
    `question_id` BIGINT NOT NULL,
    `knowledge_point_id` VARCHAR(64) NOT NULL,
    `source_type` VARCHAR(20) NOT NULL,
    `source_id` BIGINT DEFAULT NULL,
    `first_wrong_date` DATE NOT NULL,
    `last_wrong_date` DATE NOT NULL,
    `current_stage` INT NOT NULL DEFAULT 1,
    `next_due_date` DATE NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'active',
    `completed_date` DATE DEFAULT NULL,
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`error_question_id`),
    UNIQUE INDEX `uk_student_question` (`student_id`, `question_id`, `tenant_id`),
    INDEX `idx_student_status_due` (`student_id`, `tenant_id`, `status`, `next_due_date`),
    INDEX `idx_student_kp_status` (`student_id`, `tenant_id`, `knowledge_point_id`, `status`)
);
