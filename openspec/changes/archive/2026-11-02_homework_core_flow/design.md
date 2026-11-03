# Design：每日作业核心流程

> **Change 名称**：`2026-11-02_homework_core_flow`  
> **创建日期**：2026-11-02  
> **状态**：草案

---

## 1. 技术方案概述

本次 Change 实现每日作业核心流程，包括：
1. 出题 Agent 调用与结构化输出校验
2. 每日作业生成与存储
3. 作业下发接口
4. 学生作答提交
5. 自动批改
6. 掌握度更新

核心技术方案：
- **Agent 调用**：使用 AgentScope Java 框架，构造输入参数，调用 LLM 生成题目
- **结构化校验**：使用 JSON Schema 校验 Agent 输出，失败重试（最多 2 次）
- **降级策略**：重试失败后返回默认计划，记录降级日志
- **事务管理**：Service 层使用 `@Transactional(rollbackFor = Exception.class)` 管理事务边界

---

## 1.1 技术栈说明

### 后端技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行时环境 |
| Spring Boot | 3.2.x | Web 框架 |
| MyBatis-Plus | 3.5.x | ORM 框架，数据访问层 |
| MySQL | 5.7+ | 关系型数据库 |
| Maven | 3.x | 构建工具 |

### Agent 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| AgentScope Java | 待确定 | AI Agent 框架，封装 LLM 调用 |
| JSON Schema Validator | 待确定 | 校验 Agent 输出的 JSON 结构 |
| Jackson | 2.x | JSON 序列化/反序列化 |

### 测试技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| JUnit | 5.x | 单元测试框架 |
| Mockito | 5.x | Mock 框架，隔离依赖 |
| H2 Database | 2.x | 内存数据库，用于集成测试 |

### 关键技术选型说明

1. **AgentScope Java**：项目已确定使用 AgentScope Java 作为 Agent 框架，嵌入 Spring Boot 应用中。本次 Change 需要实现 `QuestionGenAgent`，调用 AgentScope SDK 与 LLM 交互。

2. **JSON Schema 校验**：Agent 输出的 JSON 必须符合预定义 Schema。推荐使用 `com.networknt:json-schema-validator` 库（Java 17 兼容），或 Apache Commons Validator。

3. **MyBatis-Plus 批量插入**：提交答案时需要批量插入 `homework_submission_detail` 记录，使用 MyBatis-Plus 的 `saveBatch` 方法或自定义批量插入 SQL。

4. **状态机并发控制**：`daily_plan` 表无 `version` 字段（V1 schema），并发更新通过状态机校验（`UPDATE WHERE status = #{expectedStatus}`）防止冲突。

5. **事务传播行为**：Service 层事务方法使用默认的 `REQUIRED` 传播行为，确保批改和掌握度更新在同一事务内完成。

---

## 1.2 AgentScope 配置

### 大模型配置

本次 Change 使用 AgentScope Java SDK 调用 LLM，需要配置以下参数：

| 配置项 | 值 | 说明 |
|--------|-----|------|
| model | deepseek-v4.1-flash | 大模型名称 |
| api-key | ${AGENT_API_KEY} | API 密钥（**敏感信息，严禁明文记录**） |
| endpoint | ${AGENT_ENDPOINT} | API 端点 |

> **⚠️ 敏感信息管理规范**：
> - 所有密钥、Token 等敏感信息**仅通过环境变量或密钥管理服务注入**
> - **严禁**出现在任何工件文件（proposal/design/tasks）、配置模板、日志、代码注释中
> - 开发环境使用 `application-local.yml`（已加入 `.gitignore`，不提交版本库）
> - 生产环境使用环境变量或 K8s Secret / 云密钥管理服务

### 配置管理策略

**开发环境**：使用本地配置文件 `application-local.yml`（不提交到 Git）

```yaml
# src/main/resources/application-local.yml
agent:
  agentscope:
    model: deepseek-v4.1-flash
    api-key: ${AGENT_API_KEY}  # 从环境变量读取，严禁明文
    endpoint: ${AGENT_ENDPOINT}  # 从环境变量读取
    timeout: 30s  # Agent 调用超时时间
    max-retries: 2  # 最大重试次数
```

本地启动前设置环境变量：
```bash
export AGENT_API_KEY=<your-api-key>
export AGENT_ENDPOINT=<your-endpoint>
```

**生产环境**：使用环境变量注入

```yaml
# src/main/resources/application.yml
agent:
  agentscope:
    model: ${AGENT_MODEL:deepseek-v4.1-flash}
    api-key: ${AGENT_API_KEY:}  # 必须通过环境变量提供
    endpoint: ${AGENT_ENDPOINT:}
    timeout: ${AGENT_TIMEOUT:30s}
    max-retries: ${AGENT_MAX_RETRIES:2}
```

启动命令：
```bash
export AGENT_API_KEY=<your-api-key>
export AGENT_ENDPOINT=<your-endpoint>
java -jar target/studyplan-1.0.0.jar
```

### .gitignore 配置

确保 `application-local.yml` 不被提交到 Git：

```gitignore
# 本地配置文件（包含敏感信息）
src/main/resources/application-local.yml
```

### AgentScope Java SDK 集成

**依赖配置**（pom.xml）：

```xml
<dependency>
    <groupId>com.agentscope</groupId>
    <artifactId>agentscope-java-spring-boot-starter</artifactId>
    <version>待确定</version>
</dependency>
```

**配置类**：

```java
@Configuration
@ConfigurationProperties(prefix = "agent.agentscope")
public class AgentScopeConfig {
    private String model;
    private String apiKey;
    private String endpoint;
    private Duration timeout = Duration.ofSeconds(30);
    private int maxRetries = 2;
    
    // getters and setters
}
```

