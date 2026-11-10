# Tasks：目标创建生成整段计划骨架 + 自动调整

> **Change 名称**：`2026-11-10_goal_planning_agent`

---

### Task 1：规划 Agent

- [x] `V13`：`daily_plan` 增加 `planned_kp_ids`、`planned_question_count`
- [x] `planning_schema.json` + `planning_v1.txt`
- [x] `PlanningContextDTO` / `PlanDraft`
- [x] `AgentOutputValidator.validatePlanning`
- [x] `PlanningAgent`（含 2 次重试与降级）

### Task 2：骨架生成

- [x] `PlanSkeletonService` + `Impl`：查真实数据 → 规划 → 落骨架
- [x] 模板降级（Agent 失败时按知识点顺序轮转）
- [x] `GoalServiceImpl.createGoal` 调用（失败不阻塞目标创建）

### Task 3：每日出题读骨架

- [x] `HomeworkServiceImpl`：骨架知识点与可用知识点取交集；骨架题量优先于目标上限
- [x] 无骨架时维持原逻辑

### Task 4：自动调整

- [x] `AutoAdjustmentTask`（02:30，Asia/Shanghai）
- [x] 命中信号 → 生成 diff → 直接应用
- [x] 逐目标异常隔离

### Task 5：测试

- [x] `PlanSkeletonServiceTest` 4 例
- [x] `AutoAdjustmentTaskTest` 4 例
- [x] `GoalServiceTest` 补骨架服务 mock

### Task 6：文档

- [x] 产品规则 #1（计划骨架）、#3（按骨架出题）、#5（自动调整）
- [x] `openspec/specs/learning-loop.md` 同步

### Task 7：构建与联调

- [x] `mvn test` → 578 tests, 0 failures
- [ ] 联调：创建目标后查 `daily_plan` 骨架（应覆盖整段周期）
- [ ] 联调：02:00 出题是否按骨架知识点；02:30 是否自动调整
