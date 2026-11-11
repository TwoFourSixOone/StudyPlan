# Proposal：试卷上传 → LLM 视觉解析 → 错题/掌握度 → 自动调整 → 历史回看

> **Change 名称**：`2026-11-11_exam_paper_upload_llm`
> **状态**：待实施（propose 完成，未动源码）
> **关联规则**：产品规则 #2、#3、#4、#5、#6、#8、#9、#11；隐性约定 #1/#2/#3/#4/#9/#10/#12/#14/#18/#19/#21/#22/#24/#25

---

## 1. 背景与现状

试卷链路目前是「腾讯云 OCR 切题 → 归因 Agent → 学生手工录入每题对错」的半成品，闭环缺三段：

| 环节 | 现状 | 问题 |
|------|------|------|
| 切题 | `OcrService` + `TencentOcrServiceImpl`（腾讯云手写体 OCR） | 手写体识别精度不可靠；OCR 只给文本，不区分"题目印刷内容"和"学生手写作答"；无法判断对错 |
| 判分 | `POST /api/student/exam/result/{id}/grade` 由学生**手工录入**每题对错/得分 | 与产品定位（AI 自动分析）不符；且 `error_question.question_id` 为 `NOT NULL`，手工录入路径传 `null` 会直接写库失败，错题入队实际是死代码 |
| 掌握度 | 仅 `submitExamGrade` 触发 `updateMasteryFromResult` | 依赖手工录入；答对事件也会被更新但整条链路不可达 |
| 信号 | 试卷链路**不检测**计划调整信号 | SIG-2（考试导致掌握度下降）只能等次日 01:30 定时任务，学生当天看不到计划响应 |
| 历史 | 只有 `/results`、`/result/{id}`，且 `student-app/src/api/exam.ts` 路径过期（`/analysis/{id}` 与后端不符）；小程序**没有任何 exam 页面** | 试卷分析结果无法回看 |

同时，`AgentScopeClient` 只会发 `TextBlock`，无法把试卷图片交给视觉模型；`homework-detail` 用 URL 传 `items` JSON（反例），试卷详情页不能重复这个错误。

## 2. 目标（用户已拍板的 9 条前置决策原样继承）

1. **图片通道**：扩 `client/AgentScopeClient` 支持 AgentScope `ImageBlock`（base64 data URL），**不另起 HTTP 客户端**。
2. **模型固定** `qwen3.8-flash`，复用 `AgentScopeConfig`，**不新增模型名配置**。
3. **判分口径同现有批改**：`choice` 字符串比较；`numeric`/`short`/`self` 走 `grading_v1.txt`，失败回字符串比较。
4. **废弃 `submitExamGrade` 手工录入**：删除接口 + Service 方法 + 相关 DTO + 单测。
5. **掌握度按规则 #2**：答对与答错**都更新**（`W_source = 1.0`）；答错同时入 `error_question`。
6. **≤8 张**；错题 / 掌握度 / 信号**全部复用现有服务**；**零 DDL**。
7. 新增 `exam_parse_v1.txt` + `exam_parse_schema.json`（用户已授权 `src/main/resources/prompts/`）。
8. 全部题目落库并可回看：「我的」→ 列表 → 详情，详情交互与作业答题区一致（上一题 / 下一题），**只传 `paperId`**。
9. 性能是硬要求，见 `design.md` §11「性能设计」。

