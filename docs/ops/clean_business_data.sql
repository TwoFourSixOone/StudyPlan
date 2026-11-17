-- 清空业务数据（保留：知识点、课程标准、先修关系、题库、科目）
-- 用途：重新联调/演示前的数据重置（人工执行，不进 Flyway）
-- 保留表：subject、knowledge_point、knowledge_point_dependency、knowledge_point_standard、
--         curriculum_standard、question_bank
-- 清空表：学生、目标、计划、作业提交、生成题、错题、掌握度、学习记录、试卷链路、
--         调整 diff/log、降级日志、测评结果、导入日志、待校对提醒

SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE student;
TRUNCATE TABLE learning_goal;
TRUNCATE TABLE daily_plan;
TRUNCATE TABLE homework_submission_detail;
TRUNCATE TABLE generated_question;
TRUNCATE TABLE error_question;
TRUNCATE TABLE student_knowledge_state;
TRUNCATE TABLE mastery_change_log;

TRUNCATE TABLE exam_paper;
TRUNCATE TABLE exam_question;
TRUNCATE TABLE exam_analysis_task;
TRUNCATE TABLE exam_analysis_result;

TRUNCATE TABLE plan_adjustment_diff;
TRUNCATE TABLE plan_adjustment_log;
TRUNCATE TABLE agent_degradation_log;

TRUNCATE TABLE assessment_result;
TRUNCATE TABLE assessment_submission_detail;

TRUNCATE TABLE data_import_log;
TRUNCATE TABLE data_import_error_log;
TRUNCATE TABLE pending_review_alert;

SET FOREIGN_KEY_CHECKS = 1;
