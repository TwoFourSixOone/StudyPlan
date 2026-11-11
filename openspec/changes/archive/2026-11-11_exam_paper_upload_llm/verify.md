# Verify：试卷上传 → LLM 视觉解析 → 错题/掌握度 → 自动调整 → 历史回看

> **Change**：`2026-11-11_exam_paper_upload_llm`（v4b，t22 绑定：design 118,828B / proposal 28,835B / tasks 49,570B）
> **验证人**：verifier（t4 round-1 / t26 round-2）
> **快照时间**：2026-11-11 17:13（+08:00）
> **Round-2 结论（t26）**：**PASS（1 条中等级工具脚本 finding，不阻断应用功能）**。B1/M1/L1 已闭环；真实 4 图上传成功（19 题、16 判定/3 不可判定、11 条掌握度日志、SIG-4 自动调整）；M2 的 `docs/ops/exam_paper_explain.sql` 仍有 1 处无效列（#13），详见 §13。
> **Round-1 结论（t4）**：**FAIL（1 个 BLOCKER：建会话 JSON body 契约不一致）**；修复与复验见 §13。

## 0. 快照与边界

- 本报告基于 **2026-11-11 17:01 工作区** 的字节；t23（backend 修复：`@RequestBody`、Worker 节流、explain.sql、StudentContext）与 t24（frontend 字段对齐）在验证期间**正在并行修改**，部分行号在 round-2 会偏移，但文件/方法名不变。
- 未执行 `mvn clean`、未改业务代码/工件、未做 git commit。
- 验证证据目录：`target/verify/`（临时，`mvn clean` 会删除；需要时按下文命令复现）。

## 1. 命令与退出码

| # | 命令 | 退出码 | 结果 |
|---|------|--------|------|
| 1 | `mvn -q compile` | 0 | 无输出，编译通过 |
| 2 | `mvn test` | 0（日志 `BUILD SUCCESS`） | **781 用例 / 0 失败 / 0 错误 / 5 跳过**（45.0s；日志 `target/verify/mvn-test.log`；surefire 105 个测试类） |
| 3 | `npm --prefix student-app run build:mp-weixin` | 0 | `DONE Build complete.`；产物含 `pages/exam-upload`、`pages/exam-list`、`pages/exam-detail`（日志 `target/verify/build-mp-weixin-final.log`） |
| 4 | `docker exec mysql mysql ... -D studyplan`（结构/基线/EXPLAIN） | 0 | 见 §5/§7 |
| 5 | `javac/java` codec 往返检查（`target/verify/CodecCheck.java`） | 0 | `blank/unreadable` 编码段可还原；`answered/null` 无该段；旧数据无标记兼容；选项/答案/题干保持 |

**用例总数基线差**：本 change 前基线 619；t7 674 → t8 715 → t17 751 → t19 781，**净增 162 个用例**（各任务汇报可追溯）。

## 2. 验收项结果

| 验收项 | 结果 | 证据/说明 |
|--------|------|-----------|
| `mvn test` 与 `mvn -q compile` 通过并记录总数 | **PASS** | 781/0/0/5，BUILD SUCCESS；compile exit 0 |
| `npm --prefix student-app run build:mp-weixin` 通过 | **PASS** | exit 0，`DONE Build complete.` |
| 工件每条需求都有文件:行号级证据或明确标注未验证 | **FAIL** | 绝大多数有证据；但 `POST /papers` 契约未实现（BLOCKER B1），且 4 项偏离/未验证见 §8 |
| Docker MySQL 冒烟查询已执行并记录结果 | **PASS** | 见 §5 |
| 历史试卷：全部题目落库、列表/详情隔离与归属校验有证据 | **PASS（代码+单测；无真实数据）** | 见 §6；真实上传未做，DB 中 `exam_paper=0` |
| 前端只传 paperId、页面栈策略有代码证据 | **PASS** | 见 §6.2 |
| 性能证据：EXPLAIN、无 select *、固定查询条数 | **PASS（附偏离）** | 见 §7；主表/详情命中；列表聚合派生表在 1 行数据下为 ALL，需真实数据复测 |
| 性能证据：批量写、并发池、base64 释放点 | **PASS** | 见 §7.3 |
| `verify.md` 含阻塞/建议分级与未验证清单 | **PASS** | 本文件 §8/§9 |

## 3. 需求-实现对照（A/B/D/E/C 组）

> 标记：✅ 已实现且有证据；⚠ 偏离/需关注；⛔ 未实现/阻断；◐ 仅代码或单测证据（未做真实链路）。

### Backend A — 视觉解析通道

| 项 | 状态 | 证据 |
|----|------|------|
| A0 视觉探针（S6） | ✅ | design §3.1 L176 记录 captain 实测（request_id 657f1d71…）；本报告不重复探针 |
| A1 Prompt/Schema（v4 占位符 + kp） | ✅ | `src/main/resources/prompts/versions/exam_parse_v1.txt:3-21`；`schemas/exam_parse_schema.json:14-41`（`knowledgePointId` required，≤64）；`ExamParsePromptContractTest` |
| A2 `callVision`（ImageBlock/Base64Source） | ✅ | `client/AgentScopeClient.java:148-164`（全局闸门包裹）、`:204-228`（TextBlock+ImageBlock、逐图 base64、`base64=null`）；`AgentScopeClientVisionTest` |
| A3 线程池/全局闸门/超时 | ⚠ | `config/ThreadPoolConfig.java:103-117`（core=max=3、queue=16、CallerRunsPolicy）、`:127-141`（注册关闭管理器）；`LlmConcurrencyLimiter.java:36-57`；`AgentScopeClient.java:125-164`；`ExamParseServiceImpl.java:115-200`（deadline 180s / 单图 60s / CompletionService）——**Worker 拉取节流缺失**，见 M1 |
| A4 题面 codec | ✅ | `common/util/ExamQuestionTextCodec.java:64-133`（`【题干】/【选项】/【正确答案】/【解析】/【作答状态】`）；`CodecCheck` 实测（answered 无段、blank/unreadable 可还原、旧数据兼容） |
| A5 解析编排 | ✅ | `service/impl/ExamParseServiceImpl.java:115-200`（逐图任务、deadline）、`:253-355`（题号重排、相似度 0.9 去重、200 截断）、`:370-438`（直出/兜底/未决三档）；`ExamParseServiceTest` 14 个用例 |
| A6 图片读取 | ✅ | `service/FileStorageService.java` + `LocalFileStorageServiceImpl.readBytes`（basePath 校验）；`FileStorageServiceTest` |
| A7 A 组测试 | ✅ | `ExamParseAgentImplTest`、`ExamParseServiceTest`、`ExamQuestionTextCodecTest`、`AgentOutputValidatorTest`、`FileStorageServiceTest`（781 全绿） |

### Backend B — 上传/任务/判分/结算