> **修订（2026-11-11，吸收 t6 评审）**：以上 9 条前置决策**全部保留**（ImageBlock、`qwen3.8-flash`、判分同口径、对错都更新掌握度、零 DDL、只传 `paperId`）。评审提出的 7 条阻塞（B1–B7）与建议 S1–S10、S13 已在 design 修订，逐条映射见 `design.md` §18「修订记录」。
> **v3 修订（2026-11-11）**：用户新增「试卷错题必须进入原题重做 + 举一反三」，**推翻 B6 方案 (b)**（原"不参与"）；本 proposal 同步更新 §3 范围（新增试卷错题回流 + 不落题库副本）、§4 规则 #3 回写口径、§5 决策 D19–D23、§6 风险（含 V0 已通过）、§7 验收 10–12。
> **v4 修订（2026-11-11）**：用户新增①「知识点在解析阶段直出」（候选注入 + Schema + 白名单 + AttributionAgent 降级为兜底）②「数学记号收紧」。同步更新 §3 范围、§4 规则 #4/#24 回写、§5 决策 **D24/D25**、§6 风险、§7 验收 13–14；tasks 新增 **Backend E**。
> **v4b 补丁（2026-11-11，含 1 条阻塞）**：①**阻塞**：判分三态——标准答案缺失/无法辨识/超时 → `is_correct=NULL`（不可判定），不再判 `false`；②kp 候选**分级上限**（parse 150 / attribution 500）修"截断即不可达"。同步 §3/§5（**D26/D27**）/§6/§7（15–16）、tasks B4/B5/D4/E1/E4/E5、design §6.1/§3.7/§12/§15/§18。

## 3. 范围

### 包含

- **判分三态（v4b，阻塞修复）**：`AnswerGradingService` 返回 `Boolean` 三态——**标准答案缺失/作答无法辨识/超时 → `NULL`（不可判定，不调 AI、不计掌握度、不入错题、计 `unjudged`）**；只有"学生空 + 标准答案有"才判 `false`；`judgedCount = is_correct IS NOT NULL`。作业侧共享同一服务，`is_correct=null` 必须跳过错题/掌握度（现状会把 null 当答错，须一并修）
- **知识点直出（v4）**：候选知识点（subject + 学生年级过滤 + 仅 active，**parse 上限 150 / 兜底归因上限 500**）由服务端构造并注入 `exam_parse_v1.txt`，视觉模型逐题输出 `knowledgePointId`；Schema 增量 + **服务端白名单**（幻觉/越界置空 + warn，规则 #8）；`AttributionAgent` **降级为兜底**（只补空 kp 题，≤40/批，清单 500 且 >500 分片，保证被 parse 截断的 kp 仍可达）；四档统计 `direct/fallback/unresolved/unjudged` 写入学习记录 JSON
- **视觉解析**：`AgentScopeClient` 增加图文混合调用；新增 `ExamParseAgent`（Prompt + Schema + 校验 + 单图重试）与题面编解码工具
- **上传**：分段上传（创建会话 → 逐张追加 → 显式提交解析），≤8 张 / **单图 ≤9.5MB**（容器上限 10MB 留余量）/ 单次上传总量 ≤30MB 校验与新错误码；`paperType` 枚举校验（`1191`）
- **解析任务**：`ExamAnalysisWorker` 消费改造（**全局 LLM 并发 ≤3**、单 task 总超时、幂等 CAS、retry 复用库中题目、状态机）
- **归因**：复用 `AttributionAgent`，按批（≤40 题/批）调用并保持归因重试与整卷重试独立计数
- **判分**：抽 `AnswerGradingService`，作业批改改为委托（行为不变），试卷判分复用；`D_norm` 走 `MasteryFormulaUtil.validateDNorm`（偏离 >0.2 → 0.5）
- **结算**：错题批量入队（复用 `ErrorQuestionService`）+ 掌握度批量更新（复用 `MasteryService` 公式与日志）+ **学习记录写入 `exam_analysis_result`（规则 #9，含 1 题 1 分制得分）** + 一次性事务
- **信号**：新增「试卷结算后即时信号检测 + 自动应用调整」入口（复用 `SignalDetector` / `PlanAdjustmentService` / `PlanDiffService`），带幂等与 12h **信号调整**去重
- **历史回看**：`GET /papers`（2 条 SQL）、`GET /papers/{paperId}`（3 条 SQL，含 `userScore/totalScore`）+ 小程序 `ExamList` / `ExamDetail` 两个新页面
- **试卷错题回流作业（v3，用户新增要求）**：试卷错题**进入**每日作业的「原题重做」与「举一反三」——`daily_plan.content` 引入 `questionSource="exam"`（`questionId=-exam_question.id`），作业组装/提交/判分/艾宾浩斯 stage 全链路接通；举一反三源题"题库优先 + exam 兜底"（临时 `QuestionBank`，不落库）
- **删除/迁移**：`submitExamGrade` 链路（接口 / Service 方法 / DTO / 单测），OCR 服务标记废弃但保留；旧 `/results`、`/result/{id}`、`/task/{id}` 下线（见 §5.1）
- **既有缺陷顺带修复**（S10、N1、v3）：`selectCriticalErrors` 补 `student_id` 与 `question_id > 0`；**`kpErrorCountMap` 在 service 侧 clamp 到 `KP_MAX=4`**（试卷错题计入配额分母后，同 kp ≥5 题不再让次日作业生成抛异常）；`completeStageWithCheck` 的 `next_due_date` 改写 `completedDate`（列 `NOT NULL`）；`getMasteryChangesBySource` 传 `ChangeType.EXAM.getCode()`（小写）修学习记录详情掌握度恒空
- **文档**：产品规则 #3/#4/#5/#9 同步、隐性约定新增 #26、`openspec/specs/*` 同步、EXPLAIN 验证脚本

