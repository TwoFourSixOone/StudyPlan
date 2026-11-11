-- ============================================================
-- 清理「无有效选项的选择题」坏数据（2026-11-11）
--
-- 位置：docs/ops/（人工执行，不进 Flyway、不被应用自动执行）
--
-- 背景：举一反三生成的选择题曾有概率不带选项（或选项不足 2 个），
--       学生端渲染成「有题干、无选项、无法提交」的死题。
--       代码已修复（提交 31265eb），但**已落库的坏数据不会自愈**，需要清理。
--
-- 判定标准（与 ChoiceOptions 的可用性判断一致）：
--   content.formatType = 'choice' 且
--   content.choices 缺失 / 不是数组 / 数组长度 < 2
--
-- 注意：字符串数组形态（["A. 4","B. 2"]）现在是**合法**的，不会被清掉。
--       新代码落库时会写成 [{"label":"A","text":"..."}]；
--       旧数据若是字符串数组，出参侧会归一化，**无需清理**。
--
-- 2026-11-11 本地核对结果：坏题 0 条（当时仅 5 道生成题，选项均可用），
--       本脚本保留用于后续排障，正常无需执行。
--
-- 范围：只清理坏题与其所属计划，**不回滚**已写入的掌握度 / 错题。
--       若要彻底重来（连掌握度一起清），请改跑 reset_learning_data.sql。
--
-- 兼容性：MySQL 5.7（不使用 CTE / JSON_TABLE）
-- 租户：默认 default，多租户请全局替换
-- 执行方式：建议按 STEP 顺序逐段执行，先看诊断结果再执行清理
-- ============================================================


-- ============================================================
-- STEP 0.（先跑这个）只看「今天」的计划要不要处理
-- ============================================================

-- 0.1 今天的计划概览：每份计划有几道题、状态是什么
SELECT dp.plan_id,
       dp.goal_id,
       dp.status,
       JSON_LENGTH(dp.content) AS question_count,
       dp.update_time
FROM daily_plan dp
WHERE dp.tenant_id = 'default'
  AND dp.plan_date = CURDATE()
ORDER BY dp.plan_id;

-- 0.2 今天计划里的坏题（有行 → 需要重置今天；0 行 → 不用动）
SELECT gq.generated_question_id AS question_id,
       gq.daily_plan_id,
       gq.knowledge_point_id,
       JSON_UNQUOTE(JSON_EXTRACT(gq.content, '$.stem')) AS stem,
       JSON_LENGTH(JSON_EXTRACT(gq.content, '$.choices')) AS choice_count
FROM generated_question gq
JOIN daily_plan dp ON dp.plan_id = gq.daily_plan_id
WHERE gq.tenant_id = 'default'
  AND dp.plan_date = CURDATE()
  AND JSON_UNQUOTE(JSON_EXTRACT(gq.content, '$.formatType')) = 'choice'
  AND (
        JSON_EXTRACT(gq.content, '$.choices') IS NULL
     OR JSON_TYPE(JSON_EXTRACT(gq.content, '$.choices')) <> 'ARRAY'
     OR JSON_LENGTH(JSON_EXTRACT(gq.content, '$.choices')) < 2
  );


-- ============================================================
-- STEP 1. 诊断：先看有多少坏题、影响哪些计划（只读）
-- ============================================================

-- 1.1 坏题明细
SELECT gq.generated_question_id AS question_id,
       gq.daily_plan_id,
       dp.plan_date,
       dp.status           AS plan_status,
       gq.knowledge_point_id,
       JSON_UNQUOTE(JSON_EXTRACT(gq.content, '$.stem')) AS stem,
       JSON_UNQUOTE(JSON_EXTRACT(gq.content, '$.formatType')) AS format_type,
       JSON_LENGTH(JSON_EXTRACT(gq.content, '$.choices')) AS choice_count
FROM generated_question gq
JOIN daily_plan dp ON dp.plan_id = gq.daily_plan_id
WHERE gq.tenant_id = 'default'
  AND JSON_UNQUOTE(JSON_EXTRACT(gq.content, '$.formatType')) = 'choice'
  AND (
        JSON_EXTRACT(gq.content, '$.choices') IS NULL
     OR JSON_TYPE(JSON_EXTRACT(gq.content, '$.choices')) <> 'ARRAY'
     OR JSON_LENGTH(JSON_EXTRACT(gq.content, '$.choices')) < 2
  )
ORDER BY gq.daily_plan_id, gq.generated_question_id;

