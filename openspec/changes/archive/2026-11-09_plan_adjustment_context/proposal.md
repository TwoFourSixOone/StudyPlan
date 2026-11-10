# Proposal：计划调整 Agent 上下文补全与信号检测修复

> **Change 名称**：`2026-11-09_plan_adjustment_context`
> **创建日期**：2026-11-09（工件于 2026-11-10 补齐，与已落地代码对齐）
> **版本**：v1
> **状态**：代码已实施，待 verify

---

## 1. 需求背景

联调「计划调整」链路时暴露 5 个缺陷，均已定位到具体代码：

1. **调整 Agent 信息不足**：`generateDiff` 只传入 `goalId` 与触发信号，LLM 看不到目标详情、未来计划、未覆盖知识点，只能凭空编造日期与知识点 ID；确认后幻觉数据被写入 `daily_plan.content`，违反产品规则 #8（Agent 只能引用已启用知识点）。
2. **SIG-2 永不触发**：`selectMaxMasteryDropByGoalId` 取 `MIN(delta)` 得到负数（如 -0.3），而代码判断 `maxDrop > threshold`，负数永远不满足。
3. **SIG-2 / SIG-4 抛 BadSqlGrammar**：两者按 `goal_id` 关联 `exam_analysis_result` / `student_knowledge_state`，但这两张表没有 `goal_id` 列，SQL 直接报错。
4. **PlanDiffDTO 反序列化失败**：DTO 含 `LocalDate`，`PlanDiffDTO` 与 diff 历史 VO 转换使用裸 `ObjectMapper`，缺 JSR310 模块，读取 `diff_content` 时抛异常。
5. **确认 diff 后学生看到空作业**：`newContent` 为空数组时仍把计划置为 `pending`，但 content 无题目引用，出题 Agent 不会再介入。

## 2. 核心目标

1. 调整 Agent 基于 Service 层组装的**真实数据**生成 diff，不再凭空编造
2. 修正 SIG-2 的方向判断与 SIG-2/SIG-4 的查询维度，使信号能真实触发
3. 修复 diff 的 JSON 序列化/反序列化（JSR310）
4. 空内容计划日改为 `pending_generation`，交由出题 Agent 重新生成

## 3. 功能范围

### 包含

| 模块 | 变更内容 |
|------|---------|
| DTO | 新增 `AdjustmentContextDTO`（目标详情 + 未来 7 天计划 + 未覆盖知识点，含嵌套 `FuturePlanItem` / `KpItem`） |
| Agent | `AdjustmentAgent.generateDiff` 签名由 `(signals, goalId, criticalIds)` 改为 `(signals, context, criticalIds)`；`AdjustmentAgentImpl` 改用 `adjustment_v2.txt` 并渲染 5 段上下文 |
| Service | `PlanAdjustmentServiceImpl.buildAdjustmentContext()`：查 goal → subject → 未来 7 天 `daily_plan` → 未覆盖知识点（截断 30 条） |
| Service | `PlanDiffServiceImpl`：注册 JavaTimeModule；`newContent` 为空（null/空白/`[]`）时状态置 `pending_generation` |
| 信号 | `SignalDetectorImpl`：SIG-2 取绝对值比较；SIG-2/SIG-4 改为 学生 + 科目 维度查询 |
| Mapper | `DailyPlanMapper.selectPlansBetween`、`MasteryChangeLogMapper.selectMaxMasteryDropByStudentAndSubject`、`StudentKnowledgeStateMapper.selectUncoveredKpList` / `countUncoveredByStudentAndSubject` |
| Prompt | 新增 `prompts/versions/adjustment_v2.txt`（含 `{goalInfo}`/`{futurePlans}`/`{uncoveredKps}` 等占位符） |
| 配置 | `agent.agentscope.timeout` 30s → 120s |

### 不包含

- 计划调整 diff 的前端确认/拒绝交互页（当前仅触发 + 历史列表）
- Prompt 效果调优与多轮对话式调整
- 调整历史的分页改造
- **范围外杂项**：`application-dev.yml`（本地 DB 口令）、`V3__error_question_table.sql`（排序规则改为 `utf8mb4_general_ci`）属独立 chore，不在本 change 内

## 4. 隐性约定检查

| 约定 / 规则 | 是否涉及 | 说明 |
|------|---------|------|
| #10 事务边界精确控制 | ✅ | Agent 调用在事务外，仅 `saveDiff` 落库在事务内（`TransactionTemplate`） |
| #12 Agent 调用超时降级 | ⚠️ | 降级事件已写入 `agent_degradation_log`（脱敏 + 截断 500 字符）；仅超时值由 30s 放宽到 120s 与建议值不符（见设计 §6 遗留风险） |
| #14 Agent 技术异常与业务异常分层 | ✅ | `AdjustmentException` 由 Service 捕获后降级为空 diff，不向上透传 |
| #1 统一响应结构 | ✅ | 未新增接口，沿用 `Result<T>` |
| 规则 #5 计划调整只输出 diff | ✅ | 仍只输出需修改的日期，未改为整份重生成 |
| 规则 #8 Agent 只引用已启用知识点 | ✅ | 未覆盖知识点清单由 Service 查询后注入，禁止 LLM 自造 ID |
| 规则 #6 Agent 输出结构化可校验 | ✅ | 仍走 `adjustment_schema.json` 校验 |

## 5. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|---------|
| 未来 7 天计划未生成时上下文为空，Agent 输出仍可能偏离 | 中 | Prompt 明确"仅基于真实数据"；无未来计划时应输出空 diff（待补充用例） |
| 未覆盖知识点截断 30 条，可能漏掉应优先覆盖的知识点 | 低 | 按掌握度升序取前 30；数量上限由 `UNCOVERED_KP_LIMIT` 常量集中控制 |
| 超时放宽到 120s，线程池在高峰期可能被长时间占用 | 中 | 见设计 §6，需在后续 change 中改为分段超时或异步任务化 |
| 无对应单元测试覆盖新增上下文组装逻辑 | 高 | 本 change 未补测试，需在 verify 前补齐（见 tasks.md Task 6） |

## 6. 前置依赖

- `2026-11-03_plan_adjustment`（已归档）— diff 表结构、触发信号定义、确认流程