**QuestionGenAgent 使用示例**：

```java
@Component
public class QuestionGenAgent {
    
    // IMP-4: AgentScopeClient 由 Spring 容器管理，复用连接
    @Autowired
    private AgentScopeClient agentScopeClient;
    
    @Autowired
    private AgentOutputValidator validator;
    
    @Autowired
    private PromptTemplateLoader promptLoader;
    
    /**
     * 生成每日作业题目
     * IMP-8: 内部完成 validate → parse 原子流程，Service 只接收最终结果
     * 
     * @param knowledgePoints 知识点列表（含掌握度）
     * @param questionCount 题目数量（默认 15）
     * @return Agent 生成的题目列表（已校验通过）
     */
    public List<GeneratedQuestion> generate(
            List<KnowledgePointWithMastery> knowledgePoints,
            int questionCount) {
        // 1. 构造 Prompt
        String prompt = buildPrompt(knowledgePoints, questionCount);
        
        // 2. 调用 AgentScope SDK
        String agentOutput = agentScopeClient.call(prompt);
        
        // 3. 校验 + 解析（原子流程）
        return validateAndParse(agentOutput);
    }
    
    private List<GeneratedQuestion> validateAndParse(String agentOutput) {
        ValidationResult result = validator.validate(agentOutput, "question_gen_schema.json");
        if (!result.isSuccess()) {
            throw new BusinessException(ErrorCodes.AGENT_OUTPUT_INVALID, result.getMessage());
        }
        return parseOutput(agentOutput);
    }
}
```

> **IMP-4 说明**：`AgentScopeClient` 作为 `@Bean` 由 `AgentScopeConfig` 创建并注入，不在 Agent 内部手动 new。

---

## 2. 分层设计

### 2.1 Controller 层

新增 `HomeworkController`，提供学生端接口：

> **BLOCK-2 修复**：所有接口的 `studentId` / `tenantId` 均从 JWT 上下文（`StudentContext`）提取，不接受客户端参数传入，防止伪造租户。

```java
@RestController
@RequestMapping("/api/homework")
public class HomeworkController {
    
    @Autowired
    private HomeworkService homeworkService;
    
    /**
     * 生成每日作业
     * POST /api/homework/generate?subjectId=1&planDate=2026-11-02
     * NEW-3: 新增 subjectId 参数，用于调用 GoalService.getActiveGoal(subjectId)
     */
    @PostMapping("/generate")
    public Result<Void> generateHomework(@RequestParam Long subjectId,
                                         @RequestParam LocalDate planDate) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        homeworkService.generateDailyHomework(studentId, tenantId, subjectId, planDate);
        return Result.success();
    }
    
    /**
     * 查询指定日期作业列表
     * IMP-10: 接口名改为 /by-date，避免 today 语义误导
     * NEW-3: 新增 subjectId 参数，用于定位 goal_id
     * GET /api/homework/by-date?subjectId=1&planDate=2026-11-02
     */
    @GetMapping("/by-date")
    public Result<List<HomeworkVO>> getHomeworkByDate(
            @RequestParam Long subjectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        List<HomeworkVO> homework = homeworkService.getHomeworkByDate(studentId, tenantId, subjectId, planDate);
        return Result.success(homework);
    }
    
    /**
     * 提交单题答案
     * POST /api/homework/submit
     * BLOCK-2: SubmitAnswerRequest 不含 tenantId，从 StudentContext 获取
     */
    @PostMapping("/submit")
    public Result<Void> submitAnswer(@RequestBody SubmitAnswerRequest request) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        homeworkService.submitAnswer(studentId, tenantId, request);
        return Result.success();
    }
    
    /**
     * 批改作业
     * OBS-1: 新增 subjectId 参数，用于定位 goal_id → daily_plan
     * POST /api/homework/grade?subjectId=1&planDate=2026-11-02
     */
    @PostMapping("/grade")
    public Result<GradeResultVO> gradeHomework(@RequestParam Long subjectId,
                                               @RequestParam LocalDate planDate) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        GradeResultVO result = homeworkService.gradeHomework(studentId, tenantId, subjectId, planDate);
        return Result.success(result);
    }
}
```

### 2.2 Service 层

新增 `HomeworkService` 接口与 `HomeworkServiceImpl` 实现：

```java
public interface HomeworkService {
    
    /**
     * 生成每日作业
     * NEW-3: 新增 subjectId 参数，用于调用 GoalService.getActiveGoal(subjectId)
     * 
     * @param studentId 学生 ID（来自 StudentContext）
     * @param tenantId 租户 ID（来自 StudentContext）
     * @param subjectId 科目 ID（来自请求参数）
     * @param planDate 计划日期
     */
    void generateDailyHomework(Long studentId, String tenantId, Long subjectId, LocalDate planDate);
    
    /**
     * 查询指定日期作业列表（NEW-7: 注释已修正）
     * NEW-3: 新增 subjectId 参数
     */
    List<HomeworkVO> getHomeworkByDate(Long studentId, String tenantId, Long subjectId, LocalDate planDate);
    
    /**
     * 提交单题答案
     * NEW-4: 接口签名包含 studentId/tenantId，与 Controller 调用对齐
     */
    void submitAnswer(Long studentId, String tenantId, SubmitAnswerRequest request);
    
    /**
     * 批改作业
     * OBS-1: 新增 subjectId 参数
     */
    GradeResultVO gradeHomework(Long studentId, String tenantId, Long subjectId, LocalDate planDate);
}
```

**核心实现逻辑**：