| 项 | 状态 | 证据 |
|----|------|------|
| B1 错误码 1182–1191 | ✅ | `common/exception/ErrorCodes.java:152-192`（1182/1183/1184 9.5MB/1185/1186/1187/1188/1189/1190/1191）；`GlobalExceptionHandler` 映射 MaxUploadSizeExceededException（t8） |
| B2 上传接口/校验 | ⛔ | **BLOCKER B1：`ExamPaperController.java:50-54` 用 `@RequestParam`，前端 `api/exam.ts:193-195` 发 JSON body → 400**；≤8/9.5/30/1131/1191 的 Service 校验在 `ExamUploadValidator.java:33-116`、`ExamPaperServiceImpl.java:169-335`（本身正确） |
| B3 Worker 编排/幂等 | ◐ | `ExamPaperServiceImpl.java:370-432`（task CAS、paper 状态、已有题跳过解析）、`:440-492`（TX-A 批量插题）、`:501-613`（判分→TX-B）、`:785-807`（失败脱敏 500）；`ExamAnalysisWorker.java:48-76` 无节流（M1）；`ExamPaperServiceTest` 覆盖 CAS/跳过解析/TX-B 失败 |
| B4 判分三态 | ✅ | `AnswerGradingServiceImpl.java:58-85`、`:88-181`（unreadable→null；双空→null；空答+有答案→false；有答+缺答案→null 且不调 AI）；`AnswerGradingServiceTest` 15 个用例；作业侧 null 处理见 `HomeworkServiceImpl.java:857/940-990`、`MasteryServiceImpl.java:131-138` |
| B5 批量写/得分 | ✅ | `ExamQuestionMapper.java:31-43`（batchInsert ≤100）、`:79-96`（CASE WHEN batchUpdate）；`ExamPaperServiceImpl.java:526-548`（NULL 不计 judgedCount，score/full_score 留 NULL）、`:567-570`；`ExamPaperServiceTest.should_parseJudgeAndSettle` |
| B6 错题批量入队/负 ID | ✅ | `ErrorQuestionServiceImpl.java:133-192`（先查后插/更新、DuplicateKey 回退）；`ErrorQuestionMapper.java:341-373`（batchInsert/batchResetExisting）；`ExamPaperServiceImpl.java:572-583`（`-question.id`）；`ErrorQuestionServiceTest` |
| B7 掌握度批量 | ✅ | `MasteryServiceImpl.java:294-420`（批量 state/创建/UPDATE/日志/syncPauseStatus）；`:425-431`（非法难度→0.5）、`:438-...`（1 条历史均值）、`:368`（validateDNorm）；`MasteryServiceTest` 的 D_norm 四条 + 批量用例 |
| B8 B 组测试 | ✅ | `mvn test` 781 绿；`ExamPaperServiceTest`、`AnswerGradingServiceTest`、`ErrorQuestionServiceTest`、`MasteryServiceTest`、`HomeworkServiceTest` |
| B9 学习记录 | ✅ | `ExamPaperServiceImpl.java:655-702`（exam_name 查重、字段、1 题 1 分制、status=completed/analyzed_at；`payload.put("paperId")`）；`:663-667` 查重；`ExamPaperServiceTest` 断言 totalScore=3/userScore=1 |

### Backend D — 试卷错题回流作业（v3）

| 项 | 状态 | 证据 |
|----|------|------|
| D1 引用形态/常量 | ✅ | `common/util/ExamQuestionRefs.java`（toRefId/examIdOf）；`ExamRedoIntegrationTest` |
| D2 到期统计/redo 组装/去重 | ✅ | `HomeworkServiceImpl.java:398-430`（clamp）、`:599`（去重）、`:656-690`（examMap）、`:1131-1210`（题面 exam 分支）；`ExamRedoIntegrationTest` ①② |
| D3 题面 5 处 exam 分支 | ✅ | `HomeworkServiceImpl.java:1131-1210`（buildHomeworkVO）、`:1292-1350`（answer/content）、`:1439-1453`（formatType）、`:1266-1274`（kp）；`ExamRedoIntegrationTest` |
| D4 提交/判分/stage | ✅ | `HomeworkServiceImpl.java:940-997`（null 跳过；redo 负 ID 直接 advance/reset）、`:1266-1274`（submitAnswer kp）；`ExamRedoIntegrationTest` ④ |
| D5 KP_MAX clamp | ✅ | `HomeworkServiceImpl.java:409-422`（`Math.min(raw, KP_MAX)`，按 kp 合计，warn）；`HomeworkServiceTest.should_notFailGeneration_when_kpHasFiveExamWrongQuestions` / `should_clampKpErrorCountToKpMax_byTotal` |
| D6 举一反三 exam 兜底 | ✅ | `HomeworkServiceImpl.java:1503-1527`（selectDueExamSourceQuestion → 缺答案降级日志 → 适配临时 QuestionBank）；`ErrorQuestionMapper.java:276-289`；`ExamQuestionSourceAdapter`；`ExamRedoIntegrationTest` ② |
| D7 不调 analysis_gen | ✅ | `HomeworkServiceImpl.java:878-880/891-916`（exam redo 取 codec explanation）；`ExamRedoIntegrationTest` 断言未调用 |
| D8 D 组测试 | ✅ | `ExamRedoIntegrationTest`（7）、`ExamQuestionSourceAdapterTest`（4）、`HomeworkServiceTest`（+2）、`ErrorQuestionServiceTest`（+2） |

### Backend E — 知识点直出 + 记号（v4/v4b）

| 项 | 状态 | 证据 |
|----|------|------|
| E1 kp 候选/缓存/双上限 | ✅ | `ExamKpCandidateServiceImpl.java:78-88`（缓存 key 含 studentId，TTL）、`:94-174`（active+年级过滤→精确年级/有 state/kpId 排序→parse150/attribution500；截断 warn+degradation log；>1000 kp_unreachable）；`ExamKpCandidateServiceTest` 7 |
| E2 Prompt 注入/记号 | ✅ | `exam_parse_v1.txt:3-21` 含 `{knowledgePoints}` 与 v4 记号规则；`ExamParsePromptContractTest`；`LlmTextSanitizerMathNotationTest` |
| E3 Schema/白名单/清洗 | ✅ | `AgentOutputValidator.java`（knowledgePointId required/空串合法/超长非法）；`ExamParseServiceImpl.java:377-392`（白名单置空）；`ExamQuestionTextCodec`/`LlmTextSanitizer`；测试 |
| E4 兜底 500/分片/可达性 | ✅ | `ExamParseServiceImpl.java:370-456`（仅未决题进兜底、≤40/批、500/片分片、每片一次）；`ExamKpCandidateServiceTest.should_applyTieredLimits_andKeepTruncatedKpForAttribution` |
| E5 E 组测试 | ✅ | `ExamKpCandidateServiceTest`、`ExamParsePromptContractTest`、`AttributionAgentImplTest`、`LlmTextSanitizerMathNotationTest`；781 全绿 |
| 统计落库 | ◐ | `ExamPaperServiceImpl.java:681-686`（`payload.put("stats", stats)`）、`:742-761`（kpDirect/kpFallback/kpUnresolved/kpTotal/kpTruncated/judged/unjudged）；**单测未断言 stats 键，真实 DB 未落（见 §9）** |

### Backend C — 信号/历史/删除手工录入

| 项 | 状态 | 证据 |
|----|------|------|
| C1 结算后信号调整 | ✅ | `ExamSignalAdjustServiceImpl.java:66-135`（aiExecutor 异步、无 goal 短路、`countConfirmedSince` 12h、detectSignals、triggerAdjustment、applyDiff、全程 try/catch）；`PlanAdjustmentLogMapper.countConfirmedSince`；`ExamSignalAdjustServiceTest` 7 |
| C2 历史读接口 | ✅ | `ExamPaperServiceImpl.java:824-843`（2 SQL）、`:845-915`（3 SQL、题号排序、kp 批量、userScore/totalScore）、`:985-1010`（task 越权 1135）；`ExamPaperMapper.java:96-152`；`ExamQuestionMapper.java:59-66`；`ExamPaperReadServiceTest` 7 |
| C3 删除手工录入 + S10/大小写 | ✅ | `ExamPaperControllerTest.should_return404_when_legacyEndpointsRemoved`；`ErrorQuestionMapper.java:305-316`（student_id + question_id>0）；`LearningRecordServiceImpl` 小写 exam（t17 汇报）；`ExamPaperMapper` 无 `/results` 路由 |
| C4 EXPLAIN 脚本 | ⚠ | `docs/ops/exam_paper_explain.sql` 存在，但 **#8/#9 仍带 `question_id > 0`（L126-147），与 v3 反向；缺 #12/#13/selectCriticalErrors**（M2） |
| C5 C 组测试 | ✅ | `ExamPaperControllerTest`、`ExamSignalAdjustServiceTest`、`ExamPaperReadServiceTest`、`ErrorQuestionServiceTest` 扩展 |

## 4. 历史试卷专项（t4 第 3 项）

