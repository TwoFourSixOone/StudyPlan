# Design：基础框架搭建

> **Change ID**: `2026-10-30_backend_foundation`  
> **关联 Proposal**: proposal.md

---

## 1. 技术方案

### 1.1 技术栈版本

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 运行时 |
| Spring Boot | 3.2.5 | Web 框架 |
| MyBatis-Plus | 3.5.6 | ORM 框架 |
| MySQL | 5.7+ | 数据库 |
| jjwt | 0.12.5 | JWT 实现库 |
| Knife4j | 4.4.0 | 接口文档 |
| Lombok | 最新 | 代码简化 |
| JUnit 5 | 5.10.x | 单元测试 |
| Mockito | 5.x | Mock 框架 |

### 1.2 项目包结构

```
com.jiang
├── controller          # Controller 层：接收 HTTP 请求
├── service             # Service 层：业务逻辑
│   └── impl            # Service 实现类
├── mapper              # DAO 层：MyBatis-Plus Mapper 接口
├── model               # 数据模型
│   ├── entity          # 实体类（对应数据库表）
│   ├── dto             # 数据传输对象（接口入参）
│   ├── vo              # 视图对象（接口出参）
│   └── enums           # 枚举类
├── config              # 配置类
├── common              # 公共组件
│   ├── result          # 统一响应结构
│   ├── exception       # 异常处理
│   └── handler         # 通用处理器
└── util                # 工具类
```

---

## 2. 分层设计

### 2.1 统一响应结构

> **HTTP 状态码与业务码约定**：HTTP 状态码遵循 RESTful 规范——业务异常返回 200 + 业务错误码；参数校验失败返回 400；未授权返回 401；资源不存在返回 404；请求方法不支持返回 405；系统异常返回 500。业务状态由 `Result.code` 表达（200 成功，非 200 为错误码），前端优先根据 HTTP 状态码判断请求结果，再根据 `Result.code` 判断业务结果。

```java
// Result<T> — 统一响应
public class Result<T> {
    private Integer code;      // 业务状态码（200=成功，非 200=错误）
    private String message;    // 提示信息
    private T data;            // 业务数据
    
    // 静态工厂方法
    public static <T> Result<T> success(T data);
    public static <T> Result<T> success();
    public static <T> Result<T> error(Integer code, String message);
    public static <T> Result<T> error(ErrorCodes errorCode);
}

// PageResult<T> — 分页响应
public class PageResult<T> {
    private Long total;        // 总记录数
    private Integer page;      // 当前页码（从 1 开始）
    private Integer size;      // 每页条数
    private List<T> records;   // 数据列表
}
```

### 2.2 异常处理

```java
// BusinessException — 业务异常
public class BusinessException extends RuntimeException {
    private Integer code;
    private String message;
    
    public BusinessException(ErrorCodes errorCode);
    public BusinessException(Integer code, String message);
}

// ErrorCodes — 错误码枚举
public enum ErrorCodes {
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    // 业务错误码（按业务域分段，1000 起）
    // 学生相关（1000-1009）
    STUDENT_NOT_FOUND(1000, "学生不存在"),
    // 知识点相关（1010-1019）
    KNOWLEDGE_POINT_NOT_FOUND(1010, "知识点不存在"),
    // ... 按需扩展，完整清单以 ErrorCodes.java 为准
}

// GlobalExceptionHandler — 全局异常处理器
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<?> handleBusinessException(BusinessException e);
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleValidationException(MethodArgumentNotValidException e);
    
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBindException(BindException e);
    
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMissingParamException(MissingServletRequestParameterException e);
    
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMessageNotReadable(HttpMessageNotReadableException e);
    
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e);
    
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<?> handleNoResourceFound(NoResourceFoundException e);
    
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e);
}
```

### 2.3 MyBatis-Plus 配置

