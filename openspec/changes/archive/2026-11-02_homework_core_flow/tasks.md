# Tasks：每日作业核心流程

> **Change 名称**：`2026-11-02_homework_core_flow`  
> **创建日期**：2026-11-02  
> **状态**：已完成

---

## Task 清单

### T-00：AgentScope 配置与依赖

**任务描述**：
- 在 `pom.xml` 中添加 AgentScope Java SDK 依赖和 JSON Schema Validator 依赖
- 新增 `AgentScopeConfig` 配置类，读取 `application.yml` 中的配置
- `AgentScopeConfig` 中创建 `AgentScopeClient` 作为 `@Bean`（IMP-4: 由 Spring 容器管理，复用连接）
- 创建 `application-local.yml` 本地配置文件（开发环境，**密钥从环境变量读取，严禁明文**）
- 更新 `.gitignore`，排除 `application-local.yml`
- 在 `application.yml` 中添加 AgentScope 配置项（占位符，从环境变量读取）
- 新增 `AgentType` 枚举（IMP-15: 避免魔法字符串）

**涉及文件**：
- `pom.xml`（修改，添加 AgentScope + json-schema-validator 依赖）
- `src/main/java/com/jiang/config/AgentScopeConfig.java`（新增，含 AgentScopeClient @Bean）
- `src/main/java/com/jiang/model/enums/AgentType.java`（新增）
- `src/main/resources/application-local.yml`（新增，不提交到 Git）
- `src/main/resources/application.yml`（修改，添加配置占位符）
- `.gitignore`（修改，排除 application-local.yml）

**预估工作量**：0.5 天

---

### T-01a：新增实体类与 Mapper

**任务描述**：
- 新增 `DailyPlan`、`HomeworkSubmissionDetail`、`GeneratedQuestion`、`AgentDegradationLog` 实体类
- **NEW-1**：`GeneratedQuestion` 实体与 `generated_question` 表对齐（V1 schema 已存在）
- **V1 适配**：`DailyPlan` 使用 `goalId`（非 studentId），`content` JSON 字段（非 questionIds），**无 `@Version` 字段**（BLOCK-A 强调）
- **V1 适配**：`HomeworkSubmissionDetail` 包含 `questionSource`、`questionType`、`submittedAt` 字段
- `DailyPlanMapper.selectByGoalIdAndDate`（通过 goal_id 查询）
- `DailyPlanMapper.updateStatusWithCheck` 带状态机校验（BLOCK-5 调整: V1 无 version，改用 WHERE status = #{expectedStatus}）
- `DailyPlanMapper.updateContentAndStatus` 更新作业内容和状态（IMP-I: 也加 AND status 条件）
- `HomeworkSubmissionDetailMapper` 使用 `insertOrUpdate`（IMP-3: 覆盖语义，含 question_source/question_type/submitted_at 字段）
- `HomeworkSubmissionDetailMapper.selectByPlanId` 按 question_id 去重取最新 submitted_at（IMP-3）
- `GeneratedQuestionMapper` 继承 BaseMapper，使用 BaseMapper.insert
- 编写对应的单元测试（H2 内存库，测试 setup 中执行 CREATE UNIQUE INDEX，BLOCK-C）

**涉及文件**：
- `src/main/java/com/jiang/model/entity/DailyPlan.java`（新增）
- `src/main/java/com/jiang/model/entity/HomeworkSubmissionDetail.java`（新增）
- `src/main/java/com/jiang/model/entity/GeneratedQuestion.java`（新增，NEW-1）
- `src/main/java/com/jiang/model/entity/AgentDegradationLog.java`（新增）
- `src/main/java/com/jiang/mapper/DailyPlanMapper.java`（新增）
- `src/main/java/com/jiang/mapper/HomeworkSubmissionDetailMapper.java`（新增）
- `src/main/java/com/jiang/mapper/GeneratedQuestionMapper.java`（新增，NEW-1）
- `src/main/java/com/jiang/mapper/AgentDegradationLogMapper.java`（新增）
- `src/test/java/com/jiang/mapper/DailyPlanMapperTest.java`（新增）
- `src/test/java/com/jiang/mapper/HomeworkSubmissionDetailMapperTest.java`（新增）