| 检查 | 结果 | 证据 |
|------|------|------|
| 全部题目落库（含答对/未作答/无法判定） | ✅ 代码+单测 | `ExamPaperServiceImpl.persistQuestions:440-492` 对所有 `outcome.questions` 插入，`is_correct` 统一 NULL；`ExamPaperServiceTest.should_parseJudgeAndSettle` 断言 4 题全插、题目顺序 1..4；`ExamQuestionMapper.batchInsert:31-43` |
| 数量与解析结果一致 | ✅ 代码+单测 | `persistQuestions` 以 `questions` 全量插入；`ExamPaperServiceTest` 断言 batchInsert 捕获 4 条；判分条数不一致时 `settle:517-524` 直接 failed、不写 is_correct |
| `GET /papers` 分页 + tenant/student 隔离 | ✅ 代码+单测 | `ExamPaperServiceImpl.getPapers:825-843`（clamp page≥1、size≤50；count+分页 2 条 SQL）；`ExamPaperMapper.java:96-135`（全部带 student_id+tenant_id）；`ExamPaperReadServiceTest.should_listPapers_withClampAndTwoQueries` / `should_passTenantId_toAllQueries` |
| `GET /papers/{id}` 全部题目且题号升序 | ✅ 代码+单测 | `getPaperDetail:845-915`（questions 排序、kp 批量、userScore/totalScore）；`ExamQuestionMapper.selectByPaperOrderByIndex:59-66`（ORDER BY question_index）；`ExamPaperReadServiceTest.should_returnDetail_withSortedQuestionsAndKpNames` |
| 归属校验（他人 paperId 报错） | ✅ 代码+单测 | `getPaperDetail:851-855`（selectDetailWithSubject 带 student_id+tenant_id → null → 1136）；`ExamPaperControllerTest.should_return1136_when_otherStudentPaper`；`requireOwnedPaper:356-366` |
| `/tasks/{id}` 越权 → 1135 | ✅ 代码+单测 | `getTaskProgress:985-998`（task→paper 校验） |
| `uploading/processing/failed` 的详情响应 | ✅ 代码 | `getPaperDetail` 不按 status 拒绝，返回 paper 头 + 已落库 questions + `remark` 作为 imageWarning；前端 `ExamDetail.vue:24-35` 分别提示解析中/失败/未提交 |
| 列表字段一致性 | ⚠ 修复中 | 后端 `ExamPaperListItemVO.questionCount`（VO:47）、Mapper:110；前端 `ExamList.vue:29` 已改读 `questionCount`（17:00 后 t24 修复），但 `api/exam.ts:163` 仍声明 `totalQuestionCount`（仅类型，运行不影响） |

## 5. Docker MySQL 冒烟

```
docker ps        -> mysql Up 9 days / redis Up 7 days
SHOW TABLES      -> exam_paper / exam_question / exam_analysis_task / exam_analysis_result /
                    error_question / student_knowledge_state / mastery_change_log ... 均存在
```

关键结构（`information_schema.COLUMNS`，2026-11-11 实测）：

- `exam_paper.image_urls` **NOT NULL**（JSON）；`status` 默认 pending、`grade_status` 默认 ungraded；`total_score decimal(5,2)`。
- `exam_question` 有 `question_content TEXT NOT NULL`、`student_answer TEXT`、`is_correct tinyint(1) NULL`、`score/full_score decimal(5,2) NULL`、`question_type/difficulty/attribution_status`；**无 choices/correct_answer/explanation 列**（零 DDL 成立）。
- `exam_analysis_task` 有 `ocr_retry_count/attribution_retry_count/error_message/started_at/completed_at`。
- `error_question.next_due_date DATE NOT NULL`；`uk_student_question(student_id,question_id,tenant_id)`。
- `student_knowledge_state` 有 `uk_student_kp(student_id,knowledge_point_id,tenant_id)`。
- `mastery_change_log.change_type/ source_id/delta/before/after` 齐全。

基线（student=9 / tenant=default，`target/verify/baseline_2026-11-11.txt`）：

| 指标 | 值 |
|------|----|
| exam_paper | 0 |
| exam_question | 0 |
| exam_analysis_task | 0 |
| exam_analysis_result | 0 |
| error_question | 11（全部 homework；无负 ID） |
| student_knowledge_state | 8（math cnm_h144-147 / english cne_h082-085） |
| mastery_change_log | 57 |
| learning_goal | 2（active：goal 6 math / goal 7 english） |
| daily_plan | 37 |

> 因 BLOCKER B1，**未做真实上传**；`exam_paper` 仍为 0，未伪造任何上传结果。

## 6. 前端专项

### 6.1 入口/列表/详情/上传

| 检查 | 证据 |
|------|------|
| 「我的」入口 | `student-app/src/pages/profile/Profile.vue:117-127`（试卷上传/历史试卷），`:452-469`（navigateTo） |
| 页面注册 | `student-app/src/pages.json:70-91`（exam-upload/exam-list/exam-detail） |
| 列表分页/onShow | `ExamList.vue:95`（size=10）、`:106-117`（onShow/触底/下拉）、`:129/152`（getPapers page） |
| 类型中文 | `ExamList.vue:24`（paperTypeName 优先，回退 paperTypeLabel）；`api/exam.ts:41-62` 映射 |
| 详情只读 paperId | `ExamDetail.vue:232-239`（`options?.paperId`，非数字退出）；`:247-256`（getPaperDetail） |
| 上一题/下一题 | `ExamDetail.vue:314-332`；`:155-159` 底部按钮；无提交按钮 |
| 轮询/清理 | `ExamDetail.vue:199-200`（2s/90 次）、`:242-245`（onUnload stopPolling）、`:293-311`（setTimeout 链 + stop） |
| 防重复入栈 | `ExamList.vue:171-183`（getCurrentPages + 同 paperId → navigateBack） |
| 上传 2 并发/预检 | `ExamUpload.vue:157-169`（MAX=8、9.5/30MB、阈值 800KB、quality 85、并发 2）、`:325-379`（sizeType original/压缩/双限）、`:496-500`（worker 并发） |
| multipart 字段 `file` | `api/exam.ts:203-205` → `uploadWithAuth(..., 'file')`；`utils/upload.ts:35-41` 默认 `files` 被显式覆盖 |
| 上传完成跳详情 | `ExamUpload.vue:609-612`（redirectTo）；失败重试 `:626-632` |

### 6.2 页面栈策略（t4 第 4 项）

- 进入详情：`navigateTo`，URL 只有 `paperId`（`ExamList.vue:182`；`ExamUpload.vue:611`）。
- 上传完成用 `redirectTo` 替换上传页，避免返回已提交页（`ExamUpload.vue:609-612`）。
- 详情返回：`navigateBack`，无上一页时 `redirectTo` 回列表（`ExamDetail.vue:338-340`）。
- 防连点/重复入栈：`getCurrentPages` + 栈顶同 paperId → `navigateBack`（`ExamList.vue:171-180`）。
- 定时器：`onUnload` 清理（`ExamDetail.vue:242-245`）。
- 注：t3 采用独立 `ExamUpload.vue` 而非 design §10.3 写的 `Profile.vue` 内嵌；t3 验收口径允许“上传页”，功能等价，记为轻微工件漂移（L2）。

## 7. 性能专项

### 7.1 EXPLAIN 实测（corrected script，student=9 / tenant=default；1 行级数据）

> 原始 `docs/ops/exam_paper_explain.sql` 执行时因占位符/内容问题部分段未回显；下表的 #8/#9 已按 v3 实际 SQL 重跑，#3 无 paper 行。原始结果见 `target/verify/explain-result.txt`；脚本待 t23 更新后 round-2 重跑。

| # | 查询 | 实际 key / type / Extra | 与 design §11.6 对比 |
|---|------|--------------------------|----------------------|
| 1 | 列表 count | `uk_student_exam` / ref / `Using index` | 预期 `idx_student_status`；索引前缀等价，可接受 |
| 2 | 列表分页 | p `uk_student_exam` ref；s `PRIMARY` eq_ref；派生 a `auto_key` ref；**q 派生 `ALL`（无 key）**；p filesort | q 预期 `idx_paper_index` ref；当前 1 行数据优化器选 ALL，需真实数据复测 |
| 3 | 详情 paper | `no matching row in const table`（无 paper 行；SQL 有 PK+student+tenant） | 需真实 paper 复测；SQL 形态正确 |
| 4 | 详情 questions | `idx_paper_index` ref / `Using index condition` | 命中；无 filesort ✅ |
| 5 | 详情 kp 批量 | `PRIMARY` range / `Using where` | 命中 ✅ |
| 6 | state 批量 | `uk_student_kp` range / `Using index condition` | 命中 ✅ |
| 7 | 错题本 JOIN exam_question | eq `ALL`；eqx `ALL`（hash join） | 预期 eq ref / eqx eq_ref；11 行数据 + `eqx.id=-eq.question_id` 表达式导致；需真实数据复测 |
| 8 | 到期 kp（v3 不过滤负 ID） | `idx_student_status_due` range / `Using index condition; Using temporary; Using filesort` | 命中，filesort 允许 ✅ |
| 9 | 配额分母（v3 不过滤） | `idx_student_kp_status` ref / `Using index` | 命中 ✅ |
| 10 | 学习记录查重 | `idx_student` ref / `Using where` | 命中 ✅ |
| 11 | D_norm 难度分组 | `idx_kp_difficulty` range / `Using index` | 优于预期（无 temporary/filesort） ✅ |
| 12 | 作业组装 exam 题面（selectBatchIds） | `PRIMARY` range / `Using where` | 命中 ✅ |
| 13 | 举一反三 exam 兜底 | eq `idx_student_kp_status` ref；eqx `PRIMARY` eq_ref（ref=func） | 命中 ✅ |
| — | `selectCriticalErrors`（S10） | **`ALL` / key NULL / Using where**（11 行） | 预期不得 ALL；数据量小导致，需真实数据复测 |

