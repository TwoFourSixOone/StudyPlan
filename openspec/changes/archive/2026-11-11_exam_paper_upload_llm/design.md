# Design：试卷上传 → LLM 视觉解析 → 错题/掌握度 → 自动调整 → 历史回看

> **Change 名称**：`2026-11-11_exam_paper_upload_llm`
> **约束**：零 DDL；`≤8` 张；模型固定 `qwen3.8-flash`；判分口径与作业批改一致；废弃手工录入；性能为硬要求（§11）

---

## 0. 决策速览

| 编号 | 决策 | 一句话理由 |
|------|------|-----------|
| D1 | 上传分段：`POST /papers` 建会话 + `POST /papers/{id}/images` 逐张 + `POST /papers/{id}/submit` 提交解析 | 小程序 `uni.uploadFile` 只能单文件；逐张上传让内存峰值 = 1 张图 |
| D2 | `AgentScopeClient` 内新增 vision 调用（同一 client、同一 `AgentScopeConfig`、同一模型名，内部挂 `DashScopeChatFormatter`） | 拍板 #1/#2；formatter 是 AgentScope 视觉调用的硬要求，文本实例保持不变以免回归 |
| D3 | 题面零 DDL 承载：`exam_question.question_content` 存分段纯文本，`ExamQuestionTextCodec` 编解码 | 表里没有 `choices`/`correct_answer` 列；详情页要与作业一致就必须能还原结构化字段 |
| D4 | 试卷错题 `error_question.question_id = -exam_question.id`（负命名空间） | `question_id NOT NULL` + `uk_student_question` 需要唯一；正数与题库/生成题自增 ID 必撞 |
| D5 | 判分抽 `AnswerGradingService`，作业批改委托它 | 口径单点维护；`HomeworkServiceImpl` 行为零变化 |
| D6 | `MasteryService` 新增批量入口（同公式、同日志字段） | 8 图 ≈ 数十题，逐题调用是 N+1 且同知识点反复读改写 |
| D7 | 信号检测异步、结算事务提交后；**信号调整** 12h 去重（`action='confirmed'`）+ pending diff 冲突跳过 | 避免与 01:30 `AutoAdjustmentTask` 同日双调；SIG-2 读 `mastery_change_log`，必须等掌握度落库 |
| D8 | OCR 保留但不再调用（`@Deprecated`），`ocr_retry_count` 列名不变、语义改为**整卷重试次数**（见 D14） | 零 DDL；删 SDK 依赖属独立变更 |
| D9 | 接口收敛到 `/api/student/exam/papers*`，删除 `/results`、`/result/{id}`、`/result/{id}/grade` | 前端路径已过期，一次收口避免双契约 |
| D10 | 详情页轮询**详情接口**的 `status`，`GET /tasks/{taskId}` 只服务上传页进度 | 从列表进入详情时拿不到 `taskId`（参数只传 `paperId`） |
| D11 | D_norm 一律过 `MasteryFormulaUtil.validateDNorm(declared, 同知识点题库历史均值)`；非法/缺失/偏离 >0.2 → **0.5** | 试卷难度是视觉模型"声明"的，规则 #2 要求与历史均值偏离 >0.2 降级 0.5；与现有 `convertDifficultyToDNorm` 兜底口径一致 |
| D12 | `plan_adjustment_log.action` **不新增 'applied'**，信号去重窗口查 `action='confirmed'` | `PlanDiffServiceImpl.applyDiff` 实际写 `confirmed`（手动确认也写 `confirmed`），改枚举会牵动既有日志与 `PlanDiffServiceTest` |
| D13 | TX-B 同步写一条 `exam_analysis_result`（`status=completed`），得分按 **1 题 1 分制** | 规则 #9 要求考试试卷分析结果可回溯；「学习记录」的考试筛选读的正是该表 |
| D14 | 整卷重试计数只写 `ocr_retry_count`（0..2，最多重试 2 次）；单图重试仅内存计数 | 避免一列两义导致"重试次数"不可解释 |
| D15 | 全局 LLM 并发用 **`Semaphore(3)`** 同时包住视觉解析/归因/判分 | 池(3) + `CallerRunsPolicy` + 试卷任务线程可叠到 6，需要全局闸门 |
| D16 | `paperType` 固定 5 值 `midterm/final/monthly/mock/other`，默认 `midterm`，非法 → `1191` | 前端 `Profile.vue` picker 已是这 5 值，后端此前无校验 |
| D17 | 重试时若 `exam_question` 已有行则**跳过解析/归因**，直接用库中行判分；否则按 `(exam_paper_id, question_index)` 严格对齐 | 避免"重新解析 → 题号漂移 → 判分结果错配到别的题" |
| D18 | 单图业务上限 **9.5MB**（不是 10MB）+ `GlobalExceptionHandler` 映射 `MaxUploadSizeExceededException/MultipartException → 1184` | `multipart.max-file-size: 10MB` 会让"恰好 10MB"先被容器拒且未映射（落 500），双保险兜底 |
| D19 | **v3**：试卷错题**进入**每日作业「原题重做」与「举一反三」（`questionSource="exam"`） | 用户 2026-11-11 明确要求；推翻 B6 方案 (b) |
| D20 | **v3**：`daily_plan` ref / `homework_submission_detail.question_id` 用 **`-exam_question.id`（与 `error_question.question_id` 同值）**，不用正数 | `uk_plan_question(plan_id, question_id, tenant_id)` 会让正数 exam id 与 bank id 互相覆盖；负值零转换命中 `error_question` |
| D21 | **v3**：`kpErrorCountMap` 在 service 侧 `min(raw, KP_MAX=4)` 后传配额计算器（断言保留） | 试卷错题计入配额分母后，同 kp ≥5 题会触发 `KP_MAX` 硬校验抛异常 → 次日作业失败；clamp 只消异常不改分配 |
| D22 | **v3**：举一反三源题「题库优先 + exam 兜底」，exam 源题适配为**临时 `QuestionBank`（不落库）** | `SimilarQuestionService` 签名零改动；不写 `question_bank` 副本（规则 #10 题库治理） |
| D23 | **v3**：`completeStageWithCheck` 的 `next_due_date` 改写 `completedDate` | 列是 `DATE NOT NULL`，写 NULL 会在 stage 5 完成时 SQL 报错（exam redo 首次走到该路径） |
| D24 | **v4**：知识点在**视觉解析阶段直出**（`{knowledgePoints}` 候选注入 + Schema `knowledgePointId` + 服务端白名单），`AttributionAgent` 降为兜底 | 用户 2026-11-11 决定；减少一次 LLM 往返、kp 与题面同源更准；规则 #8 靠白名单兜住 |
| D25 | **v4**：数学记号收紧——允许 `x^2`/`a_n`，禁 `^{...}`/`_{...}` 花括号与反斜杠命令 | captain 2026-11-11 建议；约定 #24 措辞同步（不是全禁 `^`） |
| D26 | **v4b（阻塞）**：判分三态——**标准答案缺失/无法辨识/超时 → `is_correct=NULL`**（不可判定），只有"学生空 + 标准答案有"才判 `false` | 真实图实测 6 道选择题有 4 道模型给不出答案；旧契约会误判学生答对为错、压低掌握度、污染错题本 |
| D27 | **v4b**：kp 候选**分级上限**——parse 150（体积敏感）/ attribution **500**（仅未决题，>500 分片，>1000 记不可达） | 修 v4 的"截断即不可达"漏洞：被 parse 截断的 kp 仍能被兜底命中，保"直出+兜底可归因率 ≥90%"名副其实 |

---

## 1. 端到端流程

```
小程序                              后端
────────────────────────────────────────────────────────────────────────
① 选图 ≤8（压缩）       →  POST /papers                 建 paper(status=uploading) + 声明 imageCount
② 逐张上传（并发 2）    →  POST /papers/{id}/images     追加 URL；校验类型/单图/总量/张数
③ 显式提交解析          →  POST /papers/{id}/submit      status=pending + 建 task + Redis 入队
                                                 ↘ 兜底：上传数达到声明 imageCount 时自动触发（与 submit 只生效一次）
③' 轮询                ←  GET /tasks/{taskId}          Worker 消费（≤3 并发）
                                                        ├ 读图 → base64 → ImageBlock（逐张，用完即弃）
                                                        ├ 视觉解析（2 次重试，独立计数）
                                                        ├ 题号重排 + 跨图去重
                                                        ├ 归因（复用 AttributionAgent，≤40 题/批）
                                                        ├ 落库 TX-A（题目 is_correct=NULL，paper=completed）
                                                        ├ 判分（choice 比较 / 其他 grading_v1，≤3 并发）
                                                        ├ 结算 TX-B（批量更新 is_correct/score + 错题 + 掌握度 + 学习记录 + graded）
                                                        └ 异步：信号检测 → 自动 applyDiff
④ 进详情               →  GET /papers/{paperId}        3 条 SQL（paper / questions / kp 批量）
⑤ 历史列表             →  GET /papers?page&size        2 条 SQL（分页+count）
```

### 1.1 状态机

```
exam_paper.status:       uploading → pending → processing → completed
                                                ↘ failed（可 retry → pending）
exam_paper.grade_status: ungraded → graded（结算事务内 CAS）
exam_analysis_task.status: pending → processing → completed
                                             ↘ failed（保留已落库题目，is_correct 为 NULL）
```

联动规则（沿用 `aggregatePaperStatus` 语义，改为按 paper 上的状态直接推进，减少"N 个 task 聚合"查询）：

| 触发点 | paper.status | task.status |
|--------|-------------|------------|
| 创建会话 | `uploading` | — |
| `POST /papers/{id}/submit` 或图片数达到声明张数 | `pending` | `pending`（新建） |
| Worker CAS 成功 | `processing` | `processing` |
| TX-A 提交（题目已落库） | `completed` | 仍 `processing` |
| TX-B 提交（结算完成） | `completed` | `completed` |
| TX-A/TX-B 抛异常 | `failed` | `failed`（`error_message` ≤500 字） |

> `grade_status` 与 `status` 分离的意义：题目落库即可回看（`completed` + `ungraded`），判分失败不影响"历史试卷可回看"这条需求。

---

## 2. 上传契约

### 2.1 为什么分段上传

- 微信小程序 `uni.uploadFile` 的 `filePath` 是**单文件**，无法一次 multipart 传 8 张；`uni.request` 传 8 张 base64 会超过小程序请求体限制（本设计按 10MB 量级保守处理）。
- 逐张上传把后端内存峰值压到 **1 张图/请求**，且 `spring.servlet.multipart.max-file-size: 10MB` 无需修改（`application.yml` 不动）。
- 逐张上传可单张重试，避免"第 7 张失败整卷重传"。

### 2.2 接口

| 方法 | 路径 | 入参 | 出参（响应字段） | 归属校验 | 说明 |
|------|------|------|----------------|---------|------|
| POST | `/api/student/exam/papers` | `subjectId`、`examDate`、`paperType`（§2.4 枚举）、`imageCount∈[1,8]` | `paperId`、`status`、`imageCount` | 当前登录学生（`StudentContext`） | 建上传会话；同学生+科目+日期已有非 `uploading` 试卷 → `1131` |
| POST | `/api/student/exam/papers/{paperId}/images` | multipart 字段名 **`file`**（单张） | `paperId`、`uploadedCount`、`imageCount`、`status` | paper.student_id + tenant_id | 校验归属/状态/类型/单图/总量/张数；达到 `imageCount` 时自动创建 task 并入队（兜底） |
| POST | `/api/student/exam/papers/{paperId}/submit` | — | `paperId`、`taskId`、`status` | paper.student_id + tenant_id | **正常触发路径**（前端上传完所有成功张后显式调用，`uploadedCount` 可小于声明 `imageCount`）；0 张 → `1187` |
| POST | `/api/student/exam/papers/{paperId}/retry` | — | `paperId`、`taskId`、`status`、`ocrRetryCount` | paper.student_id + tenant_id | 仅 `status=failed` 且 `ocr_retry_count < 2`（整卷最多重试 2 次，D14） |
| GET | `/api/student/exam/tasks/{taskId}` | — | `taskId`、`paperId`、`status`、`ocrRetryCount`、`attributionRetryCount`、`startedAt`、`completedAt`、`errorMessage`（≤500 字脱敏） | **task → exam_paper 校验 student_id + tenant_id**（越权/不存在统一 `1135`） | 上传页展示进度；旧 `/task/{taskId}` 下线（proposal §5.1） |
| GET | `/api/student/exam/papers` | `page`(≥1)、`size`(1–50) | §10.1 | student_id + tenant_id（SQL 条件） | 分页列表 |
| GET | `/api/student/exam/papers/{paperId}` | — | §10.2 | paper.student_id + tenant_id（越权 `1136`） | 详情 |
| ~~POST~~ | ~~`/result/{resultId}/grade`~~ | — | — | — | **删除**（拍板 #4） |

> **IDOR 防护（B4）**：`GET /tasks/{taskId}` 以前只校验 `tenant_id`。改为先查 task，再按 `task.exam_paper_id` 查 paper，校验 `paper.student_id == 当前学生` 且 `paper.tenant_id == 当前租户`；任一不满足返回 `1135`（不区分"不存在"与"非本人"，避免探测）。`/papers/{paperId}/images|submit|retry` 同理（不满足 → `1130`）。

### 2.3 校验与语义

| 校验 | 位置 | 规则 | 失败错误码 |
|------|------|------|-----------|
| 张数 | 创建会话 / 追加 | 声明 `imageCount ∈ [1,8]`；追加时 `已上传数 + 1 > 8` 拒绝 | `1182` / `1189` |
| 空文件 | 追加 | `file == null || file.isEmpty()` | `1183` |
| 单图大小 | 追加 | **业务上限 9.5MB**（不是 10MB，见下） | `1184` |
| 单请求总量 | 追加 | 一次上传（同一 paper 的累计）`Σ size ≤ 30MB`（逐张追加时按已上传累计 + 本次文件核算）；取值依据见 §11.3 | `1185` |
| 类型 | 追加 | `Content-Type ∈ image/jpeg,image/jpg,image/png`（沿用现有正则） | `1139`（复用） |
| 试卷类型 | 创建会话 | 必须是 §2.4 的 5 个枚举之一（大小写敏感、trim 后比较） | `1191` |
| 会话归属 | 追加/重试 | paper 必须属于当前 `studentId + tenantId` | `1130`（复用） |
| 会话状态 | 追加 | 仅 `status=uploading` 可追加；已满再追加 → `1189` | `1186` / `1189` |
| 上传完整性 | 触发解析（`submit`） | `uploadedCount ≥ 1`；`submit` 与"达到声明张数自动触发"二者只生效一次（CAS 到 `pending`，第二次返回 `1186`） | `1187` / `1186` |
| 重复上传 | 创建会话 | 同学生+科目+日期已存在 `pending/processing/completed/failed` 记录 → 拒绝；存在**30 分钟内**的 `uploading` 记录 → 复用（续传）；超 30 分钟的 `uploading` → 清空 `image_urls` 重置后复用 | `1131` |

> **9.5MB 而不是 10MB（S4）**：`application.yml` 的 `spring.servlet.multipart.max-file-size: 10MB` 与 `max-request-size: 10MB` 会让"恰好 10MB"的单图**先被容器拒绝**（`MaxUploadSizeExceededException`，当前 `GlobalExceptionHandler` 未映射，会落到通用 500）。本 change 采用双保险并写死：
> 1. **业务主口径**：`exam.parse.max-image-mb = 9.5`，超过即返回 `1184`（`1184` 文案同步改为"单张试卷图片不能超过 9.5MB"）；
> 2. **兜底映射**：`GlobalExceptionHandler` 新增 `@ExceptionHandler(MaxUploadSizeExceededException.class)`（含其父类 `MultipartException`）→ 返回 `1184`，避免容器层 500。
>
> 不改 `application.yml`（受保护文件，且 9.5MB 策略无需改）。

- 文件落盘仍在 `fileStorageService.upload(file, "exam-papers/" + studentId)`（事务外），URL 追加到 `exam_paper.image_urls`（JSON 数组）。
- 事务边界（约定 #10）：**文件上传在事务外**；只有"追加 URL + 可能的 task 创建 + 状态推进"在事务内（`TransactionTemplate`，≤4 条 SQL）。
- 孤儿会话：`uploading` 且 30 分钟未推进不复用即被下次同科目/日期上传重置，不新增定时清理任务。

### 2.4 `paperType` 枚举（S13 钉死）

| code | 中文（前端 picker 展示） | 说明 |
|------|------------------------|------|
| `midterm` | 期中考试 | **默认值** |
| `final` | 期末考试 | — |
| `monthly` | 月考 | — |
| `mock` | 模拟考试 | — |
| `other` | 其他 | — |

