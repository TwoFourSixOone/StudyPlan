# Tasks：试卷上传 → LLM 视觉解析 → 错题/掌握度 → 自动调整 → 历史回看

> **Change 名称**：`2026-11-11_exam_paper_upload_llm`
> **修订**：2026-11-11 吸收 t6 评审（B1–B7 + S1–S13），逐条映射见 `design.md` §18；本文件相应新增 A0（视觉探针）、B9（学习记录写入）、F6（错题详情兜底）并修订既有条目
> **v3 修订（2026-11-11）**：用户要求「试卷错题进入原题重做 + 举一反三」，**推翻 B6 方案 (b)**；新增 **Backend D** 组；撤销 B6/N1 的 `question_id > 0` 排除（配额改 clamp 方案 A，超出顺延）；V0 探针已通过（captain 实测），A0/V0 已标记完成
> **v4 修订（2026-11-11）**：知识点改为**视觉解析直出**（候选注入 + Schema + 白名单，`AttributionAgent` 降为兜底），新增 **Backend E** 组；数学记号收紧（D25）；规则 #4 回写口径同步 ARCH1
> **v4b 补丁（2026-11-11，含 1 条阻塞）**：**判分三态**（标准答案缺失 → `is_correct=NULL`，不判 false）落在 B4/B5/D4/E5；**kp 分级上限**（parse 150 / attribution 500，>500 分片）落在 E1/E4；B4 同时修作业侧"null 当答错"与 `MasteryService` 跳过 null
> **执行约束**：apply 只做本文件范围内的事；`prompts/` 新增/修改已授权（proposal §4）；**零 DDL**；不改 `application.yml`（新增配置项一律用代码内默认值）
> **分组**：Backend A（视觉解析通道）→ Backend B（上传/任务/判分/结算）→ **Backend D（试卷错题回流作业，v3）** → **Backend E（知识点直出 + 记号收紧，v4）** → Backend C（信号/历史/删除手工录入）→ Frontend → Verify → Review → Archive
> **依赖顺序**：A → B → D/E → C 可并行提测，但 B 依赖 A 的解析 DTO、D 依赖 B 的错题入队与 §4 编解码、E 依赖 A 的解析链路；Frontend 依赖 C 的接口契约；Verify 依赖 A+B+C+D+E+Frontend；Review 与 Verify 分离；Archive 最后

---

## Backend A — 视觉解析通道（LLM 客户端 + Prompt/Schema + 解析服务）

### A0. 视觉能力前置探针（S6，**已通过**）

- [x] ~~用一张真实试卷图跑 `ExamParseAgent` 最小链路，确认 `qwen3.8-flash` 返回合法 JSON~~ → **captain 2026-11-11 实测通过**：`POST {AGENT_ENDPOINT}/api/v1/services/aigc/multimodal-generation/generation`，`input.messages[0].content=[{"image":"data:image/png;base64,..."},{"text":"..."}]`、`parameters.result_format=message`；400×140 PNG（黑字 `H7Q2`）→ `output.choices[0].message.content[0].text="H7Q2"`，`image_tokens=72`，HTTP 200，`request_id=657f1d71-8262-9101-a04e-612d155f1066`
- [x] 结论：`qwen3.8-flash` 支持图像输入、base64 data URL 可用；S6 由"阻断级"降为"已验证（降级路径仅兜底）"
- [ ] 实现期复核：`AgentScopeClient.callVision` 的 `ImageBlock(Base64Source)` 在 DashScope 端点下与上述 body 等价（同一 `mediaType`、无 `data:` 前缀）
- [ ] 验收：A0 证据已写入 verify 报告；若复核失败再回退到"阻断"流程

### A1. Prompt 与 Schema（已授权新增）

- [ ] 新增 `src/main/resources/prompts/versions/exam_parse_v1.txt`
  - 占位符：`{subject}`、`{grade}`（`GradeMatcher.displayName`，空值"当前"）、`{imageSeq}`、`{imageTotal}`、`{retryHint}`
  - 必写约束（design §3.2 九条）：只输出 JSON / 题干与学生作答分离 / 选择题 `label+text` 且 `correctAnswer` 为标签 / 非选择题答案为结论式 / 不可辨认走 `unreadable` 且不臆造 / 纯文本禁 LaTeX 与 Markdown / 不输出总分与薄弱点总结 / 阅读顺序 + 跨页重复只出一次
  - 验收：占位符与 `ExamParseAgent` 的 `replace` 调用一一对应（单测断言无残留 `{...}`）
- [ ] 新增 `src/main/resources/prompts/schemas/exam_parse_schema.json`
  - 与 design §3.3 完全一致（`questions[].required`、`formatType` 枚举、`answerStatus` 枚举、`maxItems` 60/8/6、各 `maxLength`）
  - 验收：`AgentOutputValidatorTest` 用该文件做正反例校验

### A2. `AgentScopeClient` 图文调用

- [ ] `client/AgentScopeClient` 新增 `visionModel`（同 `AgentScopeConfig` 的 apiKey/baseUrl/modelName/endpointType，附 `DashScopeChatFormatter`），`@PostConstruct` 中一并初始化
- [ ] 新增 `String callVision(String prompt, List<VisionImage> images)`：`VisionImage = (String mediaType, byte[] bytes)`；内部生成 base64（剥离入参可能带的 `data:...;base64,` 前缀）、构造 `TextBlock` + `ImageBlock(Base64Source)`；沿用现有异常包装（`AGENT_CALL_FAILED`）
- [ ] 文本路径 `call/doCall` **零改动**（回归依据：`HomeworkServiceTest`/`AttributionAgentImpl` 相关测试全绿）
- [ ] 新增 `dto/VisionImage`（或复用具名 record），不新增模型名配置项
- [ ] 验收：单测断言 `Msg.content` 含 1 个 `TextBlock` + 1 个 `ImageBlock`；`mediaType` 透传；异常转 `BusinessException`

### A3. 解析线程池、全局并发闸门与超时预算

- [ ] `config/ThreadPoolConfig` 新增 `@Bean(name="examParseExecutor", destroyMethod="")`（`createExecutor("exam-parse", core, core, queue)`），并注册进 `executorShutdownManager`（`thread-pool.exam-parse.await-terminate-seconds:60`）
- [ ] 新增 `config/LlmConcurrencyLimiter`（`Semaphore(3)`，许可数读 `exam.parse.grading-concurrency`，默认 3）：`AgentScopeClient.call()` 与 `callVision()` 内部 `acquire/release`（`try/finally`），**视觉 + 归因 + 判分共用**（S1）
- [ ] 配置项默认值（`@Value`）：`thread-pool.exam-parse.core-size:3`、`queue-capacity:16`；`exam.parse.task-timeout-seconds:180`、`image-timeout-seconds:60`、`grading-timeout-seconds:60`、`grading-concurrency:3`、`max-images:8`、**`max-image-mb:9.5`**、`max-total-mb:30`、`max-questions:200`
- [ ] `task/ExamAnalysisWorker` 拉取节流：`getActiveCount() >= coreSize` 时本轮不取任务
- [ ] 验收：单测断言 **"全局 LLM 并发峰值 ≤3"**（视觉与判分任务同时提交时观测计数峰值 ≤3）与"池满时不从队列拉取"；启动日志出现 `线程池已创建: prefix=exam-parse`

### A4. 题面编解码工具