结论：**详情 3 条查询均命中索引；列表主表命中、聚合派生表与错题/超期查询在 1 行数据下为 ALL**。固定 SQL 条数由 `ExamPaperServiceImpl.getPapers`（2）与 `getPaperDetail`（3）保证；新读 SQL 显式列名，无 `select *`（`ExamPaperMapper:107-152`、`ExamQuestionMapper:59-66`）。

### 7.2 批量写/并发池/base64

| 证据 | 位置 |
|------|------|
| 题目批量插入 ≤100 | `ExamQuestionMapper.java:31-43`；`ExamPaperServiceImpl.java:472-476` |
| 判分结果批量 CASE WHEN ≤100 | `ExamQuestionMapper.java:79-96`；`ExamPaperServiceImpl.java:566-570` |
| 错题批量查/插/更新 ≤100 | `ErrorQuestionServiceImpl.java:133-192`；`ErrorQuestionMapper.java:326-373` |
| 掌握度 state 批量 IN/UPDATE + 日志批插 ≤100 | `MasteryServiceImpl.java:315-340`、`:397-408` |
| 解析池 | `ThreadPoolConfig.java:103-117`：`exam-parse` core=max=3、queue=16、CallerRunsPolicy；`:127-141` 注册 shutdown |
| 全局 LLM 并发 ≤3 | `LlmConcurrencyLimiter.java:36-57`；`AgentScopeClient.java:125-164`（`call`/`callVision` 均过闸门） |
| base64 峰值控制 | `AgentScopeClient.java:204-228`：每次 `callVision` 只带 1 张图，逐图 encode，`base64=null`；`ExamParseServiceImpl.java:202-248` 单图任务返回 DTO 不含字节；`ExamParseServiceImpl.java:115-200` 仅 `Map<Integer,ImageTaskResult>` |
| 禁止项扫描 | `grep "List<byte[]>|List<String>.*base64|Base64.*getBytes"` 无命中；日志只打 `promptLength/imageCount`（`AgentScopeClient.java:179-180`） |

## 8. 问题分级（BLOCKER / 建议）

### B1（BLOCKER）：建上传会话前后端契约不一致，主流程第一步失败

- 前端：`student-app/src/api/exam.ts:193-195` `createPaper()` → `post('/student/exam/papers', params)`；`student-app/src/utils/request.ts:160-169` 固定 `Content-Type: application/json` + `data` 对象 → **JSON body**。
- 后端：`src/main/java/com/jiang/controller/ExamPaperController.java:50-54` 用 `@RequestParam` 接收 `subjectId/examDate/paperType/imageCount`，只从 query/form 绑定，**不解析 JSON body**。
- 后果：`POST /api/student/exam/papers` 返回 400（MissingServletRequestParameter）/业务失败，上传闭环无法开始；t23 已派修复（`@RequestBody` DTO + MockMvc 契约用例）。
- 证据缺口：`ExamPaperControllerTest` 只覆盖 GET/404，无 createPaper 用例；前端只验证构建。

### M1（建议，中）：`ExamAnalysisWorker` 缺 `getActiveCount() >= coreSize` 拉取节流

- `src/main/java/com/jiang/task/ExamAnalysisWorker.java:48-76`：每秒固定 `rightPop` 最多 3 个任务并 `executor.submit`，未检查 `executor.getActiveCount()`。
- 与 tasks A3 / design §11.2 L910 的“忙时不从 Redis 取任务，避免 CallerRunsPolicy 把调度线程拖成长任务”不一致；t23 已派修复。

### M2（建议，中）：`docs/ops/exam_paper_explain.sql` 与 v3 反向

- `docs/ops/exam_paper_explain.sql:126-147`（#8/#9）仍写 `question_id > 0`；design §7.6/§7.2.1 v3 已撤销 due/count 的负 ID 排除。
- 脚本也缺 v3 #12/#13（exam redo 题面 / exam 兜底源题）与 `selectCriticalErrors`；t23 已派修复。

### L1（建议，低）：Worker/信号调整仍 set/clear `StudentContext`

- `ExamPaperServiceImpl.java:387-395,429-430` 与 `ExamSignalAdjustServiceImpl.java:109-111,132-134` 设置并 finally 清理；design §9.1/§8.2 要求 Worker 不设置 `StudentContext`（避免线程池身份串味）。有 finally 清理，风险可控；t23 已派修复。

### L2（建议，低）：工件与实现轻微漂移

- `tasks.md:426` 写 `exam_analysis_result.questions.kpStats`，实现/design v4b 用 `stats`（`ExamPaperServiceImpl.java:685`）。
- `tasks.md:385` 写 `uni.compressImage({quality: 60})`，实现为 800KB 阈值 + quality 85（`ExamUpload.vue:167-168`；t20 已确认真图 60 导致选项读不出）。
- `design.md:863` 写上传页 `Profile.vue` 内嵌，实现为独立 `ExamUpload.vue`（t3 验收口径允许）。
- 无 `ExamSettlementTest` 文件；结算用例实际在 `ExamPaperServiceTest`（`should_parseJudgeAndSettle` 等），不影响覆盖，但工件/测试命名不一致。

### L3（未验证，低）：`stats` 落库与真实数据 EXPLAIN

- `stats` 写入代码确定（`ExamPaperServiceImpl.java:681-686/742-761`），但 `ExamPaperServiceTest` 未断言 `stats` 键；因 B1 未做真实上传，未做 `exam_analysis_result.questions.stats` 的真实 DB 验证。
- §7.1 的 ALL/无 key 计划需在真实 `exam_paper/exam_question` 数据量下复测（本报告已标注）。

## 9. 未验证清单

1. **真实微信/真机 8 图上传**：因 B1 未做；需要 t23 修复后，用 `E:\test-source` 4 张真实图（或 8 张）在开发者工具/真机走通。
2. **真实视觉解析质量/知识点准确率**：本 change 期间未重新跑 v4 prompt 的真实图（v4 增加了 `{knowledgePoints}` 与数学记号清洗）；design §3.7 的“2 份真实卷 ≥20 题、直出≥70%/可归因≥90%”未抽样。
3. **真实图床（COS）**：`CosFileStorageServiceImpl.readBytes` 仍抛 1188（未实现，设计与 upload 一致）；本报告只验证 local。
4. **页面栈手工回归**：未在微信开发者工具手工点“我的→上传→详情→返回/连点”；仅代码+构建验证。
5. **信号自动调整真实链路**：`ExamSignalAdjustServiceTest` 覆盖无目标/无信号/12h confirmed/冲突/apply；未用真实考试触发 SIG-2 并在 `plan_adjustment_log` 观察 `action='confirmed'`。
6. **判分三态真实落库**：单测覆盖三态；未用“选择题缺标准答案”的真实卷验证 `is_correct=NULL` 不进错题/掌握度、`judgedCount` 排除。
7. **`exam_analysis_result.questions.stats` 真实 JSON**：代码已写，未做 DB 抽查。
8. **真实网络下 8 图稳定性/耗时**：未做；单卷 60–120s 目标只有 design 估算。

## 10. 「真实上传链路」两级（按 captain 要求）