> **⚠️ V1 Schema 适配说明**：
> - `daily_plan` 表使用 `goal_id`（非 `student_id`），通过 studentId → learning_goal → goal_id 关联
> - `daily_plan.content` 为 JSON 字段，存储题目引用列表（非 `question_ids` 列）
> - `daily_plan` 表无 `version` 字段，乐观锁改用状态机校验（BLOCK-5 调整）
> - `daily_plan.status` 枚举：`pending_generation` / `pending` / `in_progress` / `completed` / `cancelled`
> - `homework_submission_detail` 包含 `question_source`（bank/generated）、`question_type`、`submitted_at` 字段
> - `generated_question` 表已存在（V1 schema），主键 `generated_question_id` 自增

> **BLOCK-B: V1 关键列类型参考**（源自 `src/main/resources/db/V1__init_schema.sql`）：
>
> | 表名 | 主键 | 类型 | 外键/关联列 | 类型 |
> |------|-------|------|------------|------|
> | `daily_plan` | `plan_id` | BIGINT AUTO_INCREMENT | `goal_id` | BIGINT NOT NULL |
> | `homework_submission_detail` | `submission_id` | BIGINT AUTO_INCREMENT | `plan_id` / `question_id` | BIGINT NOT NULL |
> | `generated_question` | `generated_question_id` | BIGINT AUTO_INCREMENT | `daily_plan_id` / `source_question_id` | BIGINT |
> | `question_bank` | `question_id` | BIGINT AUTO_INCREMENT | `knowledge_point_id` | VARCHAR(64) |
> | `learning_goal` | `goal_id` | BIGINT AUTO_INCREMENT | `student_id` / `subject_id` | BIGINT NOT NULL |
>
> 所有主键均为 `BIGINT AUTO_INCREMENT`，Java 侧统一使用 `Long` + `@TableId(type = IdType.AUTO)`。

#### generateDailyHomework 方法

> **IMP-1 修复**：明确覆盖规则。
> **IMP-5 修复**：重试循环位于 Service 外层。
> **NEW-1 修复**：生成题落 `generated_question` 表，题目引用存入 `daily_plan.content` JSON。

```
1. 通过 subjectId 调用 `GoalService.getActiveGoal(subjectId)` 获取 active goal（NEW-3: 已有 Service 方法）
   - OBS-3: 现有实现中 `GoalServiceImpl.getActiveGoal` 内部从 `StudentContext` 获取 studentId/tenantId（已有约定，复用）
   - 若无 active goal → GoalService 返回 null → 抛 LEARNING_GOAL_NOT_FOUND(1077)
   - 从返回的 GoalVO 中提取 goalId
2. 查询 daily_plan WHERE goal_id + plan_date + tenant_id
   a. 存在且 status=in_progress/completed → 抛 HOMEWORK_ALREADY_EXISTS，拒绝
   b. 存在且 status=pending_generation/pending → 允许覆盖，记录日志，继续
   c. 不存在 → 创建 daily_plan（status=pending_generation）
3. 查询学生当前掌握度状态（调用 MasteryService.getStudentDashboard）
4. 查询已启用知识点列表（调用 KnowledgePointService）
5. 构造 Agent 输入参数
6. 重试循环（最多 2 次）：
   a. 调用 QuestionGenAgent.generate（内部已含 validate + parse）
   b. 成功 → 跳出循环
   c. 抛 AgentOutputInvalidException → 记录日志，重试
7. 重试耗尽 → 调用 HomeworkDegradationHandler 生成默认计划
   - IMP-F: 若降级也失败（抛异常）→ 更新 daily_plan.status = cancelled，记录日志，抛 BusinessException
8. 落题：
   8a. 生成题（source=generated）→ 写入 generated_question 表（daily_plan_id 关联，获取自增主键）
   8b. 题库原题（source=bank）→ 沿用 question_bank.question_id
   8c. 组装题目引用列表，写入 daily_plan.content JSON：
       [{"questionId":1,"questionSource":"bank","taskType":"review"},
        {"questionId":2,"questionSource":"generated","taskType":"similar"}]
9. 更新 daily_plan.status 为 pending（题目已就绪，待学生作答）
```

#### submitAnswer 方法

> **IMP-3 修复**：明确语义为「覆盖」。
> **NEW-4 修复**：接口签名包含 studentId/tenantId 参数。

```
1. 用 planId 直接查询 daily_plan（OBS-2: selectById(planId)，无需 goal_id）
   - 校验 daily_plan 存在且 status=pending（题目已就绪）
2. 校验题目存在且属于当日作业（通过 daily_plan.content 中的题目列表校验）
3. 校验学生身份 + plan 归属（OBS-2 补充）：
   a. studentId 与 JWT 一致
   b. 通过 daily_plan.goal_id 反查 learning_goal，校验 goal.studentId == 当前 studentId（防越权提交他人 plan）
4. 写入 homework_submission_detail（含 question_source、question_type、submitted_at 字段）：
   - 若该 (plan_id, question_id, tenant_id) 已存在记录 → UPDATE（覆盖）
   - 否则 → INSERT
   - NEW-1: 跨层字段映射：`detail.setQuestionType(request.getTaskType())`（SubmitAnswerRequest.taskType → HomeworkSubmissionDetail.questionType，语义均为「任务类型」，实体层因 V1 DB 列名限制保留 questionType 命名）
5. 更新 daily_plan 状态为 in_progress（学生作答中）
```

#### gradeHomework 方法

> **BLOCK-3 修复**：收集 submissionId 列表，答对答错均更新掌握度。
> **IMP-2 修复**：增加幂等性检查。

