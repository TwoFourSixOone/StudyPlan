-- =============================================
-- V12：计划调整策略落到目标（产品规则 #5，2026-11-10）
--
-- 背景：每日作业改为 02:00 自动生成后，"调整计划"不能再只改某天内容
--       （未来日期尚无 daily_plan 行）。因此把可跨天生效的策略挂到目标上：
--       - daily_question_limit：日题量上限（SIG-3 学习懈怠时下调）
--       - paused_kp_ids：本轮暂缓的知识点（SIG-4 进度风险时腾出课时）
--
-- 回滚（如需）：
--   ALTER TABLE learning_goal DROP COLUMN daily_question_limit;
--   ALTER TABLE learning_goal DROP COLUMN paused_kp_ids;
-- =============================================

ALTER TABLE `learning_goal`
    ADD COLUMN `daily_question_limit` TINYINT DEFAULT NULL
        COMMENT '每日题量上限(1-15)，NULL=默认15（产品规则 #5 调整策略）';

ALTER TABLE `learning_goal`
    ADD COLUMN `paused_kp_ids` TEXT DEFAULT NULL
        COMMENT '本轮暂缓的知识点 ID（JSON 数组字符串，NULL=不暂缓）';
