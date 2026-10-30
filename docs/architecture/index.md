# 项目架构总览

> 本文件描述智学伴（StudyMate）项目的整体架构设计，帮助 AI 快速了解系统全貌。
> 业务规则细节以 `docs/product/index.md` 为准，本文件只做架构导航。

---

## 1. 项目概述

- **项目名称**：智学伴（StudyMate）— AI 自适应学习辅助小程序
- **后端技术栈**：Java 17 + Spring Boot 3.2.x + Maven + MyBatis-Plus + MySQL 5.7+
- **Agent 技术栈**：AgentScope Java（嵌入 Spring Boot）
- **前端技术栈**：
    - 学生端：uni-app（Vue 3 语法，编译至微信小程序）
    - 管理后端：Vue 3 + Element Plus + Vite（PC 端浏览器访问）
- **鉴权**：JWT (JSON Web Token)
- **工作流**：OpenSpec 变更生命周期管理
- **AI 平台**：QoderCn
- **项目定位**：跑通“设定目标 → Agent 规划每日计划 → 完成每日作业 → 知识点评测 → 考试试卷分析 → Agent 动态调整计划”的完整学习闭环

---

## 2. 系统架构

系统分为四个独立部署单元，通过 HTTP/JSON 通信：

```
┌─────────────────┐                    ┌─────────────────┐
│  学生端          │  HTTPS            │  管理后端        │  HTTPS
│  uni-app 小程序   │ ──────┐          │  PC 端 Vue 3     │ ──────┐
│                 │       │          │                 │       │
└─────────────────┘       │          └─────────────────┘       │
                          │                                     │
                          ▼                                     ▼
                ┌────────────────────────────────────────────────────────────┐
                │                    后端服务  Spring Boot                    │
                │                    + MySQL 5.7+                           │
                │  ┌─────────────────────────────────────────────────────┐  │
                │  │  内嵌 AI Agent 服务 (AgentScope Java)              │  │
                │  ├─────────────────────────────────────────────────────┤  │
                │  │  内嵌平台侧功能（Knife4j、Prompt 管理、异步队列）  │  │
                │  └─────────────────────────────────────────────────────┘  │
                └────────────────────────────────────────────────────────────┘
```

> **部署说明**：管理后端为独立前端构建产物，Demo 阶段与后端服务同机部署（后端服务同时提供 API 与管理后端的静态资源托管）。学生端与管理后端无直接通信，均通过后端服务 API 交互。**平台侧（Knife4j、Prompt 版本管理、异步队列）为后端服务的内嵌开发者功能，非独立部署单元**，仅供开发调试使用，不属于 Demo 演示范围。详见产品规则 §1.1（5）。

| 部署单元 | 职责 | 关键技术 |
|---------|------|----------|
| 学生端（uni-app 小程序） | 目标设定、每日作业、评测、试卷上传、掌握度看板、计划调整 | uni-app (Vue 3)、微信小程序 |
| 管理后端（PC 端） | 题库管理、知识点管理、先修关系管理、课程标准管理、数据导入、列表搜索与筛选 | Vue 3、Element Plus、Vite |
| 后端服务（Spring Boot） | 业务逻辑、数据持久化、JWT 鉴权、Agent 调度、异步任务管理；内嵌平台侧开发者功能（Knife4j、Prompt 管理、异步队列） | Spring Boot、MyBatis-Plus、MySQL、JWT |
| AI Agent 服务 | 规划、出题、归因、调整四类 Agent 的 LLM 推理 | AgentScope Java、LLM Gateway |

> **平台侧功能**（Knife4j、Prompt 版本管理、异步队列）内嵌于后端服务，仅供开发者使用，不属于 Demo 演示范围。详见产品规则 §1.1（5）。

### 管理后端前端架构

