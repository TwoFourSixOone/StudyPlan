# Proposal：计划调整 diff 按学生隔离（修复跨学生读/写越权）

> **Change**：`2026-11-19_plan_adjustment_isolation`

## 背景（2026-11-19 缺陷）

学生端「计划调整」链路由客户端直接驱动的入口共有 4 个：diff 详情、确认、拒绝、调整历史；另有触发调整入口。
此前这些入口只按 `tenant_id` 过滤，同一租户内学生 A 只要拿到 B 的 `diffId`（或 `goalId`）就能：

- 读 B 的调整方案（触发信号、要改哪些日期、日题量上限、暂缓知识点）；
- 把 B 的 diff 置为 `rejected`，或在确认后由 `applyDiff` **改写 B 的目标策略与未来 `daily_plan`**；
- 调整历史返回整个租户所有学生的调整记录；
- 对 B 的 `goalId` 触发调整，生成 pending diff（7 天内阻塞 B 本人再次调整）。

## 根因（file#symbol）

| 入口 | 原实现 | 缺陷 |
|---|---|---|
| `PlanAdjustmentServiceImpl#getDiffDetail` / `#confirmDiff` / `#rejectDiff` | `diffMapper.selectById(diffId)` + MyBatis-Plus 租户插件追加的 `tenant_id` | `plan_adjustment_diff` 只有 `goal_id`（`src/main/resources/db/V7__plan_adjustment.sql`），学生归属在 `learning_goal.student_id` → 缺学生维度 |
| `PlanAdjustmentServiceImpl#getAdjustmentHistory` | 只按 `tenant_id` 分页 | `plan_adjustment_log` 同样只有 `goal_id` → 返回全租户历史 |
| `PlanAdjustmentServiceImpl#triggerAdjustment(Long)` | 把客户端 `goalId` 直接透传给显式重载 | 未校验目标归属 → 可对他人目标触发调整（写路径） |

## 方案（4 个入口 + 3 条归属 SQL）

1. `PlanAdjustmentDiffMapper#selectByIdWithStudent(diffId, studentId, tenantId)`：
   `plan_adjustment_diff d JOIN learning_goal lg ON d.goal_id = lg.goal_id`，同时带 `d.tenant_id`、`lg.tenant_id`、
   `lg.student_id`；不存在 / 非本人 / 非本租户**统一** `PLAN_ADJUST_NOT_FOUND(1140)`（不区分存在性，避免探测）。
2. `LearningGoalMapper#selectOwnedGoalId(goalId, studentId, tenantId)`：`triggerAdjustment(Long)` 的写入口归属校验，
   失败同样 1140。
3. `LearningGoalMapper#selectGoalIdsByStudent(studentId, tenantId)`：调整历史先取当前学生全部 goalIds
   （**不过滤 status**，历史要完整）；空 → 空分页（不退化为全租户），非空 → `in(goalId, goalIds)` + MyBatis-Plus `Page`。
4. `PlanAdjustmentServiceImpl#confirmDiff` 的归属校验必须在 `PlanDiffService#applyDiff` **之前**；
   状态类错误（已确认/已拒绝/已过期）在归属校验之后判定，避免向他人泄漏 diff 状态。

系统路径不动：`PlanDiffService#applyDiff(diffId)` 同时被 01:30 自动调整（`AutoAdjustmentTask`）与考试结算
（`ExamSignalAdjustServiceImpl`）复用，没有 HTTP 上下文/学生身份，因此**不做**学生归属校验（Javadoc 写明
「归属校验由调用方负责；系统路径无学生上下文」），租户取自 diff 自身 `tenantId`；
`triggerAdjustment(goalId, studentId, tenantId)` 是显式身份重载（系统入口），同样不做归属校验。

## 不包含

- 不给 `daily_plan` / `plan_adjustment_diff` / `plan_adjustment_log` 增加 `student_id` 列（零 DDL，归属经 `learning_goal` 表达）；
- 不改定时任务/考试结算链路（`AutoAdjustmentTask`、`ExamSignalAdjustServiceImpl` 零改动）；
- 不处理「一个科目多目标并行」的调整历史归属（另立 change）。

## 验收

- 学生 A 用 B 的 `diffId` 调 `GET /api/student/plan/diff/{diffId}`、`POST .../confirm`、`POST .../reject` → 1140，且不产生任何写入；
- 学生 A 用 B 的 `goalId` 调 `POST /api/student/plan/adjust` → 1140，不生成 diff；
- 学生 A 的调整历史只含自己 goal 的日志；无目标时返回空分页；
- 定时任务与考试结算仍能正常 `applyDiff`（系统路径不回归）；
- `mvn -o test` 全绿（实施侧最终基线 1023 tests / 0 failures / 0 errors / 0 skipped；V1 以 `mvn -o clean test` 复跑为准）。