```
1. 通过 subjectId 调用 `GoalService.getActiveGoal(subjectId)` 获取 goal_id（OBS-1: 与 generate/getHomework 统一 subjectId 参数）
   - OBS-3: 复用现有约定，GoalService 内部从 StudentContext 获取 studentId
   - 若无 active goal → 抛 LEARNING_GOAL_NOT_FOUND(1077)
2. 查询 daily_plan WHERE goal_id + plan_date + tenant_id
   a. status == completed → 抛 HOMEWORK_ALREADY_GRADED（幂等拒绝）
   b. status != in_progress → 抛 HOMEWORK_NOT_SUBMITTED
3. 查询当日所有提交记录（按 question_id 去重，取最新 submitted_at）
4. 逐题批改（对比标准答案）：
   - IMP-D: bank 题从 question_bank.answer 取标准答案
   - IMP-D: generated 题从 generated_question.content JSON 内的 answer 字段取（见 IMP-C 结构定义）
5. 更新批改结果（is_correct 字段）
6. 收集本次所有 submissionId 列表（含答对与答错，因公式有 up/down 两套）
7. 调用 MasteryService.updateMasteryFromHomework(studentId, tenantId, submissionIds)
8. 更新 daily_plan 状态为 completed
   - IMP-G: gradeHomework 与 MasteryService.updateMasteryFromHomework 必须在同一事务内（REQUIRED 传播），任一步失败整体回滚
9. 返回 GradeResultVO（affectedKnowledgePoints 为去重后的知识点 ID 列表，IMP-9）
```

### 2.3 Agent 层

> **NEW-3 修复**：删除与 §1.2 重复的示例代码，统一引用 §1.2 的最终实现版本。

`QuestionGenAgent` 的完整实现参见 §1.2 示例。核心要点：
- `AgentScopeClient` 由 Spring 容器注入（IMP-4）
- 内部完成 validate → parse 原子流程（IMP-8）
- 重试机制不在 Agent 内部，由 Service 外层实现（IMP-5）

### 2.4 Agent 输出校验

新增 `AgentOutputValidator`，负责校验 Agent 输出是否符合 Schema：

```java
@Component
public class AgentOutputValidator {
    
    @Autowired
    private JsonSchemaLoader schemaLoader;
    
    /**
     * 校验 Agent 输出
     * 
     * @param agentOutput Agent 返回的 JSON 字符串
     * @param schemaName Schema 文件名（如 "question_gen_schema.json"）
     * @return 校验结果
     */
    public ValidationResult validate(String agentOutput, String schemaName) {
        // 1. 加载 Schema
        JsonSchema schema = schemaLoader.load(schemaName);
        
        // 2. 解析 JSON
        JsonNode jsonNode;
        try {
            jsonNode = new ObjectMapper().readTree(agentOutput);
        } catch (JsonProcessingException e) {
            return ValidationResult.fail("JSON 解析失败: " + e.getMessage());
        }
        
        // 3. 校验 Schema
        Set<ValidationMessage> errors = schema.validate(jsonNode);
        if (errors.isEmpty()) {
            return ValidationResult.success();
        } else {
            return ValidationResult.fail(errors.toString());
        }
    }
}
```

### 2.5 DAO 层

新增 `DailyPlanMapper`、`HomeworkSubmissionDetailMapper`、`GeneratedQuestionMapper`：

> **BLOCK-5 调整**：V1 schema 中 `daily_plan` 无 `version` 字段，乐观锁改为状态机校验（UPDATE WHERE status = expected_status）。
> **NEW-2 修复**：`homework_submission_detail` 需新增唯一索引 `uk_plan_question`，支持 `ON DUPLICATE KEY UPDATE` 覆盖语义。

```java
@Mapper
public interface DailyPlanMapper extends BaseMapper<DailyPlan> {
    
    /**
     * 查询指定日期作业计划（适配 V1: 通过 goal_id 查询）
     */
    @Select("SELECT plan_id, goal_id, plan_date, content, status, " +
            "tenant_id, create_time, update_time " +
            "FROM daily_plan " +
            "WHERE goal_id = #{goalId} AND tenant_id = #{tenantId} " +
            "AND plan_date = #{planDate}")
    DailyPlan selectByGoalIdAndDate(@Param("goalId") Long goalId,
                                    @Param("tenantId") String tenantId,
                                    @Param("planDate") LocalDate planDate);
    
    // 使用 BaseMapper.insert（自动处理 IdType.AUTO）
    
    /**
     * 更新作业状态（BLOCK-5 调整: 状态机校验替代乐观锁）
     * V1 无 version 字段，通过 WHERE status = #{expectedStatus} 防止并发冲突
     */
    @Update("UPDATE daily_plan SET status = #{newStatus}, update_time = NOW() " +
            "WHERE plan_id = #{planId} AND tenant_id = #{tenantId} " +
            "AND status = #{expectedStatus}")
    int updateStatusWithCheck(@Param("planId") Long planId,
                             @Param("newStatus") String newStatus,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("tenantId") String tenantId);
    
    /**
     * 更新作业内容（题目引用 JSON）
     * IMP-I: 也加状态机校验，仅在 pending_generation 状态允许更新
     */
    @Update("UPDATE daily_plan SET content = #{content}, status = #{newStatus}, " +
            "update_time = NOW() " +
            "WHERE plan_id = #{planId} AND tenant_id = #{tenantId} " +
            "AND status = #{expectedStatus}")
    int updateContentAndStatus(@Param("planId") Long planId,
                               @Param("content") String content,
                               @Param("newStatus") String newStatus,
                               @Param("expectedStatus") String expectedStatus,
                               @Param("tenantId") String tenantId);
}

@Mapper
public interface HomeworkSubmissionDetailMapper extends BaseMapper<HomeworkSubmissionDetail> {
    
    /**
     * 插入或更新提交记录（IMP-3: 覆盖语义，基于唯一约束 uk_plan_question）
     * 适配 V1: 包含 question_source、question_type、submitted_at 字段
     */
    @Insert("INSERT INTO homework_submission_detail " +
            "(plan_id, question_id, question_source, question_type, " +
            "student_answer, is_correct, knowledge_point_id, submitted_at, " +
            "tenant_id, create_time, update_time) " +
            "VALUES (#{planId}, #{questionId}, #{questionSource}, #{questionType}, " +
            "#{studentAnswer}, #{isCorrect}, #{knowledgePointId}, #{submittedAt}, " +
            "#{tenantId}, #{createTime}, #{updateTime}) " +
            "ON DUPLICATE KEY UPDATE " +
            "student_answer = VALUES(student_answer), " +
            "is_correct = VALUES(is_correct), " +
            "submitted_at = VALUES(submitted_at), " +
            "update_time = VALUES(update_time)")
    int insertOrUpdate(HomeworkSubmissionDetail submission);
    
    /**
     * 查询当日提交记录（按 question_id 去重，取最新 submitted_at）
     */
    @Select("SELECT s1.submission_id, s1.plan_id, s1.question_id, s1.question_source, " +
            "s1.question_type, s1.student_answer, s1.is_correct, " +
            "s1.knowledge_point_id, s1.submitted_at, " +
            "s1.tenant_id, s1.create_time, s1.update_time " +
            "FROM homework_submission_detail s1 " +
            "INNER JOIN (" +
            "  SELECT question_id, MAX(submission_id) AS max_id " +
            "  FROM homework_submission_detail " +
            "  WHERE plan_id = #{planId} AND tenant_id = #{tenantId} " +
            "  GROUP BY question_id" +
            ") s2 ON s1.submission_id = s2.max_id")
    List<HomeworkSubmissionDetail> selectByPlanId(@Param("planId") Long planId,
                                                  @Param("tenantId") String tenantId);
}

@Mapper
public interface GeneratedQuestionMapper extends BaseMapper<GeneratedQuestion> {
    // 使用 BaseMapper.insert 插入生成题，自动获取自增主键
    // 使用 BaseMapper.selectBatchIds 按 ID 批量查询
}
```

