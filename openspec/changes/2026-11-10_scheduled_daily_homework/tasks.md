# Tasks：每日凌晨 2 点自动生成当日作业

> **Change 名称**：`2026-11-10_scheduled_daily_homework`
> **状态**：待实施

---

### Task 1：题库去重窗口 30 → 7 天

- [x] `HomeworkServiceImpl` 近 N 天计划去重窗口改为 7 天
- [x] 日志文案同步

**涉及文件**：`src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`

---

### Task 2：定时任务

- [x] 新增 `DailyHomeworkGenerationTask`（与 `KnowledgePointReviewTask` 同包）
- [x] `@Scheduled(cron = "0 0 2 * * ?")`
- [x] 查询 active 且日期覆盖今天的目标；已有当日计划则跳过
- [x] 循环内设置/清理 `StudentContext`，逐目标异常隔离
- [x] 输出汇总日志（总数/成功/跳过/失败）

**涉及文件**：
- `src/main/java/com/jiang/task/DailyHomeworkGenerationTask.java`（新增）

---

### Task 3：测试

- [x] `DailyHomeworkGenerationTaskTest`：无目标不调用 / 已有计划跳过 / 无计划调用 / 单个失败不影响后续

**涉及文件**：
- `src/test/java/com/jiang/task/DailyHomeworkGenerationTaskTest.java`（新增）

---

### Task 4：文档

- [x] 产品规则 #3：补充自动生成触发方式与幂等规则
- [x] `openspec/specs/learning-loop.md`：每日作业触发入口

---

### Task 5：构建与联调

- [x] `mvn test` 全量通过
- [ ] 联调：把 cron 临时改为近 1 分钟验证一次，确认计划自动生成；恢复为 02:00
