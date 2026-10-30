# Tasks：基础框架搭建

> **Change ID**: `2026-10-30_backend_foundation`  
> **关联 Design**: design.md

---

## Task 1：更新 Maven 依赖

**目标**：在 `pom.xml` 中添加所有必需的依赖。

**涉及文件**：
- `pom.xml`

**具体任务**：
1. 添加 Spring Boot 3.2.5 parent
2. 添加以下依赖：
   - `spring-boot-starter-web`
   - `spring-boot-starter-validation`
   - `mybatis-plus-spring-boot3-starter` (3.5.6)
   - `mysql-connector-j`
   - `jjwt-api`, `jjwt-impl`, `jjwt-jackson` (0.12.5)
   - `knife4j-openapi3-jakarta-spring-boot-starter` (4.4.0)
   - `lombok`
   - `spring-boot-starter-test` (test scope)
   - `mockito-core` (test scope)
3. 配置 `spring-boot-maven-plugin`

**验收标准**：
- `mvn compile` 执行成功
- 无依赖冲突警告

---

## Task 2：创建应用启动类

**目标**：创建 Spring Boot 应用主类。

**涉及文件**：
- `src/main/java/com/jiang/StudyPlanApplication.java`

**具体任务**：
1. 添加 `@SpringBootApplication` 注解

> **注意**：`@MapperScan("com.jiang.mapper")` 统一由 Task 5 的 `MybatisPlusConfig` 负责，启动类不重复添加，避免 Mapper 重复扫描产生 WARN。

**验收标准**：
- 应用可正常启动（无数据库连接时仅报数据库错误）

---

## Task 3：创建统一响应结构

**目标**：实现 `Result<T>` 和 `PageResult<T>`。

**涉及文件**：
- `src/main/java/com/jiang/common/result/Result.java`
- `src/main/java/com/jiang/common/result/PageResult.java`

**具体任务**：
1. `Result<T>` 包含：code, message, data
2. 提供静态工厂方法：`success()`, `success(data)`, `error(code, message)`, `error(ErrorCodes)`
3. `PageResult<T>` 包含：total, page, size, records
4. 提供静态工厂方法：`of(total, page, size, records)`

**验收标准**：
- JSON 序列化结果符合预期格式
- 单元测试通过

---

## Task 4：创建异常处理组件

**目标**：实现 `BusinessException`、`ErrorCodes`、`GlobalExceptionHandler`。

**涉及文件**：
- `src/main/java/com/jiang/common/exception/BusinessException.java`
- `src/main/java/com/jiang/common/exception/ErrorCodes.java`
- `src/main/java/com/jiang/common/exception/GlobalExceptionHandler.java`

**具体任务**：
1. `BusinessException` 继承 `RuntimeException`，包含 code 和 message
2. `ErrorCodes` 枚举定义常用错误码（200, 400, 401, 403, 404, 500, 1000-1005）
3. `GlobalExceptionHandler` 处理：
   - `BusinessException` → 返回对应错误码
   - `MethodArgumentNotValidException` → 返回 400 + 校验错误详情
   - `Exception` → 返回 500 + 日志记录

**验收标准**：
- 各类异常能被正确捕获
- 返回格式统一为 `Result<T>`

---

## Task 5：创建 MyBatis-Plus 配置

**目标**：配置 MyBatis-Plus 分页插件、租户插件、自动填充。

**涉及文件**：
- `src/main/java/com/jiang/config/MybatisPlusConfig.java`
- `src/main/java/com/jiang/common/handler/AutoFillHandler.java`

**具体任务**：
1. `MybatisPlusConfig`：
   - 配置 `PaginationInnerInterceptor`
   - 配置 `TenantLineInnerInterceptor`（固定 tenant_id = 'default'）
   - 配置 `@MapperScan`
2. `AutoFillHandler`：
   - 实现 `MetaObjectHandler`
   - insertFill：填充 createTime, updateTime, tenantId
   - updateFill：填充 updateTime

**验收标准**：
- 分页查询返回正确的分页信息
- 新增记录自动填充 createTime/updateTime/tenantId
- 更新记录自动填充 updateTime