- [ ] 新增 `common/util/ExamQuestionTextCodec`：`encode(stem, choices, correctAnswer, explanation)` / `decode(String)`（返回 `stem/choices/correctAnswer/explanation`）
- [ ] 格式与容错按 design §4.1（`【题干】/【选项】/【正确答案】/【解析】`，无标记 → 整段为 `stem`；选项按 `A. ` 前缀解析）
- [ ] 验收：`ExamQuestionTextCodecTest` 往返一致、旧数据兼容、缺段落容错、选项顺序稳定

### A5. 解析服务（`ExamParseService` / `ExamParseAgent`）

- [ ] 新增 `agent/ExamParseAgent`（接口 + `impl`）：`List<ParsedQuestion> parseImage(byte[] image, String mediaType, ParseContext ctx)`
  - 调用 `callVision`，`AgentOutputValidator.validateExamParse` 校验，失败重试最多 2 次（带 `{retryHint}`），计数返回给调用方
  - 单题非法/选项不足 → 丢弃该题；`questions` 空数组 + `imageWarning` → 合法 0 题
  - 文本清洗（`LlmTextSanitizer`）+ `ChoiceOptions.canonicalize`
- [ ] 新增 `service/ExamParseService`：整卷编排
  - 逐图提交 `examParseExecutor`，`ExecutorCompletionService` + deadline 收集（design §3.5）；每次 LLM 调用经 `LlmConcurrencyLimiter`（S1）
  - 题号重排 `1..N`、跨图去重（相似度 ≥0.9，常量 `CROSS_PAGE_DUPLICATE_THRESHOLD`）、总题数 >200 截断
  - 归因：映射为 `ExamQuestionOcrDTO`，按 ≤40 题/批调用现有 `AttributionAgent`，批间串行，失败重试 ≤2 次（累加 `attribution_retry_count`）
  - **单图重试只记内存计数**（`ExamParseOutcome.cutRetryCount` 仅进日志/降级摘要），**不写 `ocr_retry_count`**（B3/D14）
  - 返回 `ExamParseOutcome`（题目列表 + 每图状态 + 单图/归因重试次数 + 告警）
- [ ] 验收：`ExamParseServiceTest` / `ExamParseAgentTest` 覆盖 design §15 的全部条目（含全局并发 ≤3、单图重试不写 `ocr_retry_count`）

### A6. 图片读取

- [ ] `service/FileStorageService` 新增 `byte[] readBytes(String url)`
- [ ] `LocalFileStorageServiceImpl` 实现：`baseUrl` 前缀剥离 → `Path.normalize` 后必须仍在 `basePath` 下（路径穿越拒绝 → `1188`）
- [ ] `CosFileStorageServiceImpl` 实现：抛 `BusinessException(EXAM_PARSE_FAILED)`（未实现，与 `upload` 一致）
- [ ] 验收：`FileStorageServiceTest` 覆盖正常/穿越/未实现三种

### A7. A 组测试

- [ ] `ExamParseAgentTest`（新）、`ExamParseServiceTest`（新）、`ExamQuestionTextCodecTest`（新）
- [ ] `AgentOutputValidatorTest`（+）`validateExamParse` 正反例
- [ ] `FileStorageServiceTest`（+）
- [ ] 验收：`mvn test -Dtest=ExamParse*Test,ExamQuestionTextCodecTest,AgentOutputValidatorTest` 全绿

---

## Backend B — 上传 / 解析任务 / 判分 / 结算

### B1. 错误码

- [ ] `common/exception/ErrorCodes` 新增 `1182–1191`（design §13 文案逐字一致；**1184 为 9.5MB**，**1190 为"最多重试 2 次"**，1191 为 `paperType` 非法）
- [ ] `GlobalExceptionHandler` 新增 `@ExceptionHandler(MaxUploadSizeExceededException.class)`（含 `MultipartException`）→ `1184`（S4 兜底）
- [ ] `1132/1133/1134` 标记删除（B3/C2 中删除调用方后移除）；`1137 OCR_FAILED` 标 `@Deprecated`
- [ ] 验收：`ErrorCodesTest` 通过（码值唯一、相邻不冲突）；`MaxUploadSizeExceededException` 单测返回 1184 而非 500

### B2. 上传会话接口

- [ ] `controller/ExamPaperController` 新增：
  - `POST /api/student/exam/papers`（`createPaper(subjectId, examDate, paperType, imageCount)`）
  - `POST /api/student/exam/papers/{paperId}/images`（multipart 单张）
  - `POST /api/student/exam/papers/{paperId}/submit`（显式触发解析；0 张 → `1187`）
  - `POST /api/student/exam/papers/{paperId}/retry`
  - 删除 `POST /result/{resultId}/grade`
- [ ] `service/ExamPaperService` 同步方法签名；`ExamUploadVO` 改为 `paperId/status/uploadedCount/imageCount/taskId?`
- [ ] `ExamPaperServiceImpl`：
  - 创建会话：科目校验 + **`paperType` 白名单校验（§2.4 五值，非法 `1191`，空按 `midterm`）** → 同学生+科目+日期去重（`1131`）→ 30 分钟内 `uploading` 复用 / 超期重置 → 插入 `image_urls='[]'`（**注意 `image_urls` 为 `NOT NULL`**）→ 返回
  - 追加图片：归属/状态/类型/**单图 ≤9.5MB**/累计 ≤30MB/张数 ≤8 → 文件落盘（事务外）→ 事务内追加 URL（达声明张数时自动触发解析，兜底）
  - 提交解析（`submit`）：`uploadedCount ≥ 1` → CAS `uploading → pending` + 建 task + Redis 入队；重复/已提交 → `1186`
  - 重试：仅 `failed` 且 **`ocr_retry_count < 2`**（整卷最多重试 2 次，B3）→ `ocr_retry_count += 1` + 重置 task 为 `pending` + 入队（复用原图）
  - **归属校验（B4）**：`/images`、`/submit`、`/retry`、`/tasks/{taskId}` 均按 paper 校验 `student_id + tenant_id`；task 越权统一 `1135`、paper 越权 `1130`
- [ ] 前端 multipart 字段名固定 `file`（S5；`utils/upload.ts` 的 `uploadWithAuth(url, filePath, name)` 调用处显式传 `'file'`）
- [ ] 验收：`ExamPaperServiceTest` 覆盖 design §15 的上传用例；`ExamPaperControllerTest` 覆盖 1182/1183/1184/1185/1187/1191/1131/1139 + **task 多人越权 1135**

### B3. 解析任务编排（Worker）

- [ ] `ExamPaperServiceImpl.processExamAnalysis` 重写为 design §1/§7 流程：
  - CAS 抢占（`WHERE id=? AND status='pending'`）+ paper 状态校验
  - **先查库判重（B7）**：`exam_question` 已有行 → 跳过视觉解析与归因，直接 codec 解码库中行进入判分；无行才调 `ExamParseService`（A5）→ TX-A
  - 调 `AnswerGradingService.gradeBatch`（B4）→ TX-B 结算（B5/B6/B7 + B9 学习记录）
  - 失败：`task/paper=failed` + `error_message` 截断 500 + `agent_degradation_log`
  - 提交成功后异步触发 `ExamSignalAdjustService`（C1）
