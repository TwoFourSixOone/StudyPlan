-- ============================================================================
-- 试卷上传/解析/结算相关查询 —— 只读 EXPLAIN 验证脚本
-- Change: 2026-11-11_exam_paper_upload_llm（design §11.6 预期表 + tasks C4 / v3 更新）
--
-- 用法（只读账号，禁止任何写操作）：
--   mysql -h <host> -u <readonly_user> -p <db> < docs/ops/exam_paper_explain.sql
--
-- 说明：
--   1. 脚本中的 :student_id / :paper_id / :tenant_id / :kp_id 为占位符，
--      执行前请替换为真实值（例如 1 / 1 / 'default' / 'kp_math_001'）。
--   2. 每段注释给出 design §11.6 的预期 key / Extra / type，执行后请把实际结果回填 verify 报告（V2）。
--   3. 末尾附数据量统计，用于判断 rows 量级是否合理。
--
-- v3 变更（t23 修正）：
--   * #8/#9 去掉 `AND question_id > 0`：v3 起试卷错题（负 ID）要进入原题重做/举一反三，
--     两处到期查询与一处活跃计数均不再过滤负 ID（配额风险由 service 侧 clamp 解决，design §7.2.1）。
--   * 新增 #12 作业组装 exam 题面批量（selectBatchIds(absIds)）。
--   * 新增 #13 举一反三 exam 兜底源题（error_question JOIN exam_question ON eqx.id = -eq.question_id）。
--   * #9 改为 selectCriticalErrors（S10 新版：补 student_id，保留负 ID 防御），
--     原「配额分母统计」移到 #9b 并去掉负 ID 过滤。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 历史试卷列表 count（design §11.6 #1）
--    预期：key = idx_student_status，type = ref，Extra 含 Using index
-- ---------------------------------------------------------------------------
EXPLAIN SELECT COUNT(*)
FROM exam_paper
WHERE student_id = :student_id AND tenant_id = :tenant_id;

-- ---------------------------------------------------------------------------
-- 2. 历史试卷列表分页（design §11.6 #2）
--    预期：p 命中 idx_student_status（ref）；s 命中 PRIMARY（eq_ref）；
--          q 命中 idx_paper_index（ref）；派生表 a 为 PRIMARY；
--          Extra 允许 p 侧 Using filesort（单学生 ≤ 百行）
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT p.id, p.subject_id, s.name AS subject_name, p.exam_date, p.paper_type,
       p.status, p.grade_status, p.create_time,
       COALESCE(a.questionCount, 0) AS question_count,
       COALESCE(a.correctCount, 0)  AS correct_count,
       COALESCE(a.judgedCount, 0)   AS judged_count
FROM exam_paper p
LEFT JOIN subject s ON s.subject_id = p.subject_id AND s.tenant_id = p.tenant_id
LEFT JOIN (
  SELECT q.exam_paper_id,
         COUNT(*) AS questionCount,
         SUM(CASE WHEN q.is_correct = 1 THEN 1 ELSE 0 END) AS correctCount,
         SUM(CASE WHEN q.is_correct IS NOT NULL THEN 1 ELSE 0 END) AS judgedCount
  FROM exam_question q
  WHERE q.tenant_id = :tenant_id
    AND q.exam_paper_id IN (
      SELECT id FROM (
        SELECT id FROM exam_paper
        WHERE student_id = :student_id AND tenant_id = :tenant_id
        ORDER BY exam_date DESC, id DESC
        LIMIT 0, 10
      ) page_ids
    )
  GROUP BY q.exam_paper_id
) a ON a.exam_paper_id = p.id
WHERE p.student_id = :student_id AND p.tenant_id = :tenant_id
ORDER BY p.exam_date DESC, p.id DESC
LIMIT 0, 10;

-- ---------------------------------------------------------------------------
-- 3. 试卷详情 paper + 科目名（design §11.6 #3）
--    预期：p 命中 PRIMARY，type = const/eq_ref
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT p.id, p.student_id, p.subject_id, s.name AS subject_name, p.exam_date,
       p.paper_type, p.image_urls, p.status, p.grade_status, p.remark, p.total_score,
       p.create_time
FROM exam_paper p
LEFT JOIN subject s ON s.subject_id = p.subject_id AND s.tenant_id = p.tenant_id
WHERE p.id = :paper_id AND p.student_id = :student_id AND p.tenant_id = :tenant_id;

-- ---------------------------------------------------------------------------
-- 4. 试卷详情题目（design §11.6 #4）
--    预期：key = idx_paper_index (exam_paper_id, question_index, tenant_id)，
--          Extra 不得出现 Using filesort（已按索引顺序取）
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT id, exam_paper_id, question_index, question_content, question_image_url,
       knowledge_point_id, attribution_status, student_answer, is_correct,
       score, full_score, question_type, difficulty, tenant_id, create_time, update_time
FROM exam_question
WHERE exam_paper_id = :paper_id AND tenant_id = :tenant_id
ORDER BY question_index ASC;

