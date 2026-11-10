-- =============================================
-- V13：规划 Agent 生成的每日计划骨架（2026-11-10）
--
-- 背景：创建目标时应由规划 Agent 拆出整段计划（每天学哪些知识点、多少题），
--       02:00 出题时按骨架执行，而不是每天临场决定知识点。
--
-- 回滚：
--   ALTER TABLE daily_plan DROP COLUMN planned_kp_ids;
--   ALTER TABLE daily_plan DROP COLUMN planned_question_count;
-- =============================================

ALTER TABLE `daily_plan`
    ADD COLUMN `planned_kp_ids` TEXT DEFAULT NULL
        COMMENT '规划 Agent 指定的知识点 ID（JSON 数组；NULL=未规划，按掌握度现算）';

ALTER TABLE `daily_plan`
    ADD COLUMN `planned_question_count` TINYINT DEFAULT NULL
        COMMENT '规划 Agent 指定的当日题量（1-15；NULL=按配额计算）';