### 第一级：可自动化（本报告已执行）

- `mvn -q compile` / `mvn test`（781）/ `npm run build:mp-weixin`。
- codec 往返（`target/verify/CodecCheck.java`）。
- MySQL 结构/基线/EXPLAIN（§5/§7）。
- 单测覆盖：上传校验（≤8/9.5/30/paperType）、解析重排/去重、三态判分、结算幂等、错题负 ID、掌握度 D_norm、信号去重、历史读、前端构建。

### 第二级：必须人工/真机（B1 修复后执行）

1. 真机/开发者工具：一次选 4–8 张真实试卷图（含手写）→ 建会话 → 逐张上传 → 提交 → 详情轮询完成。
2. 若前端/后端修复后仍失败，先看 `GET /tasks/{taskId}.errorMessage`（≤500 脱敏）与 `agent_degradation_log`。

**五表核对 SQL（`student=9 / tenant='default' / @paper=最新 paper`）**

```sql
-- 1) 试卷 + 任务
SELECT id,student_id,subject_id,exam_date,paper_type,status,grade_status,total_score,
       JSON_LENGTH(image_urls) image_count,create_time
FROM exam_paper WHERE student_id=9 AND tenant_id='default' ORDER BY id DESC LIMIT 1;
SELECT id,exam_paper_id,task_type,status,ocr_retry_count,attribution_retry_count,
       error_message,started_at,completed_at
FROM exam_analysis_task WHERE exam_paper_id=@paper AND tenant_id='default' ORDER BY id DESC;

-- 2) 题目全量（含 unreadable/无 kp/未判定）+ 题号升序
SELECT COUNT(*) total, MIN(question_index) min_idx, MAX(question_index) max_idx,
       SUM(is_correct=1) correct_cnt, SUM(is_correct=0) wrong_cnt,
       SUM(is_correct IS NULL) unjudged_cnt,
       SUM(knowledge_point_id IS NULL) no_kp_cnt,
       SUM(attribution_status='pending_manual_review') pending_manual
FROM exam_question WHERE exam_paper_id=@paper AND tenant_id='default';
SELECT id,question_index,question_type,difficulty,knowledge_point_id,attribution_status,
       is_correct,score,full_score,LEFT(question_content,80) content_head
FROM exam_question WHERE exam_paper_id=@paper AND tenant_id='default' ORDER BY question_index;

-- 3) 错题（只应为 is_correct=0 且有 kp；负 ID 反查 exam_question）
SELECT COUNT(*) exam_error_cnt, SUM(eq.question_id<0) negative_cnt,
       SUM(xq.id IS NULL) missing_exam_question
FROM error_question eq
LEFT JOIN exam_question xq ON xq.id=-eq.question_id AND xq.tenant_id=eq.tenant_id
WHERE eq.student_id=9 AND eq.tenant_id='default' AND eq.source_type='exam' AND eq.source_id=@paper;
SELECT eq.error_question_id,eq.question_id,eq.knowledge_point_id,eq.current_stage,eq.status,
       xq.question_index,xq.is_correct
FROM error_question eq
LEFT JOIN exam_question xq ON xq.id=-eq.question_id AND xq.tenant_id=eq.tenant_id
WHERE eq.student_id=9 AND eq.tenant_id='default' AND eq.source_type='exam' AND eq.source_id=@paper;

-- 4) 掌握度 + “原本无 state 是否建档成功”
SELECT x.knowledge_point_id,
       CASE WHEN s.state_id IS NULL THEN 'MISSING_STATE' ELSE 'OK' END bootstrap,
       s.state_id,s.mastery_score,s.confidence,s.source,s.last_assessed_at,s.create_time
FROM (SELECT DISTINCT knowledge_point_id FROM exam_question
      WHERE exam_paper_id=@paper AND tenant_id='default'
        AND knowledge_point_id IS NOT NULL AND knowledge_point_id<>'') x
LEFT JOIN student_knowledge_state s
  ON s.student_id=9 AND s.tenant_id='default' AND s.knowledge_point_id=x.knowledge_point_id;
-- 断言：无 MISSING_STATE；新建行 source='exam'、confidence>=0.1、last_assessed_at≈结算时间

-- 5) 掌握度日志（每题一条；change_type='exam'）
SELECT log_id,knowledge_point_id,change_type,source_id,delta,mastery_before,mastery_after,create_time,
       ROUND(mastery_after-mastery_before,4) calc_delta
FROM mastery_change_log
WHERE student_id=9 AND tenant_id='default' AND change_type='exam' AND source_id=@paper
ORDER BY log_id;
-- 断言：delta=calc_delta；答对>0/答错<0；is_correct IS NULL 的题没有日志

-- 6) 学习记录 stats / 总分一致性
SELECT analysis_id,exam_name,status,total_score,user_score,analyzed_at,
       JSON_EXTRACT(questions,'$.paperId') paper_id_in_json,
       JSON_EXTRACT(questions,'$.stats') stats
FROM exam_analysis_result WHERE student_id=9 AND tenant_id='default'
  AND JSON_EXTRACT(questions,'$.paperId')=@paper;
-- 断言：paperId 匹配；stats 含 kpDirect/kpFallback/kpUnresolved/judged/unjudged；
--       paper.total_score = result.total_score = judgedCount；user_score=correctCount
```

## 11. 附录：关键测试类与方法

- `ExamPaperServiceTest`：`should_validateImageCount`、`should_validatePaperType`、`should_reuseRecentUploadingSession`、`should_resetExpiredUploadingSession`、`should_checkOwnershipAndSessionStatus_when_uploadImage`、`should_validateSizeAndCount_when_uploadImage`、`should_triggerAnalysis_when_reachMaxImages`、`should_submitPaper`、`should_retryPaper`、`should_checkTaskOwnership`、`should_returnEarly_when_casFailed`、`should_parseJudgeAndSettle`、`should_notRepeatAccounting_when_alreadySettled`、`should_skipParse_when_questionsAlreadyPersisted`、`should_failTaskAndPaper_when_parseFailed`。
- `ExamPaperReadServiceTest`：`should_listPapers_withClampAndTwoQueries`、`should_returnDetail_withSortedQuestionsAndKpNames`、`should_throw1136_when_notOwned`、`should_passTenantId_toAllQueries`。
- `AnswerGradingServiceTest`：15 个（三态：choice/非 choice × 答案有/无；blank/unreadable/超时）。
- `ExamRedoIntegrationTest`：7 个（exam redo 题面、混排去重、缺答案降级、5+ 不失败、stage 推进、submitAnswer）。
- `ExamKpCandidateServiceTest`：7 个（过滤/排序/双上限/可达性/缓存/kp_unreachable/渲染）。
- `ExamParseServiceTest`：14 个（直出/兜底/统计/500 分片/重排/去重/截断/降级）。
- `ExamSignalAdjustServiceTest`：7 个（无目标/无信号/confirmed 去重/冲突/apply/异常/aiExecutor）。
- `HomeworkServiceTest`：`should_notFailGeneration_when_kpHasFiveExamWrongQuestions`、`should_clampKpErrorCountToKpMax_byTotal`。
- `MasteryServiceTest`：D_norm 四条 + 批量 state 创建/答对答错。
- `ErrorQuestionServiceTest`：负 ID due、S9 兜底、S10 双条件、completedDate。
- 其他：`ExamQuestionTextCodecTest`、`FileStorageServiceTest`、`LlmTextSanitizerMathNotationTest`、`ExamParsePromptContractTest`、`AttributionAgentImplTest`、`ExamPaperControllerTest`、`ExamQuestionSourceAdapterTest`。

## 12. 复现命令

```powershell
# 后端
mvn -q compile
mvn test                       # 781/0/0/5
# 前端
npm --prefix student-app run build:mp-weixin
# DB 结构/基线
docker exec mysql mysql -uroot -psmileday159 -D studyplan -t -e "SHOW TABLES;"
# EXPLAIN（推荐用 round-2 更新后的 docs/ops/exam_paper_explain.sql；当前可用 target/verify/explain_actual.sql）
Get-Content -Raw target/verify/explain_actual.sql | docker exec -i mysql mysql -uroot -psmileday159 -D studyplan -t
# codec 往返
javac -encoding UTF-8 -cp target/classes -d target/verify target/verify/CodecCheck.java
java -cp "target/classes;target/verify" CodecCheck
```

