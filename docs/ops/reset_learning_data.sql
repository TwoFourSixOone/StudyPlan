-- ============================================================
-- 重置学习数据：删除全部学习目标 / 每日计划 / 作业 / 相关痕迹
--
-- 用途：重新走一遍「设定目标 → 规划 → 每日作业」闭环
-- 保留：student、subject、knowledge_point、question_bank、
--       knowledge_point_dependency、curriculum_standard、导入日志
-- 不影响：学生的 grade（年级设置）、知识点的 status（启用状态）
--
-- 执行顺序：子表 → 父表（plan_adjustment_log 有指向 learning_goal 的外键）
-- 租户：默认 default，如多租户请逐个替换
-- ============================================================

-- ① 先看现有数据量（执行后可选再跑一次对比）
SELECT 'learning_goal' AS tbl, COUNT(*) AS cnt FROM learning_goal   WHERE tenant_id = 'default'
UNION ALL SELECT 'daily_plan',              COUNT(*) FROM daily_plan              WHERE tenant_id = 'default'
UNION ALL SELECT 'homework_submission_detail', COUNT(*) FROM homework_submission_detail WHERE tenant_id = 'default'
UNION ALL SELECT 'generated_question',      COUNT(*) FROM generated_question      WHERE tenant_id = 'default'
UNION ALL SELECT 'plan_adjustment_diff',    COUNT(*) FROM plan_adjustment_diff    WHERE tenant_id = 'default'
UNION ALL SELECT 'plan_adjustment_log',     COUNT(*) FROM plan_adjustment_log     WHERE tenant_id = 'default'
UNION ALL SELECT 'student_knowledge_state', COUNT(*) FROM student_knowledge_state WHERE tenant_id = 'default'
UNION ALL SELECT 'mastery_change_log',      COUNT(*) FROM mastery_change_log      WHERE tenant_id = 'default'
UNION ALL SELECT 'error_question',          COUNT(*) FROM error_question          WHERE tenant_id = 'default'
UNION ALL SELECT 'assessment_result',       COUNT(*) FROM assessment_result       WHERE tenant_id = 'default'
UNION ALL SELECT 'assessment_submission_detail', COUNT(*) FROM assessment_submission_detail WHERE tenant_id = 'default'
UNION ALL SELECT 'exam_paper',              COUNT(*) FROM exam_paper              WHERE tenant_id = 'default';

START TRANSACTION;

-- ② 目标 / 计划 / 作业（必删）
-- 调整日志有外键指向 diff 与 goal，必须最先删
DELETE FROM plan_adjustment_log      WHERE tenant_id = 'default';
DELETE FROM plan_adjustment_diff     WHERE tenant_id = 'default';

DELETE FROM homework_submission_detail WHERE tenant_id = 'default';
DELETE FROM generated_question         WHERE tenant_id = 'default';
DELETE FROM daily_plan                 WHERE tenant_id = 'default';
DELETE FROM learning_goal              WHERE tenant_id = 'default';

-- ③ 掌握度 / 错题（建议一起删，否则新目标会继承旧的掌握度与错题队列）
DELETE FROM mastery_change_log      WHERE tenant_id = 'default';
DELETE FROM error_question          WHERE tenant_id = 'default';
DELETE FROM student_knowledge_state WHERE tenant_id = 'default';

-- ④ 专项评测（可选：不打算重新评测可保留）
DELETE FROM assessment_submission_detail WHERE tenant_id = 'default';
DELETE FROM assessment_result            WHERE tenant_id = 'default';

-- ⑤ 考试试卷与解析（可选：不打算重新上传试卷可保留）
DELETE FROM exam_analysis_result WHERE tenant_id = 'default';
DELETE FROM exam_analysis_task   WHERE tenant_id = 'default';
DELETE FROM exam_question        WHERE tenant_id = 'default';
DELETE FROM exam_paper           WHERE tenant_id = 'default';

-- ⑥ Agent 降级日志（可选）
DELETE FROM agent_degradation_log WHERE tenant_id = 'default';

COMMIT;

-- ⑦ 执行后校验：以下应全部为 0
SELECT 'learning_goal' AS tbl, COUNT(*) AS cnt FROM learning_goal   WHERE tenant_id = 'default'
UNION ALL SELECT 'daily_plan',              COUNT(*) FROM daily_plan              WHERE tenant_id = 'default'
UNION ALL SELECT 'homework_submission_detail', COUNT(*) FROM homework_submission_detail WHERE tenant_id = 'default'
UNION ALL SELECT 'generated_question',      COUNT(*) FROM generated_question      WHERE tenant_id = 'default'
UNION ALL SELECT 'student_knowledge_state', COUNT(*) FROM student_knowledge_state WHERE tenant_id = 'default'
UNION ALL SELECT 'error_question',          COUNT(*) FROM error_question          WHERE tenant_id = 'default';

-- ⑧ 保留项确认（应仍有数据）
SELECT 'student' AS tbl, COUNT(*) AS cnt FROM student
UNION ALL SELECT 'knowledge_point', COUNT(*) FROM knowledge_point WHERE tenant_id = 'default'
UNION ALL SELECT 'question_bank',   COUNT(*) FROM question_bank   WHERE tenant_id = 'default'
UNION ALL SELECT 'subject',         COUNT(*) FROM subject         WHERE tenant_id = 'default';
