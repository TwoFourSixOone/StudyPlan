# 隐性业务约定

> **本文件是整个 Harness 中最关键的文件之一。**  
> 记录项目中"大家都知道，但没人写下来"的隐性约定。  
> AI 在编码前**必须**阅读本文件，避免"本地能跑、联调出错"的问题。

---

## 1. 使用说明

每条隐性约定应包含：
- **约定内容**：具体描述
- **影响范围**：涉及哪些模块/接口
- **违反后果**：不遵守会导致什么问题
- **发现时间**：何时发现这条约定

---

## 2. 隐性约定列表

### 约定 #1：统一响应结构

- **约定内容**：所有接口必须返回 `Result<T>` 统一响应结构，包含 `code`（状态码）、`message`（提示信息）、`data`（业务数据）。分页接口使用 `Result<PageResult<T>>`。
- **影响范围**：全部 Controller 接口
- **违反后果**：前端无法统一解析响应，导致联调失败
- **发现时间**：2026-10-28

### 约定 #2：异常处理规范

- **约定内容**：业务异常统一抛出 `BusinessException`，携带 `ErrorCodes` 枚举。全局异常处理器 `GlobalExceptionHandler` 负责捕获并转换为统一响应格式。Controller 层不允许 try-catch 吞掉异常。
- **影响范围**：全部 Service 和 Controller
- **违反后果**：异常信息不一致，前端无法根据错误码做对应处理
- **发现时间**：2026-10-28

### 约定 #3：分页参数规范

- **约定内容**：分页参数 `page` 从 **1** 开始（非 0），`size` 默认值为 **10**。前端传入 `page=1` 表示第一页。
- **影响范围**：所有列表查询接口（知识点、题目、先修关系、课程标准、学习记录等）
- **违反后果**：分页数据错位，前端显示错误页码
- **发现时间**：2026-10-28

### 约定 #4：时间格式规范

- **约定内容**：所有时间字段使用 `LocalDateTime` 类型，由 MyBatis-Plus 自动填充 `createTime` 和 `updateTime`。前端展示格式为 `yyyy-MM-dd HH:mm:ss`。
- **影响范围**：全部实体类和 VO
- **违反后果**：时间格式不一致导致前端解析失败
- **发现时间**：2026-10-28

### 约定 #9：@Transactional 必须指定 rollbackFor

- **约定内容**：所有 `@Transactional` 注解必须显式指定 `rollbackFor = Exception.class`。Spring 默认仅对 `RuntimeException` 回滚，checked exception 不会触发回滚，必须显式声明。
- **影响范围**：全部 Service 层事务方法（掌握度更新、计划调整、作业生成、试卷分析、数据导入等）
- **违反后果**：checked exception 发生时事务不回滚，导致数据不一致（如掌握度已更新但变更日志未写入）
- **发现时间**：2026-10-29

### 约定 #10：事务边界精确控制

- **约定内容**：Service 方法中包含外部 API 调用（如 Agent LLM 调用、HTTP 请求等）时，**禁止**使用 `@Transactional` 包裹整个方法。应改用 `TransactionTemplate` 编程式事务，仅将数据库写操作放入事务回调内，外部调用在事务外执行。超时时间建议 ≤ 30s。
- **影响范围**：所有涉及外部 I/O + 数据库写入的 Service 方法（如 `HomeworkServiceImpl.generateDailyHomework`）
- **违反后果**：长事务占用 DB 连接和行锁，外部调用超时（30s+）导致连接池耗尽或死锁
- **发现时间**：2026-11-02

### 约定 #11：并发写入保护模式

- **约定内容**：对可能并发插入的表（如 `daily_plan` 按 `goal_id + plan_date + tenant_id` 唯一），必须先建唯一索引，Service 层写入时捕获 `DuplicateKeyException`，回退到查询已有记录后做业务判断（如拒绝/覆盖/复用）。
- **影响范围**：所有存在并发写入风险的表（当前：`daily_plan.uk_goal_date`、`homework_submission_detail.uk_plan_question`、`generated_question.uk_kp_content_hash`）
- **违反后果**：并发请求产生重复记录，数据不一致
- **发现时间**：2026-11-02

### 约定 #12：Agent 调用超时降级

- **约定内容**：所有 Agent/LLM 调用必须设置超时（建议 30s），超时或异常后触发降级策略（如返回默认计划、记录 `agent_degradation_log`）。降级日志写入前必须脱敏（截断至 500 字符 + 移除 API Key、学生标识等敏感信息）。
- **影响范围**：`QuestionGenAgent`、`HomeworkDegradationHandler` 及后续新增的 Agent 调用
- **违反后果**：Agent 调用无超时导致请求永久挂起；降级日志泄露敏感信息
- **发现时间**：2026-11-02

### 约定 #13：UNIQUE 索引列禁止 DEFAULT NULL

- **约定内容**：参与 UNIQUE 索引的列必须定义为 `NOT NULL`，并提供合理的默认值（如空串 `''`）。MySQL 中 UNIQUE 索引对多个 NULL 值不做唯一性约束，若列允许 NULL 则唯一索引对该行失效。
- **影响范围**：所有新增或修改的唯一索引列（当前：`generated_question.uk_kp_content_hash` 的 `content_hash` 列）
- **违反后果**：唯一索引防重失效，可能导致重复数据写入
- **发现时间**：2026-11-03