```java
// MybatisPlusConfig — 核心配置
@Configuration
@MapperScan("com.jiang.mapper")
public class MybatisPlusConfig {
    
    // 插件配置（注意：租户插件必须在分页插件之前添加）
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 租户插件（Demo 阶段固定 tenant_id = 'default'）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue("default");
            }
            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }
            @Override
            public boolean ignoreTable(String tableName) {
                return false; // 所有表都需要 tenant_id
            }
        }));
        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}

// AutoFillHandler — 自动填充处理器
@Component
public class AutoFillHandler implements MetaObjectHandler {
    
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime::now, LocalDateTime.class);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime::now, LocalDateTime.class);
        this.strictInsertFill(metaObject, "tenantId", () -> "default", String.class);
    }
    
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime::now, LocalDateTime.class);
    }
}
```

### 2.4 基础实体类设计

```java
// BaseEntity — 所有实体的公共字段
@Data
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEntity implements Serializable {
    @TableField(fill = FieldFill.INSERT)
    private String tenantId;      // 租户 ID
    
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;  // 创建时间
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;  // 更新时间
}
```

> **设计说明**：所有业务实体继承 `BaseEntity`，自动获得 `tenantId`、`createTime`、`updateTime` 三个公共字段。MyBatis-Plus 通过 `AutoFillHandler` 在插入/更新时自动填充这些字段。

---

## 3. 数据库表结构设计

### 3.1 基础约定

- 所有表字符集：`utf8mb4`
- 排序规则：`utf8mb4_general_ci`
- 所有表包含 `tenant_id` 字段（Demo 阶段固定 `'default'`）
- 所有表包含 `create_time`、`update_time` 字段（自动填充）
- 主键命名：`{表名简写}_id` 或 `id`（根据业务语义）
- 索引命名：`idx_{字段名}` 或 `uk_{字段名}`（唯一索引）
- **多租户索引约定**（对应 database.md §7）：所有索引（含 `idx_*` 与 `uk_*`）均以 `tenant_id` 为**前导列**，保证多租户改造时零成本切换。例如：`idx_subject_status (tenant_id, subject, status)`、`uk_openid (tenant_id, openid)`。下文各表「索引」行省略 `tenant_id` 前导列（仅列出业务列），实际 DDL 以本节约定为准。

### 3.2 数据表清单

根据产品规则，需创建以下数据表：

> **说明**：`student`、`subject`、`learning_goal` 为基础实体表，产品规则正文未点名故未入其附表；实现以本 design 为准。

#### 基础数据表（规则 #8/#10）

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `knowledge_point` | 知识点基础数据 | knowledge_point_id, subject, name, level, parent_id, status, standards, mastery_criteria, diagnostic_question |
| `question_bank` | 题库 | question_id, knowledge_point_id, content, choices, answer, analysis, difficulty, type |
| `knowledge_point_dependency` | 先修关系 | dependency_id, knowledge_point_id, prerequisite_id, dependency_type |
| `curriculum_standard` | 课程标准 | standard_id, subject, stage, code, description |
| `knowledge_point_standard` | 知识点-课程标准关联 | knowledge_point_id, standard_id |

#### 基础配置表

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `subject` | 科目信息 | subject_id, name, stage, description |

#### 学生与目标表

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `student` | 学生信息 | student_id, openid, nickname, avatar, grade |
| `learning_goal` | 学习目标 | goal_id, student_id, subject_id, target_score, direction, start_date, end_date, status |

#### 计划与作业表（规则 #3/#5）

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `daily_plan` | 每日计划 | plan_id, goal_id, plan_date, content, status |
| `generated_question` | Agent 生成的新题 | generated_question_id, **daily_plan_id**, knowledge_point_id, source_question_id, content, difficulty, similarity_checked |
| `plan_adjustment_log` | 计划调整日志 | adjustment_id, goal_id, trigger_signals, diff_content, status, confirmed_at |

