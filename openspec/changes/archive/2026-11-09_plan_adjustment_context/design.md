# Design：计划调整 Agent 上下文补全与信号检测修复

> **Change 名称**：`2026-11-09_plan_adjustment_context`
> **版本**：v1

---

## 1. 技术方案总览

```
PlanAdjustmentController.trigger
        │
        ▼
PlanAdjustmentServiceImpl.triggerAdjustment
        │  1. 取当前活跃目标 goal
        │  2. signalDetector.detect(goalId)      ← 修复 SIG-2/SIG-4 查询维度
        │  3. 查超期错题（>30 天）
        │  4. buildAdjustmentContext(goalId)     ← 新增：组装真实上下文
        │  5. adjustmentAgent.generateDiff(signals, context, criticalIds)  ← 事务外
        │  6. saveDiff(...)                      ← 仅落库在事务内
        ▼
PlanDiffServiceImpl.confirmDiff
        │  逐条更新 daily_plan.content
        └─ newContent 为空 → status = pending_generation（否则 pending）
```

分层职责保持：Controller 不新增逻辑；Service 负责数据组装与事务；Agent 只负责「Prompt 渲染 + LLM 调用 + 结果校验」；Mapper 只做查询。

---

## 2. 上下文 DTO 设计

`com.jiang.dto.AdjustmentContextDTO`（新增，Lombok `@Builder`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `goalId` / `studentId` / `subjectId` | Long | 目标维度标识 |
| `subjectName` | String | 科目名称（Prompt 展示用，查询不到时为 null） |
| `targetScore` / `direction` | Integer / String | 目标分数与方向描述 |
| `startDate` / `endDate` | LocalDate | 目标周期 |
| `futurePlans` | `List<FuturePlanItem>` | 未来 7 天真实计划（`date` / `status` / `content`） |
| `uncoveredKps` | `List<KpItem>` | 未覆盖知识点（`kpId` / `name`），最多 30 条 |

嵌套类均为 `static` + `@Builder`，避免 Service 层出现长参数构造。

**窗口与截断常量**（`PlanAdjustmentServiceImpl`）：
- `FUTURE_PLAN_DAYS = 7`：`[today, today+6]`，与 Prompt 中「未来 7 天」表述一致
- `UNCOVERED_KP_LIMIT = 30`：防止 Prompt 膨胀

---

## 3. 上下文组装（Service 层）

```java
private AdjustmentContextDTO buildAdjustmentContext(Long goalId, String tenantId) {
    LearningGoal goal = goalMapper.selectById(goalId);   // 不存在 → LEARNING_GOAL_NOT_FOUND
    Subject subject = goal.getSubjectId() == null ? null : subjectMapper.selectById(goal.getSubjectId());
    List<DailyPlan> plans = planMapper.selectPlansBetween(goalId, tenantId, today, today.plusDays(6));
    List<Map<String,Object>> rows = knowledgeStateMapper.selectUncoveredKpList(
            goal.getStudentId(), goal.getSubjectId(), tenantId, UNCOVERED_KP_LIMIT);
    // → 映射为 FuturePlanItem / KpItem
}
```

要点：
- **所有 ID 与名称都来自数据库**，Prompt 只做占位符替换，杜绝模型自造
- `future_plans` 带原始 `content`，让模型在「已有题目引用」基础上改，而不是从零生成
- 未覆盖知识点仅含 `mastery_score IS NULL OR mastery_score = 0` 的行，同组内按 `knowledge_point_id` 升序稳定返回，截断在 SQL 层完成（`LIMIT`）。**注**：由于该结果集内掌握度全部为空/零，按掌握度排序无区分度，故不引入 `ORDER BY mastery`

---

## 4. 信号检测修复