---

## Task 6：创建 Knife4j 配置

**目标**：配置接口文档。

**涉及文件**：
- `src/main/java/com/jiang/config/Knife4jConfig.java`

**具体任务**：
1. 创建 OpenAPI 文档配置（标题、描述、版本）
2. 配置分组（学生端 API、管理端 API）

**验收标准**：
- 访问 `/doc.html` 可查看接口文档

---

## Task 7：创建枚举类

**目标**：定义业务枚举。

**涉及文件**：
- `src/main/java/com/jiang/model/enums/KnowledgePointStatus.java`
- `src/main/java/com/jiang/model/enums/QuestionDifficulty.java`
- `src/main/java/com/jiang/model/enums/QuestionType.java`
- `src/main/java/com/jiang/model/enums/QuestionTaskType.java`
- `src/main/java/com/jiang/model/enums/PlanStatus.java`
- `src/main/java/com/jiang/model/enums/ChangeType.java`
- `src/main/java/com/jiang/model/enums/GoalStatus.java`
- `src/main/java/com/jiang/model/enums/QuestionSource.java`

**具体任务**：
1. `KnowledgePointStatus`：pending_review, active, disabled
2. `QuestionDifficulty`：basic(0.3), standard(0.6), challenge(0.9)（含 D_norm 映射值）
3. `QuestionType`：numeric, choice（题型维度）
4. `QuestionTaskType`：review, redo, similar, comprehensive（任务类型维度，与 QuestionType 不同）
5. `PlanStatus`：pending, in_progress, completed, cancelled
6. `ChangeType`：homework, assessment, exam
7. `GoalStatus`：active, completed, expired
8. `QuestionSource`：bank, generated（区分题目来源）

**验收标准**：
- 枚举值与产品规则一致

---

## Task 8：创建 Entity 类（基础数据表）

**目标**：创建基础数据表对应的实体类。

**涉及文件**：
- `src/main/java/com/jiang/model/entity/KnowledgePoint.java`
- `src/main/java/com/jiang/model/entity/QuestionBank.java`
- `src/main/java/com/jiang/model/entity/KnowledgePointDependency.java`
- `src/main/java/com/jiang/model/entity/CurriculumStandard.java`
- `src/main/java/com/jiang/model/entity/KnowledgePointStandard.java`

**具体任务**：
1. 使用 `@TableName` 指定表名
2. 使用 `@TableId` 指定主键
3. 使用 `@TableField(fill = FieldFill.INSERT)` 标记自动填充字段
4. JSON 字段：DB 列为 `json` 类型，Entity 字段为 `String`，通过 `@TableField(typeHandler = JacksonTypeHandler.class)` 序列化/反序列化
5. 添加 Lombok 注解（`@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`）

**验收标准**：
- Entity 字段与 DDL 设计一致
- 编译无错误

---

## Task 9：创建 Entity 类（学生与目标表）

**目标**：创建学生、科目、学习目标实体类。

**涉及文件**：
- `src/main/java/com/jiang/model/entity/Student.java`
- `src/main/java/com/jiang/model/entity/Subject.java`
- `src/main/java/com/jiang/model/entity/LearningGoal.java`

**具体任务**：
1. `Student`：student_id, openid, nickname, avatar, grade
2. `Subject`：subject_id, name, stage, description
3. `LearningGoal`：goal_id, student_id, subject_id, target_score, direction, start_date, end_date, status

**验收标准**：
- Entity 字段与 DDL 设计一致

---

## Task 10：创建 Entity 类（计划与作业表）

**目标**：创建计划、作业、新题实体类。

**涉及文件**：
- `src/main/java/com/jiang/model/entity/DailyPlan.java`
- `src/main/java/com/jiang/model/entity/GeneratedQuestion.java`
- `src/main/java/com/jiang/model/entity/PlanAdjustmentLog.java`