### 不包含

- **不做任何 DDL**：不加列、不加表、不加索引（`exam_question` 无 `choices`/`correct_answer` 列，题面按 design §4 的文本编码承载）
- 不做手写体高精度 OCR 兜底人工录入界面（产品规则 §3 边界不变）
- 不改 `application*.yml`（新增配置项一律带代码内默认值，可用环境变量覆盖；单图业务上限 9.5MB + 容器异常映射 `1184`，无需改配置）
- 不删除腾讯云 OCR SDK 依赖与 `OcrService` 实现（独立 change 清理）
- **不做真实卷面分值/答题卡识别**：统一 **1 题 1 分制**（`user_score=正确题数`、`total_score=已判定题数`）写入学习记录；真实分值留待后续 change
- 不做多科目批量上传、不做试卷重传历史版本
- **不落地多知识点映射**：每题只落 1 个 kp（`exam_question.knowledge_point_id` 单列）；跨知识点题由模型选"最核心"一个，不新增关联表（零 DDL）
- **不落 `question_bank` 副本**：试卷题面只存在于 `exam_question`，不写入题库（规则 #10 题库只经 JSON 导入 + 人工审核）；举一反三/重做都通过 `questionSource="exam"` 分流读取
- 不修 `assessment` 路径的同类大小写缺陷（登记为 §17-5 遗留）

## 4. 授权记录（受保护区域）

`AGENTS.md` §5 列出的受保护区域，本 change 需要触达以下项，**逐条记录授权来源**：

| 受保护区域 | 需要的改动 | 授权来源 | 状态 |
|-----------|-----------|---------|------|
| `src/main/resources/prompts/` | 新增 `versions/exam_parse_v1.txt`、`schemas/exam_parse_schema.json`；**v4 追加修改这两个新文件的正文**（注入 `{knowledgePoints}`、Schema 增 `knowledgePointId`、数学记号收紧） | 用户 2026-11-11 拍板第 7 条明确授权（v4 补充确认：同一对文件的正文修改仍在该授权内） | ✅ 已授权 |
| `docs/product/index.md` | 规则 #3（**试卷错题与题库错题一视同仁：进入艾宾浩斯到期队列、可被抽为原题重做、可作举一反三源题**；v3 推翻"不参与"口径）、**规则 #4（v4：归因前置到视觉解析，AttributionAgent 降级为兜底）**、#5（新增"考试结算后即时检测"入口）、#9（试卷题目与判分结果 + 学习记录写入 `exam_analysis_result`） | 需人工审批（propose 阶段只列改动清单，verify 阶段确认已回写） | ⏳ 待人工审批后回写 |
| `docs/architecture/implicit-contracts.md` | 新增约定 #26（试卷错题在 `error_question` 使用负 ID 命名空间；题面文本编码）；**#24 措辞修订（v4：允许 `x^2`/`a_n`，禁 `^{...}`/`_{...}` 与反斜杠命令）** | 允许在 change 内同步更新（AGENTS.md §5 注），需人工确认编号/措辞 | ⏳ 待人工确认 |
| `src/main/resources/db/`、`sql/` | **不修改**（零 DDL，零迁移脚本） | 用户拍板第 6 条「零 DDL」 | ✅ 无需改动 |
| `src/main/resources/application*.yml` | **不修改**（配置项用代码内默认值 + 环境变量覆盖；v4 新增 `exam.parse.kp-candidates.max/ttl-seconds` 同样走 `@Value` 默认值） | 本 change 决策：避免不必要审批 | ✅ 无需改动 |