- 后端：`ExamPaperServiceImpl` 创建会话时校验（`Set.of(...)` 白名单），非法 → `1191 EXAM_PAPER_TYPE_INVALID`（"试卷类型无效，可选：midterm/final/monthly/mock/other"）；空值按 `midterm` 处理。
- 前端：`Profile.vue` 的 `examPaperTypes` / `examPaperTypeNames` 已与上表一致（现状即这 5 值），只新增"非法值兜底回 `midterm`"与接口 1191 的提示透传，不再新增取值。
- 展示：`ExamPaperListItemVO.paperType` 返回 code，前端映射中文（沿用现有 picker 映射表，不直接显示 code）。

---

## 3. 视觉解析契约

### 3.1 LLM 调用（`AgentScopeClient` 扩展）

```java
// 新增（同一 client 内），入参为图片字节 + mediaType，内部转 base64 data URL 语义
String callVision(String prompt, List<VisionImage> images);   // images: (mediaType, byte[] or base64)

// Msg 构造（AgentScope 2.0 API）
Msg.builder().role(MsgRole.USER).content(List.of(
        TextBlock.builder().text(prompt).build(),
        ImageBlock.builder()
                .source(Base64Source.builder()
                        .data(base64 /* 纯 base64；入参若带 "data:image/jpeg;base64," 前缀先剥离 */)
                        .mediaType("image/jpeg")
                        .build())
                .build()))
```

- **一个 client、两个 model 实例**：`model`（现状，文本）与 `visionModel`（新增，同 `apiKey/baseUrl/modelName/endpointType=MULTIMODAL`，额外 `.formatter(new DashScopeChatFormatter())`）。不新增模型名配置（拍板 #2），仍读 `agent.agentscope.model = qwen3.8-flash`。
- 单图调用沿用 `AgentScopeConfig.timeout`（120s）+ `maxRetries`，由 AgentScope `ExecutionConfig` 控制；本 change 不在此层加重试（重试语义在 `ExamParseAgent`，见 §3.4）。
- **每次调用只带 1 张图**：多图一次送入模型会导致 8×13MB base64 同时驻留，与 §11 冲突；一张图的题目通常 5–20 道，足够。

> **视觉能力已实测通过（2026-11-11，captain 执行；原 S6 阻断风险解除）**：`qwen3.8-flash` + `AGENT_ENDPOINT` 默认 endpoint，`POST {base}/api/v1/services/aigc/multimodal-generation/generation`，body `input.messages[0].content=[{"image":"data:image/png;base64,..."},{"text":"..."}]`、`parameters.result_format=message`；400×140 PNG（黑字 `H7Q2`）返回 `output.choices[0].message.content[0].text="H7Q2"`、`image_tokens=72`、HTTP 200、`request_id=657f1d71-8262-9101-a04e-612d155f1066`。结论：**支持图像输入，base64 data URL 可用**。仍保留降级路径（模型偶发不可用/输出非 JSON → 单图重试 2 次后 `pending_manual_review`），但**不再列为阻断级**；V0 在 tasks 中已标记完成并附上述证据。

### 3.2 Prompt：`prompts/versions/exam_parse_v1.txt`（新增）

占位符：`{subject}`、`{grade}`（`GradeMatcher.displayName`，约定 #23，空值"当前"）、`{imageSeq}`/`{imageTotal}`、`{retryHint}`（重试时附上次失败原因，首次为空）、**`{knowledgePoints}`（v4：候选知识点清单，格式见 §3.7）**。

Prompt 必须写清（模型看不到 Schema，约定 #25）：

1. **只输出 JSON**，不要 Markdown 代码块、不要解释文字。
2. 逐题抽取：题号（本图内从 1 连续）、题干、题型（`choice|numeric|short|self`）、选项、正确答案、**学生手写作答**、作答状态（`answered|blank|unreadable`）、难度（`basic|standard|challenge`）、**知识点 ID（从清单选）**、纯文本解析。
3. **题干只含印刷内容**；学生手写内容一律放 `studentAnswer`，禁止混入题干（这是"判分"的前提）。
4. 选择题：`choices` 为 `[{"label":"A","text":"..."}]`，`correctAnswer` 必须是**选项标签**（A/B/C…）；无法确定选项标签时 `correctAnswer=""`。
5. 非选择题：`correctAnswer` 为模型解出的标准答案（纯文本）；解答过程只写结论式答案，不要把整段推导塞进 `correctAnswer`。
6. 图片模糊/裁切/旋转导致某题无法识别 → 仍然输出该题，`answerStatus="unreadable"`，`unclearFields` 列出字段名；**不得臆造**未出现的题目。
7. **纯文本 + 数学记号（v4 收紧，约定 #24）**：禁止 `$...$` 公式定界符、`**`、`#`、反引号；**禁止任何反斜杠命令**（`\frac`、`\sqrt`、`\vec`、`\cdot` 等）与 **`^{...}` / `_{...}` 花括号写法**；允许纯文本 `x^2`、`a_n`；幂**优先** `²/³`（如 `x²`）或 `^2`，下标**优先** `a(n)` 或中文（如"a 下标 n"）；常用符号用 `√`、`≤`、`≥`、`×`、`÷`、`θ=90°`、`1/2`。
8. 不输出试卷总分、不输出"薄弱点总结"（产品规则 #4：不允许 LLM 直接给总结/掌握度）。
9. 多栏/分页：按"从上到下、从左到右"的阅读顺序输出；跨页重复出现的题只输出一次（后续由后端再按相似度兜底）。
10. **知识点选择（v4）**：每题的 `knowledgePointId` **只能**从 `{knowledgePoints}` 清单中选一个 ID 原样输出；**不得**杜撰、拼接、改写 ID，不得输出清单外的名称或编号；确实无法判断 → 输出空字符串 `""`（不要把题干数字或题号当 ID）。
11. 每题最多给 1 个知识点；若跨两个知识点，选**最核心**的一个（不要输出数组、不要用逗号分隔多个 ID）。

### 3.3 Schema：`prompts/schemas/exam_parse_schema.json`（新增）

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Exam Paper Parse Output Schema",
  "description": "试卷视觉解析输出（单图）",
  "type": "object",
  "required": ["questions"],
  "properties": {
    "questions": {
      "type": "array", "minItems": 0, "maxItems": 60,
      "items": {
        "type": "object",
        "required": ["questionIndex", "stem", "formatType", "studentAnswer", "answerStatus", "knowledgePointId"],
        "properties": {
          "questionIndex": { "type": "integer", "minimum": 1, "maximum": 60 },
          "stem": { "type": "string", "minLength": 1, "maxLength": 2000 },
          "formatType": { "type": "string", "enum": ["choice", "numeric", "short", "self"] },
          "knowledgePointId": {
            "type": "string",
            "maxLength": 64,
            "description": "v4：从注入清单中选择的知识点 ID；无法判断时为空字符串（服务端再做白名单校验）"
          },
          "choices": {
            "type": "array", "maxItems": 8,
            "items": {
              "type": "object", "required": ["label", "text"],
              "properties": {
                "label": { "type": "string", "maxLength": 2 },
                "text": { "type": "string", "minLength": 1, "maxLength": 300 }
              }
            }
          },
          "correctAnswer": { "type": "string", "maxLength": 500 },
          "studentAnswer": { "type": "string", "maxLength": 1000 },
          "answerStatus": { "type": "string", "enum": ["answered", "blank", "unreadable"] },
          "difficulty": { "type": "string", "enum": ["basic", "standard", "challenge"] },
          "explanation": { "type": "string", "maxLength": 1000 },
          "unclearFields": { "type": "array", "maxItems": 6, "items": { "type": "string" } }
        }
      }
    },
    "imageWarning": { "type": "string", "maxLength": 200 }
  }
}
```

上限依据：单图 ≤60 题（`maxItems` 与 `attribution_schema` 的 50 对齐时取 60 留余量），单卷总题数 **≤200**（超出截断并记降级日志）。

### 3.4 校验与重试（切题 2 次，与规则 #6 独立）

`AgentOutputValidator` 新增 `validateExamParse(String)`（复用现有"加载 Schema + 结构/枚举/length 校验"骨架）：

| 步骤 | 判定 | 处理 |
|------|------|------|
| 1 | JSON 解析失败 / 顶层不是对象 / `questions` 非数组 | **整图判失败** → 重试 |
| 2 | 单题结构非法（缺 `stem`、`formatType` 不在枚举、length 越界） | **丢弃该题**（不重试整图），`log.warn` |
| 3 | `formatType=choice` 且 `ChoiceOptions.isUsable(choices)==false` | 丢弃该题（约定 #25：禁止落"有题干无选项"的死题） |
| 4 | 有效题数 = 0 且原数组非空 | 整图判失败 → 重试 |
| 5 | `questions` 为空数组且 `imageWarning` 非空（整页无题，如封面） | **合法**：该图 0 题，不重试 |
| 6 | 文本字段 | 全部过 `LlmTextSanitizer.sanitize`（约定 #24，v4 收紧）；`stem/choices[].text/correctAnswer/explanation/studentAnswer`；额外兜底 `^{...}`/`_{...}` 花括号 → `^(...)`/`_(...)`、反斜杠命令 → 可读符号 |
| 7 | 选择题归一 | `ChoiceOptions.canonicalize`（label 重排 A/B/C…、`answer` → 标签）；仍归不出来 → `correctAnswer=null`（判分退化，见 §6） |
| 8 | 难度缺失/非法 | **解析层不兜底**（保持 `NULL`）→ 计算 `D_norm` 时缺失/非法一律 **0.5**（`validateDNorm` 语义，见 §7.3） |
| 9 | **`knowledgePointId`（v4）** | **白名单**：只接受本卷注入集合内的 ID（大小写敏感、trim）；空字符串合法（进兜底归因）；**不在集合 → 置空 + `log.warn`**（规则 #8：不得引用未注入/未启用知识点）；超长/含逗号多 ID → 置空 |

**重试计数（拍板 #3 + 规则 #4/#6 独立性 + D14/B3）**：

| 计数 | 存储 | 语义 | 上限 |
|------|------|------|------|
| 视觉切题（单图） | **仅内存**（`ExamParseService` 局部变量 + `ExamParseOutcome.cutRetryCount` 用于日志/降级摘要） | 单图最多重试 **2 次**（总尝试 3 次），每次把失败原因塞 `{retryHint}` | 2 次/图 |
| **整卷重试** | `exam_analysis_task.ocr_retry_count` | **只记"整卷重试次数"0..2**：每次调用 `POST /papers/{id}/retry` 时 +1；不累加单图重试 | 2 次（`< 2` 才允许 retry） |
| 归因 Agent | `exam_analysis_task.attribution_retry_count` | 归因批失败重试次数 | 2 次 |

- **`ocr_retry_count` 只有上面这一种语义（D14）**：单图内部重试**绝不写该列**，避免"8 张图各重试 2 次 → 该列变成 16"这种不可解释的值；单图重试只出现在日志与 `agent_degradation_log` 的输入摘要里。
- 切题重试（单图，内存）与归因重试（`attribution_retry_count`）**独立计数、互不共享配额**（规则 #4 vs #6）。

### 3.5 有界并发与总超时

```
pool  = ThreadPoolConfig#examParseExecutor（默认 core=max=3，queue=16，CallerRunsPolicy）
任务   = 单图解析（读字节 → base64 → LLM → 校验/重试）→ 返回结构化 DTO（不含任何图片字节）
deadline = 任务开始 + exam.parse.task-timeout-seconds（默认 180s）
单图预算 = min(exam.parse.image-timeout-seconds(60), deadline - now)
```

- 用 `ExecutorCompletionService` + `poll(remaining, MS)` 收集：任一图完成立即收结果（不等待最慢的那张再一起交），超时未完成的图 `cancel(true)` 并标记"该图解析失败"。
- 单图 LLM 调用自身的 AgentScope 超时（120s）是外层兜底；两者叠加保证 **单 task 必然在 ≤180s 内收尾**（成功、部分降级或失败）。
- 已完成的图即使整体超时也要落库（不因一张图拖死整卷）。

### 3.6 题号重排与跨图去重

1. 按图片顺序（`image_urls` 数组下标）拼接各图 `questions`。
2. **题号重排**：忽略模型给的 `questionIndex`，按"图序 → 图内顺序"重排为 `1..N`（模型题号跨图必重复/跳号）。
3. **跨图去重**：新题题干与已保留题干的 `SimilarityDetector.detectSimilarity ≥ 0.9` → 丢弃后出现者（跨页重复拍摄）。阈值 0.9 与规则 #3 出题查重 0.8 不同：这里只合并"同一题被拍两次"，不能误合并题型相近的不同题，故取更高阈值并在单测中固定该常量。
4. 总题数 >200 → 截断并写 `agent_degradation_log`。

### 3.7 知识点候选构造与注入（v4）

> **决定**：把"逐题知识点"前置到视觉解析（直出），`AttributionAgent` 降为**兜底**。规则 #8「Agent 只能引用已启用知识点」在服务端用**白名单**保证，不靠模型自觉。

**候选来源（服务端构造，唯一入口 `ExamKpCandidateService#build(subjectId, studentGrade, tenantId)`）**：

```sql
SELECT knowledge_point_id, name, grade_range
FROM knowledge_point
WHERE tenant_id = ? AND subject = ? AND status = 'active';   -- 显式列名，禁 select *
```

1. **年级过滤**：`GradeMatcher.matchesRange(studentGrade, grade_range)`；`grade_range` 为空 = 全年级通用，保留；学生年级为空 → 不做年级过滤（与规则 #11「空年级不过滤」一致）。
2. **排序（决定截断时保谁）**：① 年级**精确匹配**优先（`GradeMatcher.matchesExactGrade`）；② 该学生在 `student_knowledge_state` 有记录的知识点优先（考过的更可能再考）；③ `knowledge_point_id` 升序（稳定可复现）。
3. **分级上限（v4b 修正，解决"兜底不可达"）**：
   - **直出（parse）上限 = 150**（`exam.parse.kp-candidates.max=150`）：体积敏感（8 图 × 每图一次调用）。
   - **兜底归因（attribution）上限 = 500**（`exam.parse.kp-candidates.attribution-max=500`）：只对"直出为空"的题调用，通常 0–1 批，体积可接受。
   - 两者都基于**同一份 grade-filtered active 全集**排序（不是"parse 截断后的子集"）。

**超限结论（写死为「分级上限 + 兜底可达性保证」）**：

| 方案 | 结论 | 理由 |
|------|------|------|
| parse 150 / attribution 500（分级上限） | ✅ **采用（方案 a）** | 直出截断掉的 kp **仍会出现在兜底清单**（≤500 内），因此不存在"直出+兜底都不可达"的死区；兜底调用极少（仅未决题），体积可接受 |
| attribution 也沿用 150 | ❌ 否 | 正是 v4b 要堵的漏洞：截断掉的 kp 直出与兜底都无法命中 → 永远 `pending_manual_review` |
| 每图分片多次调用（150×N 分片） | ❌ 否 | parse 阶段调用次数 ×N（8 图 × 分片数），延迟与配额不可控；跨图题号/题面拼接易错 |
| 回退到"全量注入"（parse 也用全量） | ❌ 否 | 实测种子数据 `topics.json` 共 **3108** 条、单科目最多 **870**（数学），全量注入 ≈ 20k+ chars，parse 阶段体积与延迟不可控 |
| 本地关键词召回 TopN 再喂 LLM（方案 b） | ❌ 否（本轮不选） | 需要新增检索实现与阈值调优（`knowledge_point` 只有 `name` 可用于召回，题干长短差异大、召回率不稳），且 Demo 的 kp 规模用分级上限已能覆盖；若 verify 实测 attribution 仍 >500 再启用（§17 遗留） |
| 回退到"不注入、只靠 AttributionAgent" | ❌ 否 | 违背用户 v4 决定（直出为主） |

- **实测依据**：种子数据 `topics.json` 3108 条，按年级分布：7 年级 340 / 8 年级 355 / 9 年级 273（全科目合计）；单科目单年级通常在 50–150 区间。分科目合计最多 870（数学全学段）⇒ 年级过滤后仍可能 >150，故 **parse 截断是常态、attribution 500 一般够用**。
- **截断率统计（v4b 明确口径）**：`totalAfterGradeFilter` = 候选全集大小；`truncated = max(0, totalAfterGradeFilter - 150)`；`truncationRate = truncated / totalAfterGradeFilter`。
  - **超限必 warn**：`log.warn("[ExamKp] 候选截断: subjectId={}, grade={}, total={}, injected=150, rate={}")` **并** 写 `agent_degradation_log`（`agent_type=exam_parse`、`action=truncate_kp_candidates`、`input_summary` 记 subjectId/grade/total/injected/rate）。
  - 该指标写入 `exam_analysis_result.questions.stats`（`kpTotal`、`kpTruncated`）与 verify 报告，按科目/年级汇总。