**预估工作量**：1 天

---

### T-01b：V2 DDL 变更脚本（BLOCK-C）

**任务描述**：
- **NEW-2**：编写 V2 DDL 变更脚本，为 `homework_submission_detail` 新增唯一索引 `uk_plan_question (plan_id, question_id, tenant_id)`
- **IMP-O**：脚本命名遵循 Flyway 规范（`V2__homework_uk_index.sql`）
- **BLOCK-C 执行顺序**：本任务需人工审批后 apply 到生产/测试库；T-01a 的 Mapper 单测使用 H2 内存库并在测试 setup 中执行 CREATE UNIQUE INDEX，不依赖本任务的审批进度
- 脚本位于受保护目录 `src/main/resources/db/`，需人工审批

**涉及文件**：
- `src/main/resources/db/V2__homework_uk_index.sql`（新增，受保护需人工审批）

**依赖**：无（可与 T-01a 并行，但生产部署前必须完成）

**预估工作量**：0.25 天

---

### T-02：新增 VO、DTO 与工具类

**任务描述**：
- 新增 `HomeworkVO`（含 questionSource、questionFormatType、taskType 字段，适配 V1 schema）
- 新增 `GradeResultVO`（IMP-9: affectedKnowledgePoints 注释补充「去重」）
- 新增 `SubmitAnswerRequest`（BLOCK-2: 删除 tenantId；IMP-A: 统一命名 taskType 替代 questionType）
- 新增 `KnowledgePointWithMastery`（带掌握度的知识点 DTO）
- **IMP-B**：新增 `DailyPlanContentParser` 工具类（解析/序列化 daily_plan.content JSON）
- **IMP-B**：新增 `DailyPlanQuestionRef` DTO（content JSON 元素：questionId + questionSource + taskType）

**涉及文件**：
- `src/main/java/com/jiang/vo/HomeworkVO.java`（新增）
- `src/main/java/com/jiang/vo/GradeResultVO.java`（新增）
- `src/main/java/com/jiang/dto/SubmitAnswerRequest.java`（新增）
- `src/main/java/com/jiang/dto/KnowledgePointWithMastery.java`（新增）
- `src/main/java/com/jiang/dto/DailyPlanQuestionRef.java`（新增，IMP-B）
- `src/main/java/com/jiang/common/util/DailyPlanContentParser.java`（新增，IMP-B）

**预估工作量**：0.5 天

---

### T-03：实现 Agent 输出校验器

**任务描述**：
- 新增 `AgentOutputValidator` 类，负责校验 Agent 输出是否符合 JSON Schema
- 新增 `JsonSchemaLoader` 工具类，加载 `src/main/resources/prompts/schemas/` 下的 Schema 文件
- 编写单元测试，覆盖校验成功、JSON 解析失败、Schema 校验失败等场景

**涉及文件**：
- `src/main/java/com/jiang/common/util/AgentOutputValidator.java`（新增）
- `src/main/java/com/jiang/common/util/JsonSchemaLoader.java`（新增）
- `src/test/java/com/jiang/common/util/AgentOutputValidatorTest.java`（新增）

**预估工作量**：0.5 天

---

### T-04：实现出题 Agent 调用

**任务描述**：
- 新增 `QuestionGenAgent` 类，封装出题 Agent 调用逻辑
- 构造输入 Prompt（包含知识点列表 + 掌握度状态）
- 注入 `AgentScopeClient`（IMP-4: 由 Spring 容器注入，不手动 new）
- 内部完成 validate → parse 原子流程（IMP-8: Service 只接收最终结果）
- 编写单元测试，mock Agent 调用
- **注意**：重试机制不在 Agent 内部，由 Service 外层实现（IMP-5）

**涉及文件**：
- `src/main/java/com/jiang/agent/QuestionGenAgent.java`（新增）
- `src/test/java/com/jiang/agent/QuestionGenAgentTest.java`（新增）

**依赖**：T-00（AgentScope 配置）、T-03（Agent 输出校验器）

**预估工作量**：1 天