#### 学习记录表（规则 #9）— 只读不删

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `homework_submission_detail` | 作业提交明细 | submission_id, plan_id, question_id, **question_source**, question_type, student_answer, is_correct, knowledge_point_id, submitted_at |
| `assessment_result` | 专项评测结果 | assessment_id, student_id, knowledge_point_ids, score, total_score, results, assessed_at |
| `exam_analysis_result` | 试卷分析结果 | analysis_id, student_id, subject_id, exam_name, image_urls, questions, total_score, user_score, status, analyzed_at |

#### 掌握度状态表（规则 #2）

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `student_knowledge_state` | 学生知识点掌握度 | state_id, student_id, knowledge_point_id, mastery_score, confidence, source, last_assessed_at |
| `mastery_change_log` | 掌握度变更日志 | log_id, student_id, knowledge_point_id, change_type, source_id, delta, mastery_before, mastery_after |

#### 系统日志表（规则 #6/#8）— 只读不删

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `agent_degradation_log` | Agent 降级日志 | log_id, agent_type, input_summary, failure_reason, degradation_action |
| `pending_review_alert` | 校对超期提醒 | alert_id, knowledge_point_id, alert_type, message, created_at |
| `data_import_log` | 数据导入日志 | import_id, data_type, file_source, success_count, fail_count, skip_count, imported_at |
| `data_import_error_log` | 导入错误记录 | error_id, import_id, record_id, error_reason |

### 3.3 详细 DDL 设计

> 完整 DDL 脚本将存放于 `src/main/resources/db/V1__init_schema.sql`（受保护目录，需人工审批后执行）。
> 以下为核心表的字段设计说明。

#### 3.3.1 基础数据表

**knowledge_point（知识点表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| knowledge_point_id | VARCHAR(64) PK | 知识点唯一 ID（不可复用） |
| subject | VARCHAR(50) | 科目（如 "数学"） |
| name | VARCHAR(200) | 知识点名称 |
| level | TINYINT | 层级：1=章节, 2=知识点, 3=子知识点 |
| parent_id | VARCHAR(64) | 父节点 ID |
| status | VARCHAR(20) | 状态：pending_review / active / disabled |
| standards | JSON | 关联的课程标准 ID 数组（冗余存储，供 Agent 读取，不用于查询条件）。**注意**：`knowledge_point.subject` 为冗余字段（避免 JOIN），以 `subject.name` 为准；导入时需校验一致性 |
| mastery_criteria | JSON | 掌握度评判标准（DB 列为 json 类型，Entity 字段为 String，通过 JacksonTypeHandler 序列化） |
| diagnostic_question | JSON | 诊断题目信息（DB 列为 json 类型，Entity 字段为 String，通过 JacksonTypeHandler 序列化） |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_subject_status` (subject, status), `idx_parent_id` (parent_id)

**question_bank（题库表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| question_id | BIGINT PK AUTO_INCREMENT | 题目 ID |
| knowledge_point_id | VARCHAR(64) | 关联知识点 |
| content | TEXT | 题干内容 |
| choices | JSON | 选项（选择题用，DB 列为 json 类型） |
| answer | VARCHAR(500) | 答案 |
| analysis | TEXT | 解析 |
| difficulty | VARCHAR(20) | 难度：basic / standard / challenge |
| type | VARCHAR(20) | 题型：numeric / choice |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_knowledge_point` (knowledge_point_id)

**knowledge_point_dependency（先修关系表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| dependency_id | BIGINT PK AUTO_INCREMENT | 关系 ID |
| knowledge_point_id | VARCHAR(64) | 知识点 ID |
| prerequisite_id | VARCHAR(64) | 先修知识点 ID |
| dependency_type | VARCHAR(20) | 关系类型：hard / soft |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_knowledge_point` (knowledge_point_id), `idx_prerequisite` (prerequisite_id)

**curriculum_standard（课程标准表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| standard_id | BIGINT PK AUTO_INCREMENT | 标准 ID |
| subject | VARCHAR(50) | 科目 |
| stage | VARCHAR(50) | 学段：初中 / 高中 |
| code | VARCHAR(100) | 标准编码 |
| description | TEXT | 标准描述 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`uk_subject_code` (subject, code, tenant_id) UNIQUE

**knowledge_point_standard（知识点-课程标准关联表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 关联 ID |
| knowledge_point_id | VARCHAR(64) | 知识点 ID |
| standard_id | BIGINT | 课程标准 ID |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_knowledge_point` (knowledge_point_id), `idx_standard` (standard_id)

