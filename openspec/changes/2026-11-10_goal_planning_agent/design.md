# Design：目标创建生成整段计划骨架 + 自动调整

> **Change 名称**：`2026-11-10_goal_planning_agent`

---

## 1. 三段式

```
① 创建目标（GoalServiceImpl.createGoal）
   → PlanSkeletonService.generateSkeleton(goalId, tenantId)
       → PlanningAgent.generate(PlanningContextDTO)   [失败 → 模板降级]
       → 批量写 daily_plan（pending_generation，planned_kp_ids / planned_question_count）

② 每日出题（02:00 DailyHomeworkGenerationTask）
   → HomeworkServiceImpl.generateDailyHomework
       → 读当日骨架：知识点 = 骨架 ∩ 可用知识点；题量 = planned_question_count ?? 目标上限 ?? 15
       → 无骨架：维持原逻辑（按掌握度现算）

③ 自动调整（02:30 AutoAdjustmentTask）
   → SignalDetector.detectSignals
   → 命中 → PlanAdjustmentService.triggerAdjustment（生成 diff）
          → PlanDiffService.applyDiff（直接应用，不等确认）
```

## 2. 规划 Agent 契约

输入 `PlanningContextDTO`：科目、周期、目标分数/方向、学生年级、可用知识点（已按年级过滤）+ 掌握度。

输出 `PlanDraft`：

```json
{
  "days": [ { "date": "2026-11-11", "kpIds": ["kp1","kp2"], "questionCount": 12, "focus": "..." } ],
  "reason": "先覆盖基础章节，掌握度低的回补"
}
```

校验（`AgentOutputValidator.validatePlanning`）：`days` 非空、日期格式、`kpIds` 1–4 个、`questionCount` 1–15、`reason` 必填。

**防幻觉**：持久化时只写 `daily_plan` 字段，**不信任 Agent 给的题量之外的内容**；出题时骨架知识点还要与"当前可用知识点"取交集，交集为空则忽略骨架。

## 3. 数据模型（V13）

| 列 | 类型 | 语义 |
|----|------|------|
| `daily_plan.planned_kp_ids` | TEXT | 骨架知识点 JSON 数组；NULL = 未规划 |
| `daily_plan.planned_question_count` | TINYINT | 骨架题量；NULL = 按配额算 |

'## 4. 线程池（隐性约定 #22）

异步细化使用 `planningExecutor`，声明在 `ThreadPoolConfig`（统一工厂 `createExecutor`：有界队列 + CallerRunsPolicy + 命名 + 未捕获异常处理 + 关闭管理器注册），**不单独新建配置类**。参数：`thread-pool.planning.core-size / queue-capacity / await-terminate-seconds`。

## 5. 幂等与降级'

| 场景 | 行为 |
|------|------|
| 骨架重复生成 | 按 `goal_id + plan_date` 存在性跳过；并发由唯一索引兜底 |
| 规划 Agent 失败 | 模板计划：知识点按 `knowledge_point_id` 顺序轮转，每天 2 个、10 题 |
| 无 active 知识点 | 不生成骨架（出题时也会报 1091） |
| 骨架与实时可用知识点无交集 | 忽略骨架，按掌握度现算 |

## 6. 自动调整

- 时间：02:30（在 02:00 出题之后，避免调整刚落库就被出题覆盖判断）
- 范围：`status=active` 且日期覆盖今天的目标
- 幂等：无信号直接跳过；`triggerAdjustment` 内部还有 pending diff 防重
- 隔离：逐目标 try/catch，`StudentContext` finally 清理

## 7. 测试

- `PlanSkeletonServiceTest`：Agent 成功落骨架 / Agent 失败回退模板 / 已存在跳过 / 无可用知识点跳过
- `AutoAdjustmentTaskTest`：无目标 / 无信号 / 命中自动应用 / 单目标失败隔离
- `GoalServiceTest`：补充 `@Mock PlanSkeletonService`，验证创建目标不再失败