---

### T-05：实现降级策略

**任务描述**：
- 新增 `HomeworkDegradationHandler` 类，处理出题 Agent 降级
- 实现默认计划生成逻辑（按掌握度从低到高，每知识点 1-2 题）
- 降级时记录 `agent_degradation_log`（使用 `AgentType.QUESTION_GEN` 枚举，IMP-15）
- 降级日志写入前做脱敏处理（IMP-7: 截断 + 移除敏感信息）
- **IMP-F**：降级失败时更新 `daily_plan.status = cancelled`，记录日志，抛异常
- 编写单元测试，验证降级逻辑

**涉及文件**：
- `src/main/java/com/jiang/agent/HomeworkDegradationHandler.java`（新增）
- `src/test/java/com/jiang/agent/HomeworkDegradationHandlerTest.java`（新增）

**依赖**：T-00（AgentType 枚举）、T-01（实体类）

**预估工作量**：0.5 天

---

### T-06：实现 HomeworkService 接口

**任务描述**：
- 新增 `HomeworkService` 接口，定义 4 个核心方法：
  - `generateDailyHomework(Long studentId, String tenantId, Long subjectId, LocalDate planDate)`
  - `getHomeworkByDate(Long studentId, String tenantId, Long subjectId, LocalDate planDate)`（NEW-7: 注释「查询指定日期」；NEW-3: 新增 subjectId）
  - `submitAnswer(Long studentId, String tenantId, SubmitAnswerRequest request)`（NEW-4: 接口签名包含 studentId/tenantId）
  - `gradeHomework(Long studentId, String tenantId, Long subjectId, LocalDate planDate)`（OBS-1: 新增 subjectId）
- 编写接口文档注释

**涉及文件**：
- `src/main/java/com/jiang/service/HomeworkService.java`（新增）

**预估工作量**：0.25 天

---

### T-07：实现 generateDailyHomework 方法

**任务描述**：
- 在 `HomeworkServiceImpl` 中实现生成每日作业逻辑
- **V1 适配**：通过 subjectId 调用 `GoalService.getActiveGoal(subjectId)` 获取 goal_id（NEW-3: 复用已有 Service），再通过 goal_id 查询 `daily_plan`
  - OBS-3: `GoalServiceImpl.getActiveGoal` 内部从 `StudentContext` 获取 studentId/tenantId（复用现有约定）；单测需 mock `StudentContext` ThreadLocal（使用 `@MockStatic(StudentContext.class)` 或在 setup 中预设 ThreadLocal）
- **IMP-E**：查询规则：取 status='active' 且 end_date >= 当日 的 goal（多个取最近创建）；无 active goal → 抛 LEARNING_GOAL_NOT_FOUND(1077)
- **状态流转适配 V1**：status=in_progress/completed → 拒绝；status=pending_generation/pending → 允许覆盖
- 查询学生掌握度状态（调用 `MasteryService.getStudentDashboard`）
- 查询已启用知识点列表（调用 `KnowledgePointService.getActiveKnowledgePoints`）
- 构造 Agent 输入参数
- **IMP-5: 重试循环位于 Service 外层**：调用 `QuestionGenAgent.generate`，捕获异常后重试（最多 2 次）
- 重试失败则调用 `HomeworkDegradationHandler`
- **IMP-F**：降级也失败 → 更新 daily_plan.status = cancelled，抛异常
- **NEW-1 落题拆分**：
  - 8a. 生成题（source=generated）→ 写入 `generated_question` 表（daily_plan_id 关联，获取自增主键）
  - 8b. 题库原题（source=bank）→ 沿用 `question_bank.question_id`
  - 8c. 使用 `DailyPlanContentParser.serialize()` 组装题目引用列表，写入 `daily_plan.content` JSON
- 使用 `DailyPlanMapper.updateContentAndStatus` 更新 content + status=pending（IMP-I: 带状态机校验 WHERE status='pending_generation'）
- 添加 `@Transactional(rollbackFor = Exception.class)`
- 编写单元测试，mock 依赖服务

