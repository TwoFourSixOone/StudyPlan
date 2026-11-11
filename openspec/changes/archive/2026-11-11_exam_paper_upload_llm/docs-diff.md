# docs 回写建议（docs-diff）— `2026-11-11_exam_paper_upload_llm`

> **状态**：**仅建议，未改正文**。`docs/product/index.md` 与 `docs/architecture/implicit-contracts.md` 属受保护区域（AGENTS.md §5），需人工审批后再由 captain 指派回写。
> **依据**：本 change 的 `proposal.md` §3/§4、`design.md` §6.1/§3.7/§7.2.1/§7.5、`verify.md` 真实链路证据（4 图 → 19 题、16 judged / 3 unjudged、11 条掌握度日志、SIG-4 自动调整）。
> **行号基准**：`docs/product/index.md` 402 行版本；`docs/architecture/implicit-contracts.md` 222 行版本（2026-11-11）。

---

## 一、`docs/product/index.md`

### 1. 规则 #4（试卷上传按科目分类与异步解析）——第 194–204 行

| # | 位置 | 原文（摘录） | 建议新文 | 理由 |
|---|------|-------------|---------|------|
| 4-1 | L196 规则描述 | "上传后进入异步解析流程，先通过**视觉模型（OCR + 版面分析）切题**得到题目列表，再由归因 Agent 逐题做知识点归因，**不直接让 LLM 输出"薄弱点总结"**。" | "上传后进入异步解析流程：由**视觉模型（LLM 多模态）一次性完成「切题 + 逐题判定知识点」**——服务端把该科目/年级的候选知识点清单注入 Prompt，模型逐题输出 `knowledgePointId`，服务端做**白名单校验**（只接受注入集合内的 ID）；未给知识点的题再由**归因 Agent 兜底**补一次（≤40 题/批）。**不直接让 LLM 输出"薄弱点总结"或掌握度数值**。" | v4 把归因前置到视觉解析（`AttributionAgent` 降为兜底），真实实现为 `ExamParseAgent` + `ExamKpCandidateService`；规则 #8 靠服务端白名单兜住 |
| 4-2 | L198 切题环节实现归属 | "切题由**独立的视觉模型完成，不属于四类 Agent 中的任何一个**，其重试与归因 Agent 的重试独立计数。" | "切题（含知识点直出）由**独立的视觉模型完成，不属于四类 Agent 中的任何一个**；其单图重试（≤2 次）与兜底归因 Agent 的重试（≤2 次）**独立计数**；整卷人工重试另计（`ocr_retry_count` 0..2）。" | 实现区分"单图重试（内存）/整卷重试（列）/归因重试（列）"三种计数（design §3.4） |
| 4-3 | L200 边界：重试 | "切题失败需保留原图并允许重试（最多 2 次）。" | 保留原文，追加："整卷失败可调 `POST /papers/{id}/retry`（≤2 次）；解析成功后**原图 URL 永久保留**（`exam_paper.image_urls`）。" | 与实现 retry 入口一致 |
| 4-4 | L199 边界：新增 | （无） | 新增："**图片约束**：一次最多 **8 张**、单张 ≤**9.5MB**、同一份试卷合计 ≤**30MB**；仅 `jpg/jpeg/png`；上传分段提交（建会话 → 逐张 → submit），任一张失败可单独重试。" | 实现与前端预检/后端校验（1182–1190）一致 |
| 4-5 | L199 边界：新增 | （无） | 新增："**题面存储（零 DDL）**：`exam_question.question_content` 以段落文本承载 `【题干】【选项】【正确答案】【解析】`，由 `ExamQuestionTextCodec` 编解码；不新增 `choices`/`correct_answer` 列。" | design §4 的零 DDL 方案 |
| 4-6 | L201 边界：掌握度 | "试卷归因结果按考试来源权重（`W_source = 1.0`）更新掌握度。" | "试卷判分结果按考试来源权重（`W_source = 1.0`）更新掌握度；**答对与答错都更新**（规则 #2）。" | 与规则 #2/实现一致（此前措辞易读成只按归因） |
| 4-7 | L202 边界：新增 | （无） | 新增："**判分三态**：学生未作答且标准答案存在 → 判错；**标准答案缺失/作答无法辨识/批处理超时 → 判为「不可判定」（`is_correct=NULL`）**，不更新掌握度、不入错题本、不计入正确率分母；页面显示「待人工判定」。" | v4b 阻塞修复（真实卷 6 道选择题 4 道无答案）；避免"学生答对被判错" |
| 4-8 | L202 边界：待人工 | "未识别出知识点的题目标记为**待人工归因**，由开发者通过后端管理接口处理。" | 保留原文，追加："（v4 起待人工归因只发生在「直出为空 + 兜底归因仍未命中」；管理接口仍属后续 change）。" | 说明兜底链路的边界 |
| 4-9 | L203 边界：新增 | （无） | 新增："**全部题目落库并可回看**：解析完成后所有题目（含待人工归因/待人工判定）均按题序落库，学生可在「我的 → 历史试卷 → 详情」逐题回看（题干/选项/我的答案/正确答案/解析/知识点），详情页与作业答题区一致（上一题/下一题，只传 `paperId`）。" | 用户拍板 #8 + 实现（ExamList/ExamDetail） |