-- ---------------------------------------------------------------------------
-- 5. 试卷详情知识点名称批量（design §11.6 #5）
--    预期：key = PRIMARY，type = range，rows ≤ IN 列表长度
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT knowledge_point_id, name
FROM knowledge_point
WHERE tenant_id = :tenant_id
  AND knowledge_point_id IN ('kp_math_001', 'kp_math_002', 'kp_math_003');

-- ---------------------------------------------------------------------------
-- 6. 结算掌握度 state 批量查（design §11.6 #6）
--    预期：key = uk_student_kp（student_id, knowledge_point_id, tenant_id），type = range
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT state_id, student_id, knowledge_point_id, mastery_score, confidence, source,
       last_assessed_at, version, tenant_id, create_time, update_time
FROM student_knowledge_state
WHERE student_id = :student_id AND tenant_id = :tenant_id
  AND knowledge_point_id IN ('kp_math_001', 'kp_math_002');

-- ---------------------------------------------------------------------------
-- 7. 错题本列表（含 exam_question JOIN，S9 兜底题面；design §11.6 #7）
--    预期：eq 命中 idx_student_kp_status（ref）；eqx 侧 PRIMARY（eq_ref）；
--          允许 filesort（既有 TD-PAGINATION-FILESORT）
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT eq.error_question_id, eq.question_id, eq.knowledge_point_id, kp.name AS kp_name,
       eq.source_type, eq.first_wrong_date, eq.last_wrong_date, eq.current_stage,
       eq.next_due_date, eq.status,
       COALESCE(qb.content, eqx.question_content, '[题目已删除]') AS question_content,
       COALESCE(qb.difficulty, eqx.difficulty, 'unknown') AS question_difficulty
FROM error_question eq
LEFT JOIN knowledge_point kp ON eq.knowledge_point_id = kp.knowledge_point_id
  AND kp.tenant_id = eq.tenant_id
LEFT JOIN question_bank qb ON eq.question_id = qb.question_id AND qb.tenant_id = eq.tenant_id
LEFT JOIN exam_question eqx ON eq.source_type = 'exam' AND eqx.id = -eq.question_id
  AND eqx.tenant_id = eq.tenant_id
WHERE eq.student_id = :student_id AND eq.tenant_id = :tenant_id
ORDER BY eq.first_wrong_date DESC, eq.error_question_id DESC
LIMIT 0, 10;

-- ---------------------------------------------------------------------------
-- 8. 到期知识点（v3：不再过滤 question_id > 0，试卷负 ID 错题一视同仁；design §11.6 #8）
--    预期：key = idx_student_status_due，过滤条件（status + next_due_date）不改变索引选择
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT DISTINCT knowledge_point_id
FROM error_question
WHERE student_id = :student_id AND tenant_id = :tenant_id
  AND status = 'active' AND next_due_date <= CURDATE()
ORDER BY knowledge_point_id ASC
LIMIT 51;

-- ---------------------------------------------------------------------------
-- 8b. 到期错题明细（v3：同样不过滤负 ID，每 kp 最多取 3 道由 LIMIT 控制）
--     预期：key = idx_student_status_due / idx_student_kp_status，type = ref/range，不得 ALL
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT error_question_id, question_id, knowledge_point_id, current_stage, next_due_date
FROM error_question
WHERE student_id = :student_id AND tenant_id = :tenant_id
  AND knowledge_point_id = :kp_id AND status = 'active' AND next_due_date <= CURDATE()
ORDER BY next_due_date ASC, error_question_id ASC
LIMIT 3;

-- ---------------------------------------------------------------------------
-- 9. selectCriticalErrors（S10 新版：补 student_id + 保留负 ID 防御；design §11.6 #9）
--    预期：key = idx_student_status_due 或 idx_student_kp_status，Extra 含 Using where，
--          WHERE 含 student_id + tenant_id + status + question_id，不得 type=ALL
--    ⚠ 实跑备注（t27，2026-11-11）：demo 库 error_question 仅 11 行时优化器会选 type=ALL
--      （全表扫更便宜，possible_keys 已覆盖 idx_student_status_due）；数据量上来后会走 range/ref。
--      如需确认索引可用：EXPLAIN SELECT ... FROM error_question FORCE INDEX (idx_student_status_due) ...
--      验证记录见 target/verify/explain_after_fix.txt。
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT error_question_id, student_id, question_id, knowledge_point_id, source_type,
       first_wrong_date, last_wrong_date, current_stage, next_due_date, status,
       tenant_id, create_time, update_time
FROM error_question
WHERE student_id = :student_id AND tenant_id = :tenant_id
  AND status = 'active' AND question_id > 0
  AND first_wrong_date < DATE_SUB(CURDATE(), INTERVAL 30 DAY)
ORDER BY first_wrong_date ASC
LIMIT 50;