**涉及文件**：
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`（新增）
- `src/test/java/com/jiang/service/HomeworkServiceTest.java`（新增，generateDailyHomework）

**依赖**：T-02（DailyPlanContentParser）、T-04（出题 Agent）、T-05（降级策略）、T-06（Service 接口）

**预估工作量**：2 天（IMP-L: V1 适配复杂度上调）

---

### T-08：实现 getHomeworkByDate 方法

**任务描述**：
- 在 `HomeworkServiceImpl` 中实现查询指定日期作业逻辑
- **V1 适配**：通过 subjectId 调用 `GoalService.getActiveGoal(subjectId)` 获取 goal_id（NEW-3: 复用已有 Service），再通过 goal_id + plan_date 查询 `daily_plan`
- **IMP-B**：使用 `DailyPlanContentParser.parse()` 解析 `daily_plan.content` JSON，获取题目引用列表
- **NEW-1 双来源查询**：按 questionSource 分组，批量查询（IMP-M: 使用 `selectBatchIds` 避免 N+1）：
  - source=bank → `QuestionBankMapper.selectBatchIds(bankIds)`
  - source=generated → `GeneratedQuestionMapper.selectBatchIds(generatedIds)`
- 查询 `homework_submission_detail` 表获取学生作答记录
- 组装 `HomeworkVO` 列表返回（含 questionSource、questionFormatType、taskType 字段）
- 编写单元测试

**涉及文件**：
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`（补充 getHomeworkByDate 方法）
- `src/test/java/com/jiang/service/HomeworkServiceTest.java`（补充测试）

**依赖**：T-02（DailyPlanContentParser）、T-07（Service 实现基础）

**预估工作量**：1 天

---

### T-09：实现 submitAnswer 方法

**任务描述**：
- 在 `HomeworkServiceImpl` 中实现提交答案逻辑
- **OBS-2**: 用 planId 直接查询 daily_plan（`selectById(planId)`），无需 goal_id
- 校验 daily_plan 存在且 status=pending（题目已就绪，V1 适配）
- 校验题目存在且属于当日作业（使用 `DailyPlanContentParser.parse()` 解析 content，校验 questionId 在列表中）
- **BLOCK-2**: studentId/tenantId 从方法参数传入（Controller 已从 StudentContext 提取），不从请求体获取
- **OBS-2 归属校验**: 通过 daily_plan.goal_id 反查 learning_goal，校验 goal.studentId == 当前 studentId（防越权提交他人 plan）
- **V1 适配**：写入 `homework_submission_detail` 时包含 question_source、question_type、submitted_at 字段
- **NEW-6**: 跨层字段映射：`detail.setQuestionType(request.getTaskType())`（SubmitAnswerRequest.taskType → HomeworkSubmissionDetail.questionType，语义均为「任务类型」）
- **IMP-3**: 使用 `insertOrUpdate` 覆盖语义
- 更新 `daily_plan` 状态为 `in_progress`（BLOCK-5 调整: 状态机校验，WHERE status = 'pending'）
- 添加 `@Transactional(rollbackFor = Exception.class)`
- 编写单元测试

**涉及文件**：
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`（补充 submitAnswer 方法）
- `src/test/java/com/jiang/service/HomeworkServiceTest.java`（补充测试）

**依赖**：T-07（Service 实现基础）

**预估工作量**：0.5 天

---

### T-10：实现 gradeHomework 方法

**任务描述**：
- 在 `HomeworkServiceImpl` 中实现批改逻辑
- **OBS-1**: 通过 subjectId 调用 `GoalService.getActiveGoal(subjectId)` 获取 goal_id，再查询 daily_plan（与 generate/getHomework 统一 subjectId 参数）
  - OBS-3: 复用现有约定，GoalService 内部从 StudentContext 获取 studentId
  - 若无 active goal → 抛 LEARNING_GOAL_NOT_FOUND(1077)
- **IMP-2: 幂等性检查**：校验 daily_plan.status
  - status == completed → 抛 HOMEWORK_ALREADY_GRADED（幂等拒绝）
  - status != in_progress → 抛 HOMEWORK_NOT_SUBMITTED（V1 适配）
- 查询当日所有提交记录（按 question_id 去重，取最新 submitted_at）
- 逐题批改（对比标准答案）
- 更新批改结果（is_correct 字段）
- **BLOCK-3**: 收集所有 submissionId 列表（含答对与答错，因公式有 up/down 两套）
- 调用 `MasteryService.updateMasteryFromHomework(studentId, tenantId, submissionIds)` 更新掌握度
- 更新 `daily_plan` 状态为 `completed`（BLOCK-5 调整: 状态机校验，WHERE status = 'in_progress'，失败抛 HOMEWORK_CONCURRENT_CONFLICT）
- 返回 `GradeResultVO`（IMP-9: affectedKnowledgePoints 去重）
- 添加 `@Transactional(rollbackFor = Exception.class)`
- 编写单元测试，验证批改逻辑和掌握度更新调用

**涉及文件**：
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`（补充 gradeHomework 方法）
- `src/test/java/com/jiang/service/HomeworkServiceTest.java`（补充测试）

