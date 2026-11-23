# Verify：学习目标到期自动过期 + 每日作业生成提示口径

> **Change**：`2026-11-23_goal_expiry_and_homework_hint`　**验证**：2026-11-23（单测 + 小程序构建；真机待跑）

## 现象 → 根因 → 修复

| 现象 | 根因 | 修复 |
|---|---|---|
| 「提高英语成绩 2026-11-05 ~ 2026-11-06」已过期，仍显示 `active` | `GoalStatus.EXPIRED` 只有枚举、**无任何流转代码**（无定时任务/无惰性更新） | 新增 `GoalExpiryTask`（每日 00:05 批量置 expired，幂等）+ `toGoalVO` 读路径兜底按有效状态返回 |
| 生成作业时"还能找到"该目标并报「无有效学习目标，请先设定学习目标」 | 小程序 `fetchGoals` 用 `g.status === 'active'` 过滤（不判 `endDate`）→ 传过期目标调 `/api/homework/generate` → 后端 `getActiveGoals` 有 `end_date >= today` 过滤 → 抛 `LEARNING_GOAL_NOT_FOUND(1057)` → 前端打"跳过"日志 | 前端过滤加 `endDate >= today`；提示口径改为「无目标→没有学习目标 / 有目标→生成作业成功」；单科目失败仅 `console.debug` |
| 状态以英文 code 展示 | `Profile.vue` 直接渲染 `{{ g.status }}` | 改为 `goalStatusLabel()`：进行中/已完成/已过期 |

## 判定口径（单一事实源）

**目标有效 = `status='active'` 且 `start_date ≤ 今天 ≤ end_date`**（写入隐性约定 #48）。

## 实现清单

| 层 | 文件 | 说明 |
|---|---|---|
| 定时任务 | `src/main/java/com/jiang/task/GoalExpiryTask.java`（新增） | `0 5 0 * * ?`；`status=active AND end_date < today → expired`；异常 WARN 不外抛 |
| 服务 | `GoalServiceImpl#toGoalVO` / `#effectiveStatus` | `active && endDate < today` → 对外 `expired`（不写库） |
| 小程序 | `Homework.vue` | 有效目标过滤 + 生成提示 + 无目标空状态与「去创建学习目标」 |
| 小程序 | `Profile.vue` | 状态中文映射 |
| 文档 | `docs/product/index.md` 规则 #1、`implicit-contracts.md` #48 | 判据 / 展示 / 提示口径 |

## 测试

```
mvn -o '-Dtest=GoalExpiryTaskTest,GoalServiceTest' test → Tests run: 33, Failures: 0, Errors: 0
mvn -o test                                          → Tests run: 1039, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
cd student-app && npm run build:mp-weixin            → DONE Build complete
```

| 用例 | 覆盖 |
|---|---|
| `GoalExpiryTaskTest`（3） | 有到期目标→批量 update 返回行数；无到期目标→0；mapper 异常→0 且不抛 |
| `GoalServiceTest` +2 | `active + endDate<today` → VO `expired`；`active + endDate>=today` → `active`（不误伤） |

## 未验证

| 项 | 说明 |
|---|---|
| 真机复核 | 过期目标显示「已过期」、生成作业提示「生成作业成功」、无目标账号提示「没有学习目标」 |
| 定时任务实跑 | 00:05 的 cron 未在真实环境触发过；逻辑已由单测覆盖（按日期参数执行），生产可通过日志「目标到期流转完成: N 个」确认 |
| 多租户 | 任务线程无上下文 → 租户回退 `default`（与 `DailyHomeworkGenerationTask` 一致）；真实多租户需按租户遍历（已记录，未实现） |
| 存量数据 | 已过期但仍是 `active` 的行：任务首跑即会批量修正（无需手工 SQL）；如需立即生效可手工执行同一条 UPDATE |

## 已知取舍

- **不删过期目标与其历史作业**：历史目标/作业保留可回看（产品规则 #1：历史数据只读）；
- **不静默修正 DB 于读路径**：`toGoalVO` 只改返回状态，落库交给定时任务（避免读接口写库与并发问题）；
- **短期目标仍允许创建**：问题在状态流转与提示，不在创建校验。