---

> **Round-1 最终判定：FAIL（BLOCKER B1）**。其余大部分需求有代码/单测/构建/DB 证据；B1 修复后 round-2 只需：重跑 `mvn test`/`compile`/前端构建、重跑更新后的 EXPLAIN、真机 4–8 图上传 + 上述五表 SQL 核对、补 stats/三态/信号真实链路抽样。

---

## 13. Round-2（t26，2026-11-11 17:13）

### 13.1 命令与结果

| # | 命令 | 结果 |
|---|------|------|
| 1 | `mvn -q compile` | exit 0（`target/verify/mvn-compile-r2.log`） |
| 2 | `mvn test` | **788 用例 / 0 失败 / 0 错误 / 5 跳过 / BUILD SUCCESS**（49.4s；`target/verify/mvn-test-r2.log`） |
| 3 | `npm --prefix student-app run build:mp-weixin` | exit 0，`DONE Build complete.`（`target/verify/build-mp-weixin-r2.log`） |
| 4 | 真实后端（18080）启动 | `Started StudyPlanApplication in 3.898 seconds`；`AgentScope SDK 测试调用成功`；线程池 `exam-parse coreSize=3 queue=16` |
| 5 | 真实 4 图上传 | `POST /papers` 200 → 4×`images` 200 → `submit` 200；paper 1，task 1；解析 157s，`completed`；结算 `graded`（`target/verify/e2e_upload.log`） |
| 6 | 真实 HTTP 契约/越权 | 列表/详情 200；他人 1136/1135；9 张 1182；非法 type 1191；缺字段 400（`target/verify/e2e_http_checks.log`） |
| 7 | DB 五表核对 | 见 13.4（`target/verify/e2e_db_checks.out`） |
| 8 | EXPLAIN v3 增量 | 见 13.5（`target/verify/explain_r2_data.out`） |
| 9 | 数据库备份/停止 | `target/verify/db_backup_before_e2e.sql`（7,726,119B）；后端已停，18080 无监听 |
| 10 | 真实数据量：19 题 | 题号 1..19 连续；`questionCount=19`（列表/详情一致） |

### 13.2 Round-1 findings 闭环

| ID | 闭环结果 | 证据 |
|----|----------|------|
| **B1** 建会话契约 | **✅ 已闭环** | `ExamPaperController.java:50-56` 改 `@Valid @RequestBody ExamPaperCreateRequest`；DTO `ExamPaperCreateRequest.java:31-60`（subjectId/examDate/imageCount @NotNull、imageCount @Min(1)）；`ExamPaperControllerTest.java:76-142` 4 条 JSON 契约用例（成功/1182/1191/缺字段 400）；**真实 HTTP**：create 200、9 张 1182、非法 type 1191、缺字段 400 |
| **M1** Worker 节流 | **✅ 已闭环** | `ExamAnalysisWorker.java:56-63`：`activeCount >= corePoolSize` 时不 pop 并 break；`ExamAnalysisWorkerTest.java:51-86` 3 条用例（饱和不 pop/有空闲 pop/空队列停） |
| **M2** EXPLAIN 脚本 v3 | **⚠ 未完全闭环（中）** | #8/#8b/#9b 已去 `question_id > 0`；#9 改 selectCriticalErrors；新增 #12/#13；但 **#13 `docs/ops/exam_paper_explain.sql:231` 引用不存在的 `eqx.correct_answer`**，脚本在 #13 中断。用等价（移除该列）SQL 重跑得到正确计划（见 13.5）。属工具脚本缺陷，不阻断应用 |
| **L1** StudentContext 偏离 | **✅ 已闭环** | `ExamPaperServiceImpl.java:387-388` 不再 set/clear；`ExamSignalAdjustServiceImpl.java:29/109-112` 纯显式入参；真实上传/结算/信号调整全程成功，证明不依赖 ThreadLocal |
| **L2** 工件漂移 | **仍在（按 captain 要求不判 fail，交 t10）** | `tasks.md:426` 仍 `kpStats`（实现/design 用 `stats`）；`tasks.md:385` 仍 quality 60（实现 85，t20）；`design.md:863` 仍写 Profile 内嵌（实现独立页）；仍无 `ExamSettlementTest` 文件（结算在 `ExamPaperServiceTest`） |
| **t24 列表字段** | **✅ 已闭环（运行时）** | `ExamList.vue:29` 读 `item.questionCount`；真实 GET /papers 返回 `questionCount=19`；详情 `ExamDetail.vue:41` 读 `paper.questionCount`；遗留类型声明 `api/exam.ts:163` 仍 `totalQuestionCount`（仅 TS 类型，不影响构建/运行；低） |

### 13.3 真实上传闭环（4 张真图，E:\test-source，未复制进仓库）

```text
token minted for student=9 tenant=default (not printed)
create HTTP 200 code 200 -> paperId=1 status=uploading imageCount=5
upload[1/4] 200 uploadedCount=1
upload[2/4] 200 uploadedCount=2
upload[3/4] 200 uploadedCount=3
upload[4/4] 200 uploadedCount=4
submit HTTP 200 code 200 -> taskId=1 status=pending
poll +0s pending; +3s processing; +157s completed, questions=19
final: status=completed gradeStatus=graded; 19 questions; 16 judged / 0 wrong / 3 null
```

- 采用 `imageCount=5` 上传 4 张，避免第 4 张触发“达量自动解析”而使显式 `submit` 返回 1186；这覆盖了“上传数 < 声明数”的正常 submit 路径。
- 解析耗时 157s（单卷 4 图，含 v4 kp 候选注入/归因/判分），在 180s task 预算内；结算在解析完成后约 22s 完成（`started_at 17:06:55`，`completed_at 17:09:51`）。
- 真实详情字段（`target/verify/e2e_detail_final.json`）：`questionType=choice`、`isCorrect=true`、`score/fullScore=1.0`、`answerState=judged`、`attributionStatus` 保留；无 `questionFormatType`，与 t24 前端一致。

### 13.4 DB 五表核对（真实数据）

| 表/项 | 实测 | 结论 |
|-------|------|------|
| `exam_paper` | id=1, status=completed, grade_status=graded, total_score=16.00, image_urls=4 | ✅ |
| `exam_analysis_task` | id=1, task_type=vision_parse_attribution, status=completed, ocr_retry=0, attr_retry=0, error_message=NULL | ✅ |
| `exam_question` | **19 题**，题号 1..19 连续/去重；16 judged、**0 wrong**、3 null；6 题无 kp、6 题 `pending_manual_review`；0 空 studentAnswer | ✅ 全量落库（含不可判定/无 kp） |
| `error_question` | exam 来源 0 行（本次没有答错题）；`question_id<0` 0 行；null 题未入队 | ✅ 本次无 wrong 可入队；**wrong→error 真实路径未触发**（单测覆盖） |
| `mastery_change_log` | 11 条（change_type=exam, source_id=1），11 个 kp；`delta>0` 11 条；`delta` 与 after-before 一致 | ✅ = 判分且有 kp 的题数（11） |
| `student_knowledge_state` | 12 个卷内 kp 中 11 个 OK；`cnm_h194` 唯一 `MISSING_STATE` | ✅ 该题 `is_correct=NULL`（id=10，choice/ACD），按 v4b 不更新掌握度 → 不建档是**正确行为**；其余 11 个判分 kp 均建档（10 个新建 source=exam/confidence=0.15，cnm_h145 既有更新） |
| `exam_analysis_result` | id=1, status=completed, total=16.00, user=16.00, analyzed_at=17:09:51, items=19；**stats**=`{"judged":16,"unjudged":3,"kpDirect":11,"kpFallback":2,"kpUnresolved":6,"kpTotal":128,"kpTruncated":0}` | ✅ `stats` 四档真实落库；key 是 `stats` 不是 `kpStats` |
| 总分一致性 | paper.total=result.total=judged=16；result.user=correct=16 | ✅ |
| 三态排除 | 3 个 null：id9(cnm_h164)、id10(cnm_h194)、id11(无 kp)；null 入错题=0；null 产生掌握度日志=0（日志数 11=判分且有 kp 题数）；cnm_h194 无 state | ✅ 与 v4b 一致 |
| 信号调整 | `plan_adjustment_log` id=3, goal=6, action=**confirmed**, 17:11:14, signal=**SIG-4**（剩余 69.6 < 未覆盖 114），diff_content 已生成 | ✅ 真实触发 triggerAdjustment→applyDiff；12h 去重本次只有 1 次，未实测 |
| 降级日志 | `agent_degradation_log` 无 exam_parse 记录；kpTotal=128<150 无截断 | ✅ 本次无降级 |