- **兜底可达性证明（v4b 核心）**：兜底清单 = **全部 grade-filtered active kp**（≤500/片，见下），与 parse 的 150 无关 ⇒ **被 parse 截断的 kp 仍可被兜底命中**；仅当 `totalAfterGradeFilter > 500` 时按 `knowledgePointId` 升序**分片**（每片 500），未决题对每片各调一次（仅兜底路径，成本可接受）；若分片数 >2（>1000）→ 记 `agent_degradation_log(action=kp_unreachable)`，该批题置 `pending_manual_review` 并在 verify 报告标红（明确边界）。
- **缓存**：`ConcurrentHashMap<(tenantId,subjectId,grade), CandidateSnapshot>`（同一快照含 parse 150 与 attribution 500 两个切片）+ TTL `exam.parse.kp-candidates.ttl-seconds=600`（10 分钟），不引入新依赖；知识点导入/治理后最多 10 分钟生效（Demo 可接受，记录为已知延迟）。
- **Prompt 体积/延迟实测口径**：
  - parse 150 ×（ID ≤64 + 名称 ≤30 + 分隔）≈ 6–7k chars ≈ **4–5k tokens/图**，相对无清单 **+0.5–1.0s/图**；8 图/并发 3 ⇒ 整卷 **+2–4s**。
  - attribution 500 ≈ 22–25k chars ≈ **14–16k tokens/批**（仅未决题批次，通常 1 批）⇒ **+1.5–3s**，且只在 `unresolved > 0` 时发生。
  - 清单一卷只构造 1 次（缓存复用），不产生每图 DB 查询。
- **准确率验证（不可只靠单测）**：verify 阶段取 **2 份真实试卷**（≥20 题），人工比对每题 `knowledgePointId` 对应名称是否合理，统计四档：直出命中 / 兜底命中 / 仍无 kp / **因截断不可达**（后两档要分开，避免把"设计可达但模型没命中"与"设计不可达"混为一谈）。Demo 目标：直出准确率 ≥70%、**可归因率（直出+兜底）≥90% 且分母包含被 parse 截断的知识点**（即：截断不得成为不可归因的原因）；未达标则调 `max`/`attribution-max`/排序/补充 Prompt 示例后重测（结论写入 `verify-report.md`）。

---

## 4. 题面存储编码（零 DDL）

`exam_question` 现有列：`question_content`(TEXT)、`question_image_url`、`knowledge_point_id`、`attribution_status`、`student_answer`(TEXT)、`is_correct`、`score`、`full_score`、`question_type`、`difficulty`。**没有** `choices`、`correct_answer`、`explanation` 列，零 DDL 下必须编码进 TEXT 列。

### 4.1 编码格式（`ExamQuestionTextCodec`，新增工具类）

```
【题干】<stem 纯文本>
【选项】
A. <text>
B. <text>
【正确答案】<correctAnswer>
【解析】<explanation>
```

- 段落按需出现；**解析器容错**：无任何标记 → 整段作为 `stem`（兼容旧数据与降级题）。
- `student_answer` 走独立列，不进编码；`question_type` 存 `formatType`；`difficulty` 原样存 code。
- 体积核算：stem ≤2000 + 选项 ≤8×300 + 答案 ≤500 + 解析 ≤1000 ≈ 5.5KB ≪ TEXT 64KB。

### 4.2 出参（与作业答题区一致）

`ExamQuestionItemVO`（新）字段与 `homework` 的题目项对齐：

| 字段 | 来源 |
|------|------|
| `questionId` / `questionIndex` | 列 |
| `questionFormatType` | `question_type`（`choice/numeric/short/self`） |
| `difficulty` | 列 |
| `stem` | codec 解码 |
| `choices` | codec 解码 → **仅文本数组**（label 由前端按序号渲染，约定 #25） |
| `studentAnswer` | 列 |
| `correctAnswer` | codec 解码 |
| `explanation` | codec 解码 |
| `isCorrect` | 列（`null` = 待人工判定） |
| `score` / `fullScore` | 列（1 题 1 分制：`is_correct=1→1/1`、`=0→0/1`、`NULL→null/null`，B5） |
| `knowledgePointId` / `knowledgePointName` | 列 + 批量映射 |
| `attributionStatus` | 列 |
| `answerState` | 由 `studentAnswer` 是否为空 + `isCorrect` 推导：`blank`（空）/`unreadable`（NULL 且非空）/`judged` |

前端复用 `HomeworkDetail.vue` 的渲染结构：`choice` 渲染只读选项（我的答案高亮 + 正确答案高亮），`numeric/short/self` 渲染只读答案框，底部仅"上一题/下一题"（无提交按钮）。

---

## 5. 归因：视觉直出为主 + `AttributionAgent` 兜底（v4，归因前置）

> **v4 决定**：知识点由视觉解析**逐题直出**（候选清单见 §3.7），`AttributionAgent` 从"必经环节"降为**兜底**。规则 #4 原文是"先视觉切题、再由归因 Agent 归因"，v4 把归因**前移**到视觉解析，归因 Agent 只在直出为空时补一次 → **规则 #4 的回写待办见 ARCH1**。

**判定顺序（每题三档，互斥）**：

```
1. 直出：questions[].knowledgePointId 非空 且 ∈ 本卷注入集合（§3.4 第 9 步白名单）
   → knowledge_point_id = 该 ID，attribution_status='completed'，计入 directKpCount ✅ 不再走归因
2. 兜底：直出为空 / 被白名单置空
   → 进入兜底批次（≤40 题/批，批间串行），调用 AttributionAgent（v4 新增"传入候选清单"重载）
   ├ 命中 → knowledge_point_id = 结果，attribution_status='completed'，计入 fallbackKpCount
   └ 未命中/置信度 low/批次失败重试耗尽 → knowledge_point_id=NULL，attribution_status='pending_manual_review'
                                          ，计入 unresolvedKpCount
3. 无 kp 的题：不更新掌握度、不入错题、详情页显示"待人工归因"（§7.2 / §12），由开发者按规则 #4 的后端接口人工处理（后续 change）
```

**统计口径（可观测，用于 verify 抽样与调参）**：

| 指标 | 计算 | 落地 |
|------|------|------|
| `directKpCount` | 白名单通过的直出题数 | 日志 + 学习记录 JSON |
| `fallbackKpCount` | 兜底归因补上的题数 | 同上 |
| `unresolvedKpCount` | 最终仍无 kp 的题数 | 同上 |
| `unjudgedCount` | 判分不可判定题数（`is_correct IS NULL`，含"标准答案缺失""作答无法辨识""预算超时"，§6.1） | 同上（与 kp 统计并列，互不影响） |
| `directRate` | `direct / 总题数` | verify 报告（目标 ≥70%，§3.7） |
| `resolvedRate` | `(direct + fallback) / 总题数` | verify 报告（目标 ≥90%，**含被截断知识点可达性**） |

- 写入位置：`exam_analysis_result.questions` JSON 顶层（零 DDL），**键名以实现为准（v4c 校正）**：`{"paperId":12,"source":"exam_paper","stats":{"judged":16,"unjudged":3,"kpDirect":11,"kpFallback":2,"kpUnresolved":6,"kpTotal":128,"kpTruncated":0},"items":[...]}`（`buildOutcomeStats`：`kpDirect/kpFallback/kpUnresolved/kpTotal/kpTruncated` + `judged/unjudged`；v4b 起 `kpStats` 统一为 `stats`）；同时 `log.info` 打印各档计数。
- 兜底批次：每批 ≤40 题（`attribution_schema.maxItems=50` 之下留余量）、批间串行；批内异常重试 ≤2 次（累加 `attribution_retry_count`），仍失败 → 该批全部 `pending_manual_review`，**不影响已直出的题**。
- **接口兼容**：`AttributionAgent.attribute(ocrResults, subjectId, tenantId)` 保留（其他/未来调用方），v4 新增 `attribute(ocrResults, candidates, tenantId)` 重载；`AttributionAgentImpl` 现有"按 subject+active 查全量 kp"的逻辑在重载里改为**直接用传入候选清单**（v4b：**attribution 清单 = grade-filtered active 全集，上限 500**，不是 parse 的 150；>500 时按 kpId 分片，每片 500，仅对未决题各调一次），原方法内部改为"自建候选后调用重载"（行为不变）。
- **可达性**：parse 截断（>150）不影响兜底——兜底清单是 500 切片的全集，被 parse 截断的 kp 仍可命中；`totalAfterGradeFilter > 1000` 才可能出现不可达（记 `kp_unreachable` 降级日志 + 该批 pending，verify 标红）。
- **重试计数**：兜底归因仍是 `attribution_retry_count`（≤2），与单图切题重试（内存）独立（§3.4）。
- **kp 为空的题如何进 `pending_manual_review`**：见上面第 2/3 档——直出空 → 兜底；兜底仍空/置信度 low → `attribution_status='pending_manual_review'` + `knowledge_point_id=NULL`；不更新掌握度、不入错题（沿用 §7.2「无知识点不入队」）。

### 5.1 OCR 去留（旧链路退役，不删代码）

| 维度 | 结论 |
|------|------|
| `OcrService` / `TencentOcrServiceImpl` | **保留类与 Bean，标注 `@Deprecated`**，试卷链路不再注入调用。理由：删除会牵动 `tencentcloud-sdk-java-ocr` 依赖、腾讯云密钥环境变量与部署文档，属独立 change（§17-1），本 change 不做 |
| `ExamQuestionOcrDTO` | **保留**，作为"解析结果 → 归因 Agent"的适配 DTO（字段：`questionIndex/questionContent=stem/questionType/difficulty`），`AttributionAgent` 接口零改动 |
| `exam_analysis_task.ocr_retry_count` | 列名不变（零 DDL），语义改为**整卷重试次数 0..2**（每次 `retry` 入口 +1）；**单图重试只在内存计数，不写该列**（D14/B3） |
| `exam_analysis_task.task_type` | 新任务写 `vision_parse_attribution`；Worker 不按 `task_type` 过滤，历史 `ocr_attribution` 任务仍可被消费 |
| `ErrorCodes.OCR_FAILED(1137)` | 标 `@Deprecated`（无调用方）；`FILE_UPLOAD_FAILED`/`FILE_TYPE_INVALID` 继续复用 |
| 降级语义 | OCR "整图失败 → 全部待人工归因"占位题**不再落库**（避免脏题面）；改为"该图 0 题 + `imageWarning` + `agent_degradation_log`"，原图仍保留在 `exam_paper.image_urls` 供人工查看/重试 |
| 代码引用 | `ExamPaperServiceImpl` 移除 `OcrService` 注入、`ocrWithRetry`；`exam_analysis_task.error_message` 记录视觉解析失败摘要（≤500 字） |

---

## 6. 判分（复用方式）

### 6.1 判分契约：**三态**（true / false / null）——标准答案缺失不得判错（v4b 阻塞修复）

> **真实图实测暴露的问题**：第 1 页 6 道选择题里有 4 道视觉模型给不出 `correctAnswer`（卷面不印答案、模型也没解出来）。若按 S3 的"缺答案 → false"，会出现**学生可能答对却被判错** → 掌握度被错误压低 → 错题本进脏数据。**S3 的"缺答案 → false"作废**，改为下面的三态。本服务从 `HomeworkServiceImpl` 抽取（纯搬移 + 签名统一，**唯一行为变化就是"标准答案缺失"从 false 改为 null**）。

```java
public interface AnswerGradingService {
    /** 三态：TRUE=判对、FALSE=判错、null=不可判定（不调 AI / 无标准答案 / 无法辨识 / 超时） */
    Boolean grade(String formatType, String questionContent, String correctAnswer,
                  String studentAnswer, String subjectName, String gradeLabel);

    /** 批量判分：null 语义同上；预算内未完成的题返回 null */
    Map<Integer, Boolean> gradeBatch(List<GradeRequest> requests, String subjectName,
                                     String gradeLabel, Duration budget, int concurrency);
}
```

| 学生作答 | 标准答案 | `is_correct` | 是否调 AI | 掌握度 / 错题 / 统计 |
|---------|---------|-------------|-----------|---------------------|
| 空（`blank`） | 有 | **`false`** | 不调（未作答） | 更新（按答错）/ 入错题 / 计入 `judgedCount` 分母 |
| 空（`blank`） | **空/无法确定** | **`NULL`** | 不调 | **不计掌握度、不入错题**；计入 `unjudgedCount`（待人工判定） |
| 有 | 有 | `true` / `false`（choice=标签比较；其余 `grading_v1.txt`，解析不了/异常回字符串比较） | choice 不调；其余调 | 按对错更新 / 答错入错题 / 计入分母 |
| 有 | **空/无法确定** | **`NULL`** | **不调**（非选择题也不调，避免"瞎判"） | **不计掌握度、不入错题**；详情显示"标准答案缺失，待人工判定"；计入 `unjudgedCount` |
| `unreadable`（手写无法辨识） | 任意 | **`NULL`** | 不调 | 不计掌握度、不入错题；计入 `unjudgedCount` |
| 批次预算内未完成 | 任意 | **`NULL`** | 超时 | 同上 |

- **`NULL` 的唯一含义是"不可判定"**，来源四类：学生作答无法辨识、标准答案缺失、学生与标准答案同时缺失、批处理超时。**绝不因为"没答案"判错**。
- **`judgedCount` 口径（与 §7.5 一致）**：只统计 `is_correct IS NOT NULL` 的题；`NULL` 题**既不计分子也不计分母**，因此"正确率/得分"不会被害群之马拉低。
- **`pending_manual_review` 统计口径**：`attribution_status` 仍只表达 kp 归因结果；判分不可判定另计 `unjudgedCount`（见 §5 的 stats），二者独立、都在详情页分别提示（"待人工归因" vs "待人工判定"）。
- **作业侧（共享服务）必须同步支持 null**：`HomeworkServiceImpl#gradeHomework` 的 `boolean[]` 改为三态载体；`is_correct=NULL` 时不计入 `correctCount`、不触发错题入队/`advanceStage`/`resetStage`；`MasteryServiceImpl#processSubmissions` 增加 `is_correct == null → continue`（现状 `getIsCorrect()!=null && ==1` 会把 null 当答错，必须修）。题库/生成题正常都有答案，因此作业侧实际不触发，但契约与防护必须一致。
- 并发（D15/S1）：判分任务提交到 `examParseExecutor`，并且与视觉解析、归因**共用同一个全局 `Semaphore(3)`**（`LlmConcurrencyLimiter`），保证任一时刻在途 LLM 调用 ≤3；**batch 总预算** `exam.parse.grading-timeout-seconds`（默认 60s），预算内未完成的题 → `null`。
- 年级传参：`GradeMatcher.displayName(grade)`，空值"当前"（约定 #23）。科目名取 `subject.name`。
- `HomeworkServiceImpl.update()` 改为委托：`choice` 比较与 `aiGradeQuestion` 调用点替换为 `answerGradingService.grade(...)`，**解析生成（`analysis_gen_v1.txt`）与并行结构保持原样**；`HomeworkServiceTest` 全绿 + 新增 null 三态断言作为验收依据。
- 试卷侧不生成题目解析（解析来自视觉解析的 `explanation`），因此**不调用 `analysis_gen_v1.txt`**，减少一次 LLM 往返。

---

## 7. 结算（错题 / 掌握度 / 日志）

### 7.1 顺序与事务

```
TX-A（解析完成即提交，短事务）
  ├ 批量插入 exam_question（is_correct=NULL，attribution_status 已定，≤100/批）
  ├ update exam_paper set status='completed'
  └ 不推进 task 状态（仍 processing，表示"待结算"）

判分（事务外，LLM，≤3 并发，60s 预算）

TX-B（结算，短事务；仅 DB 写，无外部调用 — 约定 #10）
  ├ CAS: update exam_paper set grade_status='graded', total_score=judgedCount where id=? and grade_status='ungraded'
  │      （0 行 = 已被结算过：跳过错题/掌握度/学习记录，直接执行最后一步把 task 置 completed）
  ├ 批量更新 is_correct + attribution_status（CASE WHEN，≤100/批）
  ├ 错题批量入队（ErrorQuestionService 批量入口，source_type=EXAM，source_id=paperId）
  ├ 掌握度批量更新（MasteryService 批量入口；答对答错都更新）
  ├ 写学习记录（exam_analysis_result，1 条；见 §7.5）
  └ update exam_analysis_task set status='completed', completed_at=now()
```

- **错题先于掌握度**（沿用 `IMP-25` 结论：`syncPauseStatus` 依赖错题已入队）。
- TX-A 与 TX-B 都不含 LLM 调用；TX-B 语句数 ≤ 9（含批内拆分与学习记录查重），预计 <150ms。
- TX-B 失败 → `task.status='failed'`，题目保留、`grade_status='ungraded'`、`is_correct` 保持 `NULL`（不产生半套掌握度/学习记录）。

### 7.2 错题入队（复用 `ErrorQuestionService`，新增批量入口）

```java
int recordWrongAnswers(Long studentId, String tenantId, List<WrongQuestionRef> items,
                       ErrorQuestionSourceType sourceType, Long sourceId, LocalDate wrongDate);
// WrongQuestionRef = (questionId, knowledgePointId)
```