- **技术栈**：Vue 3 + Element Plus + Vite
- **运行环境**：PC 端浏览器
- **列表页通用规范**：
    - 所有列表页面必须包含**搜索框区域**（位于列表上方），支持输入搜索条件后按条件筛选查询
    - 搜索框支持对应列表的核心字段（如知识点名称、题目 ID、学科领域等）
    - 列表支持分页，默认每页 20 条
    - 使用 Element Plus 的 `el-table`、`el-pagination`、`el-form` 等组件

---

## 3. 项目结构

```
StudyPlan/
├─ src/
│  ├─ main/java/com/jiang/       ← 后端主代码目录（包根：com.jiang）
│  └─ main/resources/
│     ├─ application*.yml         ← 配置文件（受保护，不得直接修改）
│     ├─ db/                      ← 数据库脚本（受保护）
│     ├─ json/                    ← 初始数据文件（题库、知识点、先修关系、课程标准）
│     └─ prompts/
│        ├─ schemas/              ← Agent JSON Schema 定义（受保护）
│        └─ versions/             ← Prompt 版本文件（受保护）
├─ src/test/java/com/jiang/       ← 后端测试代码目录
├─ admin-web/                     ← 管理后端 PC 端前端（Vue 3 + Element Plus + Vite）
├─ docs/                          ← 项目知识库
│  ├─ architecture/               ← 架构知识 & 隐性约定
│  ├─ product/index.md            ← 产品规则（业务唯一依据）
│  ├─ standards/                  ← 测试 & 数据库规范
│  └─ changelog/                  ← 公式/参数变更记录
├─ openspec/                      ← OpenSpec 变更管理
│  ├─ changes/                    ← 进行中的 change
│  └─ specs/                      ← 当前系统工作原理
├─ AGENTS.md                      ← AI 导航地图
├─ REVIEW.md                      ← 评审标准
└─ pom.xml                        ← Maven 配置
```

---

## 4. 后端分层架构

项目采用标准 Java 分层架构，各层职责如下：

### Controller 层
- **职责**：接收 HTTP 请求，参数校验，返回统一响应结构（`Result<T>`）
- **禁止**：包含任何业务逻辑、直接操作数据库
- **包路径**：`com.jiang.controller`

### Service 层
- **职责**：核心业务逻辑处理，事务管理，Agent 调用编排
- **原则**：单一职责，一个 Service 只负责一个业务域
- **包路径**：`com.jiang.service`
- **关键 Service**：
    - `AuthService` — JWT 签发与校验、openid 登录（对应产品规则 #7）
    - `GoalService` — 学习目标管理（对应产品规则 #1）
    - `MasteryService` — 掌握度计算与更新（对应产品规则 #2）
    - `HomeworkService` — 每日作业生成与提交（对应产品规则 #3）
    - `ExamService` — 试卷上传与异步解析（对应产品规则 #4）
    - `PlanAdjustmentService` — 计划调整触发与 diff 存储（对应产品规则 #5）
    - `KnowledgePointService` — 知识点树管理（对应产品规则 #8）
    - `LearningRecordService` — 学习记录查询与回溯（对应产品规则 #9）
    - `DataImportService` — 题库与知识点数据导入管理（对应产品规则 #10）

> 以上为业务核心 Service，其他辅助 Service（如 Agent 输出校验、科目管理等）按分层惯例实现，不再穷举。

### Agent 层
- **职责**：封装四类 AI Agent 的调用、输入构造、输出解析
- **包路径**：`com.jiang.agent`
- **Agent 清单**（对应产品规则 #6）：

  | Agent | 类名建议 | 输入 | 输出 Schema |
  |------|---------|------|------------|
  | 规划 Agent | `PlanningAgent` | 学习目标、知识点树 | `planning_schema.json` |
  | 出题 Agent | `QuestionGenAgent` | 知识点列表、掌握度 | `question_gen_schema.json` |
  | 归因 Agent | `AttributionAgent` | 题目+作答结果 | `attribution_schema.json` |
  | 调整 Agent | `AdjustmentAgent` | 计划+触发信号 | `adjustment_schema.json` |

