# Tasks：计划调整 diff 按学生隔离

> **Change**：`2026-11-19_plan_adjustment_isolation`　**状态**：已实施（2026-11-19，含 revision 1/2：扩到触发调整入口）

## T1 diff 读路径按学生隔离

- [x] 新增 `PlanAdjustmentDiffMapper#selectByIdWithStudent(diffId, studentId, tenantId)`：
      `JOIN learning_goal`，带 `d.tenant_id` + `lg.student_id` + `lg.tenant_id`，显式列名（禁止 `SELECT *`）
- [x] 新增 `PlanAdjustmentServiceImpl#requireOwnedDiff`：非本人/不存在/非本租户统一 `PLAN_ADJUST_NOT_FOUND(1140)`
- [x] `getDiffDetail` / `confirmDiff` / `rejectDiff` 三入口改走 `requireOwnedDiff`；
      状态类错误（ALREADY_CONFIRMED/REJECTED/EXPIRED/NOT_FOUND_OR_INVALID）在归属校验之后判定
- **验收**：A 用 B 的 diffId 读/确认/拒绝 → 1140，且不产生任何写入

## T2 diff 写路径（确认/拒绝/触发调整）

- [x] `confirmDiff` 归属校验在 `PlanDiffService#applyDiff` 之前
- [x] `rejectDiff` 归属校验在状态判断与写日志之前
- [x] 新增 `LearningGoalMapper#selectOwnedGoalId(goalId, studentId, tenantId)`；
      `triggerAdjustment(Long)` 先 `requireOwnedGoal` 再委托显式重载（他人 goalId → 1140）
- [x] `triggerAdjustment(goalId, studentId, tenantId)` 保持纯系统语义（不读 `StudentContext`、不做归属校验），Javadoc 写明归属责任
- **验收**：A 对 B 的 goalId 触发调整 → 1140，不检测信号、不生成 diff

## T3 调整历史按学生过滤

- [x] 新增 `LearningGoalMapper#selectGoalIdsByStudent(studentId, tenantId)`（不过滤 `status`）
- [x] `getAdjustmentHistory`：goalIds 为空（含无学生上下文）→ 空分页；非空 `in(goalId, goalIds)` + MyBatis-Plus `Page`
- [x] 禁止 `wrapper.last()` 拼 SQL、禁止退化为「只按 tenant_id」的全租户查询
- **验收**：A 的调整历史只含自己 goal 的日志；A 无 goal 时返回空分页

## T4 系统路径不回归

- [x] `PlanDiffServiceImpl#applyDiff` 行为零改动，仅 Javadoc 说明「归属校验由调用方负责；系统路径无学生上下文」、租户取 diff 自身
- [x] 未改 `AutoAdjustmentTask` / `ExamSignalAdjustServiceImpl`（`AutoAdjustmentTaskTest` 6/6 绿）
- **验收**：定时任务与考试结算仍能正常 `applyDiff`

## T5 回归护栏与文档

- [x] 新增 `PlanAdjustmentIsolationSqlTest`（4 例）：反射钉 SQL + 弱化形态反证
- [x] `PlanAdjustmentServiceTest` +14 例（他人 goalId/diffId、自己的 diff、空 goalIds、无学生上下文）
- [x] `PlanDiffServiceTest` +2 例（系统路径无上下文 applyDiff、applyDiff 不做归属）
- [x] 隐性约定 #46 / 产品规则 #7 边界、change 工件（proposal/design/tasks/verify）
- **验收**：定向 43 例全绿；全量实施侧最终基线 1023/0/0/0（V1 以 `mvn -o clean test` 复跑为准）

## T6 复核收尾

- [x] R1 复核（t4）verdict=pass；随后 t9 删除 `confirmDiff` 无用变量并强化隔离 SQL 测试第 4 例
- [ ] R1-b 复评（t14）：按 t9 之后的当前字节确认隔离与护栏强度（落笔时进行中，以最终结论为准）