- [ ] **判分结果按 `exam_question.id` 对齐**；条数 != 库中行数 → 记降级日志 + 不写任何 `is_correct` + `task=failed`（B7）
- [ ] 移除 `OcrService` 注入与 `ocrWithRetry`/`attributionWithRetry` 旧实现（归因重试逻辑迁入 `ExamParseService`）；**`ocr_retry_count` 只由 retry 入口写（整卷 0..2）**
- [ ] `task_type` 写 `vision_parse_attribution`，Worker 不按 `task_type` 过滤（旧值兼容）
- [ ] 验收：`ExamAnalysisWorkerTest`（CAS 幂等/重复消费/TX-A 失败/TX-B 失败/**retry 跳过解析**/**判分条数不一致不写 is_correct**）

### B4. 判分复用（抽 `AnswerGradingService`，**v4b 三态**）

- [ ] 新增 `service/AnswerGradingService` + `impl`（`grade` / `gradeBatch` 返回 **`Boolean` 三态**，design §6.1）；内部经 `LlmConcurrencyLimiter`（S1），`budget=60s`
- [ ] **三态分支（v4b 阻塞项，逐行对齐 §6.1 契约表）**：
  - 学生空 + 标准答案有 → `false`（不调 AI）
  - **标准答案空/无法确定 → `null`（choice 与非 choice 都不调 AI）**
  - 学生空 + 标准答案空 → `null`
  - `unreadable` / 批次超时 → `null`
  - 两者都有 → choice 标签比较；其余 `grading_v1.txt`（解析不了/异常 → 字符串比较）
- [ ] `HomeworkServiceImpl`：`choice` 比较与 `aiGradeQuestion` 委托；**`boolean[]` 改三态载体**，`is_correct=NULL` 时不计入 `correctCount`、不触发 `recordWrongAnswer/advanceStage/resetStage`
- [ ] `MasteryServiceImpl#processSubmissions`：`is_correct == null → continue`（修现状把 null 当答错的缺陷）
- [ ] 验收：`AnswerGradingServiceTest` 四条必测（choice/非 choice × 答案有/无）+ 学生空/答案空/unreadable/超时；`HomeworkServiceTest` 全绿 + null 处理断言（不进 correctCount/不进错题/不进掌握度）

### B5. 判分结果与题目批量写入

- [ ] `mapper/ExamQuestionMapper` 新增 `batchInsert(List<ExamQuestion>)`、`batchUpdateJudgeResult(List<JudgeResult>)`（CASE WHEN，≤100/批）、`selectIdAndIndexByPaper(paperId, tenantId)`
- [ ] **得分列首次启用（B5）**：`score = is_correct==1 ? 1 : 0`、`full_score = 1`（1 题 1 分制）；`is_correct IS NULL` 时两列留 `NULL`（不虚构 0 分）
- [ ] **v4b**：`judgedCount` 只统计 `is_correct IS NOT NULL`（`NULL` 题不计分子分母）；`unjudgedCount` 计入 `stats`
- [ ] 全部 INSERT/UPDATE 显式列名，不使用 `select *`
- [ ] 验收：单测断言"50 题 = 1 次 batchInsert + 1 次 batchUpdate"，无循环单条写；`score/full_score` 取值断言

### B6. 错题批量入队（复用 `ErrorQuestionService`）

- [ ] `service/ErrorQuestionService` 新增 `recordWrongAnswers(studentId, tenantId, List<WrongQuestionRef>, sourceType, sourceId, wrongDate)`
- [ ] 实现：先批量查已存在 → 批量 INSERT / 批量 UPDATE（语义同现有 DUPLICATE 回退）；`DuplicateKeyException` 兜底再 UPDATE
- [ ] 试卷来源 `questionId = -exam_question.id`（统一在 `ExamPaperServiceImpl` 转换，集中一处常量）
- [ ] `mapper/ErrorQuestionMapper`：
  - `selectPageWithDetail` / `selectDetailById` 增加 `LEFT JOIN exam_question eqx ON eq.source_type='exam' AND eqx.id = -eq.question_id AND eqx.tenant_id = eq.tenant_id`，题面/难度 `COALESCE` 兜底；**详情同时兜底 `question_type`（`COALESCE(qb.type, eqx.question_type, 'short')`）、`question_choices`（保持 NULL）、`correct_answer`/`question_analysis`（空串兜底）**（S9）
  - **v3：`selectDueQuestionsByKnowledgePoint` 与 `selectDueKnowledgePointIds` 不加 `question_id > 0`**（撤销 B6 方案 (b)）：负 ID 行正常返回，作为 redo 候选（`LIMIT 3`/kp 不变）
  - **v3：`selectActiveErrorCountGroupByKp` 不加过滤**（撤销 N1 排除）：试卷错题要计入 `kpErrorCountMap`；`KP_MAX` 风险由 Backend D 的 clamp 解决
  - `selectCriticalErrors` 改为 `... AND student_id = #{studentId} AND question_id > 0`（S10），签名加 `studentId`，调用方 `PlanAdjustmentServiceImpl` 传 `goal.studentId`（负 ID 对调整 Agent 无意义，属防御保留）
  - **v3 顺带修复**：`completeStageWithCheck` 的 `next_due_date = NULL` → `#{completedDate}`（列 `DATE NOT NULL`，否则 stage 5 完成时 SQL 报错）
- [ ] `ErrorQuestionVO.redoSupported` 恒为 `true`（v3：试卷错题已支持原题重做，字段保留用于前端兼容）
- [ ] 生命周期（design §7.2.1）：exam 错题**正常走艾宾浩斯**（答对 advance 1→5→completed / 答错 reset 1），掌握度 ≥0.8 时 `syncPauseStatus` 仍暂停
- [ ] 验收：`ErrorQuestionServiceTest`（+）批量入队、更新语义、**到期查询返回负 ID 行**（v3）、错题本/详情有题面且字段不空（S9）、`selectCriticalErrors` 不跨学生且负 ID 不返回（S10）、**`completeStageWithCheck` 写 `completedDate` 不报错**

### B7. 掌握度批量入口（复用 `MasteryService`）

- [ ] `service/MasteryService` 新增 `updateMasteryFromExam(studentId, tenantId, paperId, List<ExamMasteryEvent>)`
- [ ] `MasteryServiceImpl`：批量查 state（`IN`）→ 缺失批量创建 → 内存按事件顺序套 `MasteryFormulaUtil` → 批量 UPDATE state → 批量 INSERT `mastery_change_log`（每事件一条）→ 按去重 kp `syncPauseStatus`
- [ ] 日志字段严格按 design §7.4（`change_type='exam'`、`source_id=paperId`、`delta/before/after`）
- [ ] **`D_norm` 按 design §7.3（B1）**：`mapDifficulty` → 1 条 SQL 取同知识点 `question_bank` 难度分布算历史均值 → `MasteryFormulaUtil.validateDNorm(declared, avg)`；**非法/缺失/无历史/偏离 >0.2 一律 0.5**（不是 0.6）
- [ ] 验收：`MasteryServiceTest`（+）"批量结果 == 逐条调用结果"、答对也更新、答错下限、无 N+1、**D_norm 四条断言（偏离>0.2→0.5 / 无历史→0.5 / 非法→0.5 / 阈值内→declared）**

### B8. B 组测试

- [ ] `ExamPaperServiceTest`（结算用例 `should_parseJudgeAndSettle` 等）、`ExamAnalysisWorkerTest`、`AnswerGradingServiceTest`、`LearningRecordServiceTest`（新/扩展）
- [ ] `ErrorQuestionServiceTest`、`MasteryServiceTest`、`HomeworkServiceTest`（回归，**含 N1 用例：某 kp 5 道试卷错题 → 次日作业生成不失败**）
- [ ] 验收：`mvn test` 全绿