### 约定 #14：Agent 层技术异常与业务异常分层

- **约定内容**：Agent 调用产生的技术异常（如 `AgentTimeoutException`、`AgentOutputInvalidException`）与业务异常（`BusinessException` + `ErrorCodes`）分层处理。技术异常由 Service 层捕获后按降级策略处理（如重试、降级为规则校验），不直接向上层透传；若重试耗尽仍失败，则转为 `BusinessException` 报告。
- **影响范围**：所有涉及 Agent 调用的 Service（`SimilarQuestionServiceImpl`、`HomeworkServiceImpl`）
- **违反后果**：技术异常与业务异常混淆，降级策略无法正确触发
- **发现时间**：2026-11-03

### 约定 #15：管理端租户隔离通过 JWT 鉴权

- **约定内容**：管理端接口（`/api/admin/**`）通过 JWT 鉴权实现租户隔离。`WebMvcConfig` 拦截所有 `/api/**`，仅排除 `/api/auth/login` 和 `/api/admin/auth/login`（管理员登录免鉴权）。前端 admin-web 登录成功后将 JWT token 存储于 `localStorage`（key: `VITE_DEMO_TOKEN`），`request.js` 拦截器统一注入 `Authorization: Bearer <token>`，后端从 token 解析 `tenant_id`。
- **影响范围**：`Admin*Controller` 全部接口、`AdminAuthController`、`WebMvcConfig`、admin-web `request.js`
- **违反后果**：管理端接口缺少鉴权导致数据越权；token 未注入导致 401 拒绝
- **发现时间**：2026-11-03（2026-11-05 更新：管理端已独立实现登录认证，不再复用 StudentContext）

### 约定 #16：admin-web 前端路由守卫与登录流程

- **约定内容**：admin-web 前端通过 Vue Router `beforeEach` 守卫检查 `localStorage.getItem('VITE_DEMO_TOKEN')` 是否存在。未登录时自动跳转 `/login`；登录页（`Login.vue`）调用 `POST /api/admin/auth/login` 获取 token 后保存并重定向至首页。公开路由需标记 `meta: { public: true }` 跳过守卫。
- **影响范围**：`router/index.js`、`Login.vue`、`request.js`
- **违反后果**：未登录用户可访问受保护页面；token 丢失后请求全部 401
- **发现时间**：2026-11-05

### 约定 #17：admin-web 构建产物部署方式

- **约定内容**：admin-web 使用 Vite 构建，产物输出至 `admin-web/dist/` 目录。生产环境由后端 Spring Boot 托管静态资源（`spring.web.resources.static-locations` 指向 `dist/`），开发环境通过 Vite `server.proxy` 将 `/api` 请求代理至 `http://localhost:8080`。
- **影响范围**：`admin-web/vite.config.js`、后端静态资源配置、部署流程
- **违反后果**：开发环境跨域请求失败；生产环境前端页面 404
- **发现时间**：2026-11-05

### 约定 #18：uni-app `api/` 目录模块注册异常

- **约定内容**：uni-app 编译至微信小程序（mp-weixin）时，`src/api/` 目录下的 JS 文件不会被正确注册到微信小程序模块系统中，导致 `require` 时报 `module 'api/xxx.js' is not defined`。请求工具、API 接口等文件应放在 `src/utils/` 目录下。
- **影响范围**：student-app 全部前端模块导入
- **违反后果**：运行时模块加载失败，页面白屏或功能不可用
- **发现时间**：2026-11-05

### 约定 #19：微信小程序请求必须使用完整 URL

- **约定内容**：微信小程序环境不支持 Vite `server.proxy` 代理，所有 `uni.request` 必须使用完整 URL（如 `http://localhost:80/api`），不能使用相对路径（如 `/api`）。开发环境指向本地后端，生产环境需替换为正式域名。CORS 配置中 `exposedHeaders` 必须包含 `X-Refresh-Token`。
- **影响范围**：student-app `utils/request.js`、`stores/auth.js` 中的 `uni.request` 调用
- **违反后果**：请求无法发送到后端，登录和所有 API 调用失败
- **发现时间**：2026-11-05

### 约定 #20：微信开发者工具缓存机制

- **约定内容**：微信开发者工具会缓存已编译的小程序代码。源码修改并重新 `npm run build:mp-weixin` 后，必须在开发者工具中执行「工具 → 清除文件缓存 → 清除全部」或重新导入项目，否则运行的仍是旧代码。
- **影响范围**：student-app 全部前端开发调试流程
- **违反后果**：修改源码后页面行为不变，误判为代码无效，浪费排查时间
- **发现时间**：2026-11-05

---

## 3. 维护规则

1. **新增约定**：在开发过程中发现新的隐性约定时，必须立即补充到本文件
2. **OpenSpec 对齐**：在 design.md 阶段，必须显式检查本文件中的约定
3. **评审依据**：reviewer 和 verify 阶段，将本文件作为对齐依据
4. **定期回顾**：每个迭代结束后，回顾是否有遗漏的隐性约定需要补充