| 信号 | 修复前 | 修复后 |
|------|--------|--------|
| SIG-2 | `selectMaxMasteryDropByGoalId` → `MIN(delta)` 为负，`maxDrop > 0.15` 永不成立 | 改查 `(studentId, subjectId, tenantId)`，取 `MIN(delta)` 后 `Math.abs()` 与阈值比较；描述文案同步取绝对值 |
| SIG-2 / SIG-4 数据源 | 假设 `exam_analysis_result` / `student_knowledge_state` 有 `goal_id` | 改为 `student_id + subject_id` 维度（`exam_paper` 无 `goal_id` 列，这是 `BadSqlGrammar` 根因） |
| SIG-4 | `countUncoveredByGoalId` | `countUncoveredByStudentAndSubject`，语义为 `mastery_score IS NULL OR mastery_score = 0`（掌握度下限为 0.05，`=0` 只可能是初始化占位，计入未覆盖） |

`goal` 为 null 或 `studentId/subjectId` 缺失时，SIG-2/SIG-4 直接返回 `Optional.empty()`，不抛异常打断其它信号检测。

---

## 5. 序列化与状态机

**JSR310**：`PlanAdjustmentServiceImpl` / `PlanDiffServiceImpl` 的静态 `ObjectMapper` 统一改为

```java
new ObjectMapper()
    .registerModule(new JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
```

原因：`PlanDiffDTO` 内含 `LocalDate`，裸 `ObjectMapper` 反序列化 `diff_content` 时抛 `InvalidDefinitionException`，导致 diff 历史列表与确认流程失败。

**状态机**（`PlanDiffServiceImpl.confirmDiff`）：

| `newContent` | 目标状态 | 理由 |
|--------------|---------|------|
| 非空数组 | `pending` | 计划已有题目引用，学生可直接作答 |
| `null` / 空白 / `[]` | `pending_generation` | 该天需重新出题，避免学生看到空作业 |

判断集中在 `isBlankContent()`，不散落在调用点。

---

## 6. 异常处理与降级

| 场景 | 处理 |
|------|------|
| Agent 超时 / 输出校验失败（`AdjustmentException`） | 捕获 → 记 `agent_degradation_log` → 返回空 diff（前端提示"暂无需调整/稍后重试"） |
| 目标不存在 | `BusinessException(LEARNING_GOAL_NOT_FOUND)` |
| diff 已过期 / 非 pending | 沿用既有状态校验，不重复生成 |

**遗留风险（需后续 change 决策）**：`agent.agentscope.timeout` 由 30s 放宽至 120s，与隐性约定 #12「建议 30s」不一致。当前为兼容慢模型临时放宽；长期方案是「LLM 调用异步化 + 任务状态轮询」，而不是继续放大同步超时。

---

## 7. 数据库变更

**无 DDL**。仅新增/修改查询：
- `DailyPlanMapper.selectPlansBetween(goalId, tenantId, start, end)`
- `MasteryChangeLogMapper.selectMaxMasteryDropByStudentAndSubject(studentId, subjectId, tenantId)`
- `StudentKnowledgeStateMapper.selectUncoveredKpList(...)` / `countUncoveredByStudentAndSubject(...)`

上述查询均带 `tenant_id`，符合规则 #7；未新增表，无需更新产品规则附录的数据表清单。

---

## 8. 隐性约定对齐

| 约定 | 落实方式 |
|------|---------|
| #10 事务边界 | `@Transactional` 从 `triggerAdjustment` 移除，改用 `TransactionTemplate` 仅包裹 `saveDiff` |
| #12 Agent 超时降级 | 超时 + `AgentTimeoutException` → 降级空 diff；日志脱敏沿用既有实现 |
| #14 异常分层 | Agent 技术异常在 Service 层消化，不向上透传 |
| #4 时间格式 | `LocalDate` 通过 JSR310 序列化为 ISO 字符串，与前端 `yyyy-MM-dd` 对齐 |
| #11 并发写入 | 「同一 goal 只允许一个 pending diff」仍由应用层判断，未改唯一索引 |