### 13.5 EXPLAIN 增量（真实数据：1 paper / 19 questions / 11 homework errors）

| # | 实际 | 对比 design §11.6 |
|---|------|-------------------|
| 1 count | `uk_student_exam` ref / Using index | 等价命中 |
| 2 list | p `uk_student_exam` ref；s PRIMARY eq_ref；派生 a ref；**q 派生 ALL（19 行）** | q 未命中 `idx_paper_index`（单卷全匹配 + 19 行，优化器选 ALL；索引存在，需多卷复测） |
| 3 detail paper | **PRIMARY const** + s PRIMARY const | ✅ |
| 4 detail questions | **ALL + Using filesort**（possible idx_paper_index） | 未命中；原因同 #2（19 行全属 paper=1）；SQL 具备 `idx_paper_index`，需多卷/生产数据复测 |
| 5 kp 批量 | PRIMARY range | ✅ |
| 6 state 批量 | uk_student_kp range | ✅ |
| 7 错题本 JOIN | eq ALL（11 行）；**eqx PRIMARY eq_ref** | eq 未命中（小数据）；eqx 已 eq_ref ✅ |
| 8 到期 kp（v3 去过滤） | `idx_student_status_due` range | ✅ |
| 8b 到期明细 | `idx_student_kp_status` ref | ✅ |
| 9 selectCriticalErrors | **ALL**（11 行，无匹配） | 未命中；小数据；SQL 带 student+tenant+status+question_id |
| 9b 配额分母（去过滤） | `idx_student_kp_status` ref / Using index | ✅ |
| 10 学习记录查重 | `idx_student` ref | ✅ |
| 11 D_norm 分组 | `idx_kp_difficulty` range / Using index | ✅（优于预期） |
| 12 作业 exam 题面 | PRIMARY range | ✅ |
| 13 举一反三 exam 兜底（修正列后） | eq `idx_student_kp_status` ref；**eqx PRIMARY eq_ref** | ✅ |

- **M2 脚本缺陷**：原脚本 #13 选 `eqx.correct_answer`（`exam_question` 无该列），在 `docs/ops/exam_paper_explain.sql:231` 报 `ERROR 1054`，导致脚本不能完整执行；本报告 13 行改用与 `ErrorQuestionMapper.selectDueExamSourceQuestion` 一致的列集合后取得上述计划。建议 t23b 一行删除该列；同时 #12 的 `tenant_id = :tenant_id` 与真实 `selectBatchIds`（仅 id IN）不完全一致，建议同步。
- #2/#4/#7/#9 的 ALL/无 key 是**当前单卷/11 条错题数据分布**下的优化器选择，不是 SQL 缺失索引；需在“多卷 + 多学生”数据量下复测（已列入未验证）。

### 13.6 Round-2 finding（按严重度）

| ID | 级别 | 问题 | 文件:行号 | 建议 |
|----|------|------|-----------|------|
| R2-1 | **中** | explain.sql #13 引用不存在的 `eqx.correct_answer`，脚本中断；#12 的 tenant 条件与真实 selectBatchIds 不一致 | `docs/ops/exam_paper_explain.sql:231`（#13）、`:220`（#12） | 删除 `eqx.correct_answer`，按 mapper 列集合对齐；round-3/归档前重跑 |
| R2-2 | 低 | 前端类型仍声明 `totalQuestionCount`，页面已改读 `questionCount`，TS 类型与运行 VO 不一致（构建不 typecheck，运行正常） | `student-app/src/api/exam.ts:163`；`ExamList.vue:29`；`ExamDetail.vue:41` | 类型改 `questionCount`（t24 收口） |
| R2-3 | 低（环境） | MySQL 容器 `@@global.time_zone=SYSTEM`/`NOW()=09:10 UTC`，应用 `LocalDateTime=17:09`；`exam_paper.update_time`/`mastery_change_log.create_time` 为 09:09，`exam_paper.create_time`/`analyzed_at` 为 17:09 | 环境/既有，非本 change 代码 | Demo 可接受；若学习记录展示时间需统一，另开配置/时间源修正（不在本 change） |
| R2-4 | 未验证 | 真实链路 0 道答错题，`error_question` 负 ID 入队/stage 推进未在真实数据触发（单测覆盖）；12h 信号去重/多卷 EXPLAIN 亦未真实触发 | — | 后续用含错题的真实卷或构造数据复测（不伪造） |

### 13.7 Round-2 未验证清单（更新）

1. 真实微信/真机 UI 的 8 图选择/上传/页面栈手工回归（本次用后端 API + 4 张真图，未开微信开发者工具）。
2. v4 知识点直出准确率抽样（2 份真实卷 ≥20 题，直出≥70%/可归因≥90%）；本次 1 卷 19 题直出 11/兜底 2/未决 6，可归因 13/19≈68.4%，**低于设计的 90% 目标**（样本仅 1 卷，且含 6 题无 kp；需 2 卷抽样后判定）。
3. 真实错题（0 wrong）→ error_question 负 ID 入队/stage 推进；以及 12h 去重真实触发。
4. COS 图床（仍 1188 未实现，设计一致）；真实网络 8 图稳定性。
5. 多卷/多学生数据量下的 EXPLAIN（#2/#4/#7/#9）。
6. `kpTruncated>0` 的真实截断与 `truncate_kp_candidates` 降级（本次 kpTotal=128<150 未触发；单测覆盖）。

### 13.8 数据/环境处置

- 备份：`target/verify/db_backup_before_e2e.sql`（7.7MB，mysqldump `--single-transaction --triggers`）。
- 本次真实上传写入 paper 1 / 19 题 / 10 个新 state / 11 条 exam 日志 / 1 条 exam_analysis_result；SIG-4 调整了 goal 6 的未来计划（`plan_adjustment_log` id=3）。**未执行恢复**（保留真实验证证据）；如需还原 Demo 数据，用备份人工恢复。
- 后端已停止：18080 无监听；未占用 8080；未把 API Key/图片写入仓库。

> **Round-2 判定：PASS（应用功能与真实链路通过；R2-1 为工具脚本中等级 finding，建议归档前修复）。**

---

## 14. Round-3 收尾：最终字节绑定（t29，2026-11-11 17:18）

> 目的：t26 的 PASS 基于 t27（EXPLAIN 脚本）与 t28（api/exam.ts 类型）之前的字节；本节只验证并绑定最终字节，不重复全量取证。

### 14.1 最终命令（记录实测值）

| 命令 | 结果 |
|------|------|
| `mvn -q compile` | exit 0（`target/verify/mvn-compile-r3.log`） |
| `mvn test` | **788 用例 / 0 失败 / 0 错误 / 5 跳过 / BUILD SUCCESS**（50.9s；`target/verify/mvn-test-r3.log`） |
| `npm --prefix student-app run build:mp-weixin` | exit 0，`DONE Build complete.`（`target/verify/build-mp-weixin-r3.log`） |
| `grep -c "totalQuestionCount\\|questionFormatType" student-app/src/api/exam.ts` | **0**（旧字段名 0 残留） |
| 18080 监听检查 | **无监听**；`java.exe` 进程扫描无 StudyPlan/spring-boot 残留 |

### 14.2 `docs/ops/exam_paper_explain.sql` 整段实跑（t27 修复后）