- **`questionId = -exam_question.id`**（D4）：`error_question.question_id` 是 `NOT NULL` + `uk_student_question(student_id, question_id, tenant_id)`；`question_bank`/`generated_question` 的 ID 都是自增正数，负值保证不碰撞、且能被 `question_id > 0` 的题库查询天然排除。
- 批量步骤：`SELECT question_id ... WHERE student_id=? AND tenant_id=? AND question_id IN (...)`（1 条）→ 已存在则批量 UPDATE（重置 `last_wrong_date/current_stage/status`，语义同现有 `recordWrongAnswer` 的 DUPLICATE 回退）→ 不存在则批量 INSERT（≤100/批）；`DuplicateKeyException` 兜底再走一次批量 UPDATE。
- 无知识点（`pending_manual_review`）的错题**不入队**（无 kp 无法进入艾宾浩斯/知识点维度的调度）。v4 下该状态的来源是「直出为空 + 兜底归因仍未命中」（§5 第 2/3 档），不是解析失败。
- **配套（避免负 ID 污染既有查询，S9）**：
  - `ErrorQuestionMapper.selectPageWithDetail`：新增 `LEFT JOIN exam_question eqx ON eq.source_type='exam' AND eqx.id = -eq.question_id AND eqx.tenant_id = eq.tenant_id`，题面 `COALESCE(qb.content, eqx.question_content, '[题目已删除]')`、难度 `COALESCE(qb.difficulty, eqx.difficulty, 'unknown')`。`eqx.id` 是主键侧，`EXPLAIN` 应为 `eq ref PRIMARY`。
  - `ErrorQuestionMapper.selectDetailById`：同上，且**同时兜底详情页其余字段**，否则错题详情空白：
    `question_type = COALESCE(qb.type, eqx.question_type, 'short')`、`question_choices = qb.choices`（试卷题无结构化选项，保持 NULL，前端按纯文本渲染）、`correct_answer = COALESCE(qb.answer, '')`（试卷题的正确答案在 `question_content` 的【正确答案】段落里，由前端整段展示）、`question_analysis = COALESCE(qb.analysis, '')`（试卷题为【解析】段落在内容里）。
  - `ErrorQuestionMapper.selectDueQuestionsByKnowledgePoint` + `selectDueKnowledgePointIds`：**不过滤**（v3：试卷错题要进入原题重做，负 ID 行正常返回，由组装层按符号分流为 `questionSource="exam"`，见 §7.2.1）。
  - `ErrorQuestionMapper.selectActiveErrorCountGroupByKp`：**不过滤**（v3 撤销 N1 的排除）：试卷错题要占配额分母；`KP_MAX=4` 的异常风险改由 service 侧 clamp 解决（§7.2.1「配额与 KP_MAX」）。
  - `ErrorQuestionVO.redoSupported`：v3 起**恒为 true**（试卷错题已支持原题重做）；保留字段仅用于前端兼容，不再展示"暂不支持原题重做"。
  - `docs/product/index.md` 规则 #3 记录该口径（§4 授权记录）。

### 7.2.1 试卷错题回流每日作业：原题重做 + 举一反三（**v3，推翻 B6 方案 (b)**）

> **用户 2026-11-11 明确要求**：试卷错题**必须**进入每日作业的「原题重做」与「举一反三」。上一版"试卷错题不参与"的方案 (b) 作废，相关章节（§7.2/§7.6/§15/proposal §3/§7/tasks B6）同步重写。

**引用形态（关键，先钉死再谈实现）**：

| 位置 | 取值 | 原因 |
|------|------|------|
| `error_question.question_id` | **`-exam_question.id`**（负） | `uk_student_question(student_id, question_id, tenant_id)` 要求跨来源唯一；bank/generated 是自增正数，正数 exam id 必撞（D4） |
| `daily_plan.content[].questionSource` | **`"exam"`**（第三种来源） | `ContentParser` 按 String 透传，无需改解析器；语义显式 |
| `daily_plan.content[].questionId` | **`-exam_question.id`**（与 `error_question.question_id` **同值**） | **否定"用正数 exam_question.id"**：`homework_submission_detail` 的唯一键是 `uk_plan_question(plan_id, question_id, tenant_id)`，同一份作业里 `question_bank.question_id=42` 与 `exam_question.id=42` 会经 `insertOrUpdate` 的 `ON DUPLICATE KEY UPDATE` **互相覆盖**（答 A 题覆盖 B 题）。负值与 bank/generated 正整数空间不相交，且让"答题 → stage 推进"零转换 |
| `homework_submission_detail.question_source` / `question_id` | `"exam"` / `-exam_question.id` | 与 ref 一致；`question_source` 为 `VARCHAR(20)`，长度足够 |

> **备选方案（已否）**：把试卷题写入 `question_bank` 副本再引用。否掉的理由：① 违反规则 #10「题库只经 JSON 导入 + 人工审核」，OCR 题面直接入库会绕过治理；② 同一道题按学生重复落库、污染题库统计；③ `generated_question` 语义是"出题 Agent 的举一反三产物"，混入试卷题会改变规则 #3 的配额语义。**结论：保持零 DDL + 负 ID 单一命名空间。**

**作业链路改造清单（Backend D）**：

| 环节 | 触点 | 改动 |
|------|------|------|
| 到期统计 | `selectDueKnowledgePointIds` / `selectDueQuestionsByKnowledgePoint` | **撤销 `question_id > 0` 过滤**（v3 反转 B6/N1）：负 ID 行正常返回，交由组装层按符号分流 |
| 错题引用组装 | `HomeworkServiceImpl#generateDailyHomework` | `vo.getQuestionId() < 0` → `new DailyPlanQuestionRef(id, "exam", "redo")`，否则维持 `("bank","redo")`；两者都进 `errorRefs` / `occupiedQuestionIds` / `dueErrorCount` |
| 题面组装（5 处） | `buildHomeworkVO`、`findCorrectAnswer`、`getQuestionContent`、`getQuestionFormatType`、`findKnowledgePointId` | 增加 `"exam"` 分支：按 `abs(refId)` 1 条批量 `examQuestionMapper.selectBatchIds` → `Map<Long, ExamQuestion>`（key = `-id`）；`ExamQuestionTextCodec.decode` 取 `stem/choices/correctAnswer/explanation`，`LlmTextSanitizer` 兜底，`choices` 只出文本（约定 #25） |
| 提交 | `submitAnswer` / `SubmitAnswerRequest` / `findKnowledgePointId` | 来源白名单加 `exam`；(questionId, questionSource) 归属校验天然支持；kp 从 `exam_question.knowledge_point_id` 取 |
| 判分 | `gradeHomework` | 走同一 `AnswerGradingService`（choice 字符串比较 / 其余 `grading_v1.txt` 失败回比较）；**不新增第二套判分** |
| 错题 stage | `gradeHomework` 的 redo 分支 | `questionType='redo'` && `question_source='exam'` → `advanceStage/resetStage(studentId, tenantId, submission.getQuestionId())`：`submission.questionId` 本身是负数，**直接命中 `error_question`，无需任何转换** |
| 掌握度 | `updateMasteryFromHomework` | 无需改：按 submission 的 kp 更新，W_source=作业 0.4（规则 #2） |

**stage 生命周期（v3 反转"永不推进"）**：

1. exam redo 答对 → `advanceStage`：1→2→3→4→5→`completed`（艾宾浩斯正常节奏，规则 #3）；
2. exam redo 答错 → `resetStage`：回 stage 1、刷新 `last_wrong_date` 与 `next_due_date`（与题库错题完全一致）；
3. 掌握度 ≥0.8 时 `syncPauseStatus` 仍会把 `active` 置 `paused`（规则 #3），跌破 0.8 自动 reactivate——与艾宾浩斯并行不冲突（paused 期间不进到期列表）；
4. `completed` 后该错题不再计入 `selectActiveErrorCountGroupByKp`（只统计 `active`）→ `kpErrorCountMap` 自然回落，不会长期占用配额分母；
4. **顺带修复（既有缺陷，v3 才真正暴露此路径）**：`completeStageWithCheck` 现在写 `next_due_date = NULL`，但 `error_question.next_due_date` 是 `DATE NOT NULL`（V3 DDL），stage 到 5 完成时会 SQL 报错。改为 `next_due_date = #{completedDate}`（用完成日占位；`completed` 行不会被到期查询选中），补单测。题库来源 redo 同样受益。

**配额与 `KP_MAX=4`（N1 的正确解：用户 2026-11-11 选定方案 A —— clamp 到 4，超出顺延）**：

| 点 | 决定 |
|----|------|
| `selectActiveErrorCountGroupByKp` | **不过滤**（撤销 t15 的 `question_id > 0`）：试卷错题现在确实占 redo/举一反三名额，必须进分母 |
| `HomeworkQuotaCalculator` 的 `KP_MAX=4` 语义 | **不改**（用户拍板：保留断言作为防御），只改"上游喂给它的 map" |
| 夹取（方案 A） | `HomeworkServiceImpl` 构造 `kpErrorCountMap` 时 `Math.min(raw, KP_MAX)`，每 kp 首次超限 `log.warn`（记录 raw 值）；**按 kp 合计 clamp**（题库 + 试卷混排时合计超 4 就夹到 4，不是各算 4） |
| 超出部分顺延 | 当日未被抽中的到期错题保持 `status='active'`、`next_due_date` 不变 → 次日继续到期；**不写 `daily_plan` 未完成、不触发 SIG-3**（未完成判定只看 `daily_plan.status`） |
| 当日进作业上限 | 每 kp 每日 redo ≤3（现有 `selectDueQuestionsByKnowledgePoint` 的 `LIMIT 3`），天然 ≤ `KP_MAX=4`；"一张试卷同 kp 错 20 题"分多天消化 |
| 效果 | 某 kp 有 5+ 试卷错题时**不再抛 `IllegalArgumentException`** → 次日作业生成不失败；同时顺带修掉既有来源同风险（题库/生成题累计 >4 道活跃错题时同理会炸，§17-6 观察项**升级为已修**） |
| 语义影响 | `kpErrorCountMap` 只用于"有错题知识点"的配额排序与每 kp 上限夹取，值 >4 不产生额外配额（`dueErrorCount` 由 refs 数与每 kp `LIMIT 3` 决定），clamp 不改变分配结果，只消除异常 |

**举一反三源题（v3 取舍：题库优先 + exam 兜底，均不落库）**：

1. 先走现有 `selectSourceQuestionForSimilar`（`JOIN question_bank`），命中即用，行为不变；
2. 未命中（该 kp 只有试卷错题 / 题库无题）→ 新增 `selectDueExamSourceQuestion(studentId, tenantId, kpId, targetDate)`：
   `SELECT eqx.id, eqx.question_content, eqx.question_type, eqx.difficulty, eqx.knowledge_point_id FROM error_question eq JOIN exam_question eqx ON eqx.id = -eq.question_id AND eqx.tenant_id = eq.tenant_id WHERE eq.student_id=? AND eq.tenant_id=? AND eq.knowledge_point_id=? AND eq.status='active' AND eq.next_due_date <= ? ORDER BY eq.next_due_date ASC, eq.error_question_id ASC LIMIT 1`（`eqx.id` 主键侧 `eq_ref`）；
3. 适配为**临时 `QuestionBank`（transient，不落库）**：`content = 题干 + 选项行`（codec 解码后拼纯文本）、`answer = correctAnswer`、`analysis = explanation`、`type = questionType`、`difficulty`、`knowledgePointId`、`choices = 选项 JSON`、`grade = null`；
4. 仍以 `QuestionBank` 入参调用 `SimilarQuestionService.generateWithValidation(...)`，**签名零改动**；
5. 若 exam 源题的 `correctAnswer` 缺失（视觉解析未解出）→ **跳过该 kp 的举一反三** + 写 `agent_degradation_log`（`input_summary` 记 kpId 与 "exam source missing answer"），不阻塞其他 kp；
6. **不落 `question_bank` 副本**（理由同上），保证规则 #10 治理不被绕过。

**去重与题量一致性**：

- exam redo 的 `questionId` 为负数，天然与 bank/generated 正数不冲突；`occupiedQuestionIds`（`Set<Long>`）直接复用；
- 组装完成后对 `daily_plan.content` 做一次 `(questionSource, questionId)` 去重校验，重复丢弃 + `log.warn`（防御未来来源扩展）；
- 题量：exam redo 计入当日 15 题上限与单 kp 4 题上限（配额计算已覆盖）；每 kp 每日最多 3 道到期错题（现有 `LIMIT 3`），"一张试卷同 kp 错 20 题"不会挤爆某天；
- 题面：exam redo 是"考试原题"，**不调用 `analysis_gen_v1.txt`**；`analysis` 落 `explanation`（可选），无解析时前端显示"暂无解析"。

**规则 #3 回写口径（ARCH1）**：*"考试试卷来源的错题与题库错题一视同仁：进入艾宾浩斯到期队列，可被每日作业抽取为原题重做，也可作为举一反三的源题；题面来自试卷解析结果（`exam_question`），不再有'不参与'的例外。"*

### 7.3 掌握度（答对 / 答错都更新）

```java
// MasteryService 新增批量入口（同一 Service、同一 MasteryFormulaUtil、同一日志字段）
void updateMasteryFromExam(Long studentId, String tenantId, Long paperId,
                           List<ExamMasteryEvent> events);   // (knowledgePointId, isCorrect, dNorm)
```

- `W_source = ChangeType.EXAM.getSourceWeight() = 1.0`（规则 #2）。
- **`D_norm` 必须过 `MasteryFormulaUtil.validateDNorm`（B1，规则 #2）**：试卷难度是**视觉模型声明的**，与"新题难度需与同知识点历史均值偏离 ≤0.2"同一风险口径。

  ```
  1. declared = MasteryFormulaUtil.mapDifficulty(difficulty)        // basic 0.3 / standard 0.6 / challenge 0.9
     └ difficulty 非法或缺失 → catch IllegalArgumentException → 直接取 0.5（不走枚举兜底 0.6）
  2. avg = 同知识点历史难度均值（题库元数据，规则 #2「原题使用题库元数据中的难度值」）
     └ 1 条 SQL：SELECT knowledge_point_id, difficulty, COUNT(*) FROM question_bank
                 WHERE tenant_id=? AND knowledge_point_id IN (...) AND difficulty IN ('basic','standard','challenge')
                 GROUP BY knowledge_point_id, difficulty
       内存按 COUNT 加权求均值（映射值），无数据 → null
  3. dNorm = MasteryFormulaUtil.validateDNorm(declared, avg)
     └ avg == null          → 0.5（D_NORM_DEFAULT）
     └ |declared - avg| >0.2 → 0.5（降级，常量 D_NORM_DEVIATION_THRESHOLD）
     └ 否则                  → declared（保留 4 位小数）
  ```

  - **兜底统一为 0.5**（与现有 `ExamPaperServiceImpl.convertDifficultyToDNorm` 的 `0.5` 完全一致，B1 修正了上一版误写的 0.6）。
  - 单选题/主观题一视同仁；同一知识点多题各自算自己的 dNorm（平均只用于降级判断，不用于赋分）。
  - 该步骤只做**一次批量查询**（1 条 SQL + 内存计算），不产生 N+1；查询走 `question_bank` 的 `idx_knowledge_point`（既有索引，零 DDL）。
- 实现：
  1. `events` 按 `knowledgePointId` 分组；
  2. **1 条 SQL** 批量查 `student_knowledge_state`（`IN kpIds`，命中 `uk_student_kp`），缺失的按现有 `findOrCreateState` 语义批量创建（`INSERT IGNORE` 风格：批量 insert + 冲突忽略后重查一次，仍然 ≤2 条 SQL）；
  3. 内存中按事件顺序套用 `MasteryFormulaUtil.calculateMasteryCorrect/Incorrect` 与 `updateConfidence`（与逐题调用等价：`m_new` 依次演进）；
  4. **批量 UPDATE** `student_knowledge_state`（CASE WHEN，≤100/批）；
  5. **批量 INSERT** `mastery_change_log`（每事件一条，`<foreach>`，≤100/批）；
  6. `syncPauseStatus`：按去重后的 kp 逐个调用现有方法（kp 数 ≤ 题数，通常 ≤20，语义幂等）。
- 与现有 `updateMasteryFromResult` 的关系：单条入口保留（评测仍用），批量入口**不复制公式**，只复用私有计算与日志装配；`updateMasteryFromResult` 保持原样。

### 7.4 掌握度日志字段（`mastery_change_log`）

| 字段 | 取值 | 说明 |
|------|------|------|
| `student_id` / `tenant_id` | 显式入参（Worker 不读 ThreadLocal） | 约定 #7/#9 |
| `knowledge_point_id` | 该题归因结果 | 无 kp 的题不产生日志 |
| `change_type` | `exam`（`ChangeType.EXAM.getCode()`） | 与 SIG-2 的 `change_type='exam'` 一致；**不新增枚举形态**（约定 #21 不触发） |
| `source_id` | `exam_paper.id` | 追溯链：试卷 → 题目 → 日志 |
| `delta` | `mastery_after - mastery_before` | 答对为正、答错为负（SIG-2 依赖 `MIN(delta) < 0`） |
| `mastery_before` / `mastery_after` | 0.00–1.00 | 公式夹取（答对上限 1.0、答错下限 0.05） |
| `create_time` | 自动 | 日志表无 `update_time` 语义，只追加（规则 #9） |

