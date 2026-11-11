# 系统工作原理：Agent 契约

> **维护触发**：change 归档时，若新增/删除 Agent、改变其输入输出、降级动作或日志字段，必须在同一轮更新本文件。
> **冲突处理**：降级行为的产品要求见 `docs/product/index.md` 规则 #6，本文件描述"代码实际怎么实现"。

---

## 1. 落地现状

| 产品规则要求的 Agent | 代码 | 状态 |
|---------------------|------|------|
| 规划 Agent | — | ❌ **未实现**。每日计划由 `HomeworkServiceImpl` 按知识点 + 配额直接生成，无 LLM 规划环节 |
| 出题 Agent | `agent/QuestionGenAgent` | ✅ 已实现，含降级处理器 `HomeworkDegradationHandler` |
| 归因 Agent | `agent/AttributionAgent` + `impl/AttributionAgentImpl` | ✅ 已实现；**2026-11-11（v4）起在试卷链路降级为「兜底」**：只对视觉解析未给出知识点的题按批补一次（≤40 题/批），不再逐题必经 |
| 调整 Agent | `agent/AdjustmentAgent` + `impl/AdjustmentAgentImpl` | ✅ 已实现，v2 Prompt |
| （额外）新题校验 | `agent/QuestionCheckAgent` | ✅ 举一反三新题的质量校验，产品规则外的补充 |
| **（额外）试卷视觉解析** | `agent/ExamParseAgent` + `impl/ExamParseAgentImpl`；候选知识点构造 `service/ExamKpCandidateService` + `impl/ExamKpCandidateServiceImpl` | ✅ **2026-11-11 已实现**（Prompt `exam_parse_v1.txt` / Schema `exam_parse_schema.json`）：单图解析，输出逐题 `knowledgePointId`；服务端白名单校验；链路 = `ExamAnalysisWorker → ExamParseService → ExamParseAgent/ExamKpCandidateService → AttributionAgent（兜底）` |

> 现状差距：产品规则 #6 要求四类 Agent 各有降级策略，实际只有出题与调整两类有完整的「降级 + 日志」闭环；试卷视觉解析在 2026-11-11 补上了 `agent_type=exam_parse` 的降级日志（见 §4）。

---

## 2. 所有 Agent 的共同契约

1. **只做 LLM 调用**：数据查询、落库、状态变更一律在 Service 层；Agent 不直接写业务表
2. **输出必须结构化**：经 `AgentOutputValidator` + `resources/prompts/schemas/*.json` 校验；校验失败重试，最多 2 次（`agent.agentscope.max-retries`）
3. **超时受控**：`AgentScopeClient` 统一超时，当前 `agent.agentscope.timeout = 120s`
4. **只能引用已启用知识点**：ID 由 Service 查库后注入 Prompt，禁止模型自造（规则 #8）；**试卷视觉解析额外要求服务端白名单**——模型输出的 `knowledgePointId` 必须 ∈ 本卷注入集合，否则置空并 warn（见 §3）
5. **技术异常与业务异常分层**：`AgentTimeoutException` / `AgentOutputInvalidException` 由 Service 捕获转降级，不向上透传（约定 #14）

---

## 3. 各 Agent 的输入输出

| Agent | 输入 | 输出 | Prompt / Schema |
|-------|------|------|----------------|
| 出题 | 知识点列表 + 掌握度 + 配额 | 题目引用列表（`DailyPlanQuestionRef`）+ 举一反三新题 | `question_gen_v1.txt` / `question_gen_schema.json` |
| 出题（举一反三） | 原错题 + 掌握度 + 目标数量 + 难度策略 | `[{knowledgePointId, content}]`（不含 questionId/taskType，由系统落库时分配） | `similar_question_gen_v1.txt` / `similar_question_schema.json` |
| 新题校验 | 原错题 + 生成的新题 | 通过/拒绝 + 原因 | `question_check_v1.txt` |
| **试卷视觉解析（2026-11-11）** | 单张试卷图（base64 内联）+ 科目/年级 + **候选知识点清单（ID+名称，≤150）** + `imageSeq/imageTotal` | `{questions:[{questionIndex, stem, formatType, choices, correctAnswer, studentAnswer, answerStatus, difficulty, explanation, **knowledgePointId**}], imageWarning}` | `exam_parse_v1.txt` / `exam_parse_schema.json` |
| 归因（**v4 起=兜底**） | 题目文本 + 作答（仅"视觉解析未给出 kp"的题）+ 候选知识点清单（≤500） | 知识点 ID 映射 | `attribution_v1.txt` / `attribution_schema.json` |
| 调整 | 触发信号 + `AdjustmentContextDTO`（目标 / 未来 7 天计划 / 未覆盖知识点 / 超期错题） | `PlanDiffDTO`（仅需修改的日期） | `adjustment_v2.txt` / `adjustment_schema.json` |
| （辅助）解析生成 | 题干 + 正确答案 + 学生答案 + 年级 | 解析文本 | `analysis_gen_v1.txt` |
| （辅助）AI 批改 | 题干 + 正确答案 + 学生答案 | "正确"/"错误"（`AnswerGradingService` 三态：标准答案缺失→`null`） | `grading_v1.txt` |

