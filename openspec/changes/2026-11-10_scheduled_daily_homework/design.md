# Design：每日凌晨 2 点自动生成当日作业

> **Change 名称**：`2026-11-10_scheduled_daily_homework`

---

## 1. 组件

`com.jiang.task.DailyHomeworkGenerationTask`（`@Component`，与既有 `KnowledgePointReviewTask` 同包）：

```java
@Scheduled(cron = "0 0 2 * * ?")
public void generateTodayHomework() {
    LocalDate today = LocalDate.now();
    List<LearningGoal> goals = learningGoalMapper.selectList(
        new LambdaQueryWrapper<LearningGoal>()
            .eq(LearningGoal::getStatus, GoalStatus.ACTIVE.getCode())
            .le(LearningGoal::getStartDate, today)
            .ge(LearningGoal::getEndDate, today));
    for (LearningGoal goal : goals) {
        // 幂等：当日计划已存在则跳过
        if (dailyPlanMapper.selectByGoalIdAndDate(goal.getGoalId(), tenantId, today) != null) continue;
        try {
            StudentContext.setStudentId(goal.getStudentId());
            StudentContext.setTenantId(tenantId);
            homeworkService.generateDailyHomework(goal.getStudentId(), tenantId, goal.getSubjectId(), today);
        } catch (Exception e) {
            log.error(...);   // 逐目标隔离
        } finally {
            StudentContext.clear();
        }
    }
}
```

## 2. 关键决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 幂等条件 | **当日计划存在即跳过**（不论状态） | 凌晨重跑不能覆盖学生已作答/进行中的作业 |
| 身份传递 | 循环内设置 `StudentContext`，`finally` 清理 | `generateDailyHomework` 内部通过 `getActiveGoalOrThrow` 读上下文；定时线程无请求上下文 |
| 目标筛选 | `status=active` 且日期覆盖今天 | 与规则 #1 的"目标有效期"一致 |
| 租户 | 取 `goal.getTenantId()` | 不硬编码 default |
| 失败处理 | 单目标 try/catch，最后输出成功/失败计数 | 一个学生数据异常不能拖垮整批 |
| 与 2:00 的另一个任务 | 保持同一时刻 | 不同表、无锁冲突；如需错峰改 cron 即可 |

## 3. 题库去重窗口

`HomeworkServiceImpl` 的"近 N 天计划已排入题目"窗口由 **30 天改为 7 天**：

- 题库每知识点仅 4 道题，30 天排除会让第 2 天起几乎无题可抽
- 7 天意味着"一周内不重复"，与艾宾浩斯错题周期（1/3/7/15/30）的前段节奏一致

## 4. 测试策略

| 用例 | 断言 |
|------|------|
| 无进行中目标 | 不调用 `generateDailyHomework` |
| 目标已有当日计划 | 跳过，不调用 |
| 目标无计划 | 调用一次，参数为 `(studentId, tenantId, subjectId, today)` |
| 某目标生成抛异常 | 后续目标仍继续，任务不整体失败 |

## 5. 文档改动

- 产品规则 #3 / #1：补充"每日 02:00 自动生成当日作业"的触发方式
- `openspec/specs/learning-loop.md`：每日作业生成章节补触发入口
