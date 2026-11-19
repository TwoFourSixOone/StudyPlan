# Proposal：每日作业读取按学生隔离（修复跨学生数据泄漏）

> **Change**：`2026-11-19_homework_student_isolation`

## 背景（2026-11-19 真实现象）

小程序「每日作业」页出现**自己没建目标的科目卡片 + 只读 + 0/15**（截图：数学 15/15 可批改、语文 0/15 只读）。
点进去能看到**别人的题目与作答状态**。前端 `isReadOnly = !hasActiveGoal` 只是"无活跃目标 → 只读"，
真正的问题是后端把**同租户内其它学生**的当日计划返回了。

## 根因

`DailyPlanMapper.selectBySubjectAndDate`（`HomeworkServiceImpl#getHomeworkByDate` 使用）：

```sql
FROM daily_plan dp
JOIN learning_goal lg ON dp.goal_id = lg.goal_id
WHERE lg.subject_id = #{subjectId} AND dp.tenant_id = #{tenantId} AND dp.plan_date = #{planDate}
ORDER BY dp.create_time DESC LIMIT 1
```

- `daily_plan` 表**没有 student_id**，归属在 `learning_goal.student_id`；此处漏了 `lg.student_id`
  → 返回同租户内该科目该日期**任意学生**的计划（`LIMIT 1` 还会随机挑一个）；
- 随后 `HomeworkSubmissionDetailMapper.selectByPlanId(planId, tenantId)` 按 planId 取作答
  → 把**别人的作答**当成"我的作答"返回。

影响面：学生端 `GET /api/homework/by-date`（题面 + 作答 + 批改状态）跨学生泄漏。

## 已确认**不受影响**的路径

- `submitAnswer` → `validatePlanAndGet` 通过 `learning_goal.studentId` 反查校验归属（写路径安全）；
- `gradeHomework` → `GoalService#getActiveGoals` 按 `StudentContext` 的 studentId 过滤后取 goalId（写路径安全）；
- 其它计划查询（`selectByGoalIdAndDate`）以 goalId 为键，调用方均已先做学生归属校验。

## 方案

1. `DailyPlanMapper.selectBySubjectAndDate(subjectId, studentId, tenantId, planDate)`：
   WHERE 增加 `lg.student_id = #{studentId}`，并把 `lg.tenant_id = #{tenantId}` 一并带上（JOIN 侧也隔离）；
2. `HomeworkSubmissionDetailMapper.selectByPlanId(planId, studentId, tenantId)`：
   内外两层都加 `student_id = #{studentId}`（该表已冗余 student_id 且有 `idx_student`），做纵深防御；
3. `HomeworkServiceImpl#getHomeworkByDate` / `#gradeHomework` 传入当前 studentId；
4. 回归护栏：新增 `HomeworkIsolationSqlTest`（反射断严 SQL 必须有 student 过滤）+ 服务层断言
   "查询当日作业传的就是当前 studentId"。

## 不包含

- 前端"只读卡片"的展示口径（无活跃目标仍显示历史作业）保持不变；
- `daily_plan` 加 student_id 列（零 DDL 约定；JOIN 已能表达归属）；
- 存量数据清洗（本次只修读取路径，无需回填）。

## 验收

- 学生 A 无语文目标时，「语文」卡片不再出现别人的作业（或返回空）；
- `mvn test` 全绿（新增 `HomeworkIsolationSqlTest` 2 例 + 服务层 1 例）；
- 学生 A 的作业读取、提交、批改行为与修复前一致（自己的作业不受影响）。