### DAO/Repository 层
- **职责**：数据访问，SQL 执行
- **禁止**：包含业务逻辑
- **包路径**：`com.jiang.repository` / `com.jiang.mapper`
- **数据表清单**：见产品规则附录「附：数据表清单」

### Model/Entity 层
- **职责**：数据模型定义，与数据库表一一对应
- **包路径**：`com.jiang.model` / `com.jiang.entity`

### DTO/VO 层
- **职责**：数据传输对象（接口入参/出参）、视图对象（前端展示）
- **包路径**：`com.jiang.dto` / `com.jiang.vo`

### Config 层
- **职责**：配置类（数据源、AgentScope、安全等）
- **包路径**：`com.jiang.config`

---

## 5. 数据架构

### 5.1 数据库

- **类型**：MySQL 5.7+
- **字符集**：所有表统一使用 `utf8mb4`（支持数学符号与 emoji）
- **MySQL 5.7 限制**：不支持 `jsonb`（仅 `json` 类型）、不支持 `CHECK` 约束、不支持函数索引。数值范围校验在应用层实现。**注意**：`knowledge_point.mastery_criteria`、`diagnostic_question`、`question_bank.choices` 等 JSON 字段不支持索引，若需按这些字段查询将全表扫描，当前设计不对其建查询条件。
- **多租户约定**：Demo 阶段以固定租户 `'default'` 运行，所有表包含 `tenant_id` 字段（对应产品规则 #7）

### 5.2 核心数据表分组

| 分组 | 表名 | 对应规则 |
|------|------|----------|
| 掌握度状态 | `student_knowledge_state`、`mastery_change_log` | 规则 #2 |
| 学习记录 | `homework_submission_detail`、`assessment_result`、`exam_analysis_result` | 规则 #9 |
| 计划管理 | `daily_plan`、`plan_adjustment_log` | 规则 #3/#5 |
| 题目存储 | `generated_question`（Agent 生成的举一反三新题） | 规则 #3 |
| 基础数据导入 | `knowledge_point`、`question_bank`、`knowledge_point_dependency`、`curriculum_standard`、`knowledge_point_standard`、`data_import_log`、`data_import_error_log` | 规则 #8/#10 |
| 系统日志 | `agent_degradation_log`、`pending_review_alert` | 规则 #6/#8 |

### 5.3 数据不可变性

- 学习记录（规则 #9）与系统日志均**只读不删**，只允许追加
- `mastery_change_log.source_id` 关联学习记录，形成完整追溯链
- `knowledge_point_id` 一经分配不得复用（规则 #8）

### 5.4 各规则实现细节

> 对应 `docs/product/index.md` 中的业务规则，本节补充实现层面的技术细节。

#### 规则 #2 实现细节

- **存储表**：`student_knowledge_state`（含 `mastery_score`、`confidence`、`source`、`last_assessed_at`）
- **追溯日志表**：`mastery_change_log`，记录字段：`student_id`、`knowledge_point_id`、`change_type`（homework/assessment/exam）、`source_id`（关联作业/评测/试卷 ID）、`delta`、`mastery_before`、`mastery_after`、`created_at`
- **D_norm 校验逻辑**：原题使用 `question_bank.difficulty` 映射值；Agent 新题使用声明难度值，与同知识点历史均值偏离 > 0.2 时降级为 0.5
- **难度枚举映射**：`basic` = 0.3、`standard` = 0.6、`challenge` = 0.9（当前仅三档，覆盖 D_norm 范围 0.3–1.0 的中低段；若后续需更高难度档位，扩展枚举即可）

#### 规则 #6 实现细节

