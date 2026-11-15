-- V15：选择题区分单选/多选（2026-11-12，Change 2026-11-12_exam_quality_v3）
-- 背景：多选题若模型只给出一个正确标签，仅靠"答案标签数 ≥2"推断会误判为单选（真实卷 第 9 题，
--       卷面标注"全部选对的得 6 分，部分选对的得部分分，有选错的得 0 分"）。
--       改为由解析模型显式输出 choiceMode，独立落库，页面据此显示「单选/多选」。
ALTER TABLE `exam_question`
    ADD COLUMN `choice_mode` VARCHAR(10) NULL COMMENT '选择题模式：single/multi（非选择题为 NULL）' AFTER `question_type`;
