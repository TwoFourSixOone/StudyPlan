# Verify Report：计划调整 Agent 上下文补全与信号检测修复

> **Change ID**: `2026-11-09_plan_adjustment_context`
> **校验日期**: 2026-11-10（首检 ⚠️ → 修复后复检）
> **校验结论**: ✅ **对齐** — 可以进入归档

---

## 前置检查

| 检查项 | 结果 |
|--------|------|
| `openspec/changes/` 下存在进行中的 change | ✅ |
| 包含 proposal.md / design.md / tasks.md | ✅ |
| 代码已实施 | ✅（12 个后端文件，工作区未提交） |

---

## Proposal 对齐（8 个功能点）

| # | 功能点 | 状态 | 代码位置 |
|---|--------|------|---------|
| 1 | 新增 `AdjustmentContextDTO`（含 `FuturePlanItem` / `KpItem`） | ✅ | `src/main/java/com/jiang/dto/AdjustmentContextDTO.java` |
| 2 | `generateDiff` 签名改为 `(signals, context, criticalIds)` | ✅ | `agent/AdjustmentAgent.java:26-27`、`agent/impl/AdjustmentAgentImpl.java` |
| 3 | `buildAdjustmentContext()` 组装目标 + 未来 7 天 + 未覆盖知识点 | ✅ | `service/impl/PlanAdjustmentServiceImpl.java:156-204` |
| 4 | `PlanDiffServiceImpl` 注册 JSR310 | ✅ | `service/impl/PlanDiffServiceImpl.java:39-42` |
| 5 | 空 `newContent` → `pending_generation` | ✅ | `service/impl/PlanDiffServiceImpl.java:106-110`、`isBlankContent():125-127` |
| 6 | SIG-2 取绝对值 + SIG-2/SIG-4 改 学生+科目 维度 | ✅ | `service/impl/SignalDetectorImpl.java:91-112`、`:154-183` |
| 7 | 三个 Mapper 新增查询 | ✅ | `DailyPlanMapper`、`MasteryChangeLogMapper`、`StudentKnowledgeStateMapper` |
| 8 | 新增 `adjustment_v2.txt` 并实际加载 | ✅ | `prompts/versions/adjustment_v2.txt`、`AdjustmentAgentImpl.java:190` |

> 补充核对：v2 模板的 5 个占位符 `{signals}`/`{goalInfo}`/`{futurePlans}`/`{uncoveredKps}`/`{criticalErrorQuestions}` 与 `AdjustmentAgentImpl` 的 5 处 `replace()` 一一对应，无遗漏、无多余。

---

## Design 对齐

| 设计要点 | 状态 | 说明 |
|---------|------|------|
| §1 调用流程（信号 → 错题 → 上下文 → Agent 事务外 → saveDiff 事务内） | ✅ | `triggerAdjustment` 顺序一致，`TransactionTemplate` 仅包裹 `saveDiff` |
| §2 上下文 DTO 字段 | ✅ | 11 个字段与设计表一致；`subjectName` 查不到时为 null |
| §2 窗口/截断常量 | ✅ | `FUTURE_PLAN_DAYS=7`、`UNCOVERED_KP_LIMIT=30` |
| §3 窗口区间 `[today, today+6]` | ✅ | `today.plusDays(FUTURE_PLAN_DAYS - 1)` |
| §3 「未覆盖知识点排序」 | ✅ 已修正 | 实现为 `ORDER BY kp.knowledge_point_id ASC`；该结果集 mastery 全为 NULL/0，按掌握度排序无区分度，design 已改为如实描述（D-2 修复） |
| §3 「截断在 SQL 层 `LIMIT`」 | ✅ | `selectUncoveredKpList` 末尾 `LIMIT #{limit}` |
| §4 SIG-2 `Math.abs()` 比较 | ✅ | 描述文案同步取绝对值 |
| §4 SIG-2/SIG-4 换维度 | ✅ | 均改为 `(studentId, subjectId, tenantId)`，`exam_paper` 不再参与 |
| §4 SIG-4 语义 | ✅ 已修正 | SQL 为 `mastery_score IS NULL OR mastery_score = 0`（掌握度下限 0.05，`=0` 只可能是初始化占位），design 已同步（D-3 修复） |
| §5 JSR310 序列化 | ✅ | 两处静态 `ObjectMapper` 均注册模块并关闭时间戳 |
| §5 状态机（空 → `pending_generation`，非空 → `pending`） | ✅ | `isBlankContent()` 覆盖 null/空白/`[]` |
| §6 Agent 异常 → 降级空 diff | ✅ | `catch (AdjustmentException)` → `AdjustmentTriggerVO.noAdjustment()` |
| §6 Agent 异常 → **记 `agent_degradation_log`** | ✅ 已补齐 | `recordDegradation()`：`agent_type=adjustment`、`degradation_action=return_empty_diff`、`input_summary` 仅含 goalId/信号类型/错题数，`failure_reason` 脱敏 + 截断 500 字符；写入失败仅记 error 不阻断降级（D-1 修复） |
| §6 超时 30s → 120s | ✅（有遗留风险） | `application.yml:143` 已改，与隐性约定 #12「建议 30s」冲突，design 已登记为遗留风险 |
| §7 无 DDL | ✅ | 无新增脚本；新查询均带 `tenantId` 参数 |
| §8 隐性约定对齐表 | ✅ | 见下节 |

