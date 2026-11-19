# Verify：每日作业读取按学生隔离

> **Change**：`2026-11-19_homework_student_isolation`　**验证**：2026-11-19（本地单测；真机双账号复核待跑）

## 缺陷复盘（现象 → 根因 → 修复）

| 现象 | 根因 | 修复 |
|---|---|---|
| 每日作业页出现"自己没建目标的科目 + 只读 + 0/15"，点进去能看到别人的题目与作答 | `DailyPlanMapper.selectBySubjectAndDate` 的 WHERE 只有 `subject_id + tenant_id + plan_date`，`ORDER BY create_time DESC LIMIT 1` 随机命中同租户**其它学生**的计划；随后按 planId 取作答 → 他人作答一并返回 | 增加 `lg.student_id = #{studentId}`（并补 `lg.tenant_id`）；`selectByPlanId` 内/外层都加 `student_id` 过滤 |

## 需求 → 实现

| 需求 | 实现 |
|---|---|
| 计划读取按学生隔离 | `DailyPlanMapper.selectBySubjectAndDate(subjectId, studentId, tenantId, planDate)` JOIN `learning_goal` 按 `lg.student_id` 过滤 |
| 提交记录纵深防御 | `HomeworkSubmissionDetailMapper.selectByPlanId(planId, studentId, tenantId)` 内外两层带 `student_id`（表已有该列与 `idx_student`） |
| 调用方传当前学生 | `HomeworkServiceImpl#getHomeworkByDate` / `#gradeHomework` 使用入参 studentId（上游来自 `StudentContext`，不接受客户端参数） |
| 回归护栏 | `HomeworkIsolationSqlTest`（SQL 反射）+ `HomeworkServiceTest`（服务层断言 studentId 传入） |

## 不受影响的路径（已核对）

- `submitAnswer`：`validatePlanAndGet` 经 `learning_goal.student_id` 反查校验归属；
- `gradeHomework`：`GoalService#getActiveGoals` 按 `StudentContext.studentId` 过滤后取 goalId；
- 其它计划查询 `selectByGoalIdAndDate`：以 goalId 为键，调用方均已做学生归属校验。

## 测试

```
mvn test → Tests run: 959, Failures: 0, Errors: 0, Skipped: 5 → BUILD SUCCESS
```

| 用例 | 覆盖 |
|---|---|
| `HomeworkIsolationSqlTest`（2） | 当日计划 SQL 必须 JOIN learning_goal 且含 `lg.student_id`；提交记录 SQL 内外两层都含 `student_id` |
| `HomeworkServiceTest#should_scopeHomeworkByStudent_when_getByDate`（+1） | `getHomeworkByDate` 传给 mapper 的是当前 studentId（计划与提交两条查询） |
| `HomeworkServiceTest`（既有 25 例）/ `ExamRedoIntegrationTest`（7 例） | 新签名下既有行为不变（自己的作业读取/提交/批改/重做） |

## 未验证

| 项 | 说明 |
|---|---|
| 真机双账号复核 | 需造两个学生账号（A 有数学目标、B 有语文目标），确认 A「每日作业」不再出现语文作业、`by-date?subjectId=语文` 返回空数组 |
| 存量数据 | 本次只修读取路径，无需回填；历史误读不会落库 |

## 已知取舍

- 一个科目存在多个并行活跃目标时，仍取"该学生该科目该日期最新创建的一条计划"（沿用现状）；
  若产品要"多目标并行"，需改为按 goalId 列表取计划（另立 change）。
- 不为 `daily_plan` 增加 `student_id` 列（零 DDL 约定）：归属经 `learning_goal` 表达，SQL 已按索引可覆盖。