**依赖**：T-09（提交答案方法）

**预估工作量**：1.25 天

---

### T-11：实现 HomeworkController

**任务描述**：
- 新增 `HomeworkController`，提供 4 个学生端接口：
  - `POST /api/homework/generate?subjectId=1&planDate=2026-11-02`：生成作业（NEW-3: 新增 subjectId 参数）
  - `GET /api/homework/by-date?subjectId=1&planDate=2026-11-02`：查询指定日期作业（IMP-10: 接口名从 /today 改为 /by-date；NEW-3: 新增 subjectId）
  - `POST /api/homework/submit`：提交答案
  - `POST /api/homework/grade?subjectId=1&planDate=2026-11-02`：批改作业（OBS-1: 新增 subjectId）
- 所有接口返回 `Result<T>` 统一响应结构
- **BLOCK-2**: 所有接口的 `studentId`/`tenantId` 均从 `StudentContext`（JWT 上下文）提取，不接受请求参数传入
- 编写 Controller 单元测试

**涉及文件**：
- `src/main/java/com/jiang/controller/HomeworkController.java`（新增）
- `src/test/java/com/jiang/controller/HomeworkControllerTest.java`（新增）

**依赖**：T-06（Service 接口）

**预估工作量**：0.5 天

---

### T-12：集成测试

**任务描述**：
- 编写端到端集成测试，覆盖完整作业流程：
  1. 生成作业
  2. 查询作业列表
  3. 逐题提交答案
  4. 批改作业
  5. 验证掌握度更新
- 验证 Agent 降级场景
- **NEW-5**：验证无 active goal 场景 → 返回 1077 错误码
- **NEW-5**：验证降级失败场景 → daily_plan.status=cancelled
- 验证并发提交场景（BLOCK-5 调整: 状态机校验）
- 验证越权提交场景（身份校验）
- **OBS-4**: 验证 submitAnswer 提交非本人 planId → 拒绝（归属校验）
- **OBS-4**: 验证 gradeHomework 按 subjectId 定位 plan → 正确查询
- **IMP-2**: 验证幂等性（重复批改拒绝，掌握度不重复计算）
- **IMP-11**: Agent 调用通过 Mock `QuestionGenAgent` 或 WireMock 模拟，不触达真实 LLM

**涉及文件**：
- `src/test/java/com/jiang/integration/HomeworkIntegrationTest.java`（新增）

**依赖**：T-11（Controller 实现）

**预估工作量**：1 天

---

### T-13：错误码定义

**任务描述**：
- 在 `ErrorCodes` 枚举中新增作业相关错误码（IMP-14: 采用 1070+ 编号段）：
  - `HOMEWORK_ALREADY_EXISTS(1070)`：当日作业已存在且已提交/已批改
  - `QUESTION_NOT_FOUND(1071)`：题目不存在
  - `STUDENT_IDENTITY_MISMATCH(1072)`：学生身份不匹配（NEW-6: 命名修正）
  - `HOMEWORK_NOT_SUBMITTED(1073)`：作业未提交
  - `HOMEWORK_ALREADY_GRADED(1074)`：作业已批改（幂等拒绝）
  - `AGENT_OUTPUT_INVALID(1075)`：Agent 输出校验失败
  - `HOMEWORK_CONCURRENT_CONFLICT(1076)`：状态机并发冲突（BLOCK-5 调整）
  - `LEARNING_GOAL_NOT_FOUND(1077)`：无有效学习目标（IMP-E 新增）
  - `CONTENT_PARSE_ERROR(1078)`：content JSON 解析失败（NEW-2 新增）
  - `CONTENT_SERIALIZE_ERROR(1079)`：content JSON 序列化失败（NEW-2 新增）
