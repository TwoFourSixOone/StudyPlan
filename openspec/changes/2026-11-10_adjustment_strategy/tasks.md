# Tasks：计划调整改为"策略 + 未完成计划重排"

> **Change 名称**：`2026-11-10_adjustment_strategy`

---

### Task 1：数据模型

- [x] `V12__add_goal_adjustment_strategy.sql`：`daily_question_limit`、`paused_kp_ids`（含回滚注释）
- [x] `LearningGoal` 增加两字段
- [x] `PlanDiffDTO` / `PlanDiffDetailVO` 增加两字段

### Task 2：Agent 契约

- [x] `adjustment_schema.json` 增加 `dailyQuestionLimit`（1-15）、`pausedKpIds`（≤20）
- [x] 新增 `adjustment_v3.txt`（v2 + 策略手段说明），`AdjustmentAgentImpl` 切到 v3
- [x] `parseAdjustmentResult` 解析策略字段（越界忽略）

### Task 3：应用逻辑

- [x] `applyGoalStrategy`：写目标策略（空数组 = 清空暂缓）
- [x] 重排只作用于未来且 `pending/pending_generation` 的计划
- [x] 日期无计划 → 计数 `skippedNoPlan` 并记日志
- [x] 已开始/已完成 → 计数跳过，不更新

### Task 4：每日生成承接策略

- [x] `HomeworkQuotaCalculator` 新增带 `dailyQuestionLimit` 的重载（夹到 1–15）
- [x] `HomeworkServiceImpl` 读取目标策略：题量上限传给配额、暂缓知识点过滤（为空则忽略）

### Task 5：学生端确认流程

- [x] `api/plan.ts` 增加 `getDiffDetail` / `confirmDiff` / `rejectDiff`
- [x] 触发后弹出方案摘要（题量/暂缓/重排天数），确认生效、取消则拒绝

### Task 6：测试

- [x] `PlanDiffServiceTest` +3（策略写入 / 无计划跳过仍 confirm / 已开始不动）
- [x] `HomeworkServiceTest` +1（暂缓知识点不传给出题 Agent）
- [x] `HomeworkQuotaCalculatorTest` +4（默认 15 / 下调 10 / 极端模式 / 越界夹取）

### Task 7：文档与构建

- [x] 产品规则 #5 更新为"策略 + 未完成计划重排"（含信号→手段映射表）
- [x] `mvn test` 全量通过 → 570 tests, 0 failures
- [x] `npm run build:mp-weixin` 通过
- [ ] 联调：触发调整 → 确认 → 检查 `learning_goal` 两列与次日作业题量