### B9. 学习记录写入（规则 #9，B5 方案 (a)）

- [ ] 新增 `service/ExamLearningRecordService`（或并入 `ExamPaperServiceImpl` 私有方法）：TX-B 内写 1 条 `exam_analysis_result`
- [ ] 字段映射按 design §7.5：`student_id/subject_id/exam_name({科目}{类型中文} {日期})/image_urls/questions(JSON 对象含 paperId+items)/total_score(已判定题数)/user_score(正确题数)/status='completed'/analyzed_at=now()`
- [ ] 写前查重 1 条：`WHERE student_id=? AND tenant_id=? AND exam_name=? AND status='completed' LIMIT 1`，命中跳过并 `log.warn`
- [ ] `exam_paper.total_score = judgedCount`（与 `exam_analysis_result.total_score` 同口径；列表/详情展示 `userScore/judgedCount`）
- [ ] 验收：`ExamPaperServiceTest` 的结算用例（`should_parseJudgeAndSettle`/`should_notRepeatAccounting_when_alreadySettled`）断言 1 条记录且字段齐全、重复结算不写第二条；`LearningRecordServiceTest`（+）学习记录列表 `EXAM` 可见 + 详情 `scoreRate` 不 NPE

---

## Backend D — 试卷错题回流作业链路（v3，推翻 B6 方案 b）

> 目标：试卷错题进入「原题重做」与「举一反三」（design §7.2.1 / §7.6 / §14-#26 v3）。**零 DDL**，不写 `question_bank` 副本。

### D1. 引用形态与常量

- [ ] 新增常量/工具 `ExamQuestionRefs`：`toRefId(examQuestionId) = -examQuestionId`、`examIdOf(refId) = -refId`（全链路**唯一转换点**，禁止散落负号）
- [ ] `DailyPlanQuestionRef.questionSource` 注释加入 `exam`（`bank|generated|exam`）；`SubmitAnswerRequest` 注释同步；`HomeworkSubmissionDetail` 注释同步
- [ ] 验收：单测断言 `toRefId(42) = -42`、`examIdOf(-42) = 42`；组装后的 ref 里 exam 题 `questionId < 0` 且 `questionSource="exam"`

### D2. 到期统计与 redo 组装

- [ ] `HomeworkServiceImpl#generateDailyHomework`：遍历 `getDueErrorQuestions` 的 VO 时按 `vo.getQuestionId() < 0` 分流为 `new DailyPlanQuestionRef(id, "exam", "redo")`，否则维持 `("bank","redo")`
- [ ] exam redo 同样进入 `errorRefs`、`occupiedQuestionIds`、`dueErrorCount`（题量/去重与 bank 一致）
- [ ] 每 kp 每日 redo 上限沿用 `selectDueQuestionsByKnowledgePoint` 的 `LIMIT 3`（天然 ≤ `KP_MAX=4`；未选中的继续 active → 次日顺延，不记为未完成、不触发 SIG-3）
- [ ] `daily_plan.content` 组装完成后按 `(questionSource, questionId)` 去重校验，重复丢弃 + `log.warn`
- [ ] 验收：单测断言 kp 的 redo refs 同时可含 `bank` 与 `exam`，且 `questionId` 符号正确；同一 `(source,id)` 不重复

### D3. 题面组装（5 处 exam 分支）

- [ ] `buildHomeworkVO`：新增 `"exam"` 分支 → 从 `Map<Long, ExamQuestion>`（key=`-id`）取数，`ExamQuestionTextCodec.decode` 还原 `stem/choices/correctAnswer/explanation`，`LlmTextSanitizer.sanitize` 兜底，`choices` 只出文本（约定 #25），`questionFormatType = question_type`，`difficulty` 原样
- [ ] `findCorrectAnswer` / `getQuestionContent` / `getQuestionFormatType` / `findKnowledgePointId` 四处补 `"exam"` 分支（把现有 `else` 兜成 `generated` 的写法改为显式 `switch/if`，**禁止默认落到 generated**）
- [ ] 批量加载：每份作业最多 1 条 `examQuestionMapper.selectBatchIds(absIds)`（`absIds` 只含本份作业的 exam refs）
- [ ] 验收：`ExamRedoIntegrationTest` 断言 exam 题的 stem/choices/correctAnswer/explanation/type/difficulty 与 `ExamDetail` 一致；负 ID 不会去查 `question_bank`

### D4. 提交、判分与 stage 推进

- [ ] `submitAnswer`：来源白名单加 `exam`；`question_source='exam'` 落库；kp 从 `exam_question.knowledge_point_id` 取
- [ ] `gradeHomework`：判分走同一 `AnswerGradingService`（choice 比较 / 其余 AI；**v4b：标准答案缺失→`NULL`、unreadable→`NULL`、学生空+答案有→`false`**）
- [ ] redo 分支：`questionType='redo'` && `question_source='exam'` → `advanceStage/resetStage(studentId, tenantId, submission.getQuestionId())`（负 ID 直接命中 `error_question`，**不做二次换算**）；**`is_correct=NULL` 时跳过 stage 操作**（v4b）
- [ ] 掌握度：复用 `updateMasteryFromHomework`（W_source=0.4），不新增公式
- [ ] 验收：`ExamRedoIntegrationTest` ④ 答对 stage 1→2、答错 reset 1、stage 5 完成写 `next_due_date=completedDate` 且 `status='completed'`

### D5. 配额 `KP_MAX`（选 A：clamp 到 4，超出顺延）

- [ ] `HomeworkServiceImpl` 构造 `kpErrorCountMap` 时：`min(selectActiveErrorCountGroupByKp 原值, HomeworkQuotaCalculator.KP_MAX)`，每 kp 首次超限 `log.warn`（记录 raw 值）
- [ ] **不改** `HomeworkQuotaCalculator` 的 `KP_MAX=4` 语义（断言保留为防御）
- [ ] 顺延语义：未被抽中的到期错题保持 `status='active'`、`next_due_date` 不变 → 次日继续到期；**不写 `daily_plan` 未完成、不触发 SIG-3**
- [ ] 验收（两条必测）：
  - **T1**：某 kp 有 5+ 试卷错题 → 当天 `generateDailyHomework` 不失败；进作业的该 kp 错题数 ≤4；其余次日仍在到期列表（顺延），且 `daily_plan` 不被判未完成
  - **T2**：试卷错题与题库错题**混排**时 clamp 按**合计**算（如 3 道题库 + 3 道试卷 = 6 → clamp 到 4），不是各算 4（断言传入配额计算器的 map 值 = 4）

### D6. 举一反三源题（题库优先 + exam 兜底）

- [ ] `ErrorQuestionMapper.selectDueExamSourceQuestion(studentId, tenantId, kpId, targetDate)`：`error_question` JOIN `exam_question ON eqx.id = -eq.question_id`，`active` + `next_due_date <= ?`，`ORDER BY next_due_date, error_question_id LIMIT 1`
- [ ] 适配器 `ExamQuestionSourceAdapter.toQuestionBank(ExamQuestion)`：返回**临时 `QuestionBank`（不落库）**——`content = 题干 + 选项行`、`answer = correctAnswer`、`analysis = explanation`、`type = questionType`、`difficulty`、`knowledgePointId`、`choices = 选项 JSON`、`grade = null`
- [ ] `HomeworkServiceImpl` 举一反三：先 `selectSourceQuestionForSimilar`（题库），为 null 时走 exam 兜底；仍为 null 或 `answer` 为空 → 跳过该 kp + 写 `agent_degradation_log`（`input_summary` 记 kpId + 原因）
- [ ] `SimilarQuestionService.generateWithValidation` 签名**零改动**
- [ ] 验收：`ExamRedoIntegrationTest` ②（题库无源题时能生成；缺答案时跳过 + 降级日志）