- 在 `BusinessException` 中使用这些错误码

**涉及文件**：
- `src/main/java/com/jiang/common/exception/ErrorCodes.java`（修改）

**预估工作量**：0.25 天

---

### T-14：Prompt 模板与 Schema 文件

**任务描述**：
- 新增出题 Agent 的 Prompt 模板文件：`src/main/resources/prompts/versions/question_gen_v1.txt`
- 新增出题 Agent 的 JSON Schema 文件：`src/main/resources/prompts/schemas/question_gen_schema.json`
- Schema 定义题目结构：题目 ID、题目内容、题型、选项、答案、难度、关联知识点

**涉及文件**：
- `src/main/resources/prompts/versions/question_gen_v1.txt`（新增）
- `src/main/resources/prompts/schemas/question_gen_schema.json`（新增）

**预估工作量**：0.5 天

---

## 依赖关系图

```
T-00（AgentScope 配置 + AgentType 枚举）
  ↓
T-01a（实体类 + Mapper）← NEW-1, BLOCK-A
T-01b（V2 DDL 脚本）← NEW-2, BLOCK-C
  ⇅ （并行，无依赖关系；T-01a 测试用 H2 自带索引，不依赖 T-01b 审批）
  ↓
T-02（VO + DTO + DailyPlanContentParser）← IMP-A, IMP-B
  ↓
T-03（Agent 输出校验器）
  ↓
T-04（出题 Agent 调用）← T-00, T-03
  ↓
T-05（降级策略）← T-00, T-01a ← IMP-F
  ↓
T-06（Service 接口）← NEW-4
  ↓
T-07（generateDailyHomework）← T-02, T-04, T-05, T-06 ← IMP-E, IMP-F, IMP-L
  ↓
T-08（getHomeworkByDate）← T-02, T-07 ← IMP-B, IMP-M
  ↓
T-09（submitAnswer）← T-02, T-07 ← V1 适配, BLOCK-5 调整
  ↓
T-10（gradeHomework）← T-09 ← V1 适配, BLOCK-5 调整, IMP-D, IMP-G
  ↓
T-11（Controller）← T-06
  ↓
T-12（集成测试）← T-11 ← BLOCK-5 调整
  ↓
T-13（错误码）← NEW-6, IMP-E(1077)
  ↓
T-14（Prompt + Schema）← IMP-C
```

---

## 工作量汇总

| Task | 预估工作量 |
|------|----------|
| T-00 | 0.5 天 |
| T-01a | 1 天 |
| T-01b | 0.25 天 |
| T-02 | 0.5 天 |
| T-03 | 0.5 天 |
| T-04 | 1 天 |
| T-05 | 0.5 天 |
| T-06 | 0.25 天 |
| T-07 | 2 天 |
| T-08 | 1 天 |
| T-09 | 0.5 天 |
| T-10 | 1.25 天 |
| T-11 | 0.5 天 |
| T-12 | 1 天 |
| T-13 | 0.25 天 |
| T-14 | 0.5 天 |
| **总计** | **12.5 天** |

---

## 执行顺序建议

**第一阶段（基础设施）**：
- T-00 → T-01a → T-02 → T-13 → T-14
- T-01b（与 T-01a 并行，DDL 脚本编写 + 人工审批）

**第二阶段（Agent 层）**：
- T-03 → T-04 → T-05

**第三阶段（Service 层）**：
- T-06 → T-07 → T-08 → T-09 → T-10

**第四阶段（Controller + 测试）**：
- T-11 → T-12

---

**任务制定人**：AI Agent  
**审核状态**：待人工审核