> **与上一版的差异（B5）**：上一版把"不做试卷总分/得分统计"写成不包含项，导致 `exam_analysis_result.user_score/total_score` 无处落地、「学习记录」的考试筛选看不到数据。本版改为**写学习记录 + 1 题 1 分制得分**（零 DDL，`exam_question.score/full_score` 首次启用），授权表无新增受保护区域（仍不碰 db/yml）。

## 5. 关键决策摘要

| # | 决策 | 理由（详见 design.md） |
|---|------|----------------------|
| D1 | 上传分段：`POST /papers` 建会话 + `POST /papers/{id}/images` 逐张 + `POST /papers/{id}/submit` 提交解析 | 微信小程序 `uni.uploadFile` 只能单文件，无法一次 multipart 传 8 张；分张上传还能把内存峰值压到 1 张图 |
| D2 | 视觉调用复用 `AgentScopeClient`，内部挂 `DashScopeChatFormatter` 的 vision model 实例（同 apiKey/baseUrl/modelName/endpoint） | AgentScope 视觉链路要求 formatter；文本链路不动，避免回归 |
| D3 | 零 DDL 承载题面：`exam_question.question_content` 存分段纯文本（题干/选项/正确答案/解析），`ExamQuestionTextCodec` 编解码 | `exam_question` 没有 `choices`、`correct_answer` 列；详情页要"与作业答题区一致"就必须有结构化出参，编码是零 DDL 下唯一自洽方案 |
| D4 | 试卷来源错题在 `error_question.question_id` 存 **`-exam_question.id`**（负命名空间） | `question_id` 为 `NOT NULL` 且 `uk_student_question` 需要全局唯一；正数与 `question_bank`/`generated_question` 自增 ID 会碰撞，负值天然不碰撞且可被 `> 0` 条件排除 |
| D5 | 判分逻辑抽 `AnswerGradingService`，`HomeworkServiceImpl` 委托 | 用户要求"判分同现有批改"，抽公共实现才能保证两处口径永不漂移 |
| D6 | 掌握度新增批量入口（同一 Service / 同一公式 / 同一日志字段） | 8 图 ≈ 数十题，逐题调用 `updateMasteryFromResult` 是 N+1 且每知识点多次读改写 |
| D7 | 信号检测异步、试卷结算事务提交之后；**信号调整** 12h 去重 + pending diff 冲突跳过 | 避免与 01:30 `AutoAdjustmentTask` 同日双调；SIG-2 读 `mastery_change_log`，必须等掌握度落库 |
| D8 | OCR 服务保留但不再调用（标注 `@Deprecated`），`ocr_retry_count` 只记整卷重试（0..2） | 零 DDL；删 SDK 依赖属独立变更；列名语义在 design §3.4 钉死 |
| D9 | 接口统一为 `/api/student/exam/papers*`，旧 `/results`、`/result/{id}`、`/result/{id}/grade`、`/task/{id}` 下线（§5.1） | 前端 `api/exam.ts` 路径已过期，一次性收敛，避免第二套契约 |
| D10 | 详情页轮询**详情接口**的 `status`，`GET /tasks/{taskId}` 只服务上传页进度 | 从列表进入详情时拿不到 `taskId`（参数只传 `paperId`） |
| D11 | D_norm 走 `MasteryFormulaUtil.validateDNorm(declared, 同知识点题库历史均值)`；偏离 >0.2 / 无历史 / 非法缺失 → **0.5** | 试卷难度是模型"声明"的，规则 #2 要求与历史均值偏离 >0.2 降级；与现有 `convertDifficultyToDNorm` 兜底一致（B1） |
| D12 | `plan_adjustment_log.action` **不新增 'applied'**，信号调整 12h 去重查 `action='confirmed'` | `applyDiff` 实际写 `confirmed`（手动确认也写 `confirmed`）；改枚举会牵动既有日志与 `PlanDiffServiceTest`（B2） |
| D13 | TX-B 同步写 1 条 `exam_analysis_result`（`status=completed`），得分 **1 题 1 分制** | 规则 #9 要求考试分析结果可回溯；「学习记录」EXAM 筛选读的正是该表（B5） |
| D14 | 整卷重试计数只写 `ocr_retry_count`（0..2，最多重试 2 次）；单图重试仅内存计数 | 避免一列两义导致"重试次数"不可解释（B3） |
| D15 | 全局 LLM 并发 `Semaphore(3)` 同时包住视觉/归因/判分 | 池(3) + CallerRuns + Worker 线程可叠到 6，需要全局闸门，验收口径为"全局峰值 ≤3"（S1） |
| D16 | `paperType` 固定 5 值 `midterm/final/monthly/mock/other`，默认 `midterm`，非法 → `1191` | 前端 picker 已是这 5 值，后端此前无校验（S13） |
| D17 | 重试时库中已有题目 → 跳过解析/归因、直接用库中行判分；判分按 `exam_question.id` 对齐，条数不一致不写 `is_correct` | 避免重解析导致题号漂移、判分结果错配（B7） |
| D18 | 单图业务上限 **9.5MB** + 容器异常映射 `1184` | `multipart.max-file-size: 10MB` 会让"恰好 10MB"先被容器拒（未映射 → 500）（S4） |
| D19 | **v3**：试卷错题**进入**每日作业「原题重做」与「举一反三」（`questionSource="exam"`） | 用户 2026-11-11 明确要求；推翻 B6 方案 (b) |
| D20 | **v3**：ref / `homework_submission_detail.question_id` 用 **`-exam_question.id`**（与 `error_question.question_id` 同值），不用正数 | `uk_plan_question(plan_id, question_id, tenant_id)` 会让正数 exam id 与 bank id 互相覆盖；负值零转换命中 `error_question` |
| D21 | **v3**：`kpErrorCountMap` 在 service 侧 `min(raw, KP_MAX=4)` 后传配额计算器（断言保留） | 试卷错题计入配额分母后，同 kp ≥5 题会触发 `KP_MAX` 硬校验抛异常 → 次日作业失败；clamp 只消异常不改分配 |
| D22 | **v3**：举一反三源题「题库优先 + exam 兜底」，exam 源题适配为**临时 `QuestionBank`（不落库）** | `SimilarQuestionService` 签名零改动；不写题库副本（规则 #10） |
| D23 | **v3**：`completeStageWithCheck` 的 `next_due_date` 改写 `completedDate` | 列是 `DATE NOT NULL`，写 NULL 会在 stage 5 完成时 SQL 报错（exam redo 首次走到该路径） |
| D24 | **v4**：知识点在**视觉解析阶段直出**（`{knowledgePoints}` 候选注入 + Schema `knowledgePointId` + 服务端白名单），`AttributionAgent` 降为兜底 | 用户 2026-11-11 决定；减少一次 LLM 往返、kp 与题面同源；规则 #8 靠白名单兜住 |
| D25 | **v4**：数学记号收紧——允许 `x^2`/`a_n`，禁 `^{...}`/`_{...}` 花括号与反斜杠命令 | captain 2026-11-11 建议；约定 #24 措辞同步（不是全禁 `^`） |
| D26 | **v4b（阻塞）**：判分三态；**标准答案缺失/无法辨识/超时 → `is_correct=NULL`**，只有"学生空 + 答案有"才 `false` | 真实图实测 6 道选择题 4 道无答案；旧契约会误判答对为错、压低掌握度、污染错题本 |
| D27 | **v4b**：kp 候选分级上限——parse 150 / attribution **500**（>500 分片，>1000 记 `kp_unreachable`） | 修 v4 的"截断即不可达"：被 parse 截断的 kp 仍能被兜底命中，保"可归因率 ≥90%"名副其实 |

