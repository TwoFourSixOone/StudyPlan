# Tasks：每日作业读取按学生隔离

> **Change**：`2026-11-19_homework_student_isolation`　**状态**：已实施（2026-11-19）

## T1 计划读取按学生隔离

- [x] `DailyPlanMapper.selectBySubjectAndDate(subjectId, studentId, tenantId, planDate)`：
      WHERE 增加 `lg.student_id = #{studentId}`，JOIN 侧补 `lg.tenant_id = #{tenantId}`
- [x] `HomeworkServiceImpl#getHomeworkByDate` 传入当前 studentId
- **验收**：学生 A 无语文目标时，`by-date` 不再返回他人语文计划（返回空数组）

## T2 提交记录读取纵深防御

- [x] `HomeworkSubmissionDetailMapper.selectByPlanId(planId, studentId, tenantId)`：
      内层子查询与外层查询都带 `student_id = #{studentId}`
- [x] 调用点（`getHomeworkByDate` / `gradeHomework`）传 studentId
- **验收**：即使误传他人 planId，也读不到别人的作答

## T3 回归护栏

- [x] 新增 `HomeworkIsolationSqlTest`（2 例）：反射断严两条 SQL 必须有学生维度过滤
- [x] `HomeworkServiceTest` 新增 1 例：`getHomeworkByDate` 传给 mapper 的是当前 studentId；
      既有 7 处 `selectBySubjectAndDate` / `selectByPlanId` stub 更新为新签名
- [x] `ExamRedoIntegrationTest` 的 stub 同步更新
- **验收**：`mvn test` 全绿（959 通过、跳过 5）

## T4 文档

- [x] 隐性约定 #44：作业读取必须按 `student_id` 隔离（`daily_plan` 无 student_id → 必须 JOIN learning_goal；
      提交明细双条件）；编号推进到 #44，新增从 #45 起
- [x] 产品规则 #3「每日作业」边界条件补"作业按学生隔离，跨学生不可见"
- [x] 本 change 工件（proposal/design/tasks/verify）

## T5 回归与验证

- [x] `mvn -o test` → Tests run: 959, Failures: 0, Errors: 0, Skipped: 5 → BUILD SUCCESS
- [ ] 真机复核：学生 A 账号的每日作业页不再出现他人科目作业（需两个学生账号造数）