- **JSON Schema 定义位置**：`src/main/resources/prompts/schemas/` 目录，按 Agent 类型分文件（`planning_schema.json`、`question_gen_schema.json`、`attribution_schema.json`、`adjustment_schema.json`）
- **Prompt 版本管理**：`src/main/resources/prompts/versions/` 目录，按 `prompt_key` 版本化管理
- **降级日志表**：`agent_degradation_log`，记录字段：`agent_type`、`input_summary`、`failure_reason`、`degradation_action`、`created_at`
- **出题 Agent 降级状态**：`daily_plan.status` 标记为 `pending_generation`
- **归因 Agent 降级状态**：题目标记为 `pending_manual_review`

#### 规则 #7 实现细节

- **登录流程**：学生端通过 `wx.login()` 获取 `code` → 后端调用微信接口换取 `openid` → 签发 JWT
- **JWT payload**：`openid`、`student_id`、`tenant_id`（固定 `'default'`）
- **JWT 刷新**：有效期 2 小时，前端在过期前 5 分钟内请求时后端通过响应头返回新 JWT；已过期时后端返回 401，前端拦截后静默重新 `wx.login()` 换取新 JWT 并重放原请求（单次重放）。**Demo 阶段不实现令牌黑名单/轮换，旧 JWT 在有效期内仍可用；多端登录场景后续扩展**
- **tenant_id 注入**：后端每个接口从 JWT 中提取 `tenant_id` 自动注入查询条件

#### 规则 #3 实现细节（generated_question 表）

- **存储表**：`generated_question`，与 `question_bank`（题库原题）分开存储
- **关键字段**：`knowledge_point_id`（关联知识点）、`difficulty`（Agent 声明难度，用于 D_norm 校验）、`content`（题目内容 JSON）、`source_question_id`（触发生成的原错题 ID）、`similarity_checked`（是否通过相似度校验）、`created_at`
- **可用性约束**：关联知识点被停用（`disabled`）后，该新题不再进入新的每日作业，但已完成作答的记录保留（符合规则 #9 只读不删）

#### 规则 #10 实现细节

**知识点字段映射**（`topics.json` → `knowledge_point` 表）：
- `id` → `knowledge_point_id`（规则 #8 唯一标识）
- `standards[].id` → 通过中间表 `knowledge_point_standard` 关联 `curriculum_standard.id`（多对多）
- `mastery_criteria`（数组）→ JSON 字段
- `diagnostic_question`（对象）→ JSON 字段

**题目字段映射**（`questions.json` → `question_bank` 表）：
- `topicId` → `knowledge_point_id`（关联知识点）
- `choices`（数组，仅选择题）→ JSON 字段（含 `key`、`text`、可选 `figure`）
- `difficulty`（`basic`/`standard`/`challenge`）→ 枚举，映射 D_norm 数值
- `type`（`numeric`/`choice`）→ 枚举

**先修关系字段映射**（`dependencies.json` → `knowledge_point_dependency` 表）：
- `topicId` → `knowledge_point_id`
- `prerequisiteId` → `prerequisite_id`
- `strength`（`hard`/`soft`）→ `dependency_type` 枚举

**导入日志**：
- `data_import_log`：记录导入时间、数据类型、文件来源、成功数/失败数/跳过数
- `data_import_error_log`：记录逐条校验失败的记录 ID、失败原因、所属导入批次

---

## 6. 构建与运行

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 打包（跳过测试）
mvn package -DskipTests
```

---

## 7. 依赖管理

所有依赖通过 `pom.xml` 管理，当前配置：

| 依赖项 | 版本 | 用途 |
|--------|------|------|
| Java | 17 | 运行时 |
| Spring Boot | 3.2.x | Web 框架 |
| Maven | — | 构建工具 |
| MyBatis-Plus | — | ORM 框架（版本待 propose 阶段定版） |
| MySQL | 5.7+ | 数据库 |
| JWT (jjwt) | 0.12.x | 鉴权（JSON Web Token），具体实现库可选 jjwt 或 java-jwt |
| AgentScope Java | — | AI Agent 框架（嵌入 Spring Boot，版本待 propose 阶段定版） |
| JUnit 5 + Mockito | — | 测试框架（详见 `docs/standards/testing.md`） |
