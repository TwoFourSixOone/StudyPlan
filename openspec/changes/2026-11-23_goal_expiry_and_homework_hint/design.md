# Design：学习目标到期自动过期 + 每日作业生成提示口径

> **Change**：`2026-11-23_goal_expiry_and_homework_hint`

## D1 目标有效性语义（单一判据）

**目标"有效"必须同时满足：`status = 'active'` 且 `start_date <= today <= end_date`。**

- `getActiveGoals(subjectId)` 已有 `end_date >= today` 过滤 → 生成/批改链路本身是安全的；
- 缺的是**状态流转**（DB 里过期目标仍是 active）与**展示口径**（前端直接把 code 显示出来、且用 status 判可生成）。

## D2 到期流转：定时任务 + 读路径兜底

```
GoalExpiryTask（cron 0 5 0 * * ?，每日 00:05）
  UPDATE learning_goal SET status='expired'
  WHERE status='active' AND end_date < today
```

- **幂等**：只更新 `active`，重复执行影响 0 行；
- **租户**：任务线程无请求上下文 → 租户插件按 `TenantHandler` 回退 `default`（与既有
  `DailyHomeworkGenerationTask` 一致；真实多租户需按租户遍历，已在文档标注）；
- **不写库的兜底**：`GoalServiceImpl#toGoalVO` 对 `active && end_date < today` 的目标返回 `status=expired`，
  保证 00:05 之前 UI 也不会显示"仍进行中"；DB 由任务负责，避免读接口写库（并发/事务成本）。

## D3 前端提示口径

| 场景 | 提示 |
|---|---|
| 无任何学习目标（`activeGoals` 为空 + 当日无作业） | 空状态「没有学习目标，请先到「我的」页创建学习目标」+ 按钮「去创建学习目标」（`switchTab` 到我的页） |
| 至少一个有效目标，生成成功（≥1 个科目成功） | toast「生成作业成功」 |
| 有目标但全部失败 | toast「作业生成失败，请稍后重试」（不再逐科目喊"跳过"） |
| 单个科目失败 | 仅 `console.debug`（供排障），不再输出"无有效学习目标"这类误导文案 |

## D4 为什么不是"删除该目标"或"禁止创建短期目标"

- 历史目标需要可回看（产品规则：历史目标列表）；
- 短期目标（如 11-05 ~ 11-06）是合法输入，问题只在状态流转与提示，不改创建校验。

## D5 影响面

| 层 | 文件 |
|---|---|
| 定时任务 | 新增 `com.jiang.task.GoalExpiryTask`（+ 单测） |
| 服务 | `GoalServiceImpl#toGoalVO`（有效状态映射，不改 DB） |
| 小程序 | `Homework.vue`（activeGoals 过滤 + 生成提示 + 无目标空状态）、`Profile.vue`（状态中文化） |
| 文档 | 产品规则（目标状态与提示口径）、隐性约定、本 change 工件 |

## D6 测试

| 层 | 用例 |
|---|---|
| `GoalExpiryTaskTest` | 有到期 active 目标 → 调用 `update` 且返回行数；无到期目标 → 返回 0 且不报错；异常不抛出（任务不可拖垮调度） |
| `GoalServiceTest` | `active + endDate<today` → VO.status=`expired`；`active + endDate>=today` → `active`；`completed` 原样 |
| 前端 | 无自动化测试（uni-app 页面），按 proposal 的验收手工冒烟 + `build:mp-weixin` 通过 |