同一知识点多题 → **每题一条日志**（可追溯），`student_knowledge_state` 只写一次终值。

### 7.5 学习记录写入（规则 #9，B5 方案 (a)）

**决定**：TX-B 结算成功后，同步写 1 条 `exam_analysis_result`，让「学习记录」的 `EXAM` 筛选可见。

**读取方口径核对**（先核对再设计，避免"写了看不到"）：

| 读取位置 | 依赖字段/条件 | 结论 |
|---------|--------------|------|
| `LearningRecordMapper.xml#selectUnionAllRecords` / `countUnionAllRecords` | `e.student_id`、`e.tenant_id`、**`e.status='completed'`**、`e.analyzed_at`（日期筛选）、`e.create_time`（排序）、`e.exam_name`（标题）、`e.subject_id`、`e.user_score`（分数列） | 这些都是**必填**；`recordType` 映射为 `EXAM`，前端已有中文标签 |
| `LearningRecordServiceImpl#buildExamDetail` | `selectByIdAndStudentId`（含 student_id/tenant_id）→ `exam_name`、`user_score`、`total_score`；`create_time` 作为 `recordDate`；`scoreRate = user_score/total_score`（`total_score > 0` 时） | 需要 `total_score > 0` 才能显示正确率，**必须写**；`user_score` 不能为 null（否则 `divide` NPE，见第 222 行 `exam.getUserScore().divide(...)`） |
| `getMasteryChangesBySource(id, "EXAM")` | `mastery_change_log.change_type = 'EXAM'` | **既有缺陷**：落库值是 `ChangeType.EXAM.getCode()` = 小写 `exam`，此处传大写 → 永远查不到。顺带修复（见 §7.6） |

**字段映射（一条试卷一条记录）**：

| `exam_analysis_result` 列 | 取值 | 说明 |
|--------------------------|------|------|
| `student_id` / `subject_id` | `exam_paper.student_id` / `subject_id` | — |
| `exam_name` | `{科目名}{试卷类型中文} {考试日期}`，如"数学期中考试 2026-11-11"（≤200 字） | 列表标题；也用于查重 |
| `image_urls` | `exam_paper.image_urls`（JSON 数组字符串） | 原图可回溯（规则 #9） |
| `questions` | JSON **对象**：`{"paperId": 12, "source": "exam_paper", "items": [{"questionIndex":1,"knowledgePointId":"kp-1","questionType":"choice","difficulty":"standard","isCorrect":1}, ...]}` | 只存轻量摘要（≤200 题 ≈ 20KB），**不存题干全文**（题干由 `/papers/{id}` 提供）；`paperId` 内嵌用于排查与追溯 |
| `total_score` | **已判定题数**（`is_correct IS NOT NULL` 的题数，1 题 1 分制；**`NULL` 题（标准答案缺失/无法辨识/超时）不计入**） | 未判定题不计入分母（v4b 明确） |
| `user_score` | **正确题数**（`is_correct = 1`） | 与 `total_score` 同为 1 题 1 分制 |
| `status` | `completed` | 否则学习记录列表看不到 |
| `analyzed_at` | `now()` | 日期筛选依据 |
| `tenant_id` / `create_time` / `update_time` | 自动填充 | — |

- **得分口径（替换上一版"不做得分"）**：Demo 不臆造真实卷面分值，统一 **1 题 1 分制**。**"总分"只有一个口径（t15 一致性修正）**：
  - `exam_paper.total_score` = `exam_analysis_result.total_score` = **`judgedCount`（已判定题数）**；
  - `userScore` = `correctCount`；`exam_paper.grade_status='graded'`。
  - 页面展示为 `userScore/judgedCount`（如"8/10 题"），**不再有第二种 `totalScore` 取值**；未判定题（`is_correct IS NULL`）既不计入分子也不计入分母。
  - 原"不做试卷总分/得分统计"的偏差已删除（proposal §3/§4 同步）。
- **`exam_question.score/full_score`**：判分时按 1 题 1 分写 `score = is_correct==1 ? 1 : 0`、`full_score = 1`；`is_correct IS NULL` 时两列留 `NULL`（不虚构成 0 分）。这两个既有列第一次被真正使用，零 DDL。
- **事务边界**：写入与"批量更新 is_correct / 错题 / 掌握度 / task 完成"同处 TX-B（纯 DB 写，无 LLM），原子提交；不做异步写（异步会丢"可见性"与一致性）。
- **幂等（一条一卷）**：
  1. 主防线：`grade_status` CAS（TX-B 只成功一次）；
  2. 次防线：写入前 1 条查重 `SELECT analysis_id FROM exam_analysis_result WHERE student_id=? AND tenant_id=? AND exam_name=? AND status='completed' LIMIT 1`，命中则跳过写入并 `log.warn`（`exam_name` 由"科目+类型+日期"组成，同学生同科目同日已被 `uk_student_exam` 保证唯一，天然可作业务唯一键）。`exam_analysis_result` **没有 paper_id 列且零 DDL**，因此不引入额外索引，接受这条 `idx_student` 扫描（单学生考试记录 ≤ 数十条）。

### 7.6 负 ID 边界与既有缺陷顺带修复（**v3 口径：只做防御，不再排除配额**）

负 ID 命名空间（D4/约定 #26）在 v3 后同时出现在 `error_question.question_id` 与 `daily_plan/content`、`homework_submission_detail.question_id`。原则：**负 ID 合法参与业务，但任何"把 `question_id` 当题库 ID 去查表"的地方都必须先按 `questionSource` 分流**。

| 位置 | 现状 | 本 change 处理（v3） | 性质 |
|------|------|---------------|------|
| `selectDueKnowledgePointIds` / `selectDueQuestionsByKnowledgePoint` | 不过滤 | **保持不过滤**：负 ID 行正常返回参与 redo；组装层按 `questionId < 0 → "exam"` 分流，**禁止**用负 ID 去 `bankMap.get(...)`（必 null） | v3 变更（撤销 B6/N1 排除） |
| `selectPageWithDetail` / `selectDetailById` | `LEFT JOIN question_bank` | 再加 `LEFT JOIN exam_question` + `COALESCE` 兜底题面/题型/答案/解析（S9） | 本次新增 |
| `selectSourceQuestionForSimilar` | `JOIN question_bank` | 负 ID 不匹配 → 返回 null 属**预期**；新增 `selectDueExamSourceQuestion` 兜底（§7.2.1） | v3 新增 |
| `selectActiveErrorCountGroupByKp` | 统计全部 `status='active'`（无 `question_id` 过滤） | **不过滤**（v3 撤销 N1）；`kpErrorCountMap` 在 service 侧 `min(raw, KP_MAX)` 后再传 `HomeworkQuotaCalculator`，消除"某 kp ≥5 道试卷错题 → 次日作业生成抛异常" | v3 变更 |
| `selectCriticalErrors`（S10） | `WHERE current_stage >= 5 AND first_wrong_date < ? AND status='active' AND tenant_id=?` | 改为 `... AND student_id = #{studentId} AND question_id > 0`（签名加 `studentId`）；**保留 `question_id > 0`**：负 ID 对调整 Agent 的 Prompt 无意义（无题面可引用），考试错题的重点关注由 SIG-2/掌握度承接 | **既有缺陷顺带修复 + 保留防御** |
| `LearningRecordServiceImpl#getMasteryChangesBySource(id, "EXAM")` | 传大写 `EXAM`，落库是小写 `exam` | 改为传 `ChangeType.EXAM.getCode()`（`assessment` 路径同缺陷，标注为遗留） | **既有缺陷顺带修复** |
| `completeStageWithCheck`（v3 新增发现） | `next_due_date = NULL`，但列是 `DATE NOT NULL` | 改为 `next_due_date = #{completedDate}`，否则 stage 到 5 完成时 SQL 报错（exam redo 首次真正走到该路径） | **既有缺陷顺带修复** |

> v3 后**不再有"三处 `question_id > 0` 过滤"**：只有 `selectCriticalErrors` 保留 `question_id > 0`（防御性，非配额）。`redoSupported` 字段保留但恒为 `true`。

---

## 8. 信号检测与自动调整

### 8.1 时机

- **结算 TX-B 提交之后**，在 Worker 线程内 `aiExecutor.submit(...)` 异步执行（不阻塞任务收尾；任务池已被占满，不能再往解析池提交）。
- 触发点只有一个：本次试卷结算完成（同一次上传只触发一次，由 `grade_status` CAS 保证）。

### 8.2 顺序

```
1. 定位目标：select * from learning_goal
   where student_id=? and subject_id=? and status='active'
     and start_date<=today and end_date>=today
   → 无目标：记日志 "[ExamAdjust] 无进行中目标，跳过"，结束
2. 去重（B2）：select count(*) from plan_adjustment_log
   where goal_id=? and tenant_id=? and action='confirmed' and action_at >= now - 12h
   → 命中：跳过（避免与 01:30 AutoAdjustmentTask 叠加，也避免"同一天两次上传/手动确认后再自动调整"）
   ⚠️ 必须用 'confirmed'：`plan_adjustment_log.action` 实际取值只有 'confirmed' / 'rejected'
      （`PlanDiffServiceImpl.applyDiff` 写 'confirmed'，`rejectDiff` 写 'rejected'），
      上一版写的 'applied' **永不命中**。本 change 不新增 'applied'（D12）。
      副作用说明：手动确认也会在 12h 窗口内抑制自动调整——这是期望行为（同一目标 12h 内不重复动计划）。
3. signals = signalDetector.detectSignals(goalId, tenantId)   // 已显式传 tenantId
   → 空：跳过（规则 #5「无信号不调用调整 Agent」）
4. vo = planAdjustmentService.triggerAdjustment(goalId, studentId, tenantId)   // 新增显式入参重载
   → PLAN_ADJUST_CONFLICT（已有 pending diff）：记 info，跳过
   → AdjustmentException：内部已降级为 noAdjustment + 写 agent_degradation_log，跳过
5. vo.adjusted && vo.diffId != null → planDiffService.applyDiff(vo.diffId)   // 自动生效（规则 #5 自动模式）
6. 全程 try/catch：任何失败只记日志，**不影响试卷状态与已写入的掌握度/错题**
```

- `triggerAdjustment` 现依赖 `StudentContext`（控制器路径）。新增重载 `triggerAdjustment(Long goalId, Long studentId, String tenantId)`，内部把身份作为参数下传；原方法保留并委托（`StudentContext` 读取只留在 Controller 适配层）。Worker **不设置/清理 `StudentContext`**，避免线程池复用造成身份串味。

### 8.3 去重

| 维度 | 手段 |
|------|------|
| 同一试卷 | `grade_status` CAS（TX-B 只成功一次） |
| 同一目标 12h 内（**信号调整去重**，B2） | `plan_adjustment_log` 窗口查询 `action='confirmed'`（`exam.adjust.dedup-hours`，默认 12）；不查 'applied'（不存在该值） |
| 已有待确认 diff | `plan_adjustment_log`/`plan_adjustment_diff` 的 `PLAN_ADJUST_CONFLICT` |
| 同一批日志 / 调度竞争 | 调整链本身的应用层防重（`PlanAdjustmentServiceImpl` 已有） |

> **注意口径（B2）**：「12h 去重」去的是**信号调整**，不是"重复上传"——同学生同科目同日第二次上传会被 `uk_student_exam` + `1131` 直接拒，根本到不了结算与信号检测。

### 8.4 展示

不新增展示位：调整结果通过现有「我的 → 计划调整 → 调整历史」与 `daily_plan` 变化可见。试卷详情只呈现"本次考试已计入掌握度"（`gradeStatus=graded`），避免为详情接口增加第 4 条 SQL（§10.2 的"固定 3 条 SQL"是硬约束）。

---

## 9. Worker 上下文与幂等

### 9.1 上下文

| 维度 | 做法 |
|------|------|
| 身份来源 | `exam_analysis_task.tenant_id` + `exam_paper.student_id/subject_id`，全部**显式入参** |
| ThreadLocal | Worker 内**不读不写** `StudentContext`；需要身份的旧服务改为显式重载（§8.2） |
| 事务模板 | 注入 `TransactionTemplate`（编程式），不依赖 `@Transactional` 代理（Worker 内部自调用会失效） |
| 异常分层（约定 #14） | LLM/网络类异常 → 降级 + `agent_degradation_log`；业务异常（`BusinessException`）→ 记录后按 `failed` 收尾 |
| 日志脱敏（约定 #12） | `error_message`/降级日志截断 500 字符，不含 base64、图片路径、openid |

### 9.2 幂等

| 场景 | 手段 | 结果 |
|------|------|------|
| 同一 task 被重复投递/并发消费 | `UPDATE exam_analysis_task SET status='processing', started_at=NOW() WHERE id=? AND status='pending'`，`rows==0` 直接 return | 只处理一次 |
| 消费时 paper 已 `completed`/`failed` | 检查 `paper.status`，非 `pending`（且 retry 未把 task 置回 pending）直接 return | 重投不重跑 |
| TX-A 后崩溃重跑 | TX-A 前查 `SELECT 1 FROM exam_question WHERE exam_paper_id=? LIMIT 1`，已存在则**跳过视觉解析与归因**（B7，见下） | 不产生重复题目、不重花 LLM |
| 结算重复 | `grade_status` CAS（0 行 → 跳过错题/掌握度/学习记录） | 掌握度不重复累计 |
| 错题重复 | `uk_student_question` + 批量"先查后插/更新" | 不重复入队 |
| 学习记录重复 | `grade_status` CAS + 写入前按 `exam_name` 查重（§7.5） | 一条一卷 |
| 信号重复 | §8.3 四层 | 不重复调整 |
| 上传重复/续传 | §2.3 的 30 分钟会话复用规则 | 不产生重复试卷（`uk_student_exam` 兜底） |

**重试与判分对齐（B7 写死）**：

```
POST /papers/{id}/retry
 1. 校验 paper.status='failed' 且 ocr_retry_count < 2 → ocr_retry_count += 1
 2. task 置回 pending（CAS: WHERE id=? AND status='failed'）→ Redis 入队
 3. Worker 处理时先查库：SELECT id, question_index, question_content, student_answer,
                              attribution_status, question_type, difficulty
                        FROM exam_question WHERE exam_paper_id=? AND tenant_id=? ORDER BY question_index
    ├ 行数 > 0（TX-A 已提交过）：**跳过视觉解析 + 归因**，直接把库中行经 codec 解码成判分输入
    └ 行数 = 0：正常走"解析 → 归因 → TX-A"
 4. 判分结果与库中行按 exam_question.id 对齐（不是 question_index，避免题号漂移）
 5. 判分结果条数 != 库中行数 → 记 agent_degradation_log（"判分条数不一致"）+ **不写任何 is_correct**，
    task 置 failed（保留题目供人工处理），禁止部分写入
```

- 单测断言：`retry_should_skipParseAndUseDbRows_when_questionsExist`、`retry_should_notWriteIsCorrect_when_judgeCountMismatch`、`retry_should_reject_when_ocrRetryCountAtLeast2`。

---

## 10. 历史试卷接口与页面栈

### 10.1 `GET /api/student/exam/papers`（固定 2 条 SQL）

```sql
-- ① count
SELECT COUNT(*) FROM exam_paper WHERE student_id = ? AND tenant_id = ?;

-- ② 分页（科目名 + 题数/正确数聚合一次算）
SELECT p.id, p.subject_id, s.name AS subject_name, p.exam_date, p.paper_type,
       p.status, p.grade_status, p.create_time,
       COALESCE(a.total_question_count, 0) AS total_question_count,
       COALESCE(a.correct_count, 0)        AS correct_count
FROM exam_paper p
LEFT JOIN subject s ON s.subject_id = p.subject_id AND s.tenant_id = p.tenant_id
LEFT JOIN (
  SELECT q.exam_paper_id,
         COUNT(*) AS total_question_count,
         SUM(CASE WHEN q.is_correct = 1 THEN 1 ELSE 0 END) AS correct_count
  FROM exam_question q
  WHERE q.tenant_id = ?
    AND q.exam_paper_id IN (
      SELECT id FROM (
        SELECT id FROM exam_paper
        WHERE student_id = ? AND tenant_id = ?
        ORDER BY exam_date DESC, id DESC
        LIMIT ?, ?
      ) page_ids
    )
  GROUP BY q.exam_paper_id
) a ON a.exam_paper_id = p.id
WHERE p.student_id = ? AND p.tenant_id = ?
ORDER BY p.exam_date DESC, p.id DESC
LIMIT ?, ?;
```