**具体任务**：
1. `DailyPlan`：plan_id, goal_id, plan_date, content, status
2. `GeneratedQuestion`：generated_question_id, **daily_plan_id**, knowledge_point_id, source_question_id, content, difficulty, similarity_checked
3. `PlanAdjustmentLog`：adjustment_id, goal_id, trigger_signals, diff_content, status, confirmed_at

**验收标准**：
- Entity 字段与 DDL 设计一致

---

## Task 11：创建 Entity 类（学习记录表）

**目标**：创建学习记录相关实体类。

**涉及文件**：
- `src/main/java/com/jiang/model/entity/HomeworkSubmissionDetail.java`
- `src/main/java/com/jiang/model/entity/AssessmentResult.java`
- `src/main/java/com/jiang/model/entity/ExamAnalysisResult.java`

**具体任务**：
1. `HomeworkSubmissionDetail`：submission_id, plan_id, question_id, **question_source**, question_type, student_answer, is_correct, knowledge_point_id, submitted_at
2. `AssessmentResult`：assessment_id, student_id, knowledge_point_ids, score, total_score, results, assessed_at
3. `ExamAnalysisResult`：analysis_id, student_id, subject_id, exam_name, image_urls, questions, total_score, user_score, status, analyzed_at

**验收标准**：
- Entity 字段与 DDL 设计一致

---

## Task 12：创建 Entity 类（掌握度状态表）

**目标**：创建掌握度相关实体类。

**涉及文件**：
- `src/main/java/com/jiang/model/entity/StudentKnowledgeState.java`
- `src/main/java/com/jiang/model/entity/MasteryChangeLog.java`

**具体任务**：
1. `StudentKnowledgeState`：state_id, student_id, knowledge_point_id, mastery_score, confidence, source, last_assessed_at
2. `MasteryChangeLog`：log_id, student_id, knowledge_point_id, change_type, source_id, delta, mastery_before, mastery_after

**验收标准**：
- Entity 字段与 DDL 设计一致
- 数值类型使用 `BigDecimal`

---

## Task 13：创建 Entity 类（系统日志表）

**目标**：创建系统日志相关实体类。

**涉及文件**：
- `src/main/java/com/jiang/model/entity/AgentDegradationLog.java`
- `src/main/java/com/jiang/model/entity/PendingReviewAlert.java`
- `src/main/java/com/jiang/model/entity/DataImportLog.java`
- `src/main/java/com/jiang/model/entity/DataImportErrorLog.java`

**具体任务**：
1. `AgentDegradationLog`：log_id, agent_type, input_summary, failure_reason, degradation_action
2. `PendingReviewAlert`：alert_id, knowledge_point_id, alert_type, message
3. `DataImportLog`：import_id, data_type, file_source, success_count, fail_count, skip_count, imported_at
4. `DataImportErrorLog`：error_id, import_id, record_id, error_reason

**验收标准**：
- Entity 字段与 DDL 设计一致

---

## Task 14：创建 Mapper 接口

**目标**：为所有 Entity 创建 MyBatis-Plus Mapper 接口。

**涉及文件**：
- `src/main/java/com/jiang/mapper/KnowledgePointMapper.java`
- `src/main/java/com/jiang/mapper/QuestionBankMapper.java`
- `src/main/java/com/jiang/mapper/KnowledgePointDependencyMapper.java`
- `src/main/java/com/jiang/mapper/CurriculumStandardMapper.java`
- `src/main/java/com/jiang/mapper/KnowledgePointStandardMapper.java`
- `src/main/java/com/jiang/mapper/StudentMapper.java`
- `src/main/java/com/jiang/mapper/SubjectMapper.java`
- `src/main/java/com/jiang/mapper/LearningGoalMapper.java`
- `src/main/java/com/jiang/mapper/DailyPlanMapper.java`
- `src/main/java/com/jiang/mapper/GeneratedQuestionMapper.java`
- `src/main/java/com/jiang/mapper/PlanAdjustmentLogMapper.java`
- `src/main/java/com/jiang/mapper/HomeworkSubmissionDetailMapper.java`
- `src/main/java/com/jiang/mapper/AssessmentResultMapper.java`
- `src/main/java/com/jiang/mapper/ExamAnalysisResultMapper.java`
- `src/main/java/com/jiang/mapper/StudentKnowledgeStateMapper.java`
- `src/main/java/com/jiang/mapper/MasteryChangeLogMapper.java`
- `src/main/java/com/jiang/mapper/AgentDegradationLogMapper.java`
- `src/main/java/com/jiang/mapper/PendingReviewAlertMapper.java`
- `src/main/java/com/jiang/mapper/DataImportLogMapper.java`
- `src/main/java/com/jiang/mapper/DataImportErrorLogMapper.java`