-- 1.2 受影响计划汇总
SELECT dp.plan_id, dp.goal_id, dp.plan_date, dp.status,
       COUNT(*) AS bad_question_count
FROM generated_question gq
JOIN daily_plan dp ON dp.plan_id = gq.daily_plan_id
WHERE gq.tenant_id = 'default'
  AND JSON_UNQUOTE(JSON_EXTRACT(gq.content, '$.formatType')) = 'choice'
  AND (
        JSON_EXTRACT(gq.content, '$.choices') IS NULL
     OR JSON_TYPE(JSON_EXTRACT(gq.content, '$.choices')) <> 'ARRAY'
     OR JSON_LENGTH(JSON_EXTRACT(gq.content, '$.choices')) < 2
  )
GROUP BY dp.plan_id, dp.goal_id, dp.plan_date, dp.status
ORDER BY dp.plan_date, dp.plan_id;


-- ============================================================
-- STEP 2. 备份（可选，但强烈建议先跑一次）
-- ============================================================

DROP TABLE IF EXISTS bak_generated_question_20261111;
CREATE TABLE bak_generated_question_20261111 AS
SELECT gq.*
FROM generated_question gq
WHERE gq.tenant_id = 'default'
  AND JSON_UNQUOTE(JSON_EXTRACT(gq.content, '$.formatType')) = 'choice'
  AND (
        JSON_EXTRACT(gq.content, '$.choices') IS NULL
     OR JSON_TYPE(JSON_EXTRACT(gq.content, '$.choices')) <> 'ARRAY'
     OR JSON_LENGTH(JSON_EXTRACT(gq.content, '$.choices')) < 2
  );

DROP TABLE IF EXISTS bak_daily_plan_20261111;
CREATE TABLE bak_daily_plan_20261111 AS
SELECT dp.*
FROM daily_plan dp
WHERE dp.tenant_id = 'default'
  AND dp.plan_id IN (
      SELECT DISTINCT gq.daily_plan_id
      FROM generated_question gq
      WHERE gq.tenant_id = 'default'
        AND JSON_UNQUOTE(JSON_EXTRACT(gq.content, '$.formatType')) = 'choice'
        AND (
              JSON_EXTRACT(gq.content, '$.choices') IS NULL
           OR JSON_TYPE(JSON_EXTRACT(gq.content, '$.choices')) <> 'ARRAY'
           OR JSON_LENGTH(JSON_EXTRACT(gq.content, '$.choices')) < 2
        )
  );

SELECT 'bak_generated_question_20261111' AS backup_table, COUNT(*) AS rows_backed_up
FROM bak_generated_question_20261111
UNION ALL
SELECT 'bak_daily_plan_20261111', COUNT(*) FROM bak_daily_plan_20261111;


-- ============================================================
-- STEP 3. 清理
--
-- 处置策略（计划级重置，而不是只删单题）：
--   daily_plan.content 里存的是题目引用数组，删掉一道题会让引用悬空，
--   学生端会出现「题目空白 / 提交 404」。所以对受影响计划整体重置：
--     · 删除其全部作答明细（含同计划的题库题作答，测试数据可接受）
--     · 删除其全部生成题（坏题 + 同计划的好题，重新出题会重建）
--     · 计划置为 pending_generation（今天及以后）或 cancelled（历史日期）
--
-- 若你只想删坏题、不想动计划内容，请跳到 STEP 3B。
-- ============================================================

DROP TEMPORARY TABLE IF EXISTS tmp_bad_generated_question;
CREATE TEMPORARY TABLE tmp_bad_generated_question AS
SELECT gq.generated_question_id, gq.daily_plan_id
FROM generated_question gq
WHERE gq.tenant_id = 'default'
  AND JSON_UNQUOTE(JSON_EXTRACT(gq.content, '$.formatType')) = 'choice'
  AND (
        JSON_EXTRACT(gq.content, '$.choices') IS NULL
     OR JSON_TYPE(JSON_EXTRACT(gq.content, '$.choices')) <> 'ARRAY'
     OR JSON_LENGTH(JSON_EXTRACT(gq.content, '$.choices')) < 2
  );

DROP TEMPORARY TABLE IF EXISTS tmp_affected_plan;
CREATE TEMPORARY TABLE tmp_affected_plan AS
SELECT DISTINCT daily_plan_id AS plan_id FROM tmp_bad_generated_question;

SELECT COUNT(*) AS bad_questions, (SELECT COUNT(*) FROM tmp_affected_plan) AS affected_plans
FROM tmp_bad_generated_question;