---

## Tasks 对齐（21 个节点）

全部 ✅ 完成（Task 1-6，勾选 21 / 未勾选 0）。
`Task 6` 三个测试类已落地并跑通，非"补测试"占位。

---

## 隐性约定对齐

| 约定 | 状态 | 说明 |
|------|------|------|
| #10 事务边界精确控制 | ✅ | `@Transactional` 已从 `triggerAdjustment` 移除，改 `TransactionTemplate` |
| #12 Agent 调用超时降级 | ✅ | 降级事件已写 `agent_degradation_log` 并通过脱敏测试；超时值 120s 与建议 30s 的差异已登记为遗留风险（见 design §6） |
| #14 技术异常与业务异常分层 | ✅ | `AdjustmentException` 在 Service 层消化，不上抛 |
| #1 统一响应结构 | ✅ | 未新增接口 |
| #4 时间格式 | ✅ | `LocalDate` → ISO 字符串 |
| 规则 #5 只输出 diff | ✅ | 未改为整份重生成 |
| 规则 #8 只引用已启用知识点 | ✅ | 知识点由 Service 查库注入，Prompt 禁止编造 |
| 规则 #6 结构化可校验 | ✅ | 仍走 `adjustment_schema.json` |

---

## 超出范围改动

工作区内与本 change 无关的改动（未纳入本次校验）：

| 文件 | 归属 |
|------|------|
| `src/main/resources/application-dev.yml` | chore（本地 DB 口令） |
| `src/main/resources/db/V1__init_schema.sql` | P0 修复（建表冲突） |
| `src/main/resources/db/V3__error_question_table.sql` | chore（排序规则） |
| `src/test/.../HomeworkServiceTest.java`、`SubjectServiceTest.java` | P0 修复（测试基线） |
| `2026-11-09_error_detail_and_record_pages` 的全部文件 | 另一个 change |

---

## 首检发现与修复记录

首轮校验（同日）发现 3 项偏离，已全部闭环：

| # | 首检发现 | 处理 | 复核结果 |
|---|---------|------|---------|
| D-1 | Agent 降级未写 `agent_degradation_log`（design §6 + 约定 #12） | 新增 `recordDegradation()`：`agent_type=adjustment`、`degradation_action=return_empty_diff`，`failure_reason` 脱敏 + 截断 500 字符，写入失败不阻断降级；`input_summary` 只记 goalId/信号类型/错题数，不落学生标识与题目内容 | ✅ 已闭环 |
| D-2 | design §3「按掌握度升序」与实现 `ORDER BY knowledge_point_id` 不符 | 修正 design 表述并注明"该结果集内掌握度全为空/零，排序无区分度" | ✅ 已闭环（实现未改） |
| D-3 | design §4 SIG-4 语义漏写 `mastery_score = 0` | 修正 design 表述并说明 `=0` 只可能是初始化占位（掌握度下限 0.05） | ✅ 已闭环（实现未改） |

> 补测试：`should_sanitizeAndTruncate_when_degradationLogIsWritten` 断言 API Key 被脱敏、超长内容截断且以 `...[truncated]` 结尾，防止后续把脱敏逻辑删掉。

---

## 测试结果

- **编译**：BUILD SUCCESS
- **测试**：`521 tests, 0 failures, 0 errors, 5 skipped`
- 本 change 新增：`SignalDetectorTest`(7) + `PlanAdjustmentServiceTest`(7) + `PlanDiffServiceTest`(5) = 19 例，全部通过
- 回归价值：
  - `should_detectSIG2_when_dropIsNegativeBeyondThreshold` — 修复前必失败，钉住 SIG-2 符号 bug
  - `should_sanitizeAndTruncate_when_degradationLogIsWritten` — 钉住约定 #12 的脱敏与截断

---

## 结论

✅ **对齐** — 8 个功能点、21 个任务节点全部落地，19 个新增测试通过；首检发现的 D-1/D-2/D-3 已全部闭环，无遗留功能缺口。

唯一遗留项是 `agent.agentscope.timeout=120s` 与约定 #12「建议 30s」的差异，已在 design §6 登记为技术债（长期方案应改为异步任务化，而非放大同步超时），不阻断本次归档。

**可进入 archive。**