### D7. 解析/题面口径

- [ ] exam redo 不调用 `analysis_gen_v1.txt`；`homework_submission_detail.analysis` 落 codec 的 `explanation`（无则 NULL，前端"暂无解析"）
- [ ] 验收：日志/单测断言 exam redo 批改期间无 `analysis_gen` 调用；`analysis` 列写 `explanation`

### D8. D 组测试

- [ ] `ExamRedoIntegrationTest`（新）四条：① 试卷错题次日进原题重做（ref 形态 + 题面还原）；② 举一反三可用 exam 源题生成（缺答案跳过+降级）；③ 5+ 试卷错题不导致作业生成失败（同 D5-T1）；④ exam redo 答题后 stage 推进
- [ ] `HomeworkServiceTest`（+）D5-T1 / T2 两条 clamp 用例；`ErrorQuestionServiceTest`（+）`completeStageWithCheck` 写 `completedDate`
- [ ] `ExamQuestionSourceAdapterTest`（新）：临时 `QuestionBank` 字段映射 / 缺答案返回不可用
- [ ] 验收：`mvn test` 全绿

---

## Backend E — 知识点直出 + 数学记号收紧（v4）

> 目标：把知识点判定前置到视觉解析（§3.2/§3.3/§3.7），`AttributionAgent` 降为兜底（§5）；数学记号按 D25 收紧。**零 DDL**；Prompt/Schema 文件正文在 apply 时按本组执行（已授权，proposal §4）。

### E1. kp 候选构造与缓存（parse 150 / attribution 500）

- [ ] 新增 `service/ExamKpCandidateService`：`CandidateSnapshot build(tenantId, subjectId, Integer studentGrade)`，快照内同时产出 **parse 切片（前 150）** 与 **attribution 切片（前 500）**
  - Mapper 新增查询（显式列名，禁 `select *`）：`SELECT knowledge_point_id, name, grade_range FROM knowledge_point WHERE tenant_id=? AND subject=? AND status='active'`
  - 年级过滤：`GradeMatcher.matchesRange(studentGrade, gradeRange)`（空 `grade_range` 保留；学生年级为空不过滤）
  - 排序：① `GradeMatcher.matchesExactGrade` 优先 ② `student_knowledge_state` 有记录优先（1 条 `IN` 查询，禁 N+1）③ `knowledgePointId` 升序
  - 上限：`exam.parse.kp-candidates.max=150`（parse）、`exam.parse.kp-candidates.attribution-max=500`（兜底）
  - **截断率（v4b）**：`truncated = max(0, totalAfterGradeFilter - 150)`、`rate = truncated / totalAfterGradeFilter`；**超限必 `log.warn` + `agent_degradation_log`**（`agent_type=exam_parse`、`action=truncate_kp_candidates`、`input_summary` 记 subjectId/grade/total/injected/rate）
  - `totalAfterGradeFilter > 1000` → 记 `agent_degradation_log(action=kp_unreachable)`（明确边界）
  - 缓存：`ConcurrentHashMap<(tenantId,subjectId,grade), CandidateSnapshot>` + TTL `exam.parse.kp-candidates.ttl-seconds=600`；不引入新依赖
- [ ] `PromptText`/`ExamKpCandidateService#renderForPrompt(snapshot)`：输出 `- <ID> | <名称>` 行（ID 原样、名称去换行），**空清单时输出"（本卷无候选知识点）"**
- [ ] 验收：`ExamKpCandidateServiceTest`（过滤/排序/双上限/截断率与 warn+降级日志/**attribution 切片含被 parse 截断的 kp**/缓存/显式列名）

### E2. Prompt 注入与记号规则

- [ ] 修改 `prompts/versions/exam_parse_v1.txt`（apply 时执行）：
  - 新增 `{knowledgePoints}` 占位符小节 + 使用说明（只能选清单内 ID、无法判断输出 `""`、每题最多 1 个）
  - 第 7 条替换为 v4 数学记号规则：允许 `x^2`/`a_n`；**禁止** `^{...}`/`_{...}` 与任何反斜杠命令；幂优先 `²/³` 或 `^2`，下标优先 `a(n)`/中文；禁 `$...$`/`**`/`#`/反引号
- [ ] `ExamParseAgent` 渲染 Prompt 时替换 `{knowledgePoints}`（八图共用同一份快照字符串，不重复拼接）
- [ ] 验收：`ExamParsePromptContractTest`（无残留占位符 / 不允许 `^{`、`_{`、`\frac` 出现在**规则示例的正例**中 / 空清单文案存在）

### E3. Schema 与校验

- [ ] `prompts/schemas/exam_parse_schema.json`：`questions[].required` 增 `knowledgePointId`；属性 `{"type":"string","maxLength":64}`（允许空串）
- [ ] `AgentOutputValidator.validateExamParse`：`knowledgePointId` 缺失 → 单题非法（丢弃该题）；空串合法；超长/含逗号多 ID → 置空 + warn
- [ ] `LlmTextSanitizer` 兜底增强：`^{x}`/`_{x}` → `^(x)`/`_(x)`；反斜杠命令转可读符号（`\frac`→`/`、`\sqrt`→`√`、`\vec`→"向量"）；**不再移除 `^` 本身**
- [ ] 验收：`AgentOutputValidatorTest`（缺失/空串/超长/多 ID 四例）；`LlmTextSanitizerTest`（花括号与反斜杠用例，`x^2` 保留）

### E4. 白名单与兜底链路（attribution 500 + 分片 + 可达性）

- [ ] `ExamParseService`：直出 kp 在白名单内 → `attribution_status='completed'` 且**不进兜底批**；空/被置空 → 进兜底批（≤40 题/批、批间串行）
- [ ] **兜底候选 = attribution 切片（≤500/片）**：`>500` 时按 `knowledgePointId` 升序**分片**，未决题对每片各调一次（仅兜底路径）；**被 parse 150 截断的 kp 必须出现在兜底切片**（可达性单测断言）
- [ ] `AttributionAgent` 新增重载 `attribute(ocrResults, List<KpCandidate> candidates, tenantId)`；`AttributionAgentImpl` 用传入清单（不再自建全量）；原方法内部自建候选后委托（行为不变）
- [ ] `totalAfterGradeFilter > 1000` → 记 `kp_unreachable` + 该批题置 `pending_manual_review`（不静默丢题）
- [ ] 兜底失败/`confidence=low`/ID 不在清单 → `knowledge_point_id=NULL` + `attribution_status='pending_manual_review'`
- [ ] 统计：`directKpCount / fallbackKpCount / unresolvedKpCount / unjudgedCount` → 日志 + 写入 `exam_analysis_result.questions.stats`（零 DDL，JSON；v4b 把 `kpStats` 统一为 `stats`）
- [ ] 验收：`ExamParseServiceTest`（直出不进兜底、空 kp 进兜底、四档统计落 JSON、>500 分片各调一次）；`AttributionAgentImplTest`（只收空 kp 题、用传入清单、low→pending）

### E5. E 组测试

