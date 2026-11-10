# Design：计划调整改为"策略 + 未完成计划重排"

> **Change 名称**：`2026-11-10_adjustment_strategy`

---

## 1. 两层落地模型

```
调整 Agent 输出
├── 目标级策略（跨天生效）
│   ├── dailyQuestionLimit: 1-15 → learning_goal.daily_question_limit
│   └── pausedKpIds: [kpId] → learning_goal.paused_kp_ids (JSON)
└── 日期级重排（modifiedDates）
    └── 仅作用于 未来 且 status ∈ {pending, pending_generation} 的计划
```

**为什么策略必须挂目标**：每日 02:00 生成时，未来日期还没有 `daily_plan` 行；只有目标能在"还没生成"的阶段承载"题量/暂缓"意图。

## 2. 数据模型

| 表 | 列 | 说明 |
|----|----|------|
| `learning_goal` | `daily_question_limit TINYINT NULL` | NULL = 默认 15 |
| `learning_goal` | `paused_kp_ids TEXT NULL` | JSON 数组；NULL = 不暂缓 |

回滚语句写在 `V12` 注释中。

## 3. Agent 契约

`adjustment_schema.json` 新增两个**可选**字段（不 required，避免旧输出被拒）：

```json
"dailyQuestionLimit": { "type": "integer", "minimum": 1, "maximum": 15 },
"pausedKpIds": { "type": "array", "maxItems": 20, "items": { "type": "string" } }
```

Prompt 新版本 `adjustment_v3.txt`（v2 保留）：
- SIG-3 → 下调 `dailyQuestionLimit`（15→10→8）
- SIG-4 → 从"未覆盖知识点清单"中选 `pausedKpIds`
- 未命中对应信号时**不输出**该字段

## 4. 应用流程（`PlanDiffServiceImpl.applyDiff`）

```
1. 校验 pending + 未过期
2. applyGoalStrategy：写目标两列（pausedKpIds 空数组 = 清空）
3. 遍历 modifiedDates：
   ├ date < today               → skippedPast
   ├ 无 daily_plan              → skippedNoPlan（显式日志：生成时生效）
   ├ in_progress / completed    → skippedStarted（历史只读）
   └ pending / pending_generation → 更新 content + 状态
4. confirmed + 调整日志
5. 汇总日志：已更新/无计划/已开始/过去
```

## 5. 每日生成承接

```java
LearningGoal goalEntity = learningGoalMapper.selectById(goalId);
Integer limit = goalEntity.getDailyQuestionLimit();       // null → 15
List<String> paused = parsePausedKpIds(goalEntity.getPausedKpIds());
// 暂缓过滤（为空则忽略，与年级过滤降级一致）
// quota = HomeworkQuotaCalculator.calculate(..., limit)  // 内部夹到 [1,15]
```

`HomeworkQuotaCalculator` 保留原 4 参重载（默认 15），新增 5 参重载，**极端模式也受下调后的上限约束**。

## 6. 前端

触发 → 有 diffId → 拉详情 → `uni.showModal` 展示摘要：

```
连续 2 天未完成作业
每日题量调整为 10 题以内
重排 2 天的作业
```

确认 → `confirmDiff`；取消 → `rejectDiff`（不留 pending 脏数据）。

## 7. 边界

| 场景 | 行为 |
|------|------|
| 无信号 | 后端返回无 diffId → 前端提示"暂无需要调整的内容" |
| 暂缓后无题可出 | 忽略暂缓 |
| 日期无计划 | 计数跳过，策略在生成时生效 |
| 策略越界（0 或 99） | Agent 层忽略；配额层夹到 [1,15] |
| Agent 失败 | 沿用规则 #5 降级：不生成 diff，提示稍后重试 |

## 8. 测试

`PlanDiffServiceTest` +3、`HomeworkServiceTest` +1、`HomeworkQuotaCalculatorTest` +4（新建）。