### 2. 规则 #3（每日作业组成与粒度）——第 145–192 行（仅"错题重做/举一反三"两段）

| # | 位置 | 原文（摘录） | 建议新文 | 理由 |
|---|------|-------------|---------|------|
| 3-1 | L150 原题定义 | "**原题定义**：学生之前在每日作业、专项评测或考试中答错的题目，其题目内容**完全不变**地从历史题库中抽取。" | "**原题定义**：学生之前在每日作业、专项评测或考试中答错的题目，其题目内容**完全不变**地抽取：题库/生成题来源取 `question_bank` / `generated_question`；**考试试卷来源取 `exam_question`（`questionSource=exam`，题面经 `ExamQuestionTextCodec` 解码）**。" | v3 让试卷错题进入原题重做；题面不在题库 |
| 3-2 | L150 后新增 | （无） | 新增："**引用形态（考试来源）**：`daily_plan.content[].questionSource="exam"`、`questionId = -exam_question.id`（与 `error_question.question_id` 同值）；`homework_submission_detail.question_source='exam'` 且 `question_id` 同为负值。负值命名空间用于与题库/生成题自增正数区分，避免 `uk_plan_question` 冲突。" | design §7.2.1 D20；隐性约定 #26 |
| 3-3 | L154–L156 暂停/重点关注 | "周期内若该知识点掌握度已 ≥ 0.8，则该错题**暂停重做**…超过 30 天周期仍未答对的原题，标记为"需重点关注"…" | 保留；追加："（考试来源错题同样适用艾宾浩斯推进与暂停规则；`current_stage` 到达 5 后 `status=completed`、`next_due_date` 记为完成日，不再进到期列表。）" | v3 stage 生命周期 + D23 修复 |
| 3-4 | L158 举一反三来源 | "**题目来源（2026-11-10 调整）**：…**先从 `question_bank` 按"同知识点 + 年级匹配 + 排除该学生已做过"抽取 N 道**；题库不足时才由出题 Agent 生成补足…" | 保留；追加："**（2026-11-11）源题兜底**：若该知识点题库无题、只有考试试卷错题，则用最早到期的试卷错题经 `ExamQuestionTextCodec` 适配为**临时源题**（不落题库）交给出题 Agent；源题缺正确答案时跳过该知识点并记降级日志。" | v3 D22；不写 `question_bank` 副本（规则 #10） |
| 3-5 | L167 触发条件 | "**触发条件**：有到期错题时，为对应知识点生成举一反三新题…" | 保留；追加："到期错题**包含考试试卷来源**；**配额分母**按知识点统计**题库+生成题+试卷错题的合计**，单知识点超过 `KP_MAX=4` 时 `clamp` 到 4（超出部分次日顺延，不记为未完成、不触发 SIG-3）。" | v3 clamp 方案 A；避免 `KP_MAX` 硬校验让次日作业生成失败 |

### 3. 规则 #5（计划调整基于触发信号与 diff 输出）——第 206–234 行

| # | 位置 | 原文（摘录） | 建议新文 | 理由 |
|---|------|-------------|---------|------|
| 5-1 | L208–L210 规则描述：入口 | "**规则描述**：计划调整有两条入口…**自动（默认）**：每天 **01:30** 定时检测…；**手动（兜底）**：学生点击"调整计划"按钮…" | "**规则描述**：计划调整有**三条入口**，输出必须基于后端预定义的触发信号…；**自动（默认）**：每天 **01:30** 定时检测…；**手动（兜底）**：学生点击"调整计划"…；**考试结算触发（2026-11-11 新增）**：试卷结算完成（`grade_status` 由 ungraded→graded）后**立即**检测一次该学生该科目进行中目标的信号，命中则**自动应用**（`applyDiff`），无需学生确认。" | 实现 `ExamSignalAdjustService`（异步、不阻塞试卷状态） |
| 5-2 | L208 后新增 | （无） | 新增："**去重**：同一目标 **12 小时内**已有 `plan_adjustment_log.action='confirmed'` 记录时跳过（避免与 01:30 定时任务或同日多次调整叠加）；已有 `pending` diff 时跳过（`PLAN_ADJUST_CONFLICT`）。" | design §8.2/§8.3；`action` 实际取值为 `confirmed`/`rejected` |
| 5-3 | L217 信号定义 SIG-2 | "SIG-2：某知识点掌握度单次考试下降 > 0.15" | 保留；追加："（考试来源的 `mastery_change_log.source_id = exam_paper.id`；试卷结算先写掌握度、再检测信号，保证 SIG-2 数据已就绪。）" | design §8.1/§7.4 |
| 5-4 | L230 边界：确认 | "调整结果需先展示给学生确认，确认后才生效。" | "**手动入口**的调整结果需先展示给学生确认；**自动入口（01:30 定时 / 考试结算触发）**命中信号后直接应用（规则 #5 默认自动），历史只读、可追溯。" | 与 01:30 自动模式及新增考试触发一致 |