> 决策编号与 `design.md` §0 的 D1–D27 **完全对齐**（评审要求三处口径一致）。

## 5.1 接口迁移说明（S7）

| 旧接口 | 新接口 | 处理 |
|--------|--------|------|
| `POST /api/student/exam/upload`（多文件/单文件混用） | `POST /papers` + `POST /papers/{id}/images` + `POST /papers/{id}/submit` | 旧接口**下线**（删除方法） |
| `GET /api/student/exam/task/{taskId}` | `GET /api/student/exam/tasks/{taskId}` | **旧 `/task/{taskId}` 下线**；新结构见 design §2.2（含 `paperId`、重试次数、`errorMessage`） |
| `GET /api/student/exam/result/{resultId}` | `GET /api/student/exam/papers/{paperId}` | 旧接口下线 |
| `GET /api/student/exam/results` | `GET /api/student/exam/papers` | 旧接口下线 |
| `POST /api/student/exam/result/{resultId}/grade` | — | **删除**（拍板 #4） |
| 前端 `api/exam.ts` 的 `/analysis/{id}`、`EXAM_UPLOAD_PATH` | 新 6 个函数（design §2.2） | 前端整体重写，不保留兼容层 |

## 6. 风险与缓解

| 风险 | 等级 | 缓解 |
|------|------|------|
| **`qwen3.8-flash` 视觉能力（S6，**已实测通过**）** | ~~阻断~~ → **已解除** | captain 2026-11-11 实测：`POST {AGENT_ENDPOINT}/api/v1/services/aigc/multimodal-generation/generation`，400×140 PNG `H7Q2` → 返回 `H7Q2`、`image_tokens=72`、HTTP 200、`request_id=657f1d71-8262-9101-a04e-612d155f1066`；base64 data URL 可用。降级路径（单图重试 2 次 → `pending_manual_review`）仅作兜底 |
| 视觉模型把学生作答当题干、或漏题/串题 | 高 | Schema 强制 `stem`/`studentAnswer`/`answerStatus` 分离；单图 Schema 校验失败重试 2 次；跨图题号重排 + 题干相似度去重；无法判定的题降级为待人工 |
| 模型自解正确答案出错 → 判分错误 | 中 | choice 用标签字符串比较；非 choice 走 `grading_v1.txt`（要求对照正确答案判）；**v4b：标准答案缺失 → `is_correct=NULL`（不可判定）**，`answerStatus=unreadable` → `NULL`，两者都不更新掌握度、不入错题 |
| **v4b：标准答案缺失被误判为答错（阻塞）** | **高（阻塞）** | 三态契约（D26）：`is_correct=NULL` + 不计掌握度/不入错题 + `unjudged` 统计；`AnswerGradingServiceTest` 四条用例（choice/非 choice × 答案有/无）+ 作业侧 null 处理断言 |
| 8 张图 + base64 导致内存/GC 压力 | 高 | **全局 LLM 并发 ≤3**（`Semaphore(3)` 包住视觉/归因/判分）、逐图生成及时释放、单图 ≤9.5MB、单次上传总量 ≤30MB、前端压缩 + 选图预检；详见 design §11 |
| LLM 慢导致任务堆积 | 中 | 单 task 总预算 180s、单图 ≤60s、Worker 池 3 + 有界队列 + 全局闸门；超时降级不重跑整卷 |
| 负 ID 命名空间被误用于题库查询 | 中 | v3 后负 ID **合法参与 redo/举一反三**：到期查询**不过滤**，改为组装层按 `questionId < 0 → questionSource="exam"` 分流；只有 `selectCriticalErrors` 保留 `question_id > 0` 防御（负 ID 对调整 Agent 无意义）；错题本/详情按 `source_type` 分流展示；隐性约定 #26 v3 扩展 |
| **配额分母含试卷错题 → `KP_MAX` 硬校验抛异常（N1，v3 新解）** | **高（阻塞）** | `selectActiveErrorCountGroupByKp` **不过滤**（试卷错题要占名额）；`HomeworkServiceImpl` 构造 `kpErrorCountMap` 时 `min(raw, KP_MAX=4)` + warn，计算器内断言保留；用例"某 kp 5+ 试卷错题 → 次日生成不失败" |
| **v3：正数 exam id 与题库 id 撞 `uk_plan_question`** | 高 | ref/提交统一用 `-exam_question.id`（负值），与 bank/generated 正整数空间不相交；单测断言同 plan 内 bank=42 与 exam=42 可共存互不覆盖 |
| **v3：exam 源题缺正确答案 → 举一反三空转** | 中 | `correctAnswer` 缺失时跳过该 kp 的举一反三 + 写 `agent_degradation_log`（不阻塞其他 kp）；题面仍可作 redo（**v4b：判分按 §6.1 标准答案缺失 → `is_correct=NULL`，不误判**） |
| **v3：exam redo 挤占当日题量** | 中 | 计入 15 题/日与单 kp 4 题上限；每 kp 每日最多 3 道到期错题（现有 `LIMIT 3`），"大卷同 kp 错 20 题"不会挤爆单日 |
| **v3：stage 到 5 写 `next_due_date=NULL` 违反 NOT NULL** | 中 | `completeStageWithCheck` 改写 `completedDate`（D23）+ 单测，exam redo 首次暴露该既有缺陷 |
| **v4：kp 候选截断率高（种子数据单科目最多 870 > 150）** | 高 | **分级上限（D27）**：parse 150 / attribution 500（>500 分片），被 parse 截断的 kp 仍可由兜底命中；截断率公式 + warn + `truncate_kp_candidates` 降级日志；verify 记录截断率与"含被截断 kp 的可归因率" |
| **v4：Prompt 注入 kp 清单放大体积/延迟** | 中 | 150 条 ≈ 4–5k tokens/图，整卷 +2–4s，仍在 180s 预算内；候选清单一卷只构造 1 次并缓存 10 分钟（§3.7），verify 记录实测耗时 |
| **v4：模型选 kp 准确率不可控** | 高 | verify 取 2 份真实试卷 ≥20 题人工抽样：直出准确率 ≥70%、可归因率（直出+兜底）≥90%；未达标调 max/排序/Prompt 示例后重测 |
| **v4：幻觉 kp ID（规则 #8）** | 中 | 服务端白名单：不在注入集合 → 置空 + `log.warn` + 计入 `unresolvedKp`；Schema 限长 64、禁多 ID 逗号；单测覆盖 |
| **v4：模型仍输出 LaTeX 花括号** | 中 | Prompt 明确禁 `^{...}`/`_{...}` 与反斜杠命令 + `LlmTextSanitizer` 兜底 + `ExamParsePromptContractTest` 固定措辞；样例回归 |
| 试卷结算重复执行导致掌握度/错题/学习记录重复 | 高 | task CAS + `grade_status` CAS + 结算事务原子化 + 学习记录按 `exam_name` 查重；单测覆盖重复消费 |
| 与 01:30 自动调整同日叠加 | 中 | 同目标 **12h 信号调整去重**（查 `action='confirmed'`）；`PLAN_ADJUST_CONFLICT` 捕获后跳过 |
| 学习记录"写了看不到" | 中 | 已核对 `LearningRecordMapper.xml`（要求 `status='completed'`/`analyzed_at`/`user_score`）与 `LearningRecordServiceImpl`（`total_score>0` 才显示正确率、`user_score` 不能空）；顺带修 `getMasteryChangesBySource` 大小写（design §7.6） |
| 列表 `ORDER BY` 无法完全走索引 | 低 | 单学生试卷量级 ≤ 百行，`EXPLAIN` 预期命中 `idx_student_status`（`student_id` 前缀）+ 有界 filesort；在 design §11.6 登记预期与判据 |

