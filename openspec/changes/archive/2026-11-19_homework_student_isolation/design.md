# Design：每日作业读取按学生隔离

> **Change**：`2026-11-19_homework_student_isolation`

## D1 数据归属模型（现状）

```
learning_goal(goal_id, student_id, subject_id, tenant_id, status, ...)
      └─ daily_plan(plan_id, goal_id, plan_date, content, status, tenant_id)      ← 无 student_id
            └─ homework_submission_detail(submission_id, plan_id, student_id, ...) ← 有冗余 student_id + idx_student
```

- 计划的**学生归属**只能经 `learning_goal` 表达 → 任何"按科目/日期"读计划的 SQL **必须 JOIN learning_goal
  并按 `lg.student_id` 过滤**；
- 提交明细自带 `student_id`（V? 迁移新增，含 `idx_student`），读取时按"plan_id + student_id"双条件更稳。

## D2 修复 SQL

```sql
-- DailyPlanMapper.selectBySubjectAndDate
SELECT dp.plan_id, dp.goal_id, dp.plan_date, dp.content, dp.status,
       dp.tenant_id, dp.create_time, dp.update_time
FROM daily_plan dp
JOIN learning_goal lg ON dp.goal_id = lg.goal_id
WHERE lg.student_id = #{studentId}
  AND lg.subject_id = #{subjectId}
  AND lg.tenant_id = #{tenantId}
  AND dp.tenant_id = #{tenantId}
  AND dp.plan_date = #{planDate}
ORDER BY dp.create_time DESC LIMIT 1
```

```sql
-- HomeworkSubmissionDetailMapper.selectByPlanId（内层子查询 + 外层都过滤）
... WHERE plan_id = #{planId} AND student_id = #{studentId} AND tenant_id = #{tenantId} GROUP BY question_id
) s2 ON s1.submission_id = s2.max_id
WHERE s1.student_id = #{studentId} AND s1.tenant_id = #{tenantId}
```

- 保留 `ORDER BY create_time DESC LIMIT 1`：同一学生同科目同日期理论上一条计划；若历史数据重复，
  取最新一条（与学生维度无关，不改变既有语义）。
- 索引：`idx_goal_date(tenant_id, goal_id, plan_date)` + `idx_student(tenant_id, student_id)` 可覆盖
  （优化器先按 goal 定位再 JOIN，或先按学生筛 goal）；本次不加索引。

## D3 为什么不是"前端过滤"

- 前端按 `subjectList` 逐科目请求，并用 `!hasActiveGoal` 只做**只读标记**；请求本身已返回了他人数据，
  浏览器/小程序内存里就能看到 → 必须在服务端读路径隔离；
- 且判分/错题本/学习记录都复用同一份 plan（按 planId 反查），只在前端藏起来会掩盖真实泄漏面。

## D4 回归护栏

| 层 | 用例 | 目的 |
|---|---|---|
| SQL 反射 | `HomeworkIsolationSqlTest#should_filterPlanByStudentId` | `DailyPlanMapper.selectBySubjectAndDate` 必须含 `lg.student_id = #{studentId}` 与 JOIN |
| SQL 反射 | `HomeworkIsolationSqlTest#should_filterSubmissionsByStudentId` | `selectByPlanId` 内外两层都要带 `student_id` |
| Service | `HomeworkServiceTest#should_scopeHomeworkByStudent_when_getByDate` | `getHomeworkByDate` 传给 mapper 的是当前 studentId |

## D5 风险与边界

- **写路径不变**：`submitAnswer`/`gradeHomework` 已按学生校验归属，本次只是把读路径补齐，不改变业务语义；
- **多目标同科目**：`selectBySubjectAndDate` 仍取最新创建的一条计划（沿用现状）；若将来一个科目允许多个并行目标，
  需改成"按 goalId 列表取"（另立 change）；
- **性能**：JOIN 条件与过滤列都有索引，单学生单科目单日期查询为常量级；
- **无 DDL、无数据回填**：纯读取 SQL 修复。
