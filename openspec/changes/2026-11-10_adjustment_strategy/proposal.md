# Proposal：计划调整改为"策略 + 未完成计划重排"

> **Change 名称**：`2026-11-10_adjustment_strategy`
> **状态**：待实施

---

## 1. 背景

每日作业改为 02:00 自动生成后，「调整计划」的原实现（只改某些日期的 `daily_plan.content`）失效了：

- 未来日期此时**没有** `daily_plan` 行，`PlanDiffServiceImpl.applyDiff` 里 `if (plan != null)` 直接静默跳过
- 调整 Agent 的 schema 只有 `modifiedDates`，**无法表达"降低题量""暂缓知识点"**——而 SIG-3（连续未完成）、SIG-4（周期覆盖不完）恰恰需要这两种手段
- 结果：能改的（SIG-1/SIG-2）每日生成已自动消化；改不了的（SIG-3/SIG-4）永远没有手段

## 2. 目标

1. 调整支持**目标级策略**：日题量上限、暂缓知识点，跨天生效
2. 只重排**未来且尚未开始**的计划；已开始/已完成一律不动
3. 日期无计划时**显式记录跳过**，不再静默
4. 学生确认后生效（规则 #5 原有要求）

## 3. 范围

### 包含

- `V12`：`learning_goal` 增加 `daily_question_limit`、`paused_kp_ids`
- `LearningGoal` / `PlanDiffDTO` / `PlanDiffDetailVO` 增加字段
- `adjustment_schema.json` 增加两个策略字段；新增 `adjustment_v3.txt`（Prompt 版本递增，规则 #6）
- `AdjustmentAgentImpl` 解析策略字段
- `PlanDiffServiceImpl.applyDiff`：先写目标策略，再按"未开始"重排，输出计数日志
- `HomeworkQuotaCalculator` 支持日题量上限（夹到 1–15）
- `HomeworkServiceImpl` 读取策略：暂缓知识点过滤、题量上限
- 学生端确认流程（触发 → 查看方案 → 确认/忽略）

### 不包含

- 不支持修改目标周期（SIG-4 用"暂缓知识点"缓解，不延长周期）
- 不做多租户批量调整
- 不追溯修改历史计划

## 4. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 题量下限过低导致作业过少 | 低 | schema 约束 1–15；Prompt 建议 15→10→8 逐级下调 |
| 暂缓知识点后无可出题 | 中 | 暂缓后为空则忽略暂缓（与年级过滤的整体降级一致） |
| 策略写入后遗忘 | 中 | 每次调整全量覆盖；确认页展示策略变更 |
| Prompt 变更影响既有输出 | 中 | 新版本 `adjustment_v3.txt`，v2 保留可回滚 |