> **NEW-2 DDL 变更**：`homework_submission_detail` 需新增唯一索引，支持 `ON DUPLICATE KEY UPDATE`：
> ```sql
> -- V2 变更脚本（需人工审批）
> ALTER TABLE homework_submission_detail
>   ADD UNIQUE INDEX uk_plan_question (plan_id, question_id, tenant_id);
> ```
> 此索引在 V1 schema 中**不存在**，需本次 Change 补充。

### 2.6 Model 层

新增实体类：

> **BLOCK-4 修复**：`daily_plan.content` JSON 字段存储题目引用数组，携带来源标识。
> **IMP-15 修复**：新增 `AgentType` 枚举，避免魔法字符串。

```java
/**
 * 适配 V1 schema：daily_plan 表字段对齐
 */
@Data
@TableName("daily_plan")
public class DailyPlan {
    @TableId(type = IdType.AUTO)
    private Long planId;
    
    // V1: 通过 goal_id 关联学生（非 student_id）
    private Long goalId;
    private LocalDate planDate;
    
    /**
     * V1 status 枚举：pending_generation / pending / in_progress / completed / cancelled
     * 状态流转：
     *   pending_generation → pending（出题成功）
     *   pending_generation → cancelled（出题失败且降级失败）
     *   pending → in_progress（学生开始作答）
     *   in_progress → completed（批改完成）
     */
    private String status;
    
    /**
     * V1: content 为 JSON 字段，存储题目引用列表
     * 格式: [{"questionId":1,"questionSource":"bank","taskType":"review"},
     *        {"questionId":2,"questionSource":"generated","taskType":"similar"}]
     * BLOCK-4: 题目带来源标识
     */
    private String content;
    
    private String tenantId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    // V1 无 version 字段，乐观锁改用状态机校验
}

/**
 * 适配 V1 schema：homework_submission_detail 表字段对齐
 */
@Data
@TableName("homework_submission_detail")
public class HomeworkSubmissionDetail {
    @TableId(type = IdType.AUTO)
    private Long submissionId;
    
    private Long planId;
    private Long questionId;
    // V1 新增字段
    private String questionSource; // bank / generated
    // IMP-A: V1 列名为 question_type，语义为「任务类型」
    // 实体层保留 questionType 与 DB 列对齐，VO/DTO 层统一用 taskType
    private String questionType; // review / redo / similar / comprehensive
    private String studentAnswer;
    private Boolean isCorrect;
    private String knowledgePointId;
    private LocalDateTime submittedAt; // V1: 提交时间
    
    private String tenantId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    
    // NEW-2: 唯一约束 uk_plan_question (plan_id, question_id, tenant_id)
    // 需通过 V2 DDL 变更脚本补充
}

/**
 * NEW-1: GeneratedQuestion 实体定义，与 generated_question 表对齐
 */
@Data
@TableName("generated_question")
public class GeneratedQuestion {
    @TableId(type = IdType.AUTO)
    private Long generatedQuestionId;
    
    private Long dailyPlanId; // 所属每日计划 ID
    private String knowledgePointId; // 关联知识点
    private Long sourceQuestionId; // 触发生成的原错题 ID（可为空）
    
    /**
     * 题目内容（JSON 格式）
     * IMP-C: 结构定义与 question_gen_schema.json 对齐
     * JSON 内部结构示例：
     * {
     *   "stem": "题干文本",
     *   "choices": [{"label":"A","text":"..."}, ...],  // 仅 choice 题型
     *   "answer": "标准答案",
     *   "analysis": "解析文本",
     *   "formatType": "choice" | "numeric" | "short" | "self",
     *   "difficulty": "basic" | "standard" | "challenge"
     * }
     */
    private String content;
    
    private String difficulty; // basic / standard / challenge
    private Boolean similarityChecked; // 是否通过相似度校验
    
    private String tenantId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

@Data
@TableName("agent_degradation_log")
public class AgentDegradationLog {
    @TableId(type = IdType.AUTO)
    private Long logId;
    
    // IMP-15: 使用枚举替代字符串硬编码
    private String agentType; // 取值来自 AgentType 枚举
    private String inputSummary;
    private String failureReason;
    private String degradationAction; // return_default, skip, retry_exhausted
    
    private String tenantId;
    private LocalDateTime createTime;
}

/**
 * IMP-B: daily_plan.content JSON 解析工具类
 * 统一封装 JSON 解析逻辑，避免各处重复用 Jackson 解析
 */
@Component
public class DailyPlanContentParser {
    
    /**
     * 解析 content JSON 为题目引用列表
     * NEW-2: 内部捕获 JsonProcessingException，包装为 BusinessException(CONTENT_PARSE_ERROR)
     */
    public List<DailyPlanQuestionRef> parse(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) return List.of();
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(contentJson,
                new TypeReference<List<DailyPlanQuestionRef>>() {});
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.CONTENT_PARSE_ERROR,
                "daily_plan.content JSON 解析失败: " + e.getMessage());
        }
    }
    
    /**
     * 序列化为 JSON 字符串
     * NEW-2: 内部捕获 JsonProcessingException，包装为 BusinessException(CONTENT_SERIALIZE_ERROR)
     */
    public String serialize(List<DailyPlanQuestionRef> refs) {
        try {
            return new ObjectMapper().writeValueAsString(refs);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.CONTENT_SERIALIZE_ERROR,
                "daily_plan.content JSON 序列化失败: " + e.getMessage());
        }
    }
}

/**
 * IMP-B: daily_plan.content JSON 元素 DTO
 */
@Data
public class DailyPlanQuestionRef {
    private Long questionId;
    private String questionSource; // bank / generated
    private String taskType; // review / redo / similar / comprehensive
}

/**
 * IMP-15: Agent 类型枚举
 */
public enum AgentType {
    QUESTION_GEN("question_gen"),
    ATTRIBUTION("attribution"),
    PLANNING("planning"),
    ADJUSTMENT("adjustment");
    
    private final String code;
    AgentType(String code) { this.code = code; }
    public String getCode() { return code; }
}
```