## 7. 验收口径（人工可验）

1. 小程序「我的 → 试卷上传」一次选 8 张，全部上传后 60~120s 内出现解析结果；选第 9 张被拒绝并提示"最多 8 张"；单张 >9.5MB 提示"单张不能超过 9.5MB"。
2. 详情页可逐题翻看：题干、选项、我的答案、正确答案、解析、知识点名称；上一题/下一题可用；刷新页面（重新进入）数据仍在。
3. 答错的题进入「错题本」并可查看题面（详情不空白）；答对与答错的题都体现在掌握度看板（同一知识点多条变更日志，`change_type=exam`，`source_id=试卷 ID`，`delta` 有正有负）；**D_norm 降级**：声明难度与同知识点历史均值偏离 >0.2 时按 0.5 参与计算（可由变更日志的 delta 反算验证）。
4. **信号调整去重**：触发 SIG-2 的试卷（某知识点掌握度单次下降 > 0.15）在结算后自动产生一条计划调整记录（`plan_adjustment_log.action=confirmed`），无需学生点按钮；同一目标 12h 内再次触发信号不产生第二条（注意：同学生同科目同日**无法重复上传**，`1131` 已拦截，因此去重验证靠"次日再传/手动触发"场景）。
5. 8 张图解析不是串行到底：日志可见**全局并发峰值 ≤3**；关掉 LLM 后任务在 180s 内以 `failed` 收尾而不是永久挂起；`failed` 卷 `retry` 后若库中已有题目则**不再调用视觉解析**（日志可见）。
6. `GET /papers` 与 `GET /papers/{id}` 的 SQL 条数与 `EXPLAIN` 结果符合 design §11.6 的预期表；`size>50` 被夹到 50。
7. **学习记录可见**：「我的 → 学习记录」的"考试"筛选出现本次试卷（标题"数学期中考试 2026-11-11"、得分 8/10、日期正确），点进详情掌握度变更非空（`exam` 小写口径）。
8. **越权与枚举**：用他人 `taskId` 调 `/tasks/{id}` 返回 `1135`；`paperType=abc` 创建会话返回 `1191`。
9. **视觉探针（V0，已通过）**：captain 2026-11-11 实测 `qwen3.8-flash` 多模态端点返回正确文本（证据见 design §3.1 / tasks A0），本 change 不再受该风险阻断；实现期只需复核 `AgentScopeClient.callVision` 的 `ImageBlock` 与实测 body 等价。
10. **N1 / 配额（v3）**：构造某知识点在同一张试卷错 5+ 题（负 ID、active），次日 `generateDailyHomework` **正常生成作业**（`kpErrorCountMap` 被 clamp，不抛"配额计算失败"），且**该 kp 的 redo 列表里有 `questionSource="exam"` 的题**；`exam_paper.total_score` 与 `exam_analysis_result.total_score` 都等于 `judgedCount`。
11. **试卷错题进原题重做（v3）**：次日的每日作业里出现"考试原题"（题干/选项/我的答案/正确答案/解析与试卷详情一致），提交后按同一 `AnswerGradingService` 判分；答对后「错题本」该题 `current_stage` 1→2（答错回到 1），stage 到 5 完成时不报 SQL 错。
12. **试卷错题驱动举一反三（v3）**：某 kp 只有试卷错题、题库无题时，当日作业仍能生成 1 道举一反三新题（源题来自 `exam_question` 适配），新题内容与原题不同且关联同 kp；源题缺正确答案时该 kp 跳过并留下降级日志。
13. **知识点直出（v4）**：一份 8 张图的试卷解析完成后，`exam_analysis_result.questions.stats` 可见 `kpDirect/kpFallback/kpUnresolved`（+`judged/unjudged`）计数；直出 kp **全部落在注入清单内**（抽查无清单外 ID）；抽取 2 份真实试卷 ≥20 题人工比对：直出准确率 ≥70%、可归因率 ≥90%；截断发生时 `agent_degradation_log` 有 `truncate_kp_candidates` 记录。
14. **数学记号（v4）**：详情页与作业页的题干/选项/答案/解析中**不出现** `^{`、`_{`、反斜杠命令（`\frac`/`\sqrt`/`\vec`）；`x^2`、`x²`、`a_n`、`a(n)` 可正常显示。
15. **判分三态（v4b，阻塞）**：解析后若某选择题缺标准答案，详情页显示"标准答案缺失，待人工判定"，且该题**不更新掌握度、不进错题本**；`exam_analysis_result` 的 `total_score` 只统计已判定题（`NULL` 不计入分母）；学生空白且有答案的题仍判错并进错题本。
16. **kp 兜底可达性（v4b）**：构造"parse 候选 150 截断但 attribution 500 覆盖"的场景，未决题能被兜底归因命中；`stats.truncated` 与截断率出现在日志/JSON；verify 抽样中"可归因率 ≥90%"的分母包含被截断知识点，不允许出现"截断导致不可归因"。