- `size` 上限 50（>50 夹到 50，<1 夹到 1，与 `ErrorQuestionService` 的 clamp 风格一致）；`page` <1 夹到 1。
- 派生表包一层 `page_ids` 规避 MySQL 5.7 "LIMIT 不在 IN 子查询"的限制。
- 出参：`paperId/subjectId/subjectName/examDate/paperType/status/gradeStatus/totalQuestionCount/correctCount/judgedCount/userScore/totalScore/createTime`，**不含 `image_urls`**（列表不返回大字段）。
  - `userScore = correctCount`、`totalScore = judgedCount`（1 题 1 分制，B5）；`judgedCount = SUM(is_correct IS NOT NULL)`，需在派生表里一并算，**不新增 SQL 条数**（仍是 2 条）。

### 10.2 `GET /api/student/exam/papers/{paperId}`（固定 3 条 SQL）

```sql
-- ① paper + 科目名（PK）
SELECT p.id, p.subject_id, s.name AS subject_name, p.exam_date, p.paper_type,
       p.image_urls, p.status, p.grade_status, p.create_time
FROM exam_paper p
LEFT JOIN subject s ON s.subject_id = p.subject_id AND s.tenant_id = p.tenant_id
WHERE p.id = ? AND p.student_id = ? AND p.tenant_id = ?;

-- ② 题目（命中 idx_paper_index，无 filesort）
SELECT id, question_index, question_content, question_image_url, knowledge_point_id,
       attribution_status, student_answer, is_correct, question_type, difficulty
FROM exam_question
WHERE exam_paper_id = ? AND tenant_id = ?
ORDER BY question_index ASC;

-- ③ 知识点名称批量映射（命中 PRIMARY）
SELECT knowledge_point_id, name FROM knowledge_point
WHERE tenant_id = ? AND knowledge_point_id IN (...);
```

- 出参：`paperId/subjectId/subjectName/examDate/paperType/status/gradeStatus/imageUrls/totalQuestionCount/correctCount/judgedCount/userScore/totalScore/questions[]`，题目项字段见 §4.2（题目项含 `score/fullScore`，1 题 1 分制，未判定为 `null`）。
- 越权/不存在 → `1136`（`EXAM_RESULT_NOT_FOUND`，语义沿用"分析结果不存在"，不新增码）。

### 10.3 页面栈与参数

```json
// pages.json 新增
{ "path": "pages/exam-list/ExamList",  "style": { "navigationBarTitleText": "历史试卷" } },
{ "path": "pages/exam-detail/ExamDetail", "style": { "navigationBarTitleText": "试卷详情" } }
```

| 入口/动作 | 跳转 | 参数 |
|-----------|------|------|
| 我的 → 历史试卷 | `uni.navigateTo('/pages/exam-list/ExamList')` | 无 |
| 列表项点击 | `uni.navigateTo('/pages/exam-detail/ExamDetail?paperId=' + id)` | **只传 `paperId`** |
| 上传完成（我的页内） | `uni.navigateTo('/pages/exam-detail/ExamDetail?paperId=' + id)` | 只传 `paperId` |
| 详情返回 | `uni.navigateBack()` | — |
| 详情页重新拉数 | `getPaperDetail(paperId)`，`onLoad` 只解析 `options.paperId` | 禁止 URL 传 `items`/`subjectName`（`homework-detail` 反例不再复制） |

- 详情页：`onLoad` 取 `paperId`（非数字 → `showError` + `navigateBack`）；`status ∈ {pending, processing}` 时每 2s 轮询详情接口（最多 90 次 ≈ 3 分钟），完成/失败即停；`onUnload` 清理定时器。
- 详情页题序在本地 `questions` 数组切换（`currentIndex`，上一题/下一题、进度 `x/N`），与 `HomeworkDetail.vue` 完全一致的交互骨架，但**无提交按钮**（只读）。
- 上传页（**独立页 `pages/exam-upload/ExamUpload.vue`**，v4c 修正旧"Profile 内嵌"描述；`Profile.vue` 只保留入口 menu-item）：选图 `count: 8` → 逐张压缩（<800KB 不压、否则 quality 85）→ 创建会话 → 2 并发逐张上传（`uploadWithAuth` 单文件）→ 全部成功后跳详情；单张失败可点"重试该张"，不计入已上传数。
- **防重复入栈（S8）**：进入 `ExamDetail` 前先查页面栈，若栈顶已是同一 `ExamDetail`（或栈内已有同 `paperId` 的 `ExamDetail`）则 `uni.navigateBack()` 而不是再 `navigateTo`，防止连点把多层详情页压栈（每层都开 2s 轮询）。

  ```js
  const pages = getCurrentPages();
  const top = pages[pages.length - 1];
  const sameDetail = top && top.route === 'pages/exam-detail/ExamDetail'
      && String(top.options?.paperId) === String(paperId);
  sameDetail ? uni.navigateBack() : uni.navigateTo({ url: `/pages/exam-detail/ExamDetail?paperId=${paperId}` });
  ```

- **paperType 展示（S13）**：列表/详情把 code 映射为中文（`midterm→期中考试, final→期末考试, monthly→月考, mock→模拟考试, other→其他`），映射表与 §2.4 及 `Profile.vue` 的 picker 保持同一份；不直接显示 code（约定 #21 前端展示规则）。

---

## 11. 性能设计（硬要求）

### 11.1 总原则

| 资源 | 红线 | 落地手段 |
|------|------|---------|
| LLM 并发（**全局**） | **≤3**（视觉 + 归因 + 判分合计，S1） | 全局 `Semaphore(3)`（`LlmConcurrencyLimiter`，所有 `.call()/.callVision()` 调用前 `acquire`）+ `examParseExecutor`（core=max=3，有界队列 16）；`examAnalysisExecutor` 仍有 3 线程，但**每次 LLM 调用都被全局闸门卡到 ≤3** |
| 单 task 时长 | ≤180s 必收尾 | deadline + 单图预算 + 已完成结果先落库 |
| 单机内存（base64） | 峰值 ≤3 份，不随张数线性增长 | 逐图读取/生成/释放；结果 DTO 不含字节；禁止任何 `List<byte[]>`/`List<String>` 全量缓存 |
| 数据库写入 | 批量 ≤100/批，无循环单条 insert | 题目批量 insert、判分结果批量 update、错题批量 insert/update、掌握度批量 update/log |
| 数据库事务 | 事务内不做 LLM 调用、≤9 条语句 | TX-A / TX-B 拆分（约定 #10） |
| 读接口 | 列表 2 条 SQL、详情 3 条 SQL、禁 `select *`、`size ≤50` | §10 + §11.6 |

### 11.2 解析并发与超时

```
ThreadPoolConfig 新增：
  @Bean(name="examParseExecutor", destroyMethod="")  createExecutor("exam-parse",
      ${thread-pool.exam-parse.core-size:3}, 同值, ${thread-pool.exam-parse.queue-capacity:16})
  注册到 executorShutdownManager（约定 #22：await-terminate-seconds 默认 60）
配置项（代码内默认值，yml 不改）：
  thread-pool.exam-parse.core-size=3 / queue-capacity=16 / await-terminate-seconds=60
  exam.parse.task-timeout-seconds=180
  exam.parse.image-timeout-seconds=60
  exam.parse.grading-timeout-seconds=60
  exam.parse.grading-concurrency=3          // 全局闸门也读它，默认 3
  exam.parse.max-images=8 / max-image-mb=9.5 / max-total-mb=30 / max-questions=200
  exam.adjust.dedup-hours=12
```

- **全局并发闸门（S1）**：`LlmConcurrencyLimiter`（`Semaphore(exam.parse.grading-concurrency=3)`）在 `AgentScopeClient.call()` 与 `callVision()` 内 `acquire/release`（`try/finally`），因此无论任务来自 `examAnalysisExecutor`、`examParseExecutor` 还是 `CallerRunsPolicy` 的调用者线程，**在途 LLM 调用全局 ≤3**；解析池仍保留（控制任务排队与线程命名），但不再是唯一约束。
  - 死锁防护：闸门在最内层（单次调用），不嵌套获取；同一线程一次只持有一个许可；等待受"单图预算 60s / 总预算 180s"约束。
- Worker 拉取节流：`if (executor.getActiveCount() >= coreSize) return;`（忙时不从 Redis 取任务，避免 `CallerRunsPolicy` 把调度线程拖成长任务）。
- 8 图 → 3 波：`ceil(8/3)=3`；单图典型 8–15s ⇒ 视觉阶段 25–45s；归因 1–2 批 ⇒ +10–20s；判分 ≤30 题/3 并发 ⇒ 15–30s。**整卷典型 60–95s，预算 180s 有余量。**
- 超时行为：未完成图视为"该图解析失败"→ 降级日志 + 其余图正常落库；全部图失败 → `task=failed` + `paper=failed`。

### 11.3 base64 内存与 30MB 取值依据（S2）

- 单图 base64 公式：`ceil(bytes/3)*4`。单图 9.5MB ⇒ ≤12.7MB；峰值 = 3 × (原始字节 + base64) ≈ **≤63MB 理论上限**；前端压缩到 ~1.5–2MB/张时峰值 ≈ **3 × 3.5MB ≈ 10.5MB**。
- **30MB / 9.5MB 的取值依据**（评审要求写清）：
  1. **容器层**：`spring.servlet.multipart.max-request-size: 10MB`（yml 现状，本 change 不改）→ 单次请求体必须 < 10MB，故业务单图上限取 **9.5MB**（留 0.5MB multipart 边界余量），并从异常层兜底映射 `1184`（§2.3）。
  2. **内存层**：全局并发 3（S1）× 单图 9.5MB → base64 峰值 ≤63MB，加上 JVM 其他常驻（Spring/MyBatis/Hikari 等 ~300MB），在 Demo 机器 1–2GB 堆内安全；若按 8 × 10MB 放开则峰值可达 210MB+，与"8 张不驻留"目标冲突。
  3. **请求次数层**：小程序逐张上传 → 8 个请求 × ≤9.5MB，单请求内存峰值 = 1 张，**"单请求 8 张总量"实际是同一 paper 的累计上限 30MB**（≈8 张平均 3.75MB，压缩后典型 12–16MB，留 2 倍余量）。
  4. **体验层**：8 张 × 3.75MB 的 4G 上传约 20–40s，超过后用户等待成本明显；30MB 是"能传完"与"内存/带宽可控"的折中。
- 生成与释放：单图任务内 `byte[] → String base64 → ImageBlock → 调用`，`try/finally` 把局部引用置 `null`，任务返回的 DTO **只含结构化字段**；`allOf`/`CompletionService` 只持有 DTO。
- 禁止项（写进评审清单）：
  1. 一次性 `files.stream().map(Base64::encode)` 批量生成；
  2. 把 base64 写进 `exam_paper.image_urls`/`exam_analysis_task.error_message`/Redis；
  3. 把 base64 放进 `agent_degradation_log.input_summary`。
- 上传侧：每请求 1 张（≤9.5MB），Tomcat 线程结束即释放；前端逐张压缩 + 2 并发。
- 兜底：8 张极端清晰大图仍超预算时，`core-size` 调 2（环境变量 `THREAD_POOL_EXAM_PARSE_CORE_SIZE=2`）+ 全局闸门同步读该值 → 峰值压到 ≤2 份。

### 11.4 落库批量化

| 数据 | 方式 | 批大小 |
|------|------|--------|
| `exam_question` 插入 | `ExamQuestionMapper.batchInsert(List)`（`<foreach>` 多值 INSERT） | ≤100 |
| `exam_question` 判分结果 | `batchUpdateJudgeResult(List)`（`CASE id WHEN ... THEN ...`） | ≤100 |
| `error_question` 入队 | 批量查 + 批量 INSERT / 批量 UPDATE（新增 `recordWrongAnswers`） | ≤100 |
| `student_knowledge_state` | 批量查 `IN` + 批量 UPDATE（CASE WHEN） | ≤100 |
| `mastery_change_log` | `batchInsert(List)`（`<foreach>`） | ≤100 |
| 掌握度暂停联动 | `syncPauseStatus` 按去重 kp 逐条（现有方法，≤20 次） | — |

- **禁止**：`for (q : questions) mapper.insert(q)`、`for (q : questions) masteryService.updateMasteryFromResult(...)`、`for (e : events) errorQuestionMapper.insert(e)`。
- 事务只包裹上述批语句，**LLM 调用一律在事务外**（约定 #10）。

### 11.5 判分并发

- `AnswerGradingService.gradeBatch` 内部：`choice` 同步比较（无 IO）；非 choice 提交 `examParseExecutor`，并**统一经过全局 `LlmConcurrencyLimiter`（Semaphore 3）**（S1；不再各自用独立 `Semaphore(3)`，否则视觉 + 判分叠加可到 6），`budget=60s`。
- `studentAnswer` 空白 → 直接 `false`，不占 LLM 配额（大卷里空白题常占 20%+）。
- 结果一次性回写（§11.4），不做"逐题 update"。
- 验收口径改为**全局并发峰值 ≤3**（`LlmConcurrencyLimiter` 的 `availablePermits` 最小值 ≥0 且执行中的 LLM 调用计数峰值 ≤3，日志采样断言）。

### 11.6 读接口 SQL 与 EXPLAIN 预期

| # | 查询 | 预期 `key` | 预期 `Extra` | 判据 |
|---|------|-----------|-------------|------|
| 1 | 列表 count | `idx_student_status` | `Using index` | `type=ref`，`rows` = 该学生试卷数 |
| 2 | 列表分页 | `idx_student_status`(p) / `PRIMARY`(s) / `idx_paper_index`(q) / `PRIMARY`(a 派生) | `Using filesort`（仅 p，单学生 ≤ 百行排序） | 所有 JOIN `type ∈ {ref, eq_ref}`，无 `ALL` |
| 3 | 详情 paper | `PRIMARY` | `Using where` | `type=const/eq_ref` |
| 4 | 详情 questions | `idx_paper_index` | `Using where`（无 filesort） | `EXPLAIN` 中 `Extra` 不得出现 `Using filesort` |
| 5 | 详情 kp 批量 | `PRIMARY` | `Using where` | `type=range`，`rows ≤ kp 数` |
| 6 | 掌握度 state 批量 | `uk_student_kp` | `Using where` | `type=range` |
| 7 | 错题本列表（含新 JOIN） | `idx_student_kp_status`(eq) / `PRIMARY`(eqx) | 允许 filesort（既有 `TD-PAGINATION-FILESORT`） | `eqx` 侧 `type=eq_ref` |
| 8 | 到期错题（新过滤） | `idx_student_status_due` | `Using where` | 过滤 `question_id > 0` 不改变索引选择 |
| 9 | `selectCriticalErrors`（S10 新版） | `idx_student_status_due` 或 `idx_student_kp_status` | `Using where` | WHERE 含 `student_id + tenant_id + status + question_id`，不得 `ALL` |
| 10 | 学习记录写前查重（`student_id + tenant_id + exam_name + status`） | `idx_student`（`tenant_id, student_id`） | `Using where` | `type=ref`，`rows` = 该学生考试记录数（≤ 数十） |
| 11 | D_norm 历史均值（`question_bank` 按 kp+difficulty 分组） | `idx_knowledge_point`（`tenant_id, knowledge_point_id`） | `Using temporary; Using filesort`（分组，允许） | `type=range`，`rows ≤ 该 kp 题数`；仅 1 次/结算 |
| 12 | 作业组装 exam 题面（`exam_question.selectBatchIds(absIds)`，v3） | `PRIMARY` | `Using where` | `type=range`，`rows ≤ 当日在期 exam 错题数`（每 kp ≤3） |
| 13 | 举一反三 exam 兜底源题（`error_question` JOIN `exam_question` ON `eqx.id = -eq.question_id`，v3） | `idx_student_kp_status`(eq) / `PRIMARY`(eqx) | 允许 filesort（`ORDER BY next_due_date`，行数 = 该 kp 到期错题数） | `eqx` 侧 `type=eq_ref` |

- 排序键从 `create_time DESC` 改为 `exam_date DESC, id DESC`：`idx_student_status` 只含 `student_id` 前缀，两种排序都要 filesort，但 `exam_date` 更贴近"历史试卷"的心智且与 `id` 组成稳定 tiebreak（避免 `create_time` 同秒抖动）。
- 列表 `size ≤50`、`LIMIT` 深分页上限按 50 页保护（page ≤ 500 由 clamp 实现）。
- 查询 #11 的 `Using temporary` 是 `GROUP BY` 的必要代价：单次结算只执行 1 条、行数为"该学生该批 kp 在题库中的题量"，可接受；若后续题库量级上升，再评估加 `(tenant_id, knowledge_point_id, difficulty)` 覆盖索引（需 DDL，不在本 change）。
- **v3 作业组装不引入 N+1**：每份作业最多新增 **1 条** `exam_question.selectBatchIds`（只针对本份作业中 `questionSource="exam"` 的 refs，集合大小 ≤ 3×kp 数 ≤ 45）；举一反三兜底源题每 kp 至多 1 条（仅当题库无源题时）。
- 验证：新增 `docs/ops/exam_paper_explain.sql`（只读 `EXPLAIN` + 数据量统计，覆盖 #1–#11），verify 阶段人工执行并回填 verify 报告（`docs/standards/database.md` §3 允许只读 EXPLAIN）。