#### 3.3.2 基础配置表

**subject（科目表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| subject_id | BIGINT PK AUTO_INCREMENT | 科目 ID |
| name | VARCHAR(50) | 科目名称 |
| stage | VARCHAR(50) | 学段：初中 / 高中 |
| description | VARCHAR(500) | 科目描述 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`uk_name_stage` (name, stage, tenant_id) UNIQUE

#### 3.3.3 学生与目标表

**student（学生表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| student_id | BIGINT PK AUTO_INCREMENT | 学生 ID |
| openid | VARCHAR(100) | 微信 openid |
| nickname | VARCHAR(100) | 昵称 |
| avatar | VARCHAR(500) | 头像 URL |
| grade | VARCHAR(20) | 年级 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`uk_openid` (openid, tenant_id) UNIQUE

**learning_goal（学习目标表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| goal_id | BIGINT PK AUTO_INCREMENT | 目标 ID |
| student_id | BIGINT | 学生 ID |
| subject_id | BIGINT | 科目 ID |
| target_score | INT | 目标分数 |
| direction | VARCHAR(500) | 学习方向描述 |
| start_date | DATE | 开始日期 |
| end_date | DATE | 结束日期 |
| status | VARCHAR(20) | 状态：active / completed / expired |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_student` (student_id), `idx_status` (status)

#### 3.3.4 计划与作业表

**daily_plan（每日计划表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| plan_id | BIGINT PK AUTO_INCREMENT | 计划 ID |
| goal_id | BIGINT | 目标 ID |
| plan_date | DATE | 计划日期 |
| content | JSON | 计划内容（DB 列为 json 类型） |
| status | VARCHAR(20) | 状态：pending / pending_generation（出题 Agent 降级） / in_progress / completed / cancelled |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_goal_date` (goal_id, plan_date), `idx_status` (status)

**generated_question（Agent 生成新题表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| generated_question_id | BIGINT PK AUTO_INCREMENT | 新题 ID |
| daily_plan_id | BIGINT | 所属每日计划 ID（关联 daily_plan） |
| knowledge_point_id | VARCHAR(64) | 关联知识点 |
| source_question_id | BIGINT | 触发生成的原错题 ID（关联 question_bank，可为空） |
| content | JSON | 题目内容（DB 列为 json 类型） |
| difficulty | VARCHAR(20) | Agent 声明难度：basic / standard / challenge |
| similarity_checked | TINYINT | 是否通过相似度校验：0=未通过, 1=已通过 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_daily_plan` (daily_plan_id), `idx_knowledge_point` (knowledge_point_id)

> **数据模型说明**：`daily_plan.content` 仅存计划元信息（知识点列表、任务类型配额），题目明细以 `question_bank` / `generated_question` 为准，不双写题目内容，避免数据冗余与不一致。

**plan_adjustment_log（计划调整日志表）** — 只读不删

| 字段 | 类型 | 说明 |
|------|------|------|
| adjustment_id | BIGINT PK AUTO_INCREMENT | 调整 ID |
| goal_id | BIGINT | 目标 ID |
| trigger_signals | JSON | 触发信号列表（DB 列为 json 类型） |
| diff_content | JSON | 计划 diff 内容（DB 列为 json 类型） |
| status | VARCHAR(20) | 状态：pending / confirmed / rejected |
| confirmed_at | DATETIME | 确认时间 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_goal` (goal_id), `idx_status` (status)

#### 3.3.5 学习记录表（只读不删）