- [ ] `ExamKpCandidateServiceTest`、`ExamParsePromptContractTest`、`AttributionAgentImplTest`（新）；`ExamParseAgentTest`/`ExamParseServiceTest`/`AgentOutputValidatorTest`/`ExamPaperServiceTest`（结算用例）/`LlmTextSanitizerTest`（扩展）
- [ ] 验收：`mvn test` 全绿；`ExamPaperServiceTest` 断言 `pending_manual_review` 题与 `is_correct=NULL` 题都不入错题、不产生掌握度事件、不计入 `judgedCount`

---

## Backend C — 信号自动调整 / 历史查询 / 删除手工录入

### C1. 试卷结算后信号检测 + 自动调整

- [ ] `service/PlanAdjustmentService` 新增显式重载 `triggerAdjustment(Long goalId, Long studentId, String tenantId)`；原方法委托（Controller 路径不变）
- [ ] 新增 `service/ExamSignalAdjustService` + `impl`（design §8）：
  - 定位 active goal（student+subject+日期覆盖今天）→ **12h 去重查 `plan_adjustment_log.action='confirmed'`**（B2；不查 'applied'）→ `detectSignals` → `triggerAdjustment` → `applyDiff`
  - `PLAN_ADJUST_CONFLICT` / `AdjustmentException` 分别跳过，全程 try/catch 不影响试卷状态
  - 配置项 `exam.adjust.dedup-hours:12`
- [ ] Worker 在 TX-B 提交后用 `aiExecutor` 异步调用；不设置 `StudentContext`
- [ ] 验收：`ExamSignalAdjustServiceTest` 五条路径（无目标/无信号/**按 confirmed 去重命中（mock 查询参数断言）**/冲突/命中应用）

### C2. 历史试卷查询接口

- [ ] `mapper/ExamPaperMapper` 新增：
  - `selectPageWithStats(studentId, tenantId, offset, size)`（design §10.1 的 1 条分页 SQL，含 subject JOIN + 派生表聚合）
  - `countByStudent(studentId, tenantId)`
  - `selectDetailWithSubject(paperId, studentId, tenantId)`