> **IMP-7 脱敏规则**：`AgentDegradationLog` 写入前必须对 `inputSummary` 和 `failureReason` 做脱敏处理：
> - 截断至最大 500 字符
> - 移除 API Key、学生标识（studentId）、原始 LLM 输出等敏感信息
> - 异常栈仅保留异常类名 + message，不记录完整 stack trace

新增 VO 类：

```java
@Data
public class HomeworkVO {
    private Long questionId;
    private String questionSource; // V1: bank / generated
    private String questionFormatType; // 题目格式：choice / numeric（来自 question_bank.type 或 generated_question.content JSON）
    private String taskType; // 任务类型：review / redo / similar / comprehensive（来自 homework_submission_detail.question_type）
    private String questionContent;
    private List<String> choices; // 选择题选项（仅 questionFormatType=choice 时非空）
    private String difficulty; // basic, standard, challenge
    private String knowledgePointId;
    private String knowledgePointName;
    // IMP-6: 删除 isSubmitted 冗余字段，以 studentAnswer != null 判定
    private String studentAnswer; // null 表示未提交，非 null 表示已提交
    private Boolean isCorrect;
}

@Data
public class GradeResultVO {
    private Integer totalQuestions;
    private Integer correctCount;
    private Integer incorrectCount;
    private BigDecimal accuracy;
    // IMP-9: 去重后的知识点 ID 列表（一个知识点多题只列一次）
    private List<String> affectedKnowledgePoints;
}
```

新增 DTO 类：

```java
@Data
public class SubmitAnswerRequest {
    // BLOCK-2: 删除 tenantId，从 StudentContext 获取
    private Long planId;
    private Long questionId;
    // IMP-A: 统一命名，taskType 用亍任务类型（review/redo/similar/comprehensive）
    private String questionSource; // bank / generated
    private String taskType; // review / redo / similar / comprehensive
    private String studentAnswer;
}

@Data
public class KnowledgePointWithMastery {
    private String knowledgePointId;
    private String name;
    private String subject;
    private BigDecimal masteryScore;
    private BigDecimal confidence;
}
```

---

## 3. 异常处理

### 3.1 Agent 调用异常

| 异常场景 | 处理方式 |
|----------|----------|
| Agent 响应超时（>30s） | 捕获 `TimeoutException`，触发降级 |
| Agent 返回空结果 | 记录日志，触发降级 |
| Agent 返回格式错误 | 校验失败，重试（最多 2 次） |
| 重试仍失败 | 触发降级，记录 `agent_degradation_log` |

### 3.2 业务异常

> **IMP-14 修复**：错误码采用 1070+ 编号段，延续前置 Change 的编号风格。