> `adjustment_v1.txt` 保留未删，便于回滚。Prompt 一律从 `classpath:prompts/versions/` 加载，禁止硬编码。

**试卷链路的知识点判定顺序（v4，2026-11-11）**：

```
① 直出：视觉解析输出 knowledgePointId 且 ∈ 本卷注入集合（服务端白名单）
② 兜底：直出为空/被置空 → AttributionAgent 批量补一次（≤40 题/批，候选 ≤500）
③ 仍无 → attribution_status='pending_manual_review'，knowledge_point_id=NULL
          （不更新掌握度、不入错题，等人工处理）
```

- **白名单（规则 #8）**：模型只能引用注入集合内的 ID；幻觉/越界一律置空 + `log.warn`，不得落库。
- **候选构造**：`ExamKpCandidateService` 按 `tenant + subject + status='active'` 查库，再用 `GradeMatcher.matchesRange` 过滤年级；**parse 切片 ≤150 / attribution 切片 ≤500**；超限截断并写 `agent_degradation_log(action=truncate_kp_candidates)`。
- **统计**：`exam_analysis_result.questions.stats` 记录 `kpDirect/kpFallback/kpUnresolved/kpTotal/kpTruncated`（+`judged/unjudged`）。

**输出结构约束（2026-11-11）**：

- `formatType=choice` 的生成题必须带 ≥2 个有效选项（`[{label,text}]`），否则视为非法输出
- 选择题 `answer` 必须是选项标签（A/B/C/D），不是选项内容
- 题干、选项、解析一律纯文本（无 LaTeX/Markdown），出参时统一过 `LlmTextSanitizer`（约定 #24）

---

## 4. 降级契约（实现层面）

| Agent | 失败时的行为 | 是否有降级日志 |
|-------|-------------|---------------|
| 出题 | 重试 2 次 → `HomeworkDegradationHandler.generateDefaultPlan` 用题库兜底 → 仍失败则计划留 `pending_generation`，前端提示"今日作业生成失败" | ✅ `agent_degradation_log`，`agent_type=question_gen`，`degradation_action=return_default` |
| 出题（举一反三） | `SimilarQuestionServiceImpl`：输出结构非法（`AGENT_OUTPUT_INVALID`）重试，最多 5 轮；调用失败/超时立即放弃；不足数量记 `skip_similar_question` 降级日志 | ✅ `agent_degradation_log`，`agent_type=question_check`，`degradation_action=skip_similar_question` |
| 调整 | 捕获 `AdjustmentException` → 返回空 diff（"暂无需调整"），不落库 diff | ✅ `agent_degradation_log`，`agent_type=adjustment`，`degradation_action=return_empty_diff` |
| 归因（**v4 起仅兜底**） | 题目标记 `pending_manual_review`，不更新掌握度；批次异常重试 ≤2 次后整批降级，不影响已直出的题 | ⚠️ 兜底路径仍**未写**降级日志（沿用现状缺口；直出失败由视觉解析侧记 `exam_parse` 日志） |
| 解析生成 | 解析置空，不影响批改结果 | ❌ 不写日志（属可接受的静默降级） |
| AI 批改 | 降级为字符串精确比较；**标准答案缺失/作答无法辨识/超时 → `is_correct=NULL`（不可判定）** | ❌ 不写日志（属可接受的静默降级） |
| **试卷视觉解析（2026-11-11）** | 单图重试 2 次（内存计数）仍失败 → 该图 0 题 + `imageWarning`，其余图照常落库；全部图失败 → `task/paper=failed`；**kp 直出为空/幻觉 → 置空后交归因兜底，仍空 → `pending_manual_review`（不更新掌握度、不入错题）** | ✅ `agent_degradation_log`，`agent_type=exam_parse`（`AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE`），`degradation_action` 取值：`truncate_kp_candidates`（候选截断）/ `kp_unreachable`（>1000 分片）/ `timeout_skip_image`（单图超时）/ `judge_key_mismatch`（判分 key 集合不一致）等 |
| 试卷切题（OCR） | **已退役（`@Deprecated`，不再调用）**：`OcrService`/`TencentOcrServiceImpl` 保留类与 Bean 但试卷链路不再注入；原"OCR 失败 → 待人工归因占位题"逻辑删除 | ❌ 无（不再触发） |

---

## 5. 降级日志的写入规范（隐性约定 #12）

写入 `agent_degradation_log` 前必须脱敏：

| 字段 | 内容 | 规则 |
|------|------|------|
| `agent_type` | `question_gen` / `adjustment` / … / `exam_parse` | 取 `AgentType.code`；`exam_parse` 由 `AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE` 常量提供（未进 `AgentType` 枚举），不写魔法字符串 |
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
