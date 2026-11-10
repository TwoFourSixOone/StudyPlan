# 系统工作原理：总览

> **维护触发**：change 归档时，若本次变更改变了本文件描述的组成部分、请求链路、状态机或外部依赖，必须在同一轮更新本文件。
> **冲突处理**：与 `docs/product/index.md` 冲突时，以 product 为准，并修正本文件。
> **边界**：包结构、分层职责、Maven 依赖以 `docs/architecture/index.md` 为准，本文件不重复。

---

## 1. 系统构成

智学伴是一个「学生端小程序 + 管理端 Web + 单体内嵌 Agent」的 Demo 系统，四个逻辑单元：

| 单元 | 形态 | 入口 |
|------|------|------|
| 学生端 | uni-app（Vue 3 + TS）编译至 mp-weixin | `student-app/src/pages/*`，9 个页面 |
| 管理端 | Vue 3 + Element Plus + Vite，独立 Web 应用 | `admin-web/src/views/*`，开发期 `server.proxy` 把 `/api` 转发到后端 |
| 后端服务 | Spring Boot 3.2.5，单进程 | `StudyPlanApplication`，默认端口 80 |
| Agent | 进程内嵌（AgentScope Java），非独立服务 | `com.jiang.agent.*` |

## 2. 请求链路

```
小程序 / 浏览器
  → WebMvcConfig 注册的 JwtInterceptor（仅 /api/**，登录接口除外）
  → StudentContext（ThreadLocal：studentId / openid / tenantId）
  → Controller（只做参数校验 + 取上下文）
  → Service（业务编排、事务）
  → Mapper（MyBatis-Plus；仅 LearningRecordMapper 用 XML）
```

要点：

- **身份只从 JWT 取**，Controller 不接受客户端传入的 `studentId`/`tenantId`
- `afterCompletion` 清理 ThreadLocal；JWT 距过期 < 5 分钟时通过 `X-Refresh-Token` 响应头下发新令牌
- 异常统一由 `GlobalExceptionHandler` 转换：`UNAUTHORIZED` → HTTP 401，其余 `BusinessException` → **HTTP 200 + body.code**

## 3. 鉴权与租户

- 学生与管理端**共用同一套 JWT 结构**（管理员 openid 为 `admin_<username>`），当前无角色字段，靠路径前缀 `/api/admin/**` 区分
- 租户为固定 `'default'`：所有表 `tenant_id` 非空默认值，查询条件显式带上
- 管理端登录当前为硬编码账号（`AdminAuthServiceImpl`），无 admin 表

## 4. 数据表分组（26 张）

| 分组 | 表 |
|------|----|
| 基础数据 | `subject`、`student`、`learning_goal`、`knowledge_point`、`knowledge_point_dependency`、`knowledge_point_standard`、`curriculum_standard`、`question_bank` |
| 计划与作业 | `daily_plan`、`generated_question`、`homework_submission_detail` |
| 评测与考试 | `assessment_result`、`assessment_submission_detail`、`exam_paper`、`exam_question`、`exam_analysis_task`、`exam_analysis_result` |
| 错题 | `error_question` |
| 掌握度 | `student_knowledge_state`、`mastery_change_log` |
| 计划调整 | `plan_adjustment_diff`、`plan_adjustment_log` |
| 导入 | `data_import_log`、`data_import_error_log` |
| 系统日志 | `agent_degradation_log`、`pending_review_alert` |

> 建表脚本按 `V1 → V13` 顺序人工执行（**仓库未引入 Flyway**，"脚本只执行一次"需人工保证）。`plan_adjustment_log` 由 V7 创建，V1 不再重复建表。

## 5. 核心状态机

| 对象 | 状态 | 迁移 |
|------|------|------|
| `daily_plan.status` | `pending` → `in_progress` → `completed`；另有 `pending_generation`、`cancelled` | 首次提交答案 → `in_progress`；批改成功 → `completed`（带 `WHERE status=in_progress` 状态机校验，影响行数 0 抛并发冲突）；确认 diff 且内容为空 → `pending_generation` |
| `error_question` | `active` / `paused` / `completed`，`current_stage` 1–5 | 答错入队 stage=1；答对 `advanceStage`；掌握度 ≥0.8 批量暂停，跌破后重新激活；stage > 5 → `completed` |
| `plan_adjustment_diff.status` | `pending` / `confirmed` / `rejected` / `expired` | 过期由定时任务批量置 `expired` |
| `assessment_result.status` | `pending` → `graded` | 提交后批改 |

## 6. 外部依赖

| 依赖 | 用途 | 不可用时的行为 |
|------|------|---------------|
| MySQL 5.7+ | 全部业务数据 | 启动失败（Hikari 连接超时） |
| Redis | 试卷解析任务队列（`exam_analysis_queue`） | 上传接口失败 |
| 微信 `jscode2session` | openid 换取 | 登录失败（返回未登录态） |
| DashScope（`qwen3.8-flash`） | 四类 Agent 的 LLM 调用 | 按 Agent 各自降级（见 `agent-contracts.md`） |

## 7. 已知未落地

| 项 | 现状 | 影响 |
|----|------|------|
| 试卷 OCR | `TencentOcrServiceImpl` 为占位，直接抛 `OcrException` | 试卷上传后的解析链路走不通（规则 #4 未闭环） |
| 规划 Agent | 仓库中无 `PlanningAgent`；每日计划由 `HomeworkServiceImpl` 按知识点与配额直接生成 | 产品规则 #6 的四类 Agent 实际只有三类 + 一个出题降级处理器 |
| 管理端生产托管 | 仓库内无 `spring.web.resources.static-locations` 配置 | 生产环境如何托管 `admin-web/dist` 未在代码中体现 |
| 多租户 | 固定 `'default'` | — |

## 8. 相关文件

- 学习闭环细节 → `learning-loop.md`
- Agent 输入输出与降级 → `agent-contracts.md`
- 掌握度与学习记录 → `mastery-and-records.md`
- 业务规则（唯一依据） → `docs/product/index.md`
- 隐性约定 → `docs/architecture/implicit-contracts.md`