| 异常场景 | 错误码 | 编号 | 处理方式 |
|----------|--------|------|----------|
| 当日作业已存在且已进入作答/完成状态 | `HOMEWORK_ALREADY_EXISTS` | 1070 | 返回错误提示，拒绝覆盖 |
| 题目不存在 | `QUESTION_NOT_FOUND` | 1071 | 返回 404 |
| 学生身份不匹配 | `STUDENT_IDENTITY_MISMATCH` | 1072 | 返回 403（NEW-6: 命名修正） |
| 作业未提交（批改前置检查） | `HOMEWORK_NOT_SUBMITTED` | 1073 | 返回错误提示，无法批改 |
| 作业已批改（幂等拒绝） | `HOMEWORK_ALREADY_GRADED` | 1074 | 返回错误提示，防止重复批改 |
| Agent 输出校验失败 | `AGENT_OUTPUT_INVALID` | 1075 | 内部重试或降级 |
| 状态机并发冲突 | `HOMEWORK_CONCURRENT_CONFLICT` | 1076 | 返回重试提示（BLOCK-5 调整） |
| **无有效学习目标（IMP-E）** | **`LEARNING_GOAL_NOT_FOUND`** | **1077** | **返回错误提示，无法生成作业** |
| **content JSON 解析失败（NEW-2）** | **`CONTENT_PARSE_ERROR`** | **1078** | **抛出业务异常，提示作业数据格式错误** |
| **content JSON 序列化失败（NEW-2）** | **`CONTENT_SERIALIZE_ERROR`** | **1079** | **抛出业务异常，提示作业数据写入失败** |

### 3.3 降级策略

出题 Agent 降级时，生成默认计划：
- 按知识点掌握度从低到高排序
- 每个知识点分配 1-2 道基础题
- 总题数不超过 15 题
- 记录降级日志（`agent_type = 'question_gen'`, `degradation_action = 'return_default'`）
- **IMP-F**：若降级处理本身也失败（如默认题库为空），则更新 `daily_plan.status = cancelled`，记录日志，抛 `BusinessException(AGENT_OUTPUT_INVALID)`

---

## 4. 隐性约定对齐

| 约定编号 | 约定内容 | 本次实现 |
|----------|----------|----------|
| #1 | 统一响应结构 | Controller 所有方法返回 `Result<T>` |
| #2 | 异常处理规范 | Service 抛出 `BusinessException`，Controller 不 try-catch |
| #3 | 分页参数规范 | 作业列表按日期单日查询，题量有限（≤ 15），不分页；分页约定适用于历史列表（后续 Change）（NEW-5） |
| #4 | 时间格式规范 | 实体使用 `LocalDateTime`，前端展示 `yyyy-MM-dd HH:mm:ss` |
| #9 | @Transactional 指定 rollbackFor | Service 事务方法显式指定 `rollbackFor = Exception.class` |

**新增隐性约定**：
- 出题 Agent 输入必须包含学生掌握度状态，避免生成过难/过易题目
- 作业生成后允许覆盖重新生成，但需记录降级日志
- 批改完成后必须调用掌握度更新，确保学习行为反映到状态

---

## 5. 测试策略

### 5.1 单元测试

| 测试类 | 测试内容 |
|--------|----------|
| `HomeworkServiceTest` | 生成作业、提交答案、批改逻辑 |
| `QuestionGenAgentTest` | Agent 调用、输出解析、重试机制 |
| `AgentOutputValidatorTest` | Schema 校验、错误处理 |
| `DailyPlanMapperTest` | 数据库 CRUD |
| `HomeworkSubmissionDetailMapperTest` | 批量插入、查询 |

### 5.2 集成测试

> **IMP-11 修复**：集成测试不得依赖真实 LLM 调用，必须通过 Mock 实现。

| 测试场景 | 验证点 | Mock 策略 |
|----------|--------|--------|
| 完整作业流程 | 生成 → 下发 → 提交 → 批改 → 掌握度更新 | Mock `QuestionGenAgent`，返回预设题目列表 |
| Agent 降级场景 | 重试失败后返回默认计划 | Mock `AgentScopeClient` 抛异常 |
| 并发提交场景 | 状态机校验控制并发更新 | 使用 H2 内存数据库，模拟并发（BLOCK-5 调整：V1 无 version，改用状态机） |
| 幂等性测试（IMP-2） | 重复批改拒绝，掌握度不重复计算 | Mock `MasteryService`，验证只调用一次 |

> **真实 LLM 调用**作为人工验收场景，不纳入自动化测试。

---

## 6. 性能优化

| 优化点 | 方案 |
|--------|------|
| 批量插入提交记录 | 使用 MyBatis 批量插入，减少数据库交互 |
| 作业查询缓存 | 当日作业生成后缓存到 Redis（后续 Change 实现） |
| Agent 异步调用 | 使用异步队列调用 Agent（后续 Change 实现） |

---

## 7. 安全考虑

| 安全风险 | 缓解措施 |
|----------|----------|
| 越权提交 | 校验 `studentId` 与 JWT 中的身份一致 |
| **租户伪造（BLOCK-2）** | **tenantId 不可由客户端提供，统一从 JWT 上下文（StudentContext）提取** |
| 重复批改（IMP-2） | 检查作业状态，已批改则拒绝重复批改，防止掌握度重复计算 |
| Agent 注入 | 校验 Agent 输出，防止恶意 JSON 注入 |
| 降级日志泄露（IMP-7） | 写入前对 inputSummary/failureReason 做脱敏（截断 + 移除密钥/学生标识） |
| 敏感信息管理（BLOCK-1） | 密钥仅通过环境变量/密钥管理服务注入，严禁出现在工件/配置模板/日志中 |
| **DDL 变更（NEW-2）** | **homework_submission_detail 需新增唯一索引 uk_plan_question，支持 ON DUPLICATE KEY UPDATE 覆盖语义。需人工审批 V2 变更脚本** |

---

**设计人**：AI Agent  
**审核状态**：待人工审核

---

## 8. 审查修正记录

