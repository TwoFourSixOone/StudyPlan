# Proposal：学习目标到期自动过期 + 每日作业生成提示口径

> **Change**：`2026-11-23_goal_expiry_and_homework_hint`

## 现象（2026-11-23 真机）

1. 「我的」页历史目标里，一个结束日期为 `2026-11-06` 的目标（今天 11-23）**仍显示 `active`**；
2. 每日作业页点「生成今日作业」，控制台出现「英语作业生成跳过: 无有效学习目标，请先设定学习目标」——
   这个提示既有害又误导：学生明明有英语目标，只是**已过期**；
3. 需求口径：**没有任何学习目标**时提示「没有学习目标」；**有目标**时生成成功提示「生成作业成功」，
   不要再出现「跳过: 无有效学习目标」这类提示。

## 根因

| 现象 | 根因 |
|---|---|
| 过期目标仍 `active` | `GoalStatus.EXPIRED` 枚举存在，但**没有任何代码把 `active` 流转为 `expired`**（无定时任务、无惰性更新），DB 里一直是 `active` |
| 前端把它当"可生成" | `Homework.vue#fetchGoals` 用 `g.status === 'active'` 过滤，不判 `endDate`；后端 `getActiveGoals` 其实已有 `endDate >= today` 过滤，于是前端用过期目标调生成接口 → 必然 `LEARNING_GOAL_NOT_FOUND(1057)` → 控制台打"跳过"日志 |
| 状态显示为英文 `active` | `Profile.vue` 直接渲染 `{{ g.status }}`（原始 code），未做中文映射 |

## 方案

1. **到期自动过期**：新增 `GoalExpiryTask`（每日 00:05）把 `status=active AND end_date < today` 批量置 `expired`；
2. **读路径兜底**：`GoalServiceImpl#toGoalVO` 对"DB 仍是 active 但已过期"的目标返回 `status=expired`
   （定时任务跑之前 UI 也不会显示错状态；不写库，避免读路径写操作）；
3. **前端判定补齐**：`Homework.vue` 的 `activeGoals` 过滤增加 `endDate >= today`（双保险）；
4. **提示口径**：
   - 无任何学习目标 → 空状态提示「没有学习目标，请先到「我的」页创建学习目标」+「去创建学习目标」按钮；
   - 有目标 → 生成后提示「生成作业成功」；全部失败 → 「作业生成失败，请稍后重试」；
   - 删除误导性的「xx作业生成跳过: 无有效学习目标」日志；
5. **状态中文化**：`Profile.vue` 历史目标状态显示 `进行中 / 已完成 / 已过期`（保留原 CSS class）。

## 不在本次范围

- 目标到期后的**计划/作业数据清理**（历史 `daily_plan` 保留可查，符合"历史目标可回看"语义）；
- 到期目标对错题/掌握度的连带处理（`syncPauseStatus` 已有独立逻辑）；
- 多租户下按租户遍历（定时任务与既有 `DailyHomeworkGenerationTask` 一致，取默认租户，已记入文档）。

## 验收

- 过期的 active 目标：定时任务执行后 DB `status=expired`；任务未跑时接口已返回 `status=expired`；
- 「我的」页状态显示中文；过期目标不再出现在"可生成"集合里；
- 无目标时点生成（或空页面）→ 提示「没有学习目标」；
- 有目标时生成 → 提示「生成作业成功」；控制台不再出现「跳过: 无有效学习目标」；
- `mvn -o test` 与 `student-app build:mp-weixin` 通过。