### 4. 规则 #9（学习记录与回溯查看）——第 283–299 行

| # | 位置 | 原文（摘录） | 建议新文 | 理由 |
|---|------|-------------|---------|------|
| 9-1 | L290 考试试卷分析结果 | "**考试试卷分析结果**：逐题归因结果（含知识点映射）、得分、分析时间。" | "**考试试卷分析结果**：写入 `exam_analysis_result`（1 份试卷 1 条，`status='completed'`）：`exam_name`、`image_urls`、`questions`（`paperId` + `stats`：`judged/unjudged/kpDirect/kpFallback/kpUnresolved/kpTotal/kpTruncated` + 逐题轻量摘要）、`user_score`（答对题数）、`total_score`（**已判定**题数，`is_correct=NULL` 不计）、`analyzed_at`；**1 题 1 分制**（Demo 不识别卷面真实分值）。" | design §7.5；真实链路 stats 已落库 |
| 9-2 | L290 后新增 | （无） | 新增："**试卷题目本身**（含待人工归因/待人工判定）全部落库 `exam_question`，学生可在「历史试卷」列表/详情回看，属于学习记录的可回溯基础。" | 用户拍板 #8 |
| 9-3 | L294 只读不删 | "学习记录**只读不删**——一旦写入不得修改或删除。" | 保留；追加："（判分结果在学生提交后由系统一次性写入；`is_correct` 允许从 `NULL` 变为判定值【重试/人工处理】，但学习记录行不删除。）" | design §9.2 幂等 + B7 重试语义 |

---

## 二、`docs/architecture/implicit-contracts.md`

### 1. 约定 #24（LLM 文本清洗）——第 190–200 行：措辞修订

| # | 位置 | 原文（摘录） | 建议新文 | 理由 |
|---|------|-------------|---------|------|
| 24-1 | L194 Prompt 侧 | "禁止 `$...$`、`\vec`、`\frac`、`\sqrt`、`**`、`#`、反引号；数学符号用中文或常用字符（"向量a"、"a·b=0"、"θ=90°"、"√10"、"1/2"、"x²"）" | "禁止 `$...$`、`**`、`#`、反引号；**禁止任何反斜杠命令**（`\vec`、`\frac`、`\sqrt`、`\cdot` 等）与 **`^{...}` / `_{...}` 花括号写法**；**允许纯文本 `x^2`、`a_n`**；幂**优先** `²/³`（如 `x²`）或 `^2`，下标**优先** `a(n)` 或中文（如"a 下标 n"）；常用符号用 `√`、`≤`、`≥`、`×`、`÷`、`θ=90°`、`1/2`。" | v4 收紧：旧措辞易被误读为"全禁 `^`"；真实实现清洗花括号与反斜杠命令，保留 `^` |
| 24-2 | L195 代码侧 | "模型返回后必须过 `LlmTextSanitizer.sanitize(...)` 兜底（去定界符、转 LaTeX 命令、清控制字符）" | "模型返回后必须过 `LlmTextSanitizer.sanitize(...)` 兜底：去定界符/Markdown、**`^{x}`/`_{x}` → `^(x)`/`_(x)`**、反斜杠命令 → 可读符号（`\frac`→`/`、`\sqrt`→`√`、`\vec`→"向量"）、清控制字符；**不移除 `^` 本身**。" | 与 v4b 清洗实现一致 |
| 24-3 | L197 影响范围 | "题目解析（`analysis_gen_v1.txt`）、批改、试卷分析、出题 Prompt 的 stem/choices" | 追加："**试卷视觉解析（`exam_parse_v1.txt`）**：`stem`/`choices[].text`/`correctAnswer`/`explanation`/`studentAnswer` 均需清洗。" | v4 新增 Prompt 与字段 |