- 方式：占位符替换 `student_id=9 / paper_id=1 / tenant_id='default' / kp_id='cne_h084'`，`docker cp` 到 `mysql:/tmp/explain_t29.sql`，`docker exec mysql sh -c "mysql ... < /tmp/explain_t29.sql"`。
- 结果：**15/15 个 EXPLAIN 结果块返回，真实 SQL ERROR = 0**（`ERROR 1054` 计数 0；`target/verify/explain_t29.out`）。
- 数据量：exam_paper=1 / exam_question=19 / error_question=11（试卷负 ID=0）/ exam_analysis_result=1。
- **#12 作业组装 exam 题面**：`PRIMARY` **range**，rows=11，`Using where`（selectBatchIds `id IN`；不含 tenant，与实现一致）。
- **#13 举一反三 exam 兜底源题**：
  - `eq`：`ref` / **`idx_student_kp_status`** / ref=const,const,const,const；
  - `eqx`：**`eq_ref` / `PRIMARY`** / ref=func / rows=1；
  - 与 design §11.6 #13 预期一致。

### 14.3 最终字节 SHA256（绑定）

| 文件 | SHA256（前 12 位） | SHA256（完整） |
|------|--------------------|----------------|
| `docs/ops/exam_paper_explain.sql` | `4CED95E6C372` | `4CED95E6C372FEA51CE6E5FC30B4E0D3D388F76B5516A4D247659C0AA2FF9AB3` |
| `student-app/src/api/exam.ts` | `9E143450EDD7` | `9E143450EDD7573F59E7FF671A9201C5A907628241964A5F80DD90CE7274DE79` |

### 14.4 t27/t28 修复核对

- t27：`#13` 已移除不存在的 `eqx.correct_answer`，改用真实列；`#12` 去掉与 `selectBatchIds` 不符的 tenant 条件；#9 补 demo 数据量 ALL 说明。整段实跑 0 ERROR。
- t28：`api/exam.ts` 已改 `questionCount` / `questionType` / `questionContent` / `knowledgePointId: string` / `paperTypeName` / `imageCount` / `taskId` / `reused`；`grep totalQuestionCount|questionFormatType = 0`；构建 exit 0。

> **Round-3 判定：PASS。最终字节可复现，t27/t28 修复闭环；18080 已释放。**

---

## 15. Round-4 收尾回归：A1/A2/A3/A5/A6/A7（t31，2026-11-11 17:30）

> 目标：确认 t30 对评审 A1/A2/A3/A5/A6/A7 的修复无回归，记录最终字节。只读验证，不改代码/工件。

### 15.1 命令与结果

| 命令 | 结果 |
|------|------|
| `mvn -q compile` | exit 0（`target/verify/mvn-compile-r4.log`） |
| `mvn test` | **801 用例 / 0 失败 / 0 错误 / 5 跳过 / BUILD SUCCESS**（64s；`target/verify/mvn-test-r4.log`；较 t29 的 788 +13） |
| `npm --prefix student-app run build:mp-weixin` | exit 0，`DONE Build complete.`（`target/verify/build-mp-weixin-r4.log`） |
| 18080 / 8080 | **均无监听**；无 StudyPlan/spring-boot 残留 Java 进程 |

### 15.2 A1–A7 针对性核对

| 项 | 代码位置 | 测试（已含在 801 全绿中） | 结论 |
|----|----------|--------------------------|------|
| **A1** 预算到点补收已完成 Future | `AnswerGradingServiceImpl.java:182-198`（drain 后 cancel 未完成）、`:233-263`（poll 500ms + isDone 补收）；`ExamParseServiceImpl.java:184-231`（drain+isDone 补收）、`:239-254`（collectImageResult） | `AnswerGradingServiceTest.should_keepCompletedResult_when_budgetReached`（3 题、100ms 预算/300ms 慢题仍全 true）；`ExamParseServiceTest.should_keepFinishedImage_when_budgetReached`（1s 预算/1.1s 第 2 图 → succeeded=3、failed=0、无 timeout 降级） | ✅ 已修 |
| **A2** 判分分批提交 + 预算入口计时 | `AnswerGradingServiceImpl.java:138-180`（deadline 在入口、in-flight ≤ submitBatch）、`:206-209`（批大小=coreSize）、`:217-225`（未传预算才估算） | `AnswerGradingServiceTest.should_batchSubmit_when_40NonChoiceQuestions`（40 题全 true、LLM 调用 40 次、<10s） | ✅ 已修 |
| **A3** 追加图片 SQL 张数守卫 + 单实例锁 | `ExamPaperMapper.java:49-56`（`JSON_LENGTH(image_urls) < #{maxImages}` + status/tenant）；`ExamPaperServiceImpl.java:257-297`（64 段锁内复核张数/累计大小→落盘→原子追加；0 行时重读→已满 1189、否则 1186）、`:304-308`（分段锁） | `ExamPaperMapperSqlTest.should_guardAppendImageUrl_withJsonLength`；`ExamPaperServiceTest.should_throw1189_when_appendGuardRejects` / `should_throw1186_when_appendGuardRejectsAndSessionChanged` | ✅ 已修（30MB 无持久化列，size 复核依赖同一把锁；t30 已声明，零 DDL） |
| **A5** 魔数校验 | `ExamUploadValidator.java:82-95`（Content-Type 后调用 magic）、`:97-122`（PNG 89 50 4E 47… / JPEG FF D8 FF，只读 8 字节）、`:124-129`（短文件拒绝） | `ExamUploadValidatorTest.should_throw1139_when_disguisedTextFile` / `should_pass_when_magicMatches` / `should_throw1139_when_tooShort` | ✅ 已修 |
| **A6** 判分 key 集合守卫（替代不可达 size 比较） | `ExamPaperServiceImpl.java:668-689`（`batchResult.keySet()` 与 expectedKeys 比较；missing/unexpected 记 `judge_key_mismatch` 降级日志；缺失题 `put(..., null)`，不整体失败）；settle 不再重复 size 判断（`:549`） | `ExamPaperServiceTest.should_logAndKeepNull_when_judgeKeysMismatch` | ✅ 已修 |
| **A7** 科目校验带 tenant + kp 候选按 subjectId+tenant | `ExamPaperServiceImpl.java:184`（创建会话）、`:761`（学习记录科目名）均 `subjectMapper.selectByIdAndTenant`；`ExamKpCandidateServiceImpl.java:202-207`（按 subjectId+tenant 定位，再按名称查 kp） | `ExamPaperServiceTest` A7 两条（426/437）；`ExamKpCandidateServiceTest` A7 用例（213） | ✅ 已修 |

### 15.3 最终字节 SHA256（前 12 位）

| 文件 | SHA256（前 12） | SHA256（完整） |
|------|-----------------|----------------|
| `AnswerGradingServiceImpl.java` | `13999AA9ECEA` | `13999AA9ECEAEC7085743CEA313F8E36D2340DA04B3B097DBF09FECC1AD9EED3` |
| `ExamParseServiceImpl.java` | `2749C75AFBAB` | `2749C75AFBABEA8B545D762CB6293FB8987B8C90F3BD16489AA9C454AF8A6CB6` |
| `ExamUploadValidator.java` | `54F2021A12FE` | `54F2021A12FE14A2C0A7B7DA35C6C0C22FF7892601AA3AF435B61AD9D59699F0` |
| `ExamPaperMapper.java` | `C6A59AF98C20` | `C6A59AF98C2039F9DFE55559FD38D5E8F6C03E6F5F4CE9B1CC2FE4EA2604C7BA` |
| `ExamKpCandidateServiceImpl.java` | `839EE895C46F` | `839EE895C46FCF1879C31133C5851899DE20F781D2A667B4490B0E538E9EF27C` |
| `ExamPaperServiceImpl.java`（A3/A6/A7 主实现） | `31620D5A3BC6` | `31620D5A3BC6A1DAD716AAF3DF5E1A0B88662BFABFB1FCCE0DA7DA90EC4B1AE5` |

### 15.4 约束与边界

- A3 的 30MB 累计大小无持久化列（零 DDL），只靠单实例锁内复核；多实例并发下 SQL 只强一致保证“≤8 张”，大小上限仍建议后续 change 增加持久化/DB 守卫（t30 已声明，不阻断本次）。
- 本轮未重跑真实上传（t26 已验证过 4 图链路）；t30 改动集中在超时补收/分批提交/上传锁/魔数/守卫，单测覆盖 + 801 全绿足以确认无回归。
- 未改代码/工件；未占 8080/18080；未复制图片/写 key。

> **Round-4 判定：PASS。A1/A2/A3/A5/A6/A7 均有代码位置与测试证据，801 用例全绿，最终字节已绑定。**
