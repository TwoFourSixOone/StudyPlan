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
    PRIMARY KEY (`question_id`)
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
