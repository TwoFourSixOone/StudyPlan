-- =============================================
-- 智学伴（StudyMate）数据库初始化脚本
-- Change: 2026-10-30_backend_foundation
-- 注意：此文件位于受保护目录，需人工审批后执行
-- =============================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =============================================
-- 1. 基础数据表（规则 #8/#10）
-- =============================================

-- 知识点表
CREATE TABLE IF NOT EXISTS `knowledge_point` (
    `knowledge_point_id` VARCHAR(64) NOT NULL COMMENT '知识点唯一 ID（不可复用）',
    `subject` VARCHAR(50) NOT NULL COMMENT '科目（冗余字段，以 subject.name 为准）',
    `name` VARCHAR(200) NOT NULL COMMENT '知识点名称',
    `level` TINYINT NOT NULL COMMENT '层级：1=章节, 2=知识点, 3=子知识点',
    `parent_id` VARCHAR(64) DEFAULT NULL COMMENT '父节点 ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending_review' COMMENT '状态：pending_review / active / disabled',
    `standards` JSON DEFAULT NULL COMMENT '关联的课程标准 ID 数组（冗余存储，供 Agent 读取）',
    `mastery_criteria` JSON DEFAULT NULL COMMENT '掌握度评判标准',
    `diagnostic_question` JSON DEFAULT NULL COMMENT '诊断题目信息',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`knowledge_point_id`),
    INDEX `idx_subject_status` (`tenant_id`, `subject`, `status`),
    INDEX `idx_parent_id` (`tenant_id`, `parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识点基础数据表';

-- 题库表
CREATE TABLE IF NOT EXISTS `question_bank` (
    `question_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '题目 ID',
    `knowledge_point_id` VARCHAR(64) NOT NULL COMMENT '关联知识点',
    `content` TEXT NOT NULL COMMENT '题干内容',
    `choices` JSON DEFAULT NULL COMMENT '选项（选择题用）',
    `answer` VARCHAR(500) NOT NULL COMMENT '答案',
    `analysis` TEXT DEFAULT NULL COMMENT '解析',
    `difficulty` VARCHAR(20) NOT NULL COMMENT '难度：basic / standard / challenge',
    `type` VARCHAR(20) NOT NULL COMMENT '题型：numeric / choice',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`question_id`),
    INDEX `idx_knowledge_point` (`tenant_id`, `knowledge_point_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='题库表';

-- 知识点先修关系表
CREATE TABLE IF NOT EXISTS `knowledge_point_dependency` (
    `dependency_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '关系 ID',
    `knowledge_point_id` VARCHAR(64) NOT NULL COMMENT '知识点 ID',
    `prerequisite_id` VARCHAR(64) NOT NULL COMMENT '先修知识点 ID',
    `dependency_type` VARCHAR(20) NOT NULL COMMENT '关系类型：hard / soft',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`dependency_id`),
    INDEX `idx_knowledge_point` (`tenant_id`, `knowledge_point_id`),
    INDEX `idx_prerequisite` (`tenant_id`, `prerequisite_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识点先修关系表';

-- 课程标准表
CREATE TABLE IF NOT EXISTS `curriculum_standard` (
    `standard_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '标准 ID',
    `subject` VARCHAR(50) NOT NULL COMMENT '科目',
    `stage` VARCHAR(50) NOT NULL COMMENT '学段：初中 / 高中',
    `code` VARCHAR(100) NOT NULL COMMENT '标准编码',
    `description` TEXT DEFAULT NULL COMMENT '标准描述',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`standard_id`),
    UNIQUE INDEX `uk_subject_code` (`subject`, `code`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='课程标准表';

-- 知识点-课程标准关联表
CREATE TABLE IF NOT EXISTS `knowledge_point_standard` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '关联 ID',
    `knowledge_point_id` VARCHAR(64) NOT NULL COMMENT '知识点 ID',
    `standard_id` BIGINT NOT NULL COMMENT '课程标准 ID',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_knowledge_point` (`tenant_id`, `knowledge_point_id`),
    INDEX `idx_standard` (`tenant_id`, `standard_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识点-课程标准关联表';

-- =============================================
-- 2. 基础配置表
-- =============================================

-- 科目表
CREATE TABLE IF NOT EXISTS `subject` (
    `subject_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '科目 ID',
    `name` VARCHAR(50) NOT NULL COMMENT '科目名称',
    `stage` VARCHAR(50) NOT NULL COMMENT '学段：初中 / 高中',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '科目描述',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`subject_id`),
    UNIQUE INDEX `uk_name_stage` (`name`, `stage`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='科目表';

-- =============================================
-- 3. 学生与目标表
-- =============================================

-- 学生表
CREATE TABLE IF NOT EXISTS `student` (
    `student_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '学生 ID',
    `openid` VARCHAR(100) NOT NULL COMMENT '微信 openid',
    `nickname` VARCHAR(100) DEFAULT NULL COMMENT '昵称',
    `avatar` VARCHAR(500) DEFAULT NULL COMMENT '头像 URL',
    `grade` VARCHAR(20) DEFAULT NULL COMMENT '年级',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`student_id`),
    UNIQUE INDEX `uk_openid` (`openid`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学生表';

-- 学习目标表
CREATE TABLE IF NOT EXISTS `learning_goal` (
    `goal_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '目标 ID',
    `student_id` BIGINT NOT NULL COMMENT '学生 ID',
    `subject_id` BIGINT NOT NULL COMMENT '科目 ID',
    `target_score` INT DEFAULT NULL COMMENT '目标分数',
    `direction` VARCHAR(500) DEFAULT NULL COMMENT '学习方向描述',
    `start_date` DATE NOT NULL COMMENT '开始日期',
    `end_date` DATE NOT NULL COMMENT '结束日期',
    `status` VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active / completed / expired',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`goal_id`),
    INDEX `idx_student` (`tenant_id`, `student_id`),
    INDEX `idx_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学习目标表';

-- =============================================
-- 4. 计划与作业表（规则 #3/#5）
-- =============================================

-- 每日计划表
CREATE TABLE IF NOT EXISTS `daily_plan` (
    `plan_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '计划 ID',
    `goal_id` BIGINT NOT NULL COMMENT '目标 ID',
    `plan_date` DATE NOT NULL COMMENT '计划日期',
    `content` JSON DEFAULT NULL COMMENT '计划内容（仅存元信息，题目明细以 question_bank/generated_question 为准）',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态：pending / pending_generation（出题 Agent 降级） / in_progress / completed / cancelled',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`plan_id`),
    INDEX `idx_goal_date` (`tenant_id`, `goal_id`, `plan_date`),
    INDEX `idx_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='每日计划表';

-- Agent 生成新题表
CREATE TABLE IF NOT EXISTS `generated_question` (
    `generated_question_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '新题 ID',
    `daily_plan_id` BIGINT NOT NULL COMMENT '所属每日计划 ID',
    `knowledge_point_id` VARCHAR(64) NOT NULL COMMENT '关联知识点',
    `source_question_id` BIGINT DEFAULT NULL COMMENT '触发生成的原错题 ID（可为空）',
    `content` JSON NOT NULL COMMENT '题目内容',
    `difficulty` VARCHAR(20) NOT NULL COMMENT 'Agent 声明难度：basic / standard / challenge',
    `similarity_checked` TINYINT NOT NULL DEFAULT 0 COMMENT '是否通过相似度校验：0=未通过, 1=已通过',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`generated_question_id`),
    INDEX `idx_daily_plan` (`tenant_id`, `daily_plan_id`),
    INDEX `idx_knowledge_point` (`tenant_id`, `knowledge_point_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent 生成新题表';

-- 计划调整日志表（只读不删）
-- 注意：该表最终结构（含 diff_id / action / action_at）由 V7__plan_adjustment.sql 创建。
-- V1 不再重复建表，否则全新库按 V1→V13 顺序执行时 V7 会因「table already exists」失败。
-- 对应实体：com.jiang.model.entity.PlanAdjustmentLog

-- =============================================
-- 5. 学习记录表（规则 #9）— 只读不删
-- =============================================

-- 作业提交明细表
CREATE TABLE IF NOT EXISTS `homework_submission_detail` (
    `submission_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '提交 ID',
    `plan_id` BIGINT NOT NULL COMMENT '所属计划 ID',
    `question_id` BIGINT NOT NULL COMMENT '题目 ID（根据 question_source 关联）',
    `question_source` VARCHAR(20) NOT NULL COMMENT '题目来源：bank / generated',
    `question_type` VARCHAR(20) NOT NULL COMMENT '任务类型：review / redo / similar / comprehensive',
    `student_answer` TEXT DEFAULT NULL COMMENT '学生答案',
    `is_correct` TINYINT NOT NULL DEFAULT 0 COMMENT '是否正确：0=错误, 1=正确',
    `knowledge_point_id` VARCHAR(64) NOT NULL COMMENT '关联知识点',
    `submitted_at` DATETIME NOT NULL COMMENT '提交时间',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`submission_id`),
    INDEX `idx_plan` (`tenant_id`, `plan_id`),
    INDEX `idx_knowledge_point` (`tenant_id`, `knowledge_point_id`),
    INDEX `idx_submitted_at` (`tenant_id`, `submitted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='作业提交明细表（只读不删）';

-- 专项评测结果表
CREATE TABLE IF NOT EXISTS `assessment_result` (
    `assessment_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '评测 ID',
    `student_id` BIGINT NOT NULL COMMENT '学生 ID',
    `knowledge_point_ids` JSON DEFAULT NULL COMMENT '涉及的知识点 ID 列表',
    `score` DECIMAL(5,2) DEFAULT NULL COMMENT '得分（百分制，0-100，NULL 表示未完成评测）',
    `total_score` DECIMAL(5,2) DEFAULT NULL COMMENT '总分（百分制，NULL 表示未完成评测）',
    `results` JSON DEFAULT NULL COMMENT '详细结果（含各知识点正确率）',
    `assessed_at` DATETIME NOT NULL COMMENT '评测时间',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`assessment_id`),
    INDEX `idx_student` (`tenant_id`, `student_id`),
    INDEX `idx_assessed_at` (`tenant_id`, `assessed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='专项评测结果表（只读不删）';

-- 试卷分析结果表
CREATE TABLE IF NOT EXISTS `exam_analysis_result` (
    `analysis_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分析 ID',
    `student_id` BIGINT NOT NULL COMMENT '学生 ID',
    `subject_id` BIGINT NOT NULL COMMENT '科目 ID',
    `exam_name` VARCHAR(200) DEFAULT NULL COMMENT '考试名称',
    `image_urls` JSON DEFAULT NULL COMMENT '试卷图片 URL 列表',
    `questions` JSON DEFAULT NULL COMMENT '题目分析结果',
    `total_score` DECIMAL(5,2) DEFAULT NULL COMMENT '总分',
    `user_score` DECIMAL(5,2) DEFAULT NULL COMMENT '用户得分',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态：pending / processing / completed / failed',
    `analyzed_at` DATETIME DEFAULT NULL COMMENT '分析完成时间',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`analysis_id`),
    INDEX `idx_student` (`tenant_id`, `student_id`),
    INDEX `idx_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='试卷分析结果表（只读不删）';

-- =============================================
-- 6. 掌握度状态表（规则 #2）
-- =============================================

-- 学生知识点掌握度状态表
CREATE TABLE IF NOT EXISTS `student_knowledge_state` (
    `state_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '状态 ID',
    `student_id` BIGINT NOT NULL COMMENT '学生 ID',
    `knowledge_point_id` VARCHAR(64) NOT NULL COMMENT '知识点 ID',
    `mastery_score` DECIMAL(3,2) DEFAULT NULL COMMENT '掌握度（0.00-1.00，NULL 表示未评测）',
    `confidence` DECIMAL(3,2) NOT NULL DEFAULT 0.10 COMMENT '置信度（0.00-1.00，初始值 0.10，产品规则 #2）',
    `source` VARCHAR(50) DEFAULT NULL COMMENT '最近来源：homework / assessment / exam',
    `last_assessed_at` DATETIME DEFAULT NULL COMMENT '最近评测时间',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`state_id`),
    UNIQUE INDEX `uk_student_kp` (`student_id`, `knowledge_point_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学生知识点掌握度状态表';

-- 掌握度变更日志表（只读不删）
CREATE TABLE IF NOT EXISTS `mastery_change_log` (
    `log_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志 ID',
    `student_id` BIGINT NOT NULL COMMENT '学生 ID',
    `knowledge_point_id` VARCHAR(64) NOT NULL COMMENT '知识点 ID',
    `change_type` VARCHAR(20) NOT NULL COMMENT '变更类型：homework / assessment / exam',
    `source_id` BIGINT NOT NULL COMMENT '来源 ID（homework→submission_id, assessment→assessment_id, exam→analysis_id）',
    `delta` DECIMAL(5,4) NOT NULL COMMENT '变动量',
    `mastery_before` DECIMAL(3,2) NOT NULL COMMENT '变更前掌握度',
    `mastery_after` DECIMAL(3,2) NOT NULL COMMENT '变更后掌握度',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`log_id`),
    INDEX `idx_student_kp` (`tenant_id`, `student_id`, `knowledge_point_id`),
    INDEX `idx_source` (`tenant_id`, `change_type`, `source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='掌握度变更日志表（只读不删）';

-- =============================================
-- 7. 系统日志表（规则 #6/#8）— 只读不删
-- =============================================

-- Agent 降级日志表
CREATE TABLE IF NOT EXISTS `agent_degradation_log` (
    `log_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志 ID',
    `agent_type` VARCHAR(50) NOT NULL COMMENT 'Agent 类型：planning / question_gen / attribution / adjustment',
    `input_summary` TEXT DEFAULT NULL COMMENT '输入摘要',
    `failure_reason` TEXT DEFAULT NULL COMMENT '失败原因',
    `degradation_action` TEXT DEFAULT NULL COMMENT '降级处理动作',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`log_id`),
    INDEX `idx_agent_type` (`tenant_id`, `agent_type`),
    INDEX `idx_created_at` (`tenant_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent 降级日志表（只读不删）';

-- 校对超期提醒表
CREATE TABLE IF NOT EXISTS `pending_review_alert` (
    `alert_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '提醒 ID',
    `knowledge_point_id` VARCHAR(64) NOT NULL COMMENT '知识点 ID',
    `alert_type` VARCHAR(50) NOT NULL COMMENT '提醒类型：overdue / manual',
    `message` VARCHAR(1000) DEFAULT NULL COMMENT '提醒消息',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`alert_id`),
    INDEX `idx_knowledge_point` (`tenant_id`, `knowledge_point_id`),
    INDEX `idx_created_at` (`tenant_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='校对超期提醒表（只读不删）';

-- 数据导入日志表
CREATE TABLE IF NOT EXISTS `data_import_log` (
    `import_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '导入 ID',
    `data_type` VARCHAR(50) NOT NULL COMMENT '数据类型：knowledge_point / question / dependency / standard',
    `file_source` VARCHAR(500) DEFAULT NULL COMMENT '文件来源',
    `success_count` INT NOT NULL DEFAULT 0 COMMENT '成功数',
    `fail_count` INT NOT NULL DEFAULT 0 COMMENT '失败数',
    `skip_count` INT NOT NULL DEFAULT 0 COMMENT '跳过数',
    `imported_at` DATETIME NOT NULL COMMENT '导入时间',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`import_id`),
    INDEX `idx_data_type` (`tenant_id`, `data_type`),
    INDEX `idx_imported_at` (`tenant_id`, `imported_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='数据导入日志表';

-- 数据导入错误记录表
CREATE TABLE IF NOT EXISTS `data_import_error_log` (
    `error_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '错误 ID',
    `import_id` BIGINT NOT NULL COMMENT '所属导入批次 ID',
    `record_id` VARCHAR(100) DEFAULT NULL COMMENT '记录 ID',
    `error_reason` TEXT DEFAULT NULL COMMENT '错误原因',
    `tenant_id` VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`error_id`),
    INDEX `idx_import` (`tenant_id`, `import_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='数据导入错误记录表';

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================
-- 回滚方案（Rollback）— 对应 database.md §4「脚本必须包含回滚方案」
-- 说明：本脚本为 V1 初始化，回滚即按创建逆序删除全部新建表。
-- 下方 DROP 语句默认以注释形式保留，避免误执行；需回滚时手动取消注释。
-- ⚠ DROP TABLE 为高风险操作（database.md §6），必须人工审批后在 Database 面板执行，执行前确认无需保留数据。
-- =============================================
-- SET FOREIGN_KEY_CHECKS = 0;
-- DROP TABLE IF EXISTS `data_import_error_log`;
-- DROP TABLE IF EXISTS `data_import_log`;
-- DROP TABLE IF EXISTS `pending_review_alert`;
-- DROP TABLE IF EXISTS `agent_degradation_log`;
-- DROP TABLE IF EXISTS `mastery_change_log`;
-- DROP TABLE IF EXISTS `student_knowledge_state`;
-- DROP TABLE IF EXISTS `exam_analysis_result`;
-- DROP TABLE IF EXISTS `assessment_result`;
-- DROP TABLE IF EXISTS `homework_submission_detail`;
-- DROP TABLE IF EXISTS `generated_question`;
-- DROP TABLE IF EXISTS `daily_plan`;
-- DROP TABLE IF EXISTS `learning_goal`;
-- DROP TABLE IF EXISTS `student`;
-- DROP TABLE IF EXISTS `subject`;
-- DROP TABLE IF EXISTS `knowledge_point_standard`;
-- DROP TABLE IF EXISTS `curriculum_standard`;
-- DROP TABLE IF EXISTS `knowledge_point_dependency`;
-- DROP TABLE IF EXISTS `question_bank`;
-- DROP TABLE IF EXISTS `knowledge_point`;
-- SET FOREIGN_KEY_CHECKS = 1;