-- ---------------------------------------------------------------------------
-- 9b. 配额分母统计（v3：不再过滤负 ID，试卷错题占配额分母；design §7.2.1）
--     预期：仍走 idx_student_status_due / idx_student_kp_status 之一，不得 type=ALL
--     ⚠ KP_MAX=4 的越界风险由 HomeworkServiceImpl 的按 kp min(raw, 4) clamp 解决，SQL 不再排除负 ID
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT knowledge_point_id, COUNT(*) AS error_count
FROM error_question
WHERE student_id = :student_id AND tenant_id = :tenant_id
  AND status = 'active'
GROUP BY knowledge_point_id;

-- ---------------------------------------------------------------------------
-- 10. 学习记录写入前查重（design §11.6 #10）
--     预期：key = idx_student (tenant_id, student_id)，type = ref，
--           rows = 该学生考试记录数（≤ 数十）
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT analysis_id
FROM exam_analysis_result
WHERE student_id = :student_id AND tenant_id = :tenant_id
  AND exam_name = '数学期中考试 2026-11-11' AND status = 'completed'
LIMIT 1;

-- ---------------------------------------------------------------------------
-- 11. D_norm 历史均值：题库按 kp + difficulty 分组（design §11.6 #11）
--     预期：key = idx_knowledge_point (tenant_id, knowledge_point_id)，type = range；
--           Extra 允许 Using temporary; Using filesort（GROUP BY 必要代价，单次结算只跑 1 条）
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT knowledge_point_id, difficulty, COUNT(*) AS cnt
FROM question_bank
WHERE tenant_id = :tenant_id
  AND knowledge_point_id IN ('kp_math_001', 'kp_math_002')
  AND difficulty IN ('basic', 'standard', 'challenge')
GROUP BY knowledge_point_id, difficulty;

-- ---------------------------------------------------------------------------
-- 12. 作业组装 exam 题面批量（v3 新增；design §11.6 #12）
--     每份作业最多 1 条 `examQuestionMapper.selectBatchIds(ids)`（MyBatis-Plus 标准批量按主键查）
--     ⚠ 与实现一致：**不带 tenant_id 条件**（selectBatchIds 只按 id IN 查询，租户隔离在前一步
--       error_question / exam_paper 查询已保证）；此处若加 tenant 会与真实 SQL 不符、误导 EXPLAIN。
--     预期：key = PRIMARY，type = range，rows = IN 列表长度（≤ 3×kp 数 ≤ 45）；无 N+1
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT id, exam_paper_id, question_index, question_content, question_image_url,
       knowledge_point_id, attribution_status, student_answer, is_correct,
       score, full_score, question_type, difficulty, tenant_id, create_time, update_time
FROM exam_question
WHERE id IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);

-- ---------------------------------------------------------------------------
-- 13. 举一反三 exam 兜底源题（v3 新增；design §11.6 #13）
--     error_question JOIN exam_question ON eqx.id = -eq.question_id，取最早到期一道
--     ⚠ exam_question 的真实列里没有 correct_answer / explanation（标准答案与解析编码在
--       question_content 的【正确答案】/【解析】段落，由 ExamQuestionTextCodec 解码），
--       因此这里只取真实存在的列（题面编码、题型、难度、知识点、学生作答、归因状态）。
--     预期：eq 命中 idx_student_kp_status（ref）；eqx 侧 PRIMARY（eq_ref）；
--           允许 filesort（ORDER BY next_due_date，行数 = 该 kp 到期错题数）
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT eqx.id AS exam_question_id, eqx.question_content, eqx.question_type,
       eqx.difficulty, eqx.knowledge_point_id, eqx.student_answer, eqx.attribution_status
FROM error_question eq
JOIN exam_question eqx ON eqx.id = -eq.question_id AND eqx.tenant_id = eq.tenant_id
WHERE eq.student_id = :student_id AND eq.tenant_id = :tenant_id
  AND eq.knowledge_point_id = :kp_id AND eq.status = 'active'
  AND eq.next_due_date <= CURDATE()
ORDER BY eq.next_due_date ASC, eq.error_question_id ASC
LIMIT 1;

-- ============================================================================
-- 数据量统计（判断 rows 量级是否与预期一致）
-- ============================================================================
SELECT
  (SELECT COUNT(*) FROM exam_paper WHERE student_id = :student_id AND tenant_id = :tenant_id)   AS student_paper_count,
  (SELECT COUNT(*) FROM exam_question q JOIN exam_paper p ON p.id = q.exam_paper_id
     WHERE p.student_id = :student_id AND p.tenant_id = :tenant_id)                             AS student_exam_question_count,
  (SELECT COUNT(*) FROM error_question WHERE student_id = :student_id AND tenant_id = :tenant_id) AS student_error_question_count,
  (SELECT COUNT(*) FROM error_question WHERE student_id = :student_id AND tenant_id = :tenant_id
     AND question_id < 0)                                                                       AS student_exam_error_question_count,
  (SELECT COUNT(*) FROM exam_analysis_result WHERE student_id = :student_id AND tenant_id = :tenant_id) AS student_exam_record_count;
