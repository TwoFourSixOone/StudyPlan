# 系统工作原理：Agent 契约

> **维护触发**：change 归档时，若新增/删除 Agent、改变其输入输出、降级动作或日志字段，必须在同一轮更新本文件。
> **冲突处理**：降级行为的产品要求见 `docs/product/index.md` 规则 #6，本文件描述"代码实际怎么实现"。

---

## 1. 落地现状

| 产品规则要求的 Agent | 代码 | 状态 |
|---------------------|------|------|
| 规划 Agent | — | ❌ **未实现**。每日计划由 `HomeworkServiceImpl` 按知识点 + 配额直接生成，无 LLM 规划环节 |
| 出题 Agent | `agent/QuestionGenAgent` | ✅ 已实现，含降级处理器 `HomeworkDegradationHandler` |
| 归因 Agent | `agent/AttributionAgent` + `impl/AttributionAgentImpl` | ✅ 已实现（试卷解析链路因 OCR 未接入而无法端到端触发） |
| 调整 Agent | `agent/AdjustmentAgent` + `impl/AdjustmentAgentImpl` | ✅ 已实现，v2 Prompt |
| （额外）新题校验 | `agent/QuestionCheckAgent` | ✅ 举一反三新题的质量校验，产品规则外的补充 |

> 现状差距：产品规则 #6 要求四类 Agent 各有降级策略，实际只有出题与调整两类有完整的「降级 + 日志」闭环。

---

## 2. 所有 Agent 的共同契约

1. **只做 LLM 调用**：数据查询、落库、状态变更一律在 Service 层；Agent 不直接写业务表
2. **输出必须结构化**：经 `AgentOutputValidator` + `resources/prompts/schemas/*.json` 校验；校验失败重试，最多 2 次（`agent.agentscope.max-retries`）
3. **超时受控**：`AgentScopeClient` 统一超时，当前 `agent.agentscope.timeout = 120s`
4. **只能引用已启用知识点**：ID 由 Service 查库后注入 Prompt，禁止模型自造（规则 #8）
5. **技术异常与业务异常分层**：`AgentTimeoutException` / `AgentOutputInvalidException` 由 Service 捕获转降级，不向上透传（约定 #14）

---

## 3. 各 Agent 的输入输出

| Agent | 输入 | 输出 | Prompt / Schema |
|-------|------|------|----------------|
| 出题 | 知识点列表 + 掌握度 + 配额 | 题目引用列表（`DailyPlanQuestionRef`）+ 举一反三新题 | `question_gen_v1.txt` / `question_gen_schema.json` |
| 新题校验 | 原错题 + 生成的新题 | 通过/拒绝 + 原因 | `question_check_v1.txt` |
| 归因 | 题目文本 + 作答结果 | 知识点 ID 映射 | `attribution_v1.txt` / `attribution_schema.json` |
| 调整 | 触发信号 + `AdjustmentContextDTO`（目标 / 未来 7 天计划 / 未覆盖知识点 / 超期错题） | `PlanDiffDTO`（仅需修改的日期） | `adjustment_v2.txt` / `adjustment_schema.json` |
| （辅助）解析生成 | 题干 + 正确答案 + 学生答案 + 年级 | 解析文本 | `analysis_gen_v1.txt` |
| （辅助）AI 批改 | 题干 + 正确答案 + 学生答案 | "正确"/"错误" | `grading_v1.txt` |

> `adjustment_v1.txt` 保留未删，便于回滚。Prompt 一律从 `classpath:prompts/versions/` 加载，禁止硬编码。

---

## 4. 降级契约（实现层面）

| Agent | 失败时的行为 | 是否有降级日志 |
|-------|-------------|---------------|
| 出题 | 重试 2 次 → `HomeworkDegradationHandler.generateDefaultPlan` 用题库兜底 → 仍失败则计划留 `pending_generation`，前端提示"今日作业生成失败" | ✅ `agent_degradation_log`，`agent_type=question_gen`，`degradation_action=return_default` |
| 调整 | 捕获 `AdjustmentException` → 返回空 diff（"暂无需调整"），不落库 diff | ✅ `agent_degradation_log`，`agent_type=adjustment`，`degradation_action=return_empty_diff` |
| 归因 | 题目标记 `pending_manual_review`，不更新掌握度 | ⚠️ **未写降级日志**（现状缺口） |
| 解析生成 | 解析置空，不影响批改结果 | ❌ 不写日志（属可接受的静默降级） |
| AI 批改 | 降级为字符串精确比较 | ❌ 不写日志（属可接受的静默降级） |
| 试卷切题（OCR） | 抛 `OcrException`，当前无有效降级 | ❌ OCR 未接入 |

---

## 5. 降级日志的写入规范（隐性约定 #12）

写入 `agent_degradation_log` 前必须脱敏：

| 字段 | 内容 | 规则 |
|------|------|------|
| `agent_type` | `question_gen` / `adjustment` / … | 取 `AgentType.code`，不写魔法字符串 |
| `input_summary` | 输入摘要 | 只记标识与计数，**不记学生标识、题目正文**；截断 500 字符 |
| `failure_reason` | 异常类型 + message | 掩码 `apiKey` / `token` / `secret` / `studentId`；截断 500 字符 |
| `degradation_action` | `return_default` / `return_empty_diff` / … | 与降级行为一一对应 |

实现参考：`HomeworkDegradationHandler.sanitize()`、`PlanAdjustmentServiceImpl.sanitize()`（两处逻辑一致，改动需同步）。

**失败不影响主流程**：日志写入异常只记 `log.error`，不得让用户拿不到降级结果。

---

## 6. 修改 Agent 时的检查清单

1. 新增 Prompt 放到 `resources/prompts/versions/`，并在 `prompts/schemas/` 补对应 Schema
2. 输出解析失败是否有重试上限？是否落到降级？
3. 降级是否写 `agent_degradation_log`？字段是否脱敏？
4. 是否引入了新的「Agent 直接查库/写库」？—— 禁止
5. 超时值是否与 `agent.agentscope.timeout` 一致？是否在约定 #12 的合理范围内？