- [ ] `mapper/ExamQuestionMapper.selectByPaperOrderByIndex(paperId, tenantId)`（显式列名）
- [ ] `mapper/KnowledgePointMapper` 批量取名（`IN` + `tenant_id`，若已有可复用）
- [ ] `ExamPaperServiceImpl.getPapers(page, size)` / `getPaperDetail(paperId)`：clamp（page≥1、size 1–50）、越权 `1136`、题数/正确数/**已判定数**聚合、codec 解码、kp 批量映射、**`userScore/totalScore`（1 题 1 分制，B5）**
- [ ] `vo`：`ExamPaperListItemVO`（替换 `ExamResultListVO`，含 `judgedCount/userScore/totalScore`）、`ExamPaperDetailVO`（替换 `ExamResultDetailVO`）、`ExamQuestionItemVO`（替换 `ExamQuestionDetailVO`，含 `score/fullScore`）
- [ ] Controller：暴露 `GET /papers`、`GET /papers/{paperId}`、**`GET /tasks/{taskId}`（含 `paperId` 与归属校验，B4）**；删除 `GET /results`、`GET /result/{resultId}`、**旧 `GET /task/{taskId}`（S7 下线）**
- [ ] 验收：`ExamPaperControllerTest`（size clamp、越权、删除/下线路径不可用）+ SQL 条数断言（2 / 3）

### C3. 删除手工录入（拍板 #4）+ 既有缺陷顺带修复

- [ ] 删除 `POST /api/student/exam/result/{resultId}/grade`、`ExamPaperService.submitExamGrade`、`ExamPaperServiceImpl.submitExamGrade`、`updateMasteryFromExam(Long, List<ExamQuestion>)`、`recordWrongAnswers(Long, List<ExamQuestion>)` 旧私有方法
- [ ] 删除 `dto/ExamGradeRequest`、`dto/ExamGradeItemVO`（确认全仓库无引用，含测试）
- [ ] `exam_paper.grade_status` 语义更新为"系统判分完成"（`graded` 由 TX-B 写入）；**`total_score` 写 `judgedCount`**（与 `exam_analysis_result.total_score` 同口径，B5 + t15 一致性）
- [ ] **S10 顺带修复**：`ErrorQuestionMapper.selectCriticalErrors` 加 `student_id` + `question_id > 0`，签名加 `studentId`，`PlanAdjustmentServiceImpl` 传入 `goal.studentId`
- [ ] **顺带修复**：`LearningRecordServiceImpl#buildExamDetail` 的 `getMasteryChangesBySource(id, "EXAM")` 改为 `ChangeType.EXAM.getCode()`（小写），否则学习记录详情掌握度恒空（design §7.6）
- [ ] `ExamPaperServiceImpl` 中 `import ExamGradeRequest` 等清理；`ErrorQuestionSourceType.EXAM` 保留
- [ ] 验收：`grep -r "submitExamGrade|ExamGradeRequest|ExamGradeItemVO"` 无结果；单测删除对应用例；S10 与大小写两处有新增断言

### C4. SQL / 执行计划验证脚本

- [ ] 新增 `docs/ops/exam_paper_explain.sql`（只读）：`EXPLAIN` 列表 count/分页、详情 paper/questions、kp 批量、掌握度 state 批量、错题本新 JOIN、**`selectCriticalErrors` 新版 SQL**、**学习记录 `exam_analysis_result` 按 `idx_student` + `exam_name` 查重**、**`question_bank` 按 kp+difficulty 分组（D_norm 历史均值）**；附数据量统计与预期 `key/Extra` 注释
- [ ] 验收：脚本可在只读账号执行；输出与 design §11.6 预期表一致

### C5. C 组测试

- [ ] `ExamSignalAdjustServiceTest`、`ExamPaperControllerTest`（新）；`LearningRecordServiceTest`（+ 大小写修复断言）；`ErrorQuestionServiceTest`（+ `selectCriticalErrors` 双条件断言）
- [ ] 验收：`mvn test` 全绿；`mvn compile` 无 unused import 警告级错误

---

## Frontend — 学生端小程序

### F1. 接口层（约定 #18：并入已有 `api/exam.ts`，不新建模块）

- [ ] 重写 `student-app/src/api/exam.ts`：
  - `createPaper({subjectId, examDate, paperType, imageCount})`
  - `uploadPaperImage(paperId, filePath)` → `uploadWithAuth('/student/exam/papers/' + paperId + '/images', filePath, 'file')`（**S5：multipart 字段名必须是 `file`，`utils/upload.ts` 默认是 `files`，必须显式覆盖**）
  - `submitPaper(paperId)`
  - `retryPaper(paperId)`
  - `getPaperTask(taskId)`
  - `getPapers({page, size})`
  - `getPaperDetail(paperId)`
  - 删除过期的 `getExamAnalysis('/analysis/{id}')`、`uploadExamImage` 单图版、`EXAM_UPLOAD_PATH`
- [ ] 验收：`grep "analysis/"` 无残留；类型/字段与 C2 的 VO 对齐；抓包确认 multipart 字段名是 `file`

### F2. 历史试卷列表页

- [ ] 新增 `student-app/src/pages/exam-list/ExamList.vue`
  - 分页 `size=10`、`onShow` 刷新、下拉/触底加载（或"加载更多"按钮，二选一固定）
  - 列表项：科目 / 考试日期 / **类型中文（`midterm→期中考试` 等，S13 映射表与 `Profile.vue` picker 同源）** / 状态（解析中·已完成·失败）/ **得分（`userScore/totalScore`，1 题 1 分制）**
  - 点击 → `uni.navigateTo('/pages/exam-detail/ExamDetail?paperId=' + id)`（只传 `paperId`）
  - 空态 `EmptyState`、加载态 `LoadingState`、失败态可下拉重试
- [ ] `pages.json` 注册 `pages/exam-list/ExamList`
- [ ] 验收：`npm run build:mp-weixin` 通过；列表可从「我的」进入并跳详情；类型不显示英文 code

### F3. 试卷详情页（与作业答题区一致）

- [ ] 新增 `student-app/src/pages/exam-detail/ExamDetail.vue`
  - `onLoad` 只读 `options.paperId`（非法 → `showError` + `navigateBack`），数据全由 `getPaperDetail(paperId)` 拉取（**禁止 URL 传 items/subjectName**）
  - 顶部导航：科目名 + `currentIndex+1 / total`；`pending|processing` 每 2s 轮询详情（≤90 次），`onUnload` 清定时器
  - **防重复入栈（S8）**：`navigateTo` 前查 `getCurrentPages()`，栈顶已是同 `paperId` 的 `ExamDetail` 则 `navigateBack()`，防连点压多层轮询
  - 题目区复用 `HomeworkDetail.vue` 结构：标签（题型/难度）、`stem`、`choices`（只读高亮"我的答案"与"正确答案"）、`studentAnswer` 只读框、`correctAnswer`、`explanation`、知识点名、**单题得分（`score/fullScore`，1 题 1 分制）**
  - 底部仅"上一题 / 下一题"（禁用边界），无提交按钮
  - `isCorrect === null` → 展示"待人工判定"；`attributionStatus='pending_manual_review'` → "待人工归因"；接口返回 `imageWarning` → 顶部提示"部分图片未识别，可重试"
- [ ] `pages.json` 注册 `pages/exam-detail/ExamDetail`
- [ ] 验收：8 题可逐题翻看、重进页面数据一致、连点两次不会压出两层详情、页面栈返回正常

### F4. 「我的」页上传改造

- [ ] `student-app/src/pages/profile/Profile.vue`：
  - 「试卷上传」区：`uni.chooseImage({count: 8})` → 逐张按 **「<800KB 不压缩、否则 `compressImage` quality 85」** 处理（真图实测 quality 60 会把选项压糊导致读不出，t20 已改） → **预检（S2）**：`uni.getFileInfo` 拿每张 `size`，单张 >9.5MB 拒绝、合计 >30MB 拒绝 → `createPaper` → **2 并发**逐张 `uploadPaperImage` → 全部成功后 `submitPaper(paperId)`
  - 预检/上传提示文案固定：**"单张≤9.5MB、合计≤30MB（建议每张压到 2MB 内）"**
  - 上传进度展示（已上传 x/N）、单张失败可"重试该张"（不计入已上传数，用户可选择"少传几张也解析"直接 `submit`）、>8 张前端即拦截并提示（后端 1182 兜底）
  - 全部成功后 `uni.navigateTo('/pages/exam-detail/ExamDetail?paperId=' + paperId)`；若 `status=failed` 提供"重新解析"按钮（`retryPaper`，超过 2 次隐藏并提示）
  - 新增菜单项「历史试卷」→ `uni.navigateTo('/pages/exam-list/ExamList')`
- [ ] 验收：一次选 8 张可完成闭环；第 9 张在前端被拦或后端返回 1182；单张 >9.5MB / 合计 >30MB 在前端即被拦并展示固定文案；上传失败可单张重试

### F5. 错题详情兜底渲染（S9）

- [ ] `student-app/src/pages/error-detail/ErrorDetail.vue`：`questionType` 缺省时降级为纯文本渲染（不显示空白选项区）；`questionChoices` 为空/null 时不渲染选项块；`correctAnswer`/`questionAnalysis` 为空串时显示"暂无"
- [ ] `redoSupported` 字段 v3 起恒为 `true`：**删除**"来自试卷，暂不支持原题重做"的展示分支（保留字段兼容）；若误收到 `false` 仍不报错
- [ ] 验收：试卷来源错题点进详情，题面/题型/答案区不空白；构建通过

### F7. 小程序侧 v3 校验（试卷错题进入作业后无需新页面）

- [ ] 确认 `HomeworkDetail.vue` 对 `questionSource="exam"` 的题**无需改动**即可作答（`questionId` 为负数的 JSON 提交、`questionSource` 透传、`choices` 文本渲染）
- [ ] 若 `taskType=redo` 的"错题重做"标签文案对 exam 来源显示异常，仅做文案兜底（不改数据结构）
- [ ] 验收：联调时 exam redo 题可提交、批改后展示"我的答案/正确答案/解析"，返回列表正确

### F6. 前端构建与联调前置

- [ ] `cd student-app && npm run build:mp-weixin` 通过（约定 #20：联调前提醒清微信开发者工具缓存）
- [ ] 验收：产物含 `pages/exam-list`、`pages/exam-detail` 与新的 `api/exam` 逻辑

---

## Verify — 实现与工件对齐（`/opsx:verify`，非代码评审）

- [x] V0 视觉能力探针（S6）：**已通过**（captain 2026-11-11 实测 `qwen3.8-flash` 多模态端点，400×140 PNG `H7Q2` → 返回 `H7Q2`，HTTP 200，`request_id=657f1d71-8262-9101-a04e-612d155f1066`）；verify 报告只需引用该证据，不再作为阻断项
- [ ] V1 逐条对照 `proposal.md` §3 范围 / §5 决策 / `design.md` §1–§18，产出 `openspec/changes/2026-11-11_exam_paper_upload_llm/verify-report.md`：每条"已实现 / 未实现 / 偏离"，偏离必须附文件与行号
- [ ] V2 执行 `docs/ops/exam_paper_explain.sql`，把查询的 `key/Extra/rows` 回填 verify 报告，与 design §11.6 预期表逐行比对
- [ ] V3 端到端联调（真机/开发者工具 + 本地后端）：
  - [ ] 8 张上传 → 解析完成（60–120s）→ 详情逐题正确（含 `userScore/totalScore`）
  - [ ] 第 9 张 → 1182；单张 >9.5MB → 1184；错类型 → 1139；`paperType=abc` → 1191
  - [ ] 答错题出现在「错题本」且有题面（详情不空白）；答对/答错都能在掌握度看板看到 `change_type=exam` 的日志（`delta` 有正有负）；D_norm 偏离 >0.2 时按 0.5 计算
  - [ ] 构造 SIG-2（某 kp 单次下降 > 0.15）→ `plan_adjustment_log` 出现 **`action='confirmed'`** 记录；同一目标 **12h 内再次命中信号不重复调整**（注意：同学生同科目同日已被 `1131` 拦截，无法用重复上传验证去重——改用"次日再传"或手动触发 `AutoAdjustmentTask` 验证）
  - [ ] 关闭 LLM（改错 key）→ 任务 ≤180s 内 `failed`，不挂死；`failed` 卷 `retry` 时若库中已有题目，日志可见**未再调用视觉解析**
  - [ ] 「学习记录」的"考试"筛选出现本次试卷（标题/得分/日期），详情掌握度变更非空；`exam_paper.total_score` 与 `exam_analysis_result.total_score` 都等于 `judgedCount`
  - [ ] **N1 / 配额（v3 clamp 方案 A）**：某知识点在同一试卷错 5+ 题（负 ID、active）后，次日 `generateDailyHomework` 正常生成（不报"配额计算失败"）；该 kp 的 redo 列表出现 `questionSource="exam"` 的题且当日该 kp 错题数 ≤4；未被抽中的错题次日仍在到期列表（顺延），`daily_plan` 未被判未完成
  - [ ] **v3 试卷错题进 redo**：次日作业出现"考试原题"（题干/选项/我的答案/正确答案/解析与试卷详情一致），提交后按同一 `AnswerGradingService` 批改；答对后错题本 stage 1→2，答错回 1
  - [ ] **v3 试卷错题驱动举一反三**：某 kp 只有试卷错题、题库无题时仍生成 1 道举一反三新题（源题来自 `exam_question` 适配）；源题缺正确答案时该 kp 跳过并留降级日志
  - [ ] **v4 kp 直出统计**：`exam_analysis_result.questions.stats` 出现 `kpDirect/kpFallback/kpUnresolved`（及 `judged/unjudged/kpTotal/kpTruncated`）四档以上计数；抽查详情页 kp 名称**全部在注入清单内**（无幻觉 ID 落库）；截断时 `agent_degradation_log` 有 `truncate_kp_candidates`
  - [ ] **v4 准确率抽样**：取 2 份真实试卷（≥20 题）人工比对 kp：直出准确率 ≥70%、可归因率 ≥90%；未达标记录调参结论（max/排序/Prompt）到 verify 报告
  - [ ] **v4 记号验收**：详情/作业页题干、选项、答案、解析中不出现 `^{`/`_{`/`\frac`/`\sqrt`；`x^2`/`x²`/`a_n`/`a(n)` 正常显示
  - [ ] **v4b 判分三态联调**：构造"选择题缺标准答案"的试卷 → 详情显示"标准答案缺失，待人工判定"，该题不进错题本、掌握度看板无该题事件；`total_score` 只统计已判定题；空白但有答案的题仍判错
  - [ ] **v4b 兜底可达性联调**：构造 `totalAfterGradeFilter > 150 且 ≤500` 的科目 → 被 parse 截断的 kp 的题能被兜底归因命中（`fallback` 计数 >0），日志有 `truncate_kp_candidates` 与截断率
  - [ ] 用他人 `taskId` 调 `/tasks/{id}` → 1135；列表/详情从「我的」进入，返回栈正确，连点不压多层
- [ ] V4 构建与测试：`mvn compile`、`mvn test`（记录用例总数）、`npm run build:mp-weixin`
- [ ] V5 性能观测：解析日志中的**全局并发峰值（≤3）**、单卷耗时、`EXPLAIN` 结果、单次上传请求体大小；把实测值与 design §11 阈值比对并记录偏差
- [ ] V6 未覆盖项说明：真实微信网络下的 8 图上传稳定性、模型对手写体准确率（写明"依赖模型、已用降级路径兜底"）
- [ ] 验收：`verify-report.md` 无 BLOCKER 级偏离；有偏离则回流到对应 Backend/Frontend 任务修复后重跑

---

## Review — 独立评审（实施者不得自评）

- [ ] R1 `/prepare-review`：生成评审摘要（本次变更内容 + 跑过的测试 + 未测场景与理由）
- [ ] R2 `/java-architecture-review`：分层（Controller/Service/Mapper）、事务边界（约定 #9/#10）、线程池（约定 #22）、**全局 LLM 闸门（S1）**、异常分层（约定 #14）
- [ ] R3 `/sql-risk-review`：批量写 ≤100/批、禁 `select *`、`tenant_id` 覆盖、索引与 EXPLAIN、`question_id > 0` 防御（含 `selectCriticalErrors`，S10）、负 ID 约定 #26、**学习记录查重 SQL 无全表扫描风险**
- [ ] R4 独立 `@reviewer`（只读）：按 `REVIEW.md` 检查代码质量、性能红线（§11）、隐性约定（#12/#14/#18/#19/#22/#24/#25）、**判分 null 契约（S3）与 9.5MB/multipart 映射（S4/S5）**
- [ ] R5 评审结论回填 change 目录（`review-summary.md`），findings 逐条建修复任务（归入对应 Backend/Frontend 组）并重跑 V1–V6
- [ ] 验收：无 BLOCKER/HIGH 未关闭项；评审人与实施人不同

---

## Archive — 归档与文档回写

- [ ] ARCH1 `docs/product/index.md` 回写（需人工审批，proposal §4 已登记）：
  - 规则 #3：**试卷来源错题与题库错题一视同仁**——进入艾宾浩斯到期队列，可被每日作业抽为原题重做、也可作为举一反三源题；题面来自 `exam_question`；每 kp 每日 redo ≤3、按 kp 合计 clamp 到 4（超出顺延，不记未完成）
  - 规则 #4：**v4 口径：知识点归因前置到视觉解析（直出），`AttributionAgent` 降级为兜底（只补空 kp 题）**；切题改为视觉模型解析（≤8 张、单图 ≤9.5MB、总量 ≤30MB）、全部题目落库可回看、手工录入取消、`paperType` 五值
  - 规则 #5：新增"考试结算后即时检测信号并自动应用"入口（与 01:30 定时任务并存，**12h 信号调整去重按 `action='confirmed'`**）
  - 规则 #9：试卷题目与判分结果纳入学习记录（`change_type=exam` 日志、错题来源 `EXAM`、**写 `exam_analysis_result` 且得分 1 题 1 分制**）
- [ ] ARCH2 `docs/architecture/implicit-contracts.md` 新增 **约定 #26**：试卷错题在 `error_question` 使用负 `question_id`（`-exam_question.id`），题库/生成题查询必须 `question_id > 0`；题面走 `exam_question.question_content` 分段文本；**v3 扩展**：`daily_plan` ref 与 `homework_submission_detail.question_id` 对 `questionSource="exam"` 同用负值
- [ ] ARCH2b `docs/architecture/implicit-contracts.md` **约定 #24 措辞修订（v4）**：改为"允许 `x^2`/`a_n` 纯文本；**禁止** `^{...}`/`_{...}` 花括号与任何反斜杠命令；幂优先 `²/³` 或 `^2`，下标优先 `a(n)`/中文"，并注明修订原因（旧措辞易被误读为全禁 `^`）；同步 `LlmTextSanitizer` 兜底行为说明
- [ ] ARCH3 `openspec/specs/` 同步：
  - `agent-contracts.md`：新增视觉解析契约（Prompt 版本 `exam_parse_v1`、Schema、重试语义、与归因重试独立）
  - `learning-loop.md`：试卷上传闭环（上传 → 解析 → 判分 → 掌握度/错题 → 信号调整 → 历史回看）
  - `mastery-and-records.md`：考试掌握度更新（答对/答错都更新、W=1.0、日志字段）与错题来源扩展
- [ ] ARCH4 归档：`git mv`/移动 `openspec/changes/2026-11-11_exam_paper_upload_llm` → `openspec/changes/archive/`；确认 `archive/` 只读约定不被破坏
- [ ] ARCH5 登记后续 change（design §17：OCR 清理、真实卷面分值、题面结构化落库后让试卷错题参与重做、手动归因接口、assessment 大小写缺陷）到下次迭代规划
- [ ] 验收：`docs/` 与实现一致；`openspec/specs/*` 反映新行为；归档目录可读且无进行中残留