**具体任务**：
1. 所有 Mapper 继承 `BaseMapper<T>`
2. 添加 `@Mapper` 注解

**验收标准**：
- 编译无错误
- Spring 可正确注入

---

## Task 15：创建数据库 DDL 脚本

**目标**：编写所有数据表的建表 SQL。

**涉及文件**：
- `src/main/resources/db/V1__init_schema.sql`（受保护目录）

**具体任务**：
1. 按 design.md 中的表结构设计 DDL
2. 包含字段注释、索引定义
3. 字符集统一 `utf8mb4`
4. 所有表包含 `tenant_id` 字段

**验收标准**：
- SQL 可在 MySQL 5.7+ 正确执行
- 表结构与 Entity 类对应

**注意**：此文件位于受保护目录，需人工审批后执行。

---

## Task 16：创建应用配置文件

**目标**：编写 application.yml 配置。

**涉及文件**：
- `src/main/resources/application.yml`（受保护文件）
- `src/main/resources/application-dev.yml`（开发环境配置）

**具体任务**：
1. `application.yml`：
   - 数据源配置（URL、用户名、密码占位）
   - MyBatis-Plus 配置
   - JWT 配置（secret 占位、expiration）
   - Knife4j 配置
2. `application-dev.yml`：
   - 开发环境特定配置
   - 日志级别配置

**验收标准**：
- 配置格式正确
- 敏感信息使用环境变量占位

**注意**：此文件位于受保护目录，需人工审批。

---

## Task 17：编写基础单元测试

**目标**：验证框架核心组件工作正常。

**涉及文件**：
- `src/test/java/com/jiang/common/result/ResultTest.java`
- `src/test/java/com/jiang/common/exception/GlobalExceptionHandlerTest.java`
- `src/test/java/com/jiang/common/handler/AutoFillHandlerTest.java`

**具体任务**：
1. `ResultTest`：测试统一响应结构序列化
2. `GlobalExceptionHandlerTest`：测试各类异常处理
3. `AutoFillHandlerTest`：测试自动填充功能（构造 MetaObject 调用 insertFill/updateFill 断言字段）

**测试方式说明**：
- `AutoFillHandler` 可通过构造 `MetaObject` 单元测试，无需真实 DB
- 租户插件（`TenantLineInnerInterceptor`）如需集成测试，可使用 H2 内存库或 Testcontainers；若环境不支持则标记 `@Disabled` 并注明

**验收标准**：
- 所有测试通过
- 基础设施类（Result/PageResult/BusinessException/GlobalExceptionHandler/AutoFillHandler）测试覆盖

> **注意**：本 change 不追求全量覆盖率，Entity/Mapper 由后续业务 change 的集成测试覆盖。

---

## 任务依赖关系

```
Task 1 (Maven 依赖)
    ↓
Task 2 (启动类)
    ↓
Task 16 (配置文件) → Task 5 (MyBatis-Plus 配置)
    ↓                ↓
Task 3 (统一响应)   Task 6 (Knife4j 配置)
    ↓
Task 4 (异常处理)
    ↓
Task 7 (枚举类)
    ↓
Task 8-13 (Entity 类)
    ↓
Task 14 (Mapper 接口)
    ↓
Task 15 (DDL 脚本)
    ↓
Task 17 (单元测试)
```

---

## 预估工时

| Task | 预估时间 |
|------|----------|
| Task 1-7 | 2 小时 |
| Task 8-14 | 4 小时 |
| Task 15-16 | 2 小时 |
| Task 17 | 2 小时 |
| **总计** | **10 小时** |