START TRANSACTION;

-- 3.1 删除受影响计划的作答明细（homework_submission_detail 无外键，必须显式删）
DELETE hs
FROM homework_submission_detail hs
JOIN tmp_affected_plan p ON hs.plan_id = p.plan_id
WHERE hs.tenant_id = 'default';

-- 3.2 删除受影响计划的全部生成题
DELETE gq
FROM generated_question gq
JOIN tmp_affected_plan p ON gq.daily_plan_id = p.plan_id
WHERE gq.tenant_id = 'default';

-- 3.3 重置计划：清空引用，今天及以后重新出题，历史日期直接取消
UPDATE daily_plan dp
JOIN tmp_affected_plan p ON dp.plan_id = p.plan_id
SET dp.content     = NULL,
    dp.status      = CASE WHEN dp.plan_date >= CURDATE() THEN 'pending_generation' ELSE 'cancelled' END,
    dp.update_time = NOW()
WHERE dp.tenant_id = 'default';

COMMIT;


-- ============================================================
-- STEP 3B.（可选）只删坏题，不动计划
--
-- ⚠ 风险：daily_plan.content 里的引用会悬空，学生端该题会显示空白或提交失败。
--        仅当你打算立刻整份计划重新生成时再使用。
-- ============================================================
-- START TRANSACTION;
-- DELETE gq FROM generated_question gq
-- JOIN tmp_bad_generated_question b ON gq.generated_question_id = b.generated_question_id
-- WHERE gq.tenant_id = 'default';
-- COMMIT;


-- ============================================================
-- STEP 4. 校验：执行后应满足
--   4.1 坏题数量 = 0
--   4.2 受影响计划不再是 pending/in_progress（已被重置或取消）
-- ============================================================

-- 4.1 剩余坏题（应为 0）
SELECT COUNT(*) AS remaining_bad_questions
FROM generated_question gq
WHERE gq.tenant_id = 'default'
  AND JSON_UNQUOTE(JSON_EXTRACT(gq.content, '$.formatType')) = 'choice'
  AND (
        JSON_EXTRACT(gq.content, '$.choices') IS NULL
     OR JSON_TYPE(JSON_EXTRACT(gq.content, '$.choices')) <> 'ARRAY'
     OR JSON_LENGTH(JSON_EXTRACT(gq.content, '$.choices')) < 2
  );

-- 4.2 受影响计划的最终状态（今天及以后应为 pending_generation，历史应为 cancelled）
SELECT dp.plan_id, dp.plan_date, dp.status,
       dp.content IS NULL AS content_cleared
FROM daily_plan dp
JOIN tmp_affected_plan p ON dp.plan_id = p.plan_id
ORDER BY dp.plan_date, dp.plan_id;

-- 4.3 清理临时表
DROP TEMPORARY TABLE IF EXISTS tmp_affected_plan;
DROP TEMPORARY TABLE IF EXISTS tmp_bad_generated_question;


-- ============================================================
-- STEP 5.（可选）只想重生成「今天」的作业，可直接重置今天的计划
--
-- 注意：这会清空今天计划的题目引用与作答记录，next 次生成会重建。
--       不会删除 generated_question 旧行（会变成孤儿，需另行清理）。
-- ============================================================
-- START TRANSACTION;
-- DELETE hs FROM homework_submission_detail hs
-- JOIN daily_plan dp ON dp.plan_id = hs.plan_id
-- WHERE dp.tenant_id = 'default' AND dp.plan_date = CURDATE();
-- DELETE gq FROM generated_question gq
-- JOIN daily_plan dp ON dp.plan_id = gq.daily_plan_id
-- WHERE dp.tenant_id = 'default' AND dp.plan_date = CURDATE();
-- UPDATE daily_plan
-- SET content = NULL, status = 'pending_generation', update_time = NOW()
-- WHERE tenant_id = 'default' AND plan_date = CURDATE();
-- COMMIT;


-- ============================================================
-- 附：MySQL 5.7 语法说明
--   · JSON_UNQUOTE(JSON_EXTRACT(...)) 取标量值
--   · JSON_TYPE(...) 返回 'ARRAY' / 'OBJECT' / 'STRING' 等大写枚举
--   · JSON_LENGTH(数组) 返回元素个数；非数组返回 1，对象返回键数
--   · content 为 JSON 类型列，JSON_EXTRACT 不会因非法 JSON 报错
--   · 不使用 CTE / JSON_TABLE（MySQL 8.0 才有）
-- ============================================================
