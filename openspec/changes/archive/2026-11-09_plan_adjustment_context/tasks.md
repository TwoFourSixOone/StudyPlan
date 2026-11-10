# Tasks：计划调整 Agent 上下文补全与信号检测修复

> **Change 名称**：`2026-11-09_plan_adjustment_context`
> **版本**：v1
> **状态**：Task 1-6 已完成（代码在工作区，未提交），`mvn test` 全绿

---

## 任务清单

### Task 1：上下文 DTO 与 Prompt v2

- [x] 新增 `AdjustmentContextDTO`（含 `FuturePlanItem` / `KpItem` 嵌套类）
- [x] 新增 `prompts/versions/adjustment_v2.txt`，占位符 `{signals}` / `{goalInfo}` / `{futurePlans}` / `{uncoveredKps}` / `{criticalErrorQuestions}`
- [x] `AdjustmentAgentImpl` 加载 v2 模板并渲染上下文（v1 保留不删，便于回滚）
- [x] `AgentScopeClient` 增加 debug 级完整 Prompt 日志（便于核对上下文是否真正注入）

**涉及文件**：
- `src/main/java/com/jiang/dto/AdjustmentContextDTO.java`（新增）
- `src/main/resources/prompts/versions/adjustment_v2.txt`（新增）
- `src/main/java/com/jiang/agent/impl/AdjustmentAgentImpl.java`
- `src/main/java/com/jiang/client/AgentScopeClient.java`

**验证**：`mvn compile` 通过

---

### Task 2：Agent 接口签名变更

- [x] `AdjustmentAgent.generateDiff` 第二参数由 `Long goalId` 改为 `AdjustmentContextDTO context`
- [x] `PlanAdjustmentServiceImpl` 调用点同步改为传入 context

**涉及文件**：
- `src/main/java/com/jiang/agent/AdjustmentAgent.java`
- `src/main/java/com/jiang/agent/impl/AdjustmentAgentImpl.java`
- `src/main/java/com/jiang/service/impl/PlanAdjustmentServiceImpl.java`

**验证**：`mvn compile` 通过

---

### Task 3：上下文组装

- [x] `buildAdjustmentContext(goalId, tenantId)`：goal → subject → 未来 7 天计划 → 未覆盖知识点（LIMIT 30）
- [x] 新增 `DailyPlanMapper.selectPlansBetween`
- [x] 新增 `StudentKnowledgeStateMapper.selectUncoveredKpList`

**涉及文件**：
- `src/main/java/com/jiang/service/impl/PlanAdjustmentServiceImpl.java`
- `src/main/java/com/jiang/mapper/DailyPlanMapper.java`
- `src/main/java/com/jiang/mapper/StudentKnowledgeStateMapper.java`

**验证**：`mvn compile` 通过；真实库 SQL 可执行（无 `goal_id` 关联）

---

### Task 4：信号检测修复

- [x] SIG-2 取 `Math.abs(MIN(delta))` 与阈值比较，描述文案同步
- [x] SIG-2 / SIG-4 改按 学生 + 科目 维度查询
- [x] goal 缺少 `studentId` / `subjectId` 时返回空信号，不抛异常
- [x] 新增 `MasteryChangeLogMapper.selectMaxMasteryDropByStudentAndSubject`、`StudentKnowledgeStateMapper.countUncoveredByStudentAndSubject`

**涉及文件**：
- `src/main/java/com/jiang/service/impl/SignalDetectorImpl.java`
- `src/main/java/com/jiang/mapper/MasteryChangeLogMapper.java`
- `src/main/java/com/jiang/mapper/StudentKnowledgeStateMapper.java`

**验证**：`mvn compile` 通过；SIG-2 在掌握度真实下降时能命中

---

### Task 5：序列化与计划状态

- [x] `PlanAdjustmentServiceImpl` / `PlanDiffServiceImpl` 的 `ObjectMapper` 注册 `JavaTimeModule` 并关闭时间戳序列化
- [x] `confirmDiff` 中 `newContent` 为空时置 `pending_generation`，非空置 `pending`
- [x] 新增 `isBlankContent()` 统一空内容判定
- [x] Agent 异常时写 `agent_degradation_log`（`agent_type=adjustment`、`degradation_action=return_empty_diff`，脱敏 + 截断 500 字符，写入失败不阻断降级）
- [x] `agent.agentscope.timeout` 30s → 120s

**涉及文件**：
- `src/main/java/com/jiang/service/impl/PlanAdjustmentServiceImpl.java`
- `src/main/java/com/jiang/mapper/AgentDegradationLogMapper.java`（注入使用，无改动）
- `src/main/java/com/jiang/service/impl/PlanDiffServiceImpl.java`
- `src/main/resources/application.yml`

**验证**：diff 历史列表可正常反序列化含日期字段的 `diff_content`

---

### Task 6：单元测试

- [x] `SignalDetectorTest`：SIG-2 负值下降能命中 / 未达阈值不命中 / goal 缺 subjectId 时跳过且不发起按科目查询 / SIG-4 容量不足命中 / SIG-3 连续未完成命中 / 全不命中返回空
- [x] `PlanAdjustmentServiceTest`：无信号不调 Agent / pending diff 冲突 / 上下文含目标与未来计划 / 未覆盖知识点按 30 截断 / 目标缺失抛错 / Agent 异常降级并写脱敏降级日志（含 500 字符截断）/ 未来计划为空
- [x] `PlanDiffServiceTest`：`[]` → `pending_generation` / 非空 → `pending` / 过去日期跳过 / 非 pending 拒绝 / 成功写 confirmed 并追加日志

**涉及文件**：
- `src/test/java/com/jiang/service/SignalDetectorTest.java`（新增）
- `src/test/java/com/jiang/service/PlanAdjustmentServiceTest.java`（新增）
- `src/test/java/com/jiang/service/PlanDiffServiceTest.java`（新增）

**验证**：`mvn test` 全绿（520 tests / 0 failures）

---

## 范围外说明

- `src/main/resources/application-dev.yml`（本地 DB 口令字面量）与 `V3__error_question_table.sql`（排序规则改 `utf8mb4_general_ci`）不属于本 change，按 chore 单独提交。

## 验收标准

1. Task 1-6 代码可编译，`mvn test` 通过
2. 触发计划调整时，日志中可见 context 包含未来计划条数与未覆盖知识点条数
3. 掌握度真实下降 > 0.15 时 SIG-2 能命中
4. 确认含空内容日期的 diff 后，该日 `daily_plan.status = pending_generation`
5. diff 历史接口不再因日期字段反序列化失败