### 2. 约定 #26（新增）：试卷题/试卷错题的负 ID 命名空间与题面编码

| # | 位置 | 原文 | 建议新文 | 理由 |
|---|------|------|---------|------|
| 26-1 | 文件末尾（#25 之后） | （无） | **### 约定 #26：考试试卷来源的题目使用负 ID 命名空间，题面按段落文本编码**<br><br>- **约定内容**：<br>  - `error_question.question_id` 对考试来源存 **`-exam_question.id`**（负值）；`daily_plan.content[].questionId` 与 `homework_submission_detail.question_id` 在 `questionSource="exam"` 时**同用该负值**<br>  - 原因：`uk_student_question(student_id, question_id, tenant_id)` 与 `uk_plan_question(plan_id, question_id, tenant_id)` 要求跨来源唯一，而 `question_bank`/`generated_question` 是自增正数，正数 `exam_question.id` 会与其碰撞 → 提交行互相覆盖<br>  - 题面：`exam_question.question_content` 以 `【题干】【选项】【正确答案】【解析】` 段落文本承载，由 `ExamQuestionTextCodec` 编解码（零 DDL，`exam_question` 无 `choices`/`correct_answer` 列）<br>  - **任何"按 id 查题库"的代码必须先按 `questionSource`/`source_type` 分流**；`selectCriticalErrors` 保留 `question_id > 0` 防御（负 ID 对调整 Agent 无意义）<br>- **影响范围**：`ErrorQuestionService`、`HomeworkServiceImpl`（redo 组装/题面/判分/`advanceStage`）、`ExamPaperServiceImpl`、`ErrorQuestionMapper`<br>- **违反后果**：题库题与试卷题的提交记录互相覆盖；或把负 ID 当 `question_bank.question_id` 查询导致空题面/异常；错题本详情空白<br>- **发现时间**：2026-11-11（v3 试卷错题接入原题重做）<br>- **正确做法**：`ExamQuestionRefs`（或等价工具）集中做 `-id` 转换，禁止散落负号；错题本/详情 SQL 用 `LEFT JOIN exam_question eqx ON eq.source_type='exam' AND eqx.id = -eq.question_id` | design §7.2.1/§7.6 + D4/D20；实现已按此落地（`ExamPaperServiceImpl`/`HomeworkServiceImpl`） |

### 3. 约定 #27（建议新增，待确认编号）：本地图片必须 base64 内联给视觉模型

| # | 位置 | 原文 | 建议新文 | 理由 |
|---|------|------|---------|------|
| 27-1 | #26 之后 | （无） | **### 约定 #27：本地存储的试卷图片必须以 base64 内联交给视觉模型**<br><br>- **约定内容**：`file.storage.type=local` 时图片 URL 指向本机静态目录，**外部模型无法访问**；调用视觉模型前必须由服务端 `FileStorageService.readBytes(url)` 读字节 → base64 → `ImageBlock(Base64Source)` 内联，**禁止把 URL 直接交给模型**<br>- **内存约束**：逐图读取/生成/释放，峰值 ≤3 份（全局 LLM 并发 3）；**禁止**把 base64 写入 DB/Redis/降级日志<br>- **影响范围**：`AgentScopeClient.callVision`、`ExamParseService`、`FileStorageService`（local/COS）<br>- **违反后果**：模型侧取不到图（空响应/超时）；或一次性批量 base64 导致 OOM<br>- **发现时间**：2026-11-11（v4 视觉解析）<br>- **正确做法**：单图任务内读取 → 调用 → 释放；COS 未实现时抛 `1188` | design §3.1/§11.3；实现 `readBytes` + 逐图释放 |

---

## 三、回写顺序与验收

1. **人工审批**本文件（captain / 人工 owner）→ 批准后由指派成员按上表逐条改正文（不改表格以外的无关内容）。
2. `docs/product/index.md` 改完需同步检查：规则 #3/#4/#5/#9 的交叉引用（如规则 #2 的"来源权重"、规则 #8 的"只能引用已启用知识点"）不与新文冲突。
3. `implicit-contracts.md` 改完需确认 #24 修订保留原编号与"发现时间/修订原因"（沿用 #18 的修订惯例），#26/#27 追加到文末并更新维护规则里的"从 #25 起顺序编号"为"从 #27 起"。
4. 回写后重跑 `openspec/specs/agent-contracts.md` 的对齐（本 change 归档前由 t10 第二步/第三步处理，当前**暂停**）。

> 备注：截至本文件生成时，`docs/product/index.md` 与 `docs/architecture/implicit-contracts.md` **均未被本 change 修改**（`git status` 可核对），待审批后再动。
