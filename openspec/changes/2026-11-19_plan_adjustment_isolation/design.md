# Design：计划调整 diff 按学生隔离

> **Change**：`2026-11-19_plan_adjustment_isolation`

## D1 归属模型（现状）

```
learning_goal(goal_id, student_id, subject_id, tenant_id, status, ...)
      └─ plan_adjustment_diff(id, goal_id, status, trigger_signals, diff_content, expire_at, tenant_id)   ← 无 student_id
            └─ plan_adjustment_log(id, goal_id, diff_id, action, action_at, tenant_id)                    ← 无 student_id
```

- `plan_adjustment_diff` / `plan_adjustment_log` 的学生归属**只能经 `learning_goal` 表达**（`V7__plan_adjustment.sql`）；
- 只按 `tenant_id` 过滤 = 同租户内不隔离；
- 客户端可直接传 `diffId` / `goalId`，所以归属校验必须落在服务端读/写入口。

## D2 四个入口的改造

| 入口 | 原实现 | 现实现 | 失败语义 |
|---|---|---|---|
| `getDiffDetail(diffId)` | `selectById` + tenant | `requireOwnedDiff` → `selectByIdWithStudent` | 1140（不区分存在性） |
| `confirmDiff(diffId)` | `selectById` + 状态判断 | `requireOwnedDiff` **先**校验，再判状态，最后 `applyDiff` | 1140；状态类错误在归属校验之后 |
| `rejectDiff(diffId)` | `selectById` + 状态判断 | `requireOwnedDiff` 先校验，再置 `rejected` + 写日志 | 1140 |
| `getAdjustmentHistory(page,size)` | 只按 `tenant_id` 分页 | `selectGoalIdsByStudent` → 空则空分页；非空 `in(goalId, goalIds)` + MP Page | 无身份/无目标 → 空分页（禁止全租户） |
| `triggerAdjustment(goalId)` | 直接透传 goalId | `requireOwnedGoal` → `selectOwnedGoalId` 后再委托系统重载 | 1140 |

`PlanAdjustmentServiceImpl#requireOwnedDiff` 不返回"是否存在"的差异：diffId 为空、非本人、非本租户一律 `PLAN_ADJUST_NOT_FOUND(1140)`；
WARN 日志只记 `diffId`/`studentId`，不打印 diff 内容（调整内容含知识点与题目引用）。

## D3 归属 SQL

```sql
-- PlanAdjustmentDiffMapper#selectByIdWithStudent
SELECT d.id, d.goal_id, d.status, d.trigger_signals, d.diff_content,
       d.expire_at, d.confirmed_at, d.rejected_at, d.tenant_id,
       d.create_time, d.update_time
FROM plan_adjustment_diff d
JOIN learning_goal lg ON d.goal_id = lg.goal_id
WHERE d.id = #{diffId}
  AND d.tenant_id = #{tenantId}
  AND lg.student_id = #{studentId}
  AND lg.tenant_id = #{tenantId}
```

```sql
-- LearningGoalMapper#selectOwnedGoalId（触发调整的写入口）
SELECT goal_id FROM learning_goal
WHERE goal_id = #{goalId} AND student_id = #{studentId} AND tenant_id = #{tenantId}
```

```sql
-- LearningGoalMapper#selectGoalIdsByStudent（调整历史的 goalIds，不过滤 status）
SELECT goal_id FROM learning_goal
WHERE student_id = #{studentId} AND tenant_id = #{tenantId}
ORDER BY goal_id DESC
```

- 显式列名（禁止 `SELECT *`）：两张表都有 `tenant_id`/`create_time`，`*` 会串列；
- 索引：`learning_goal` 以 `student_id + tenant_id` 过滤（表上已有 `idx_student`），`plan_adjustment_diff` 以主键 `id` 命中，单次查询常量级。

## D4 为什么不在 Controller / 前端过滤

- Controller 只做参数转发（`PlanAdjustmentController` 仅补 Javadoc），过滤放 Controller 会漏掉其它调用方；
- 前端 `isReadOnly` 之类标记只能改变展示，数据已经返回给客户端 → 必须在服务端读路径隔离；
- `applyDiff` 会写库（目标策略 + 未来 `daily_plan`），写路径更必须在服务端判归属。

## D5 系统路径例外（关键约束）

`PlanDiffService#applyDiff(diffId)` 是三入口共用：

| 调用方 | 是否有学生身份 | 归属责任 |
|---|---|---|
| `PlanAdjustmentServiceImpl#confirmDiff`（学生确认） | 有（HTTP 上下文） | 调用前必须经 `requireOwnedDiff` |
| `AutoAdjustmentTask`（01:30 自动） | 无 | 目标来自 `learning_goal` 扫描，可信 |
| `ExamSignalAdjustServiceImpl`（考试结算触发） | 无 | 目标来自该学生该科目的进行中目标，可信 |

因此 `applyDiff` 保持「无学生身份」的系统入口，不读 `StudentContext`、不做 studentId 校验，租户取 diff 自身 `tenantId`；
给系统入口硬加身份会直接打断定时任务/考试结算链路。`triggerAdjustment(goalId, studentId, tenantId)` 同理。

## D6 回归护栏

| 层 | 用例 | 目的 |
|---|---|---|
| SQL 反射 | `PlanAdjustmentIsolationSqlTest`（4 例） | `selectByIdWithStudent` 必须 JOIN + 两侧租户 + `lg.student_id`；`selectOwnedGoalId` 带学生条件；`selectGoalIdsByStudent` 不过滤 status；第 4 例用「删任一条件的弱化形态」反证护栏有效 |
| Service | `PlanAdjustmentServiceTest`（+14 例） | 他人 goalId/diffId → 1140 且不写库；自己的 diff 正常读/确认/拒绝；历史只查当前学生 goal；无 goal → 空分页；无学生上下文 → 空分页 |
| Service | `PlanDiffServiceTest`（+2 例） | 系统路径无学生上下文也能 `applyDiff`；`applyDiff` 不做学生归属校验 |
| 既有 | `AutoAdjustmentTaskTest`（6 例） | 01:30 自动调整不回归 |

## D7 风险与边界

- **性能**：学生入口新增一次主键命中的轻量归属查询（`selectOwnedGoalId` / `selectByIdWithStudent`），无 N+1；
- **多目标同科目**：历史按 goalIds 列表查（不按科目），天然覆盖多目标；触发调整仍要求 goal 归属成立；
- **数据一致性**：无 DDL、无数据回填；历史误读不会落库；
- **单测技巧**：`LambdaQueryWrapper` 在无 MyBatis 容器时需要初始化 `PlanAdjustmentLog` 的 `TableInfo`（仅测试侧）。