**homework_submission_detail（作业提交明细表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| submission_id | BIGINT PK AUTO_INCREMENT | 提交 ID |
| plan_id | BIGINT | 所属计划 ID |
| question_id | BIGINT | 题目 ID（根据 question_source 关联 question_bank 或 generated_question） |
| question_source | VARCHAR(20) | 题目来源：bank / generated |
| question_type | VARCHAR(20) | 任务类型：review（知识回顾）/ redo（错题重做）/ similar（举一反三）/ comprehensive（综合提升） |
| student_answer | TEXT | 学生答案 |
| is_correct | TINYINT | 是否正确：0=错误, 1=正确 |
| knowledge_point_id | VARCHAR(64) | 关联知识点 |
| submitted_at | DATETIME | 提交时间 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_plan` (plan_id), `idx_student_kp` (knowledge_point_id), `idx_submitted_at` (submitted_at)

> **注意**：`question_type` 表示任务类型（review/redo/similar/comprehensive），与 `question_bank.type`（题型：numeric/choice）是不同维度。

**assessment_result（专项评测结果表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| assessment_id | BIGINT PK AUTO_INCREMENT | 评测 ID |
| student_id | BIGINT | 学生 ID |
| knowledge_point_ids | JSON | 涉及的知识点 ID 列表（DB 列为 json 类型） |
| score | DECIMAL(5,2) | 得分（百分制，0-100） |
| total_score | DECIMAL(5,2) | 总分（百分制） |
| results | JSON | 详细结果（DB 列为 json 类型，含各知识点正确率） |
| assessed_at | DATETIME | 评测时间 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_student` (student_id), `idx_assessed_at` (assessed_at)

> **分数口径说明**：`score` / `total_score` 为百分制（0-100），`results` JSON 中存各知识点正确率（0.0-1.0）。
>
> **默认值设计**：`score` 和 `total_score` 均为 DEFAULT NULL，表示"未完成评测"。业务层在评测完成后写入具体分数（通常 total_score=100，score=实际得分）。

**exam_analysis_result（试卷分析结果表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| analysis_id | BIGINT PK AUTO_INCREMENT | 分析 ID |
| student_id | BIGINT | 学生 ID |
| subject_id | BIGINT | 科目 ID |
| exam_name | VARCHAR(200) | 考试名称 |
| image_urls | JSON | 试卷图片 URL 列表（DB 列为 json 类型） |
| questions | JSON | 题目分析结果（DB 列为 json 类型） |
| total_score | DECIMAL(5,2) | 总分 |
| user_score | DECIMAL(5,2) | 用户得分 |
| status | VARCHAR(20) | 状态：pending / processing / completed / failed |
| analyzed_at | DATETIME | 分析完成时间 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`idx_student` (student_id), `idx_status` (status)

#### 3.3.6 掌握度状态表

**student_knowledge_state（学生知识点状态表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| state_id | BIGINT PK AUTO_INCREMENT | 状态 ID |
| student_id | BIGINT | 学生 ID |
| knowledge_point_id | VARCHAR(64) | 知识点 ID |
| mastery_score | DECIMAL(3,2) | 掌握度（0.00-1.00） |
| confidence | DECIMAL(3,2) | 置信度（0.00-1.00） |
| source | VARCHAR(50) | 最近来源：homework / assessment / exam |
| last_assessed_at | DATETIME | 最近评测时间 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

索引：`uk_student_kp` (student_id, knowledge_point_id, tenant_id) UNIQUE

> **默认值设计**：
> - `mastery_score`：DEFAULT NULL，表示"未评测"。业务层在首次评测后写入具体值（0.00-1.00）。
> - `confidence`：DEFAULT 0.10（产品规则 #2 明确："置信度初始值为 0.1"）。
>
> **设计理由**：mastery_score 为 NULL 与"已评测但掌握度为 0.10"区分，避免语义混淆。后续业务规则调整初始掌握度时无需改表结构。

**mastery_change_log（掌握度变更日志表）** — 只读不删