### 11.7 读接口其它约束

- 全部 SQL **显式列名**（禁 `select *`），含 `tenant_id` 条件（约定 #7 / database.md §7）。
- 列表不返回 `image_urls`、`remark` 等无用/大字段；详情一次返回 `imageUrls`（仅 URL 字符串，≤8 条）。
- 前端列表分页 10/页、`onShow` 只刷首页数据；详情页轮询 2s 间隔、3 分钟上限，页面卸载即停（避免后台无效请求）。

---

## 12. 降级矩阵

| 场景 | `is_correct` | 掌握度 | 错题 | `attribution_status` | 用户可见 |
|------|-------------|--------|------|---------------------|----------|
| 正常判定（答对/答错） | 1 / 0 | **更新**（W=1.0） | 答错入队 | `completed` | 详情显示 ✅/❌ + 正确答案 + 解析 |
| 学生未作答（`answerStatus=blank`） | 0 | 更新（按答错） | 入队 | `completed` | 显示"未作答" |
| 作答无法辨识（`unreadable`） | `NULL` | 跳过 | 跳过 | 保持归因结果 | 显示"待人工判定" |
| 正确答案缺失（choice 缺标签 / 非 choice 缺答案）（**v4b 阻塞修复**） | **`NULL`（不可判定，不再判 false）** | **跳过** | **跳过** | 保持归因结果 | 显示"标准答案缺失，待人工判定"；计入 `unjudged` |
| 学生作答与标准答案**同时为空**（v4b） | **`NULL`** | 跳过 | 跳过 | 保持归因结果 | 显示"未作答且无标准答案，待人工判定" |
| 判分批次超时未完成的题 | `NULL` | 跳过 | 跳过 | 保持归因结果 | 显示"待人工判定" |
| 直出为空 + 兜底归因失败（v4，无 kp） | 正常判定 | **跳过**（无 kp） | **跳过**（无 kp） | `pending_manual_review` | 显示"待人工归因" |
| 直出 kp 不在白名单（幻觉/越界） | 正常判定 | 走兜底；兜底成功则更新 | 同左 | 置空后按兜底结果 | 无感知（**warn 日志 + `stats.kpUnresolved`**） |
| 单图解析失败/超时 | — | — | — | — | 该图题目缺失 + `imageWarning` 提示"部分图片未识别"，可 `retry` |
| 全部图解析失败 | — | — | — | — | `task/paper=failed`，`1188`"试卷解析失败，请重试" |
| **视觉模型不可用/不支持图片（S6，**已实测通过**）** | `NULL` | 跳过 | 跳过 | `pending_manual_review` | 全部图重试耗尽 → `task/paper=failed`；部分图失败 → 该图 0 题 + `imageWarning`（2026-11-11 探针已通过，降级路径仅作兜底） |
| 判分阶段整体失败 | `NULL` | 跳过 | 跳过 | 保持 | 题目可回看，`gradeStatus=ungraded`，提示"判分未完成" |
| 视觉解析 JSON 全不合法（重试耗尽） | 该图 0 题 | — | — | — | 同"单图解析失败" |
| 学习记录写入失败（`exam_analysis_result`） | 已判定结果保留 | 已更新 | 已入队 | 保持 | 整笔 TX-B 回滚（同事务）→ `task=failed`，重试后重新结算；不会出现"有掌握度但无学习记录" |
| 信号检测失败 | 不影响 | 不影响 | 不影响 | — | 无提示（仅日志/降级日志），次日 01:30 定时任务兜底 |

---

## 13. 错误码（新增，接在 1181 之后）

```java
// 试卷图片与上传（1182-1191）
EXAM_IMAGE_COUNT_EXCEEDED(1182, "试卷图片最多 8 张"),
EXAM_IMAGE_EMPTY(1183, "请至少上传 1 张试卷图片"),
EXAM_IMAGE_TOO_LARGE(1184, "单张试卷图片不能超过 9.5MB"),           // 见 §2.3（容器上限 10MB 留余量）
EXAM_IMAGE_TOTAL_TOO_LARGE(1185, "试卷图片总大小不能超过 30MB"),
EXAM_UPLOAD_SESSION_NOT_FOUND(1186, "上传会话不存在或已过期，请重新上传"),
EXAM_UPLOAD_INCOMPLETE(1187, "请先上传至少 1 张试卷图片再提交解析"),
EXAM_PARSE_FAILED(1188, "试卷解析失败，请重试"),
EXAM_PAPER_IMAGE_LIMIT_REACHED(1189, "该试卷图片已达上限"),
EXAM_RETRY_NOT_ALLOWED(1190, "仅解析失败的试卷可重试，且最多重试 2 次"),  // 整卷重试，B3/D14
EXAM_PAPER_TYPE_INVALID(1191, "试卷类型无效，可选：midterm/final/monthly/mock/other"),
```

- `1184` 的**双重来源**：应用层 9.5MB 判断（主）+ `GlobalExceptionHandler` 对 `MaxUploadSizeExceededException`/`MultipartException` 的映射（兜底，S4）。
- `1190` 只统计**整卷重试**（`ocr_retry_count < 2` 才允许），与单图重试无关（D14/B3）。
- `1191` 与 §2.4 的 5 个枚举、前端 picker 完全一致（S13）。

- 沿用：`1130 EXAM_PAPER_NOT_FOUND`（归属/不存在）、`1131 EXAM_PAPER_DUPLICATE`（同科目同日重复）、`1139 FILE_TYPE_INVALID`（类型）。
- 删除：`1132 EXAM_PAPER_ALREADY_GRADED`、`1133 EXAM_PAPER_NOT_READY_FOR_GRADE`、`1134 EXAM_QUESTION_NOT_FOUND`（仅手工录入使用）、`1135 EXAM_TASK_NOT_FOUND` 保留（task 进度仍用）、`1136 EXAM_RESULT_NOT_FOUND` 保留（详情越权）、`1137 OCR_FAILED` 标记 `@Deprecated`（OCR 弃用）。

---

## 14. 隐性约定对齐

| 约定 | 本设计的落地 |
|------|-------------|
| #1 统一响应 | 全部接口返回 `Result<T>` / `Result<PageResult<T>>` |
| #2 异常处理 | Service 抛 `BusinessException(ErrorCodes)`；Controller 不 try-catch |
| #3 分页参数 | `page` 从 1，`size` 默认 10、上限 50 |
| #4 时间格式 | `LocalDate`/`LocalDateTime`，前端 `yyyy-MM-dd`/`yyyy-MM-dd HH:mm:ss` |
| 规则 #7 + 规则 #9 事务 | 所有 SQL 带 `tenant_id`；TX-A/TX-B 均 `TransactionTemplate`（编程式）+ 仅 DB 写；异常回滚由模板保证（等价 `rollbackFor=Exception`） |
| 规则 #9 学习记录 | 新增写 `exam_analysis_result`（`status=completed` + `analyzed_at` + `user_score/total_score`），只追加不删（§7.5）；`mastery_change_log`/`agent_degradation_log` 亦只追加 |
| #10 事务边界 | 视觉解析/归因/判分全在事务外；事务 ≤9 条语句 |
| #12 超时降级 | 单图 60s / 总 180s / 判分 60s；降级写 `agent_degradation_log` 并脱敏截断 500 字 |
| #14 异常分层 | LLM 技术异常 → 降级；业务异常 → `BusinessException`；两者在 Worker 内分流 |
| #18 前端接口文件 | 新接口函数**并入已有 `src/api/exam.ts`**，不新建 api 模块 |
| #19 小程序完整 URL | 继续用 `utils/upload.ts` / `utils/request.ts` 的完整基地址；multipart 字段名显式传 `file`（S5，默认值是 `files`，必须覆盖） |
| #21 枚举双形态 | `status`/`grade_status`/`attribution_status`/`paper_type` 沿用现有字符串常量，不新增枚举；`change_type` 用 `ChangeType.getCode()`（小写 `exam`），学习记录详情查询同步用该 code（§7.6） |
| #22 线程池统一 | 新增池只在 `ThreadPoolConfig` 声明 + 注册 `executorShutdownManager` |
| #23 年级中文 | `GradeMatcher.displayName`，空值"当前" |
| #24 纯文本 | v4 收紧：允许 `x^2`/`a_n` 纯文本；**禁止** `^{...}`/`_{...}` 花括号与任何反斜杠命令（`\frac`/`\sqrt`/`\vec`…）；幂优先 `²/³` 或 `^2`，下标优先 `a(n)`/中文；Prompt 明确 + `LlmTextSanitizer` 兜底（题干/选项/答案/解析/学生作答全覆盖）。与 v4 代码实现同步：清洗器新增花括号/反斜杠兜底，**不**再整段禁 `^` |
| #25 选择题归一 | `ChoiceOptions.canonicalize` 落库前归一；出参 `choices` 只放文本；<2 个有效选项丢弃该题 |
| **#26（新增）** | 试卷来源错题在 `error_question` 用 **负 `question_id = -exam_question.id`**；题库/生成题查询必须以 `question_id > 0` 排除；题面用 `exam_question.question_content` 的分段文本。**v3 扩展**：`daily_plan.content[].questionId` 与 `homework_submission_detail.question_id` 对 `questionSource="exam"` 也用同一负值（保证 `uk_plan_question` 不与 bank/generated 正数 id 冲突）；任何"按 id 查题库"的代码必须先按 `questionSource` 分流 |
| **规则 #8 知识点来源（v4 强化）** | 视觉解析直出的 `knowledgePointId` 必须过**服务端白名单**（本卷注入集合）；非法/幻觉一律置空 + warn；兜底归因只用同一清单；未命中 → `pending_manual_review`。**任何路径都不得落库未启用/未注入的知识点 ID** |

---

## 15. 测试计划

| 测试类 | 覆盖点 |
|--------|--------|
| `ExamParseServiceTest`（新） | 单图成功 / Schema 非法重试 2 次后降级 / 超时取消 / **全局 LLM 并发峰值 ≤3**（S1：视觉 + 判分同时提交时，`LlmConcurrencyLimiter` 观测计数峰值 ≤3）/ 跨图题号重排 / 相似度 0.9 去重 / 总题数 200 截断 / 降级日志脱敏 / **v4：直出 kp 的题不进兜底批、空 kp 的题进兜底批、四档统计（direct/fallback/unresolved/unjudged）写入 `exam_analysis_result.questions.stats`** / **v4b：兜底用 500 清单（含被 parse 截断的 kp），>500 分片各调一次** |
| `ExamParseAgentTest`（新） | Prompt 占位符替换（含 `retryHint`、年级中文、**`{knowledgePoints}` 清单原样注入**）；`choices` 归一；`answerStatus` 枚举；文本清洗；**v4：`knowledgePointId` 空字符串合法 / 清单内 ID 通过 / 幻觉 ID 被白名单置空 + warn / 逗号多 ID 置空** |
| `ExamKpCandidateServiceTest`（新，v4/v4b） | 只取 `status='active'` + 该 subject；年级过滤（`matchesRange`，空 grade_range 保留，学生年级空不过滤）；排序（精确匹配 → 有掌握度记录 → kpId 升序）；**分级上限：parse 150 / attribution 500**；`truncationRate` 计算与 `log.warn` + `truncate_kp_candidates` 降级日志；**attribution 清单包含被 parse 截断的 kp（可达性断言）**；>1000 时 `kp_unreachable` + 该批 pending；缓存 TTL 内不重复查库、过期后重查；SQL 显式列名 |
| `AgentOutputValidatorTest`（+） | `validateExamParse`：合法/缺 `questions`/单题缺字段/`formatType` 越界/`choices` 不足 2 个/空数组 + `imageWarning`；**v4：`knowledgePointId` 缺失视为非法（required）、空字符串合法、超长非法** |
| `ExamParsePromptContractTest`（新，v4） | 渲染后 Prompt 含 `{knowledgePoints}` 清单且无残留 `{...}` 占位符；断言**不含** `^{`、`_{` 与反斜杠命令示例；包含数学记号规则的最终措辞（`x^2` 允许、禁止花括号） |
| `AnswerGradingServiceTest`（新，**v4b 四条必测**） | **choice × 标准答案有**（大写/空格/标签比较）/ **choice × 标准答案空 → `null`（不判 false、不调 AI）** / **非 choice × 标准答案有**（AI 对/错；AI 不可解析/异常 → 字符串比较）/ **非 choice × 标准答案空 → `null`（不调 AI）**；学生空 + 答案有 → `false`；学生空 + 答案空 → `null`；`unreadable` → `null`；批次超时 → `null` |
| `HomeworkServiceTest`（回归 + v4b） | 委托改造后全绿；**v4b：`grade()` 返回 null 时 `is_correct` 留 `NULL`、不进 `correctCount`、不触发 `advanceStage/resetStage`、`updateMasteryFromHomework` 跳过该题**（覆盖现状"null 被当答错"的缺陷） |
| `ExamPaperServiceTest`（新） | 创建会话张数校验 / 30 分钟内复用 / 超期重置 / 追加单图超限（**9.5MB 边界，S4**）/ 总量超限 / **paperType 非法 → 1191**（S13）/ 达量建 task / 重复上传 1131 / `retry` 在 `ocr_retry_count≥2` 时拒绝（B3） |
| `ExamAnalysisWorkerTest`（新） | CAS 幂等（第二次 0 行直接返回）/ 并发消费只处理一次 / 解析失败 → task+paper failed / TX-B 失败保留题目与 `ungraded` / **retry 时库中已有题目 → 跳过解析与归因**（B7）/ **判分条数不一致 → 不写 is_correct + failed**（B7） |
| `ExamPaperServiceTest`（结算用例：`should_parseJudgeAndSettle` / `should_notRepeatAccounting_when_alreadySettled`；v4c 由旧名 `ExamSettlementTest` 校正） | 错题批量入队（负 ID）/ 答对也更新掌握度 / 同 kp 多题合并写入 / 日志字段（`change_type/source_id/delta/before/after`）/ `grade_status` CAS 不重复累计 / **`exam_analysis_result` 1 条且字段齐全（status/analyzed_at/user_score/total_score/exam_name/questions.paperId）**（B5）/ **重复结算不写第二条学习记录**（B5）/ **v4：直出 kp 的题正常入错题与掌握度；`pending_manual_review`（直出空+兜底失败）的题既不进错题也不进掌握度事件** / **v4b：`is_correct=NULL` 的题（标准答案缺失/无法辨识）既不进错题也不进掌握度事件，且不计入 `judgedCount`/`userScore`** |
| `AttributionAgentImplTest`（新/扩展，v4 兜底） | 只收到"直出为空"的题（调用方断言）；用传入候选清单而非自建全量；命中 → `completed`；`confidence=low`/ID 不在清单 → `pending_manual_review`；批次异常重试 ≤2 次后整批降级 |
| `ExamSignalAdjustServiceTest`（新） | 无目标跳过 / 无信号跳过 / **12h 去重按 `action='confirmed'` 命中**（B2，mock `PlanAdjustmentLog` 查询参数断言）/ `PLAN_ADJUST_CONFLICT` 跳过 / 命中则 `applyDiff` / Agent 降级不影响试卷状态 |
| `ErrorQuestionServiceTest`（+） | 批量入队（已存在 → 更新语义）/ `selectDueKnowledgePointIds`、`selectDueQuestionsByKnowledgePoint` **返回负 ID 行**（v3）/ 错题本列表与详情 JOIN `exam_question` 有题面 + `question_type/answer/analysis` 兜底（S9）/ **`selectCriticalErrors` 带 `student_id` 与 `question_id > 0`**（S10）/ **`completeStageWithCheck` 写 `next_due_date=completedDate` 不报错**（D23） |
| `HomeworkServiceTest`（+，N1 v3 回归，**两条必测**） | **T1**：某知识点有 5+ 道试卷错题（负 ID、active）→ 次日 `generateDailyHomework` 不抛异常；进作业的该 kp 错题数 ≤4；其余保持 `active` + `next_due_date` 不变（次日顺延），且 `daily_plan` 不被判未完成（不触发 SIG-3）；同时断言该 kp 的 redo refs 出现 `questionSource="exam"`（v3 参与而非排除）。**T2**：试卷错题与题库错题混排（如 3 题库 + 3 试卷 = 6）→ 传给 `HomeworkQuotaCalculator` 的 `kpErrorCountMap` 值为 **4（合计 clamp）**，不是各算 4；计算器不抛异常 |
| `ExamRedoIntegrationTest`（新，v3 四条必测） | ① **试卷错题次日进原题重做**：`daily_plan.content` 出现 `{"questionId": -id, "questionSource": "exam", "taskType": "redo"}`，`buildHomeworkVO` 经 `ExamQuestionTextCodec` 还原 stem/choices/correctAnswer/explanation/type/difficulty；② **举一反三可用 exam 源题生成**：题库无源题时走 `selectDueExamSourceQuestion` → 临时 `QuestionBank` → `SimilarQuestionService` 正常产出（缺 `correctAnswer` 时跳过并写降级日志）；③ **5+ 试卷错题不导致作业生成失败**（与上一条 HomeworkServiceTest 同场景的端到端断言）；④ **exam redo 答题后 stage 推进**：答对 1→2、答错 reset 到 1、stage 5 完成时 `next_due_date=completedDate` 且 `status='completed'` |
| `ExamPaperControllerTest`（新） | 8 张上限 1182 / 空图 1183 / 单图 1184（9.5MB）/ 总量 1185 / 未完成 1187 / **paperType 非法 1191** / 列表 size clamp 50 / 详情越权 1136 / **`GET /tasks/{id}` 越权（他人 task）→ 1135**（B4）/ `MaxUploadSizeExceededException` → 1184（S4）/ `submitExamGrade` 已删除（404/405） |
| `MasteryServiceTest`（+） | 批量入口与逐条调用结果一致（同序列 m_new 相同）/ 答对增量为正 / 答错下限 0.05 / 批量 SQL 条数（mock 校验无 N+1）/ **D_norm：`declared` 与历史均值偏离 >0.2 → 0.5、无历史 → 0.5、非法/缺失 → 0.5、合法且在阈值内 → declared**（B1） |
| `ExamQuestionTextCodecTest`（新） | 编码/解码往返 / 无标记兼容（旧数据）/ 缺段落容错 / 选项顺序 |
| `FileStorageServiceTest`（+） | `readBytes` 正常 / 路径穿越拒绝 / COS 未实现抛 `1188` |
| `LearningRecordServiceTest`（+） | 写入后「学习记录」`EXAM` 筛选可见（`status/analyzed_at` 口径）；`buildExamDetail` 的 `scoreRate` 不 NPE（`total_score>0`、`user_score` 非空）；**掌握度变更按 `exam` 小写能查到**（§7.6 顺带修复） |
| `ExamPaperSqlTest`（新，可选、mock 层） | 列表/详情 SQL 语句条数（拦截 Mapper 调用次数断言 2/3） |

