# Tasks：学习目标到期自动过期 + 每日作业生成提示口径

> **Change**：`2026-11-23_goal_expiry_and_homework_hint`　**状态**：已实施（2026-11-23）

## T1 目标到期流转

- [x] 新增 `com.jiang.task.GoalExpiryTask`：`@Scheduled(cron = "0 5 0 * * ?")`，
      `UPDATE learning_goal SET status='expired' WHERE status='active' AND end_date < today`（幂等）
- [x] 异常只 WARN 不外抛（不拖垮调度线程）；租户回退 `default`（与 `DailyHomeworkGenerationTask` 一致）
- **验收**：过期 active 目标被置 `expired`；重复执行影响 0 行

## T2 读路径有效状态兜底

- [x] `GoalServiceImpl#toGoalVO` → `effectiveStatus(goal)`：`active && end_date < today` → 返回 `expired`（不写库）
- **验收**：任务未跑时接口也不会把过期目标返回成 `active`

## T3 小程序：提示口径与状态展示

- [x] `Homework.vue#fetchGoals`：有效目标过滤增加 `endDate >= today`
- [x] `Homework.vue#generateToday`：无有效目标 → toast「没有学习目标，请先创建学习目标」；
      有目标 → 成功 toast「生成作业成功」、全部失败「作业生成失败，请稍后重试」；
      单科目失败改为 `console.debug`，删除「xx作业生成跳过: 无有效学习目标」
- [x] `Homework.vue` 空状态：无有效目标时显示「没有学习目标，请先到「我的」页创建学习目标」+「去创建学习目标」按钮
- [x] `Profile.vue`：历史目标状态改为中文（进行中/已完成/已过期）
- **验收**：`cd student-app && npm run build:mp-weixin` → DONE Build complete

## T4 测试

- [x] 新增 `GoalExpiryTaskTest`（3 例）：有到期目标→返回更新行数；无到期目标→0；底层异常→0 且不抛
- [x] `GoalServiceTest` +2：`active + endDate<today → expired`；`active + endDate>=today → active`（不误伤）
- **验收**：定向 33 例全绿

## T5 文档

- [x] `docs/product/index.md` 规则 #1 边界条件：目标有效性判据、状态展示、每日作业生成提示口径
- [x] `docs/architecture/implicit-contracts.md` 新增 **#48**（编号推进到 #48、新增自 #49）
- [x] 本 change 工件（proposal/design/tasks/verify）

## T6 回归

- [x] `mvn -o test` → Tests run: 1039, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
- [x] `cd student-app && npm run build:mp-weixin` → DONE Build complete
- [ ] 真机复核：过期目标显示「已过期」；生成作业提示「生成作业成功」；无目标账号提示「没有学习目标」