| 字段 | 类型 | 说明 |
|------|------|------|
| log_id | BIGINT PK AUTO_INCREMENT | 日志 ID |
| student_id | BIGINT | 学生 ID |
| knowledge_point_id | VARCHAR(64) | 知识点 ID |
| change_type | VARCHAR(20) | 变更类型：homework / assessment / exam |
| source_id | BIGINT | 来源 ID（对 homework 存 submission_id 逐题，对 assessment 存 assessment_id，对 exam 存 analysis_id） |
| delta | DECIMAL(5,4) | 变动量 |
| mastery_before | DECIMAL(3,2) | 变更前掌握度 |
| mastery_after | DECIMAL(3,2) | 变更后掌握度 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |

索引：`idx_student_kp` (student_id, knowledge_point_id), `idx_source` (change_type, source_id)

> **追溯链说明**：`source_id` 对 `change_type=homework` 存 `submission_id`（逐题粒度），对 `assessment` 存 `assessment_id`，对 `exam` 存 `analysis_id`——与学习记录表通过此字段形成完整追溯链。

#### 3.3.7 系统日志表（只读不删）

**agent_degradation_log（Agent 降级日志表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| log_id | BIGINT PK AUTO_INCREMENT | 日志 ID |
| agent_type | VARCHAR(50) | Agent 类型：planning / question_gen / attribution / adjustment |
| input_summary | TEXT | 输入摘要 |
| failure_reason | TEXT | 失败原因 |
| degradation_action | TEXT | 降级处理动作 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |

索引：`idx_agent_type` (agent_type), `idx_created_at` (create_time)

**pending_review_alert（校对超期提醒表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| alert_id | BIGINT PK AUTO_INCREMENT | 提醒 ID |
| knowledge_point_id | VARCHAR(64) | 知识点 ID |
| alert_type | VARCHAR(50) | 提醒类型：overdue / manual |
| message | VARCHAR(1000) | 提醒消息 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |

索引：`idx_knowledge_point` (knowledge_point_id), `idx_created_at` (create_time)

**data_import_log（数据导入日志表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| import_id | BIGINT PK AUTO_INCREMENT | 导入 ID |
| data_type | VARCHAR(50) | 数据类型：knowledge_point / question / dependency / standard |
| file_source | VARCHAR(500) | 文件来源 |
| success_count | INT | 成功数 |
| fail_count | INT | 失败数 |
| skip_count | INT | 跳过数 |
| imported_at | DATETIME | 导入时间 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |

索引：`idx_data_type` (data_type), `idx_imported_at` (imported_at)

**data_import_error_log（导入错误记录表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| error_id | BIGINT PK AUTO_INCREMENT | 错误 ID |
| import_id | BIGINT | 所属导入批次 ID |
| record_id | VARCHAR(100) | 记录 ID |
| error_reason | TEXT | 错误原因 |
| tenant_id | VARCHAR(50) | 租户 ID |
| create_time | DATETIME | 创建时间 |

索引：`idx_import` (import_id)

> **注意**：学习记录表与系统日志表均遵循产品规则 #9 的"只读不删"原则，应用层不提供删除接口。

---

## 4. 异常处理设计

### 4.1 错误码规范

| 错误码范围 | 说明 |
|-----------|------|
| 200 | 成功 |
| 400-499 | 客户端错误（参数校验、鉴权失败等） |
| 500-599 | 服务端错误（系统异常、数据库异常等） |
| 1000-1999 | 业务错误（学生、知识点、题目等业务异常） |

### 4.2 异常处理流程

```
Controller 层
    ↓ 参数校验失败 → MethodArgumentNotValidException → GlobalExceptionHandler
    ↓
Service 层
    ↓ 业务异常 → BusinessException(ErrorCodes.XXX) → GlobalExceptionHandler
    ↓
DAO 层
    ↓ 数据库异常 → DataAccessException → GlobalExceptionHandler（记录日志）
    ↓
GlobalExceptionHandler
    ↓ 统一转换为 Result<T> 格式返回
```