- 命名遵循 `should_[预期]_when_[条件]`；Controller 测试覆盖错误码透传。
- 未覆盖并说明：真实微信环境下 8 图上传（需人工联调，见 verify）；视觉模型对模糊手写体的实际准确率（依赖模型，非代码可控，用降级路径兜底）。

---

## 16. 回滚方案

| 层 | 回滚动作 |
|----|---------|
| 代码 | revert 本 change 的代码提交（无 DDL 依赖，可独立回退） |
| 数据 | 已写入的 `mastery_change_log`/`error_question`/`exam_analysis_result` 遵循"只读不删"（规则 #9），**不回滚**；如需清理 Demo 数据，走 `docs/ops/` 人工脚本（不进 Flyway，需人工执行）；`exam_analysis_result` 可按 `questions.paperId` 精确定位本次写入的记录 |
| 配置 | 无 yml 改动；新增配置项均有代码默认值，回滚后自动失效 |
| Prompt | 新增文件不影响既有 Prompt；删除 `exam_parse_v1.txt`/`exam_parse_schema.json` 后视觉链路即不可用（回滚后无调用方） |
| 任务 | 已入 Redis 的 `exam_analysis_queue` 旧任务在回滚后由新代码消费会因 `task_type` 不匹配被忽略；建议回滚前先停 Worker（`SchedulingConfig` 关闭） |

---

## 17. 后续 change（不在本次范围）

1. 清理腾讯云 OCR：删除 `OcrService`/`TencentOcrServiceImpl`/`tencentcloud-sdk-java-ocr` 依赖与 `OCR_FAILED` 错误码，并把 `ocr_retry_count` 列改名为 `parse_retry_count`（需 DDL）。
2. 真实卷面分值/答题卡（本 change 已用 1 题 1 分制写 `exam_analysis_result`；真实分值需要识别每题 `full_score`，本次不做）。
3. 试卷题面的结构化落库（`exam_question` 增加 `choices`/`correct_answer` 列）：v3 已用「负 ID + `questionSource="exam"` + codec 解码」让 exam 题参与 redo/举一反三，**不再需要题库副本**；后续若要提高举一反三的相似度精度或做试卷题统计，再考虑加列（需 DDL）。
4. 手动归因/手动改判的后端管理接口（产品规则 #4 提到的"开发者后端接口"目前仍未实现）。
5. `getMasteryChangesBySource` 在 assessment 路径的大小写缺陷（与 §7.6 同类，本 change 只修 exam 路径）。
6. ~~观察项：`HomeworkQuotaCalculator` 的 `KP_MAX=4` 硬校验对题库/生成题来源同样可能触发~~ → **v3 已修**：改为 service 侧 `min(raw, KP_MAX)` clamp（§7.2.1），对全部来源生效，同时保留计算器内的防御断言。

---

## 18. 修订记录（t6 评审 B1–B7 + 建议 S1–S10、S13；t15 补 N1 与"总分"一致性；v3 试卷错题接入 redo/举一反三 + V0 通过；v4 知识点直出 + 数学记号收紧；**v4b 判分三态 + kp 兜底可达性**）

| 编号 | 章节 | 改法（一句话） |
|------|------|---------------|
| B1 | §7.3（+§15） | D_norm 改走 `MasteryFormulaUtil.validateDNorm(declared, 同知识点 question_bank 历史均值)`：偏离 >0.2 / 无历史 / 非法缺失一律 0.5，测试固定三条断言 |
| B2 | §8.2、§8.3（+proposal §7、tasks V3） | 12h 去重改查 `action='confirmed'`（不新增 'applied'），并把"重复上传去重"表述纠正为"信号调整去重" |
| B3 | §3.4、§2.2、§13（+tasks B1/B2） | `ocr_retry_count` 只记整卷重试 0..2；单图 2 次重试仅内存计数；1190 文案与 retry 条件改 `< 2` |
| B4 | §2.2（+tasks B2/C5） | 接口表新增"归属校验"列；`GET /tasks/{taskId}` 经 paper 校验 student_id+tenant_id，越权统一 1135，补 Controller 用例 |
| B5 | §7.1、§7.5、§10.1、§10.2（+proposal §3/§4、tasks B9/C2/F2） | TX-B 同步写 1 条 `exam_analysis_result`（status=completed/analyzed_at/1 题 1 分制 user_score+total_score/questions 内嵌 paperId），并核对 `LearningRecordMapper.xml`/`LearningRecordServiceImpl` 读取字段；补得分口径 |
| B6 | §7.2.1（+§7.2 配套、proposal §3、tasks B6） | ~~采用方案 (b)：两处到期 SQL 加 `question_id > 0`，试卷错题不参与原题重做/举一反三~~ → **v3 已推翻**（改为试卷错题接入 redo/举一反三，见下方 v3 行） |
| B7 | §9.2（+tasks A5/B3） | retry 时库中已有题目 → 跳过解析/归因、直接用库中行判分；按 `exam_question.id` 对齐，条数不一致记降级日志且不写 is_correct，附三条单测断言 |
| S1 | §6.1、§11.1、§11.2、§11.5（+tasks A3） | 新增全局 `LlmConcurrencyLimiter`（Semaphore 3）包住视觉/归因/判分，验收改"全局并发峰值 ≤3" |
| S2 | §11.3、§10.3（+tasks F4） | 补 30MB/9.5MB 的四层取值依据；前端选图后做单张与合计预检，文案"单张≤9.5MB、合计≤30MB（建议每张压到 2MB 内）" |
| S3 | §6.1、§12、§15（+tasks B4） | ~~判分 null 收敛：choice 缺答案→false、非 choice 缺答案→字符串比较→false~~ → **v4b 已推翻**：标准答案缺失一律 `NULL`（不可判定），见下方 v4b 行 |
| S4 | §2.3、§13（+tasks B1/B2） | 写死双保险：业务上限 9.5MB（主）+ `GlobalExceptionHandler` 映射 `MaxUploadSizeExceededException/MultipartException` → 1184（兜底） |
| S5 | §2.2、§14（+tasks F1） | multipart 字段名钉死 `file`，前端 `uploadWithAuth` 必须显式覆盖默认 `files` |
| S6 | §3.1、§12（+proposal §6、tasks A0/V0） | ~~`qwen3.8-flash` 视觉能力列为阻断级联调风险~~ → **v3 已实测通过**（见下方 v3 行），降级到 `pending_manual_review` 仅作兜底 |
| S7 | §2.2（+proposal §5.1） | `/tasks/{taskId}` 补完整响应字段清单；旧 `/task/{taskId}` 在 proposal 迁移说明中明确下线 |
| S8 | §10.3（+tasks F3） | 进 `ExamDetail` 前查页面栈，栈顶已是同 `paperId` 详情则 `navigateBack`，防连点压多层轮询 |
| S9 | §7.2、§15（+tasks B6/F5） | 错题详情同时兜底 `question_type/choices/answer/analysis`（`COALESCE`），避免试卷来源错题详情空白 |
| S10 | §7.6（+tasks C3/C5） | `selectCriticalErrors` 补 `student_id` 与 `question_id > 0`（既有跨学生泄漏与负 ID 缺陷顺带修复），签名加 `studentId` |
| S13 | §2.4、§10.3、§13（+tasks B2/F4） | `paperType` 钉死 5 值 + 默认 `midterm` + 1191 错误码，前端 picker 与后端校验同源 |
| 附 | §7.6（+tasks C3/C5） | 顺带修复 `getMasteryChangesBySource(id,"EXAM")` 大小写导致学习记录详情掌握度恒空（既有缺陷） |
| **N1（t15）** | §7.2、§7.2.1、§7.6、§15（+proposal §3/§6/§7、tasks B6/B8/V3） | `selectActiveErrorCountGroupByKp` 加 `question_id > 0`（负 ID 防御清单第 3 处），试卷错题不计入 `kpErrorCountMap` 配额分母，消除"同 kp ≥5 道试卷错题 → 次日作业生成抛异常"；补 `HomeworkServiceTest` 回归用例 |
| **一致性（t15）** | §7.1、§7.5、§10.1（+proposal §3/§7、tasks B9/C3） | 统一"总分"口径：`exam_paper.total_score` = `exam_analysis_result.total_score` = `judgedCount`，`userScore = correctCount`，展示为 `userScore/judgedCount`，删除上一版"paper.total_score=user_score"的第二套值 |
| **v3：试卷错题接入 redo/举一反三（推翻 B6 方案 b）** | §0（D19–D23）、§7.2、§7.2.1、§7.6、§11.6、§14（#26 扩展）、§15、§17（观察项升级为已修）（+proposal §3/§4/§5/§6/§7、tasks B6 重写 + 新增 Backend D） | ① 引用形态：`questionSource="exam"` + `questionId=-exam_question.id`（与 `error_question` 同值，避免 `uk_plan_question` 与题库正数 id 互撞）；② 撤销三处 `question_id > 0` 排除，改为"组装层按符号分流 + `kpErrorCountMap` **按 kp 合计 clamp 到 `KP_MAX=4`（方案 A，超出顺延次日）**"；③ 作业链路 5 处题面分支 + 提交/判分/stage 推进（`advanceStage/resetStage` 直接用负 ID）；④ 举一反三源题题库优先 + exam 兜底（transient `QuestionBank`，不落库）；⑤ 顺带修 `completeStageWithCheck` 的 `next_due_date=NULL` 违反 `NOT NULL`；⑥ `redoSupported` 恒 true |
| **v3：V0 视觉探针通过（S6 降级）** | §3.1、§12（+proposal §6/§7、tasks A0/V0） | captain 2026-11-11 实测 `qwen3.8-flash` 多模态端点：400×140 PNG（`H7Q2`）返回 `H7Q2`、HTTP 200、`request_id=657f1d71-...`；S6 从"阻断级"改为"已验证"，降级路径仅作兜底 |
| **v4：知识点在解析阶段直出** | §0（D24）、§3.2、§3.3、§3.4、§3.7（新增）、**§5（归因链路重写，任务单中的「§7 归因链路」即本章）**、§7.2、§12、§14（规则 #8 行）、§15（+4 个 v4 测试）（+proposal §2/§3/§4/§5/§6/§7、tasks Backend E + V3/ARCH1） | 候选知识点由 `ExamKpCandidateService` 构造（subject + 年级过滤 + active，上限 **150**，超限**截断 + 同清单兜底 + 降级日志**），注入 `{knowledgePoints}`；Schema 增 `knowledgePointId`（required、可空串）；服务端白名单校验（幻觉置空 + warn）；`AttributionAgent` 只对空 kp 题按 ≤40/批兜底一次；统计 `kpDirect/kpFallback/kpUnresolved`（+`judged/unjudged/kpTotal/kpTruncated`）写入 `questions.stats`；规则 #4 回写"归因前置、AttributionAgent 降级为兜底" |
| **v4：数学记号收紧** | §3.2（Prompt 第 7 条）、§3.4（第 6 步清洗）、§14（#24 行）（+proposal §4/§5 D25、tasks E2/E5/ARCH2） | 允许纯文本 `x^2`/`a_n`；禁止 `^{...}`/`_{...}` 花括号与任何反斜杠命令；幂优先 `²/³` 或 `^2`，下标优先 `a(n)`/中文；约定 #24 措辞同步为"禁花括号与 LaTeX 命令"而非"全禁 `^`" |
| **v4b（阻塞）：判分三态** | §0（D26）、**§6.1 整节重写**、§5（`stats.unjudged`）、§7.5（`judgedCount` 明确"NULL 不计分子分母"）、§12（两行）、§15（四条用例 + 作业侧 null）、§18（+proposal §2/§3/§5/§6/§7、tasks B4/B5/D4/E5） | 标准答案缺失 → `is_correct=NULL`（不调 AI、不计掌握度、不入错题、计 `unjudged`）；学生空+答案有 → `false`；学生空+答案空 → `NULL`；`unreadable`/超时 → `NULL`（v4 已排除，补充确认）；共享服务在作业侧必须处理 null（`is_correct==null → continue`，避免现状"null 当答错"） |
| **v4b：kp 兜底可达性** | §0（D27）、**§3.7 分级上限/截断率/可达性**、§5（attribution 清单 500 + 分片）（+proposal §3/§6/§7、tasks E1/E4/V3） | parse 150 / attribution 500：被 parse 截断的 kp 仍进兜底清单，消除"直出+兜底都不可达"死区；截断率公式与 warn/降级日志写死；>1000 记 `kp_unreachable` 并标红；验证口径明确"可归因率分母包含被截断 kp" |

> 仍未决（不阻塞 apply，需人工/联调确认）：
> 1. **`qwen3.8-flash` 真实视觉能力**（S6）只能由 verify 的 V0 探针与真机联调确认，代码侧只能用降级路径兜底；
> 2. `docs/product/index.md`（规则 #3/#4/#5/#9）与 `implicit-contracts.md` #26 的回写仍需人工审批（proposal §4）；
> 3. 1 题 1 分制是为绕开"识别卷面分值"的 Demo 折中，若后续要求真实分值，需新 change（§17-2）。

### 18.1 v4c：文档漂移修正（无行为变更）

> **v4c：文档漂移修正（无行为变更）**——按 `verify.md` §8 L2 与 t10 任务要求，仅改工件文本，源码/行为未变：
> 1. `tasks.md` 的 `exam_analysis_result.questions.kpStats` → **`stats`**（并校正键名 `kpDirect/kpFallback/kpUnresolved`，与实现 `buildOutcomeStats` 一致）；
> 2. `tasks.md` 的 `uni.compressImage({quality: 60})` → **「<800KB 不压、否则 quality 85」**（真图 quality 60 会压糊选项，t20 已改实现）；
> 3. 本文件 §10.3 的"`Profile.vue` 内嵌上传页" → **独立页 `pages/exam-upload/ExamUpload.vue`**（`Profile.vue` 仅保留入口）；
> 4. 测试类名 `ExamSettlementTest`（实现中不存在）→ **`ExamPaperServiceTest` 的结算用例**（`should_parseJudgeAndSettle` / `should_notRepeatAccounting_when_alreadySettled`）；
> 5. 顺带：`design.md` §5 的 stats JSON 示例改为真实键名与真实链路数值（`judged/unjudged/kpDirect/kpFallback/kpUnresolved/kpTotal/kpTruncated`）。
>
> **字节数（v4c 后，含本行）**：`proposal.md` = **28,856 B**；`design.md` = **120,772 B**；`tasks.md` = **49,891 B**（另有新增 `docs-diff.md` = 16,706 B，为待审批的 docs 回写建议，不计入三件工件）。
>
> **未执行（按 captain 2026-11-11 指令暂停）**：`git mv` 归档、`openspec/specs/*` 回写、任何 commit；等 t30（A1/A2/A3/A5/A6/A7 修复）与 t31 复验通过后再执行。