### 第一轮审查（5 BLOCK + 15 IMP）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-1 | 阻断 | API Key 明文泄露 | §1.2 配置替换为占位符 |
| BLOCK-2 | 阻断 | tenantId 信任边界 | §2.1 Controller 统一从 StudentContext 获取 |
| BLOCK-3 | 阻断 | MasteryService 调用契约不匹配 | §2.2 gradeHomework 改为收集 submissionIds |
| BLOCK-4 | 阻断 | question_ids 双来源未区分 | §2.6 content JSON 带 source 标识 |
| BLOCK-5 | 阻断 | @Version 乐观锁与 V1 冲突 | §2.5 改为状态机校验（V1 无 version） |
| IMP-1~15 | 改进 | 覆盖语义/幂等性/命名/脱敏等 | 各对应章节 |

### 第二轮审查（7 NEW）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| NEW-1 | P0 | GeneratedQuestion 落表契约未定义 | §2.2 步骤 8a/8b/8c + §2.6 实体定义 |
| NEW-2 | P0 | uk_plan_question 索引前提未核实 | §2.5 DDL 变更脚本 + proposal §5 |
| NEW-3 | P0 | §2.3 与 §1.2 示例冲突 | §2.3 删除重复示例，引用 §1.2 |
| NEW-4 | P1 | submitAnswer 接口签名不一致 | §2.2 接口签名改为三参数 |
| NEW-5 | P1 | 分页声明矛盾 | §4 改为「单日不分页」 |
| NEW-6 | P1 | UNAUTHORIZED 命名冲突 | §3.2 改为 STUDENT_IDENTITY_MISMATCH |
| NEW-7 | P2 | Service 注释未同步 | §2.2 注释改为「查询指定日期」 |
| V1 适配 | 阻断 | design 与 V1 schema 系统性不一致 | 全文适配（goal_id/content/状态枚举/无 version） |

### 第三轮审查（3 BLOCK + 8 IMP + 7 改进）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-A | 阻断 | tasks.md 与 design.md V1 适配不同步 | tasks.md T-01~T-13 全面同步 |
| BLOCK-B | 阻断 | V1 关键列类型未引用 | §2.2 新增 V1 列类型参考表 |
| BLOCK-C | 阻断 | V2 DDL 执行顺序未固化 | tasks.md T-01 拆分为 T-01a/T-01b |
| IMP-A | 重要 | taskType/questionType 命名混淆 | §2.6 SubmitAnswerRequest 统一为 taskType |
| IMP-B | 重要 | content JSON 解析工具缺失 | §2.6 新增 DailyPlanContentParser + DailyPlanQuestionRef |
| IMP-C | 重要 | GeneratedQuestion.content JSON 结构未定义 | §2.6 补充 JSON 内部结构示例 |
| IMP-D | 重要 | 批改标准答案来源未定义 | §2.2 gradeHomework 步骤 3 补充 |
| IMP-E | 重要 | learning_goal 关联链路未闭合 | §2.2 步骤 1 补充查询规则 + 错误码 1077 |
| IMP-F | 重要 | cancelled 状态无触发点 | §2.2 步骤 7 + §3.3 补充降级失败处理 |
| IMP-G | 重要 | 事务传播顺序未显式声明 | §2.2 gradeHomework 步骤 7 补充 |
| IMP-H | 重要 | T-13 错误码命名未同步 | tasks.md T-13 同步 |
| IMP-I | 改进 | updateContentAndStatus 无状态机校验 | §2.5 SQL 补充 AND status 条件 |
| IMP-J | 改进 | T-12 集成测试仍写「乐观锁」 | tasks.md T-12 改为「状态机校验」 |
| IMP-K | 改进 | T-01 工作量不匹配 | tasks.md T-01 拆分为 T-01a + T-01b |
| IMP-L | 改进 | T-07 工作量未随 V1 适配上调 | tasks.md T-07 调增为 2 天 |
| IMP-M | 改进 | T-08 未提批量查询避免 N+1 | tasks.md T-08 补充 selectBatchIds |
| IMP-N | 改进 | 缺审查修正记录 | 本章节 |
| IMP-O | 改进 | V2 DDL Flyway 命名规范 | tasks.md T-01b 补充说明 |

### 第四轮审查（6 NEW）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| NEW-1 | 重要 | taskType→questionType 跨层映射未提示 | §2.2 submitAnswer 步骤 4 补充映射说明 |
| NEW-2 | 重要 | DailyPlanContentParser 异常处理未声明 | §2.6 parse/serialize 内部捕获 JsonProcessingException + 新增错误码 1078/1079 |
| NEW-3 | 重要 | learning_goal 查询未指定 Service | §2.1/§2.2 新增 subjectId 参数，调用已有 GoalService.getActiveGoal(subjectId) |
| NEW-4 | 改进 | T-01a/T-01b 依赖图误读为顺序 | tasks.md 依赖图改为并排 ⇅ |
| NEW-5 | 改进 | T-12 未覆盖 1077 + cancelled 场景 | tasks.md T-12 补充两个测试用例 |
| NEW-6 | 改进 | T-09 未提 taskType→questionType 映射 | tasks.md T-09 补充显式映射说明 |

### 第五轮审查（4 OBS）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| OBS-1 | 改进 | gradeHomework 接口签名缺 subjectId | §2.1/§2.2 Controller+Service 均加 subjectId；gradeHomework 步骤 1 先查 goal_id |
| OBS-2 | 改进 | submitAnswer 查询链路 + 归属校验不明确 | §2.2 submitAnswer 步骤 1 明确 planId 直查；步骤 3 补充 goal.studentId 归属校验 |
| OBS-3 | 改进 | GoalService.getActiveGoal 内部取 StudentContext 与无状态惯例冲突 | §2.2 generate/grade 步骤 1 注明「复用现有约定」；T-07 单测说明 mock 方式 |
| OBS-4 | 改进 | T-12 未覆盖 OBS-1/OBS-2 场景 | tasks.md T-12 补充两个测试用例 |