---

## 5. 隐性约定对齐

| 约定 | 本 change 实现方式 |
|------|-------------------|
| 约定 #1：统一响应结构 | 创建 `Result<T>` 和 `PageResult<T>` |
| 约定 #2：异常处理规范 | 创建 `BusinessException` + `ErrorCodes` + `GlobalExceptionHandler` |
| 约定 #3：分页参数规范 | `PageResult<T>` 中 page 从 1 开始，size 默认 10 |
| 约定 #4：时间格式规范 | Entity 使用 `LocalDateTime`，配置 `DateTimeFormat` |
| 约定 #9：@Transactional rollbackFor | 本 change 暂无事务方法，在文档中明确规范供后续遵循 |

---

## 6. 配置项设计

### application.yml

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/studyplan?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Shanghai
    username: root
    password: ${DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml  # 本 change 无自定义 XML，目录后续按需创建
  global-config:
    db-config:
      id-type: auto
      # 注意：不配置全局逻辑删除，学习记录与日志表遵循"只读不删"原则（产品规则 #9）
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

jwt:
  secret: ${JWT_SECRET}
  expiration: 7200000  # 2小时（毫秒）

knife4j:
  enable: true
  setting:
    language: zh_cn
```

---

## 7. 文件清单

本 change 需创建/修改的文件：

| 文件路径 | 说明 |
|---------|------|
| `pom.xml` | 更新 Maven 依赖 |
| `src/main/resources/application.yml` | 应用配置（受保护，需人工审批） |
| `src/main/resources/db/V1__init_schema.sql` | 数据库 DDL 脚本（受保护，需人工审批） |
| `src/main/java/com/jiang/StudyPlanApplication.java` | 启动类 |
| `src/main/java/com/jiang/common/result/Result.java` | 统一响应 |
| `src/main/java/com/jiang/common/result/PageResult.java` | 分页响应 |
| `src/main/java/com/jiang/common/exception/BusinessException.java` | 业务异常 |
| `src/main/java/com/jiang/common/exception/ErrorCodes.java` | 错误码枚举 |
| `src/main/java/com/jiang/common/exception/GlobalExceptionHandler.java` | 全局异常处理器 |
| `src/main/java/com/jiang/config/MybatisPlusConfig.java` | MyBatis-Plus 配置 |
| `src/main/java/com/jiang/config/Knife4jConfig.java` | Knife4j 配置 |
| `src/main/java/com/jiang/common/handler/AutoFillHandler.java` | 自动填充处理器 |
| `src/main/java/com/jiang/model/entity/*.java` | 所有 Entity 类 |
| `src/main/java/com/jiang/model/enums/*.java` | 枚举类 |
| `src/main/java/com/jiang/mapper/*.java` | Mapper 接口 |

---

## 8. 测试策略

本 change 以框架搭建为主，测试范围聚焦于**基础设施类**，不追求全量覆盖率：

| 测试类型 | 测试对象 | 说明 |
|---------|---------|------|
| 单元测试 | `Result` / `PageResult` | 验证序列化格式正确 |
| 单元测试 | `BusinessException` / `ErrorCodes` | 验证异常构造与错误码映射 |
| 单元测试 | `GlobalExceptionHandler` | 验证各类异常被正确捕获并转换为统一响应 |
| 单元测试 | `AutoFillHandler` | 验证自动填充字段正确 |
| 集成测试 | DDL 可执行性 | 验证建表脚本可在 MySQL 5.7+ 正确执行 |

> **不纳入本 change 测试范围**：Entity 类、Mapper 接口、枚举类由后续业务 change 的集成测试覆盖。

验收标准：
- [ ] 统一响应结构序列化正确
- [ ] 全局异常处理器捕获各类异常
- [ ] MyBatis-Plus 自动填充生效
- [ ] 数据库连接正常，DDL 可执行
