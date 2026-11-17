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

> **编号说明（2026-11-10 补）**
>
> 1. 编号 #5–#8 在本文件中**空缺**（历史遗留），**不复用**，新增约定统一从 #21 起顺序编号（起点已按序顺延，当前见第 3 条）。保留空号是为了不破坏已归档 change 中对 #9–#20 的既有引用（`archive/` 只读，无法回改）。
> 2. 历史工件中出现的「隐性约定 #7 JWT 登录态」为**悬空引用**——该条不存在，其真实出处是 **产品规则 #7**（学生登录态与数据隔离）与 **约定 #15**（管理端 JWT 鉴权）。后续新增工件请直接引用这两处。
> 3. （2026-11-15 补）当前最大编号为 **#36**，新增约定从 **#37** 起顺序编号；以 §3 维护规则第 2 条为准。

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

### 约定 #18：uni-app 接口文件目录选择（2026-11-10 修订）

- **约定内容**：uni-app 编译至微信小程序（mp-weixin）时，`src/api/` 下的**纯 JS 文件**可能无法被注册到小程序模块系统，`require` 时报 `module 'api/xxx.js' is not defined`。当前项目的做法：
    - **`.ts` 接口文件**：放 `src/api/*.ts`（如 `api/error.ts`），由 Vite + TS 编译，mp-weixin 下正常工作；**不要因为历史结论把它们挪走**
    - **`.js` 接口文件**：若必须用 JS，放 `src/utils/`，规避上述注册问题
    - **请求工具**：`utils/request.ts` / `utils/upload.ts` 始终放 `utils/`，与业务接口文件分离
- **补充（2026-11-10，年级设置联调）**：即使 `.ts` 文件放在 `src/api/`，**"新增"模块仍可能在运行期不注册**——文件已在 `dist/dev/mp-weixin/api/student.js`，但 `require` 报 `module 'api/student.js' is not defined`；重建产物、清缓存后仍不稳定。稳妥做法：
    - **新增接口函数一律并入已有 `api/*.ts` 模块**（如 `updateGrade` 并入 `api/auth.ts`），不要为此新建文件
    - **新增 `utils/*.ts` 同样有风险**；只被单个页面使用的小工具直接内联在该页面
    - 若确实必须新建模块，需在**停掉 watcher → 删除 `dist/dev` → 重启 watcher → 开发者工具清缓存**之后验证，仍失败则改为并入已有模块
- **影响范围**：student-app 全部前端模块导入
- **违反后果**：运行时模块加载失败，页面白屏或功能不可用
- **发现时间**：2026-11-05
- **修订时间**：2026-11-10
- **修订原因**：原约定成立前提是「uni-app 早期版本 + 纯 JS 文件」。项目迁 TypeScript 并经 Vite 编译后该限制不再成立（`api/*.ts` 已稳定运行），若继续按原文执行会把正常文件无谓挪到 `utils/`。**保留原编号以免破坏历史引用，仅更新内容。**


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

### 约定 #21：枚举的「DB 形态」与「API 形态」必须成对修改

- **约定内容**：带持久化语义的枚举存在两种形态，修改其中一处时必须同步另一处：
    - **DB / MyBatis 形态**：数据库列与 XML/注解 SQL 使用**枚举名**（如 `HOMEWORK`），MyBatis 按 `name()` 映射
    - **API 形态**：实体/枚举上的 `@JsonValue` 字段提供**小写 code**（如 `homework`），供前端筛选与展示
    - 前端展示需再做 code → 中文标签映射，不得直接展示 code
- **影响范围**：`RecordType`（已踩两次）、`PlanStatus`、`ErrorQuestionSourceType`、`AgentType` 及所有落库枚举
- **违反后果**：只改一边时——SQL 侧会因枚举名不匹配导致 `IllegalArgumentException` / 映射失败；API 侧会让前端拿到英文枚举名（如记录类型显示 `HOMEWORK`）
- **发现时间**：2026-11-09（学习记录类型显示英文，根因是 XML 写小写、枚举按大写映射）

---

### 约定 #22：线程池统一在 `ThreadPoolConfig` 中声明

- **约定内容**：
    - 所有业务线程池**只在** `com.jiang.config.ThreadPoolConfig` 中通过 `@Bean` 声明，**禁止**在新的 `@Configuration` 类里另建线程池
    - 一律使用工厂方法 `createExecutor(namePrefix, coreSize, maxSize, queueCapacity)`，从而统一获得：有界队列 + `CallerRunsPolicy` 背压 + 线程命名 + `UncaughtExceptionHandler` + 创建日志
    - 池大小、队列容量、关闭等待秒数走配置项 `thread-pool.<pool>.core-size / queue-capacity / await-terminate-seconds`，**不得硬编码**
    - 新增线程池必须同步注册到 `executorShutdownManager(...)`，否则容器关闭时不会优雅终止
- **影响范围**：`aiExecutor`、`examAnalysisExecutor`、`planningExecutor` 及后续新增线程池
- **违反后果**：
    - 分散创建的线程池没有 `UncaughtExceptionHandler`，任务异常被静默吞掉
    - 未注册到关闭管理器 → 停机时任务被强杀，可能丢任务或留下半写数据
    - 使用无界队列或缺省拒绝策略 → 高负载下 OOM 或任务静默丢弃
- **发现时间**：2026-11-10（规划细化线程池最初建在独立的 `PlanningConfig` 中，被要求合并回 `ThreadPoolConfig`）
- **正确做法**：在 `ThreadPoolConfig` 中加一段 `@Bean(name = "xxxExecutor", destroyMethod = "")` + `createExecutor(...)`，并在 `executorShutdownManager` 的 `Map.of(...)` 中登记

---

### 约定 #23：送给 LLM 的年级必须用中文名称

- **约定内容**：
    - 落库与接口仍用 **1–12 数字编码**（产品规则 #11）
    - 拼 Prompt 时**必须转成中文年级名**（"高三"），统一走 `GradeMatcher.displayName(raw)`，**禁止**把 `"12"` 直接塞进 Prompt
    - 未设置年级时传"当前"，不要传空串或 `null`
- **影响范围**：`analysis_gen_v1.txt`、`grading_v1.txt` 及后续任何含 `{grade}` 占位符的 Prompt
- **违反后果**：模型对 `"12"` 易按"12 年级"（美式学制）理解，解析/批改可能按错误学段生成，偏离 K12 语境
- **发现时间**：2026-11-10（联调时 Prompt 显示"学生当前年级：12"）
- **正确做法**：`prompt.replace("{grade}", GradeMatcher.displayName(grade))`，空值用"当前"兜底

---

### 约定 #24：面向小程序的 LLM 文本必须清洗为纯文本

- **约定内容**：
    - 小程序页面以**纯文本**渲染 AI 输出，不支持 LaTeX / Markdown
    - **Prompt 侧（2026-11-11 修订）**：禁止 `$...$`、`**`、`#`、反引号；**禁止任何反斜杠命令**（`\vec`、`\frac`、`\sqrt`、`\cdot` 等）与 **`^{...}` / `_{...}` 花括号写法**；**允许纯文本 `x^2`、`a_n`**；幂**优先** `²/³`（如 `x²`）或 `^2`，下标**优先** `a(n)` 或中文（如"a 下标 n"）；常用符号用 `√`、`≤`、`≥`、`×`、`÷`、`θ=90°`、`1/2`
    - **代码侧（2026-11-11 修订）**：模型返回后必须过 `LlmTextSanitizer.sanitize(...)` 兜底：去定界符/Markdown、**`^{x}` / `_{x}` → `^(x)` / `_(x)`**、反斜杠命令 → 可读符号（`\frac`→`/`、`\sqrt`→`√`、`\vec`→"向量"）、清控制字符；**不移除 `^` 本身**
    - **覆盖范围（2026-11-11 补充）**：不仅解析与批改，**题干 `stem` 与选项 `choices[].text` 同样必须清洗**（`buildHomeworkVO` 统一过一遍），否则生成题选项会直接暴露 `\sqrt{10}` 这类原文；**试卷视觉解析（`exam_parse_v1.txt`）**的 `stem`/`choices[].text`/`correctAnswer`/`explanation`/`studentAnswer` 均需清洗
- **影响范围**：题目解析（`analysis_gen_v1.txt`）、批改、试卷分析、**出题 Prompt 的 stem/choices**（`question_gen_v1.txt`、`similar_question_gen_v1.txt`）、**试卷视觉解析（`exam_parse_v1.txt`）**
- **违反后果**：页面出现 `$ec{a}$`（`\v` 被当转义）、`**解析：**` 等乱码，学生无法阅读；或选项显示 `\sqrt{10}`、`\frac{1}{2}` 字面量
- **发现时间**：2026-11-10（题目解析显示 `$ec{a}$` 与未渲染的 Markdown）；2026-11-11 补充题干/选项未覆盖，并修订记号口径（禁花括号/反斜杠、允许 `x^2`）
- **正确做法**：Prompt 明确禁止 + `LlmTextSanitizer` 兜底，二者缺一不可（模型不一定遵守，清洗也有边界）

### 约定 #25：选择题选项必须在落库前归一化，解析端必须容错

- **约定内容**：
    - 系统内**唯一标准形态**：`choices = [{"label":"A","text":"..."}]`，`answer` 为**选项标签**（A/B/C/D），不是选项内容
    - **Prompt 侧**：必须写清上述结构与 `answer` 取值规则（模型看不到 JSON Schema，Schema 只是校验用）
    - **落库侧**：`QuestionGenAgent` 解析后必须过 `ChoiceOptions.canonicalize(...)`，标签重排为 A/B/C… 连续
    - **出参侧**：`HomeworkServiceImpl` 必须用 `ChoiceOptions.normalize(...)` 兼容 `{key,text}`（题库）、字符串数组、`{"A":"..."}` 映射；`HomeworkVO.choices` 只放选项文本，label 由前端按序号渲染（不要拼 `"A. "` 前缀，否则会与前端标签重复）
    - **校验侧**：`formatType=choice` 且有效选项 < 2 时判为非法输出，不得落库
- **影响范围**：出题 Agent（知识回顾 + 举一反三）、`buildHomeworkVO`、小程序答题页
- **违反后果**：选择题渲染成「有题干、无选项、无法提交」的死题；或选项标签重复显示为「A A. 4」；或学生提交 `B` 而标准答案是选项文本，导致**全判错**
- **发现时间**：2026-11-11（举一反三选择题选项区空白）
- **正确做法**：`ChoiceOptions.canonicalize` 落库归一化 + `ChoiceOptions.normalize` 出参容错 + 前端 `choices.length` 空数组兜底

### 约定 #26：考试试卷来源的题目使用负 ID 命名空间，题面按段落文本编码

- **约定内容**：
    - `error_question.question_id` 对考试来源存 **`-exam_question.id`**（负值）；`daily_plan.content[].questionId` 与 `homework_submission_detail.question_id` 在 `questionSource="exam"` 时**同用该负值**
    - 原因：`uk_student_question(student_id, question_id, tenant_id)` 与 `uk_plan_question(plan_id, question_id, tenant_id)` 要求跨来源唯一，而 `question_bank` / `generated_question` 是自增正数，正数 `exam_question.id` 会与其碰撞 → 提交行互相覆盖
    - 题面：`exam_question.question_content` 以 `【题干】【选项】【正确答案】【解析】【作答状态】` 段落文本承载，由 `ExamQuestionTextCodec` 编解码（零 DDL，`exam_question` 无 `choices` / `correct_answer` 列）
    - **任何"按 id 查题库"的代码必须先按 `questionSource` / `source_type` 分流**；`selectCriticalErrors` 保留 `question_id > 0` 防御（负 ID 对调整 Agent 无意义）
- **影响范围**：`ErrorQuestionService`、`HomeworkServiceImpl`（redo 组装/题面/判分/`advanceStage`）、`ExamPaperServiceImpl`、`ErrorQuestionMapper`
- **违反后果**：题库题与试卷题的提交记录互相覆盖；或把负 ID 当 `question_bank.question_id` 查询导致空题面/异常；错题本详情空白
- **发现时间**：2026-11-11（v3 试卷错题接入原题重做）
- **正确做法**：`ExamQuestionRefs` 集中做 `-id` 转换，禁止散落负号；错题本/详情 SQL 用 `LEFT JOIN exam_question eqx ON eq.source_type='exam' AND eqx.id = -eq.question_id`

### 约定 #27：本地存储的试卷图片必须以 base64 内联交给视觉模型

- **约定内容**：
    - `file.storage.type=local` 时图片 URL 指向本机静态目录，**外部模型无法访问**；调用视觉模型前必须由服务端 `FileStorageService.readBytes(url)` 读字节 → base64 → `ImageBlock(Base64Source)` 内联，**禁止把 URL 直接交给模型**
    - **内存约束**：逐图读取/生成/释放，峰值 ≤3 份（全局 LLM 并发 3）；**禁止**把 base64 写入 DB / Redis / 降级日志
    - **调用约定**：`POST {AGENT_ENDPOINT}/api/v1/services/aigc/multimodal-generation/generation`，body 中 `input.messages[0].content` 为 `[{"image":"data:image/jpeg;base64,..."},{"text":"..."}]`，响应取 `output.choices[0].message.content[0].text`
- **影响范围**：`AgentScopeClient.callVision`、`ExamParseService`、`FileStorageService`（local / COS）
- **违反后果**：模型侧取不到图（空响应/超时）；或一次性批量 base64 导致 OOM
- **发现时间**：2026-11-11（v4 视觉解析）
- **正确做法**：单图任务内读取 → 调用 → 释放；非 local 存储未实现时抛 `1188`

### 约定 #28：未覆盖知识点只有一套口径，且必须扣除本轮暂缓

- **约定内容**：
    - 「未覆盖知识点」= 该科目下**年级内**（`grade_range` 命中学生年级）且**从未学过**（`mastery_score` 为 NULL 或 0）的 `knowledge_point`，并**扣除目标上本轮暂缓的 `learning_goal.paused_kp_ids`**
    - 唯一实现：`com.jiang.common.util.KpCoverage`（`uncoveredRows` / `countUncovered` / `capacity`）；**禁止**在 Service 里再写一套 `stream().filter(...)`
    - 暂缓 JSON 解析统一走 `PausedKpIds`（失败降级为空集合，不抛异常）
    - 三个消费点必须同口径：`SignalDetectorImpl.detectSIG4`（信号判定）、`PlanAdjustmentServiceImpl.buildAdjustmentContext`（Agent 上下文，先过滤后 `limit(30)`）、`GoalServiceImpl.createGoal`（容量预检）
- **影响范围**：`KpCoverage`、`PausedKpIds`、`SignalDetectorImpl`、`PlanAdjustmentServiceImpl`、`GoalServiceImpl`、`HomeworkServiceImpl`
- **违反后果**：暂缓的知识点既不出题又计入压力 → SIG-4 每天必中、每天重新暂缓一批，调整 diff 空转、LLM 成本翻倍（2026-11-12 联调实测：goal 7 为 6 天目标 + 174 未覆盖，容量 14.4，SIG-4 从创建首日恒真）
- **发现时间**：2026-11-12（日志分析 `4.0 天 × 3.0 × 0.80 = 9.6 < 174` 每日重复）
- **正确做法**：先 `KpCoverage.uncoveredRows(rows, studentGrade, PausedKpIds.parseSet(goal.getPausedKpIds()))`，再计数或截断

### 约定 #29：举一反三质量门常量以产品规则 #3 为准，不得各自定义

- **约定内容**：
    - 题干相似度阈值 **0.8**（`SimilarityDetector.SIMILARITY_THRESHOLD`），判定为「大于」阈值才拒绝，等于阈值放行
    - 重试 **首轮 + 最多 2 次**（`SimilarQuestionServiceImpl.MAX_ATTEMPTS = 3`），降级日志文案按「重试次数 = MAX_ATTEMPTS − 1」渲染
    - 语义门判据（`question_check_v2.txt`）针对的是「**是否换皮**」：情境/数据/设问三项几乎一致才判 `passed=false`；**同知识点同解法不同情境属于合格变式**，必须放行
    - 跨图去重是**另一条**独立口径（`ExamParseServiceImpl.CROSS_PAGE_DUPLICATE_THRESHOLD = 0.9`），不得混用
- **影响范围**：`SimilarityDetector`、`SimilarQuestionServiceImpl`、`QuestionCheckAgent`、`prompts/versions/question_check_v2.txt`
- **违反后果**：阈值偏低（0.6）会把同知识点同类题判成重复，5 轮全失败 → 单知识点最多 15 次 LLM 调用、长期占用全局 3 并发许可，进而放大 DashScope `PT2M` 超时（2026-11-12 日志：`similarity=0.625` 被拒、`cne_h084` 连续 10 次失败）
- **发现时间**：2026-11-12（代码 0.6/5 轮与规则 #3 的 0.8/2 次不一致）
- **正确做法**：改常量前先改规则 #3；Prompt 变更走版本递增（v1 保留）

### 约定 #30：小程序端「批量上传」只能是并发单文件请求

- **约定内容**：
    - `uni.uploadFile` / `wx.uploadFile` **一次只能传一个文件**（`filePath` 单值，`name` 为后端 multipart 字段名），
      小程序端**不存在**"一个请求带多张图"的原生能力
    - 「一次性上传」的实现语义 = **一次点击 → 同时发出所有单文件上传请求**：试卷上传页
      `UPLOAD_CONCURRENCY = MAX_IMAGES`（8），与微信 uploadFile 并发上限 10 留出余量
    - 若要单请求多文件，只能手写 multipart（`readFile({encoding:'base64'})` 常驻内存）或改 JSON+base64 数组；
      两者都与后端**单文件魔数校验、9.5MB/30MB 累计校验、同卷分段锁**冲突，且 30MB 场景有 OOM 风险 → **禁止**
    - 后端保持「建会话 → 逐张追加（字段 `file`）→ submit」；N 张的批量只是并发 N 个单文件请求
- **影响范围**：`student-app/src/pages/exam-upload/ExamUpload.vue`、`utils/upload.ts`、
  `ExamPaperController.uploadImage`、`ExamPaperServiceImpl.uploadImage`
- **违反后果**：改为单请求多文件后，后端单文件校验直接不通过（或需要推翻整套校验与锁）；
  手写 multipart 则 8 张 × 2–4MB 的 base64 常驻内存可能直接触发小程序 OOM
- **发现时间**：2026-11-12（「选择多个图片后一次性上传」需求评估）
- **正确做法**：并发单文件上传 + 每张独立失败/重试状态；满 8 张时后端自动触发解析，
  前端先按详情探活再决定是否显式 `submit`

### 约定 #31：LLM 调用必须关闭深度思考，并留下每次调用的耗时

- **约定内容**：
    - 本项目所有 Agent 调用都是**结构化输出**（切题 JSON / 出题 JSON / 判分 / 归因映射 / 调整 diff），
      必须显式设置 `agent.agentscope.enable-thinking: false`（SDK：`DashScopeChatModel.builder().enableThinking(false)`）
    - 原因：思考 token **不进入结果**，却占用 `max_tokens` 预算 → `finish_reason=length` → JSON 被截断
      → `AgentOutputInvalidException` → 重试 2 次（`maxAttempts=3`）→ 单图 13s 放大成 39s+
    - 每次调用必须记录耗时与规模：`LLM 文本调用完成: cost/promptChars/respChars`；
      视觉调用再加 `imageCount/imageBytes`，**禁止**输出 base64 或图片内容（#27）
    - `LlmConcurrencyLimiter` 获取许可等待 ≥1s 必须 WARN（区分「排队慢」与「模型慢」）
- **基线（2026-11-12 实测，网关 `ws-*.maas.aliyuncs.com`）**：短文本 0.70s；
  单张试卷图切题 关闭思考 **6.55s / finish_reason=stop**，开启思考 **13.08s / finish_reason=length**
- **影响范围**：`AgentScopeClient`、`AgentScopeConfig`、`LlmConcurrencyLimiter`、`application.yml`
- **违反后果**：后端调用明显慢于网页（网页是流式 + 短输出）；JSON 截断后重试，最坏每次调用 3×
- **发现时间**：2026-11-12（"后端调百炼很慢、网页直连很快"排查）
- **正确做法**：`enableThinking(false)` + `cost` 日志；确需推理质量的场景按 Agent 单独置 `true`，
  并同步调整 `max_tokens` 与 `timeout`

### 约定 #32：考试归因候选 = 同学段且不高于学生年级

- **约定内容**：
    - 考试知识点候选的年级过滤**不走** `GradeMatcher.matchesRange`（精确命中），而走
      `GradeMatcher.matchesRangeAtOrBelow`：**同一学段**（小学/初中/高中）且**范围上界 ≤ 学生年级**
    - 例：高三数学候选含「10」「11」「12」「10-12」，**不含**「7-9」「9」「3」；高一候选不含「11」「12」「10-12」
    - 跨学段范围（如 `7-12`）无法判定单一学段 → 不匹配
    - 切片：parse ≤300（跨年级后候选变多）、attribution ≤500
- **影响范围**：`ExamKpCandidateServiceImpl`、`GradeMatcher`、`exam_parse_v2.txt` 的 `{knowledgePoints}`
- **违反后果**：只按学生年级过滤会让高考必考的集合/复数（标注在 10/11 年级）永远待人工归因（2026-11-12 实测 2 题）
- **发现时间**：2026-11-12（试卷 4 的 Q6 复数、Q7 集合归因失败）
- **正确做法**：候选构造统一用 `matchesRangeAtOrBelow`；出题/复习仍用精确年级（产品规则 #11 不变）

### 约定 #33：选择题答案按「标签集合」判定，多选少选都算错

- **约定内容**：
    - 选择题答案的标准形态是**标签串**：单选 `B`，多选按字母序连写 `ABD`
    - **单选/多选必须显式区分**：解析模型输出 `choiceMode`（single/multi），落 `exam_question.choice_mode`（V15）；
      详情页按它显示「单选/多选」。**不得只用答案标签数推断**——多选可能只有一个正确标签（真实卷 第 9 题）
    - 归一与比对统一走 `ChoiceAnswer`：`labels()` 抽标签（`"A、B"`/`"ba"`/`"选ABD"` 都能解）、`sameAnswer()` **严格集合相等**
    - 判分口径（产品规则 #4）：多选/少选都判错；1 题 1 分制不做部分分（卷面"部分选对得部分分"暂不实现，需另立 change）
    - 选择题答案标签必须都能在本题选项里找到（`ChoiceAnswer.withinOptions`），否则答案置 null
- **影响范围**：`ExamParseAgentImpl`（解析答案归一）、`AnswerGradingServiceImpl`（判分）、`ChoiceOptions.canonicalize`（落库归一）、`ExamPaperServiceImpl`（`multiChoice` 出参）
- **违反后果**：多选答案被单标签校验（`matches("[A-H]")`）置空 → 整题判错；或学生写 `BA` 与 `AB` 被判不一致
- **发现时间**：2026-11-12（试卷出现多选题）
- **正确做法**：所有 choice 比较都走 `ChoiceAnswer.sameAnswer`，禁止再写 `equalsIgnoreCase`

### 约定 #34：题目配图由模型给归一化区域、服务端裁剪，失败回退整页原图

- **约定内容**：
    - **只取卷面印刷图形**：`figureRect` 必须框印刷的几何图/图象/统计图（含印刷坐标轴、标注），
      **禁止框学生手写、铅笔草稿、辅助线、解题过程**；学生自己画的图（卷面无印刷图）→ `hasFigure=false` 且不输出 `figureRect`
    - 视觉解析对含图形/图表的题目输出 `figureRect`：**相对本张图片**的归一化坐标 `{x,y,width,height} ∈ [0,1]`，
      框住图形本身（含坐标轴/标注），不框题干文字；`width/height < 0.05` 或无法定位时省略
    - 服务端 `ImageCropper` 按区域裁剪（四周 3% 留白），编码 JPEG，经 `FileStorageService.uploadBytes`
      落到 `exam-figures/{studentId}`，URL 写入 `exam_question.question_image_url`
    - **二次确认**：裁剪块必须再经视觉模型确认是"印刷图形"（`figure_check_v1.txt` + `FigureCheckDecision`），
      判为题号/分值/文字/学生手写/空白 → 丢弃裁剪块、回退整页原图；调用失败或回答含糊时保留裁剪块
    - **回退顺序**：裁剪失败/区域非法/非图片 → 使用**整页原图 URL**（学生至少能看到图），不得因此让解析失败
    - **文件 IO 必须在事务外**：读原图、裁剪、写裁剪件都在 TX-A 之前完成，事务内只做批量 INSERT
- **影响范围**：`exam_parse_v2.txt`、`ExamParseAgentImpl`（`figureRect` 解析与校验）、`ImageCropper`、
  `FileStorageService.uploadBytes`、`ExamPaperServiceImpl.persistQuestions`、`ExamQuestionItemVO`、`ExamDetail.vue`
- **违反后果**：带图题目看不到图（学生无法作答/复核）；或把裁剪 IO 放进事务导致长事务、连接池被占
- **发现时间**：2026-11-12（试卷 17 题几何图在详情页缺失）
- **正确做法**：模型给区域 → 服务端裁剪 → 失败回退整页；配图 URL 与题目同批落库

### 约定 #35：试卷解析走「整卷一次调用」，配图以 base64 落库

- **约定内容**：
    - 同一份试卷的全部图片（题目页 + 可能的答题卡页）**一次性**发给视觉模型（`exam_parse_paper_v1.txt`）；
      模型输出**全卷连续题号**与每题 `imageIndex`（跨页题合并、图形在另一图时 `figureRect` 以图形所在图为准、
      学生答案从答题卡按题号匹配）；单次调用失败/为空 → **回退逐图解析**（`exam_parse_v2.txt`）
    - 请求体控制：图片总字节 >6MB 时降采样到宽 1600 / quality 0.82；`max_tokens` 显式 8000（否则全卷 JSON 被截断）；
      整卷预算 300s、前端轮询 5 分钟
    - 题目配图裁剪件以 `data:image/jpeg;base64,...` 写入 `exam_question.figure_base64`（MEDIUMTEXT，V14），
      详情页优先渲染 base64，**不再依赖文件服务 URL**（`localhost:8080` 一类地址在真机不可达，曾导致配图空白）
    - 裁剪失败：`figure_base64=NULL` + `question_image_url=整页原图`；文件 IO 全在事务外
- **影响范围**：`ExamParseServiceImpl`、`ExamParseAgent`、`ExamPaperServiceImpl`、`ExamQuestionMapper`、
  `ExamQuestionItemVO`、`ExamDetail.vue`、`V14__add_exam_question_figure_base64.sql`
- **违反后果**：配图空白（URL 不可达）、多选漏判、跨页题被拆成两道、答题卡答案丢失、全卷 JSON 截断导致降级
- **发现时间**：2026-11-12（真实卷：配图空白 + 多选题被判单选 + 答题卡答案未匹配）
- **正确做法**：整卷单次 + base64 落库；`figureBase64 || questionImageUrl` 双兜底渲染

### 约定 #36：试卷诊断聚合只读，固定 4 条 SQL、不触发 LLM 与计划调整

- **约定内容**：
    - `GET /api/student/exam/papers/{paperId}/diagnosis`（`ExamPaperController#getDiagnosis` →
      `ExamPaperServiceImpl#getDiagnosis` → `ExamDiagnosisVO`）是**新用户引导方案 A** 的只读聚合，固定 **4 条 SQL**：
      ① `selectDetailWithSubject`（paper + 科目名 + 归属校验）② `selectByPaperOrderByIndex`（题目按卷，复用详情接口同一查询）
      ③ `countActiveExamErrorsByPaper`（该卷来源活跃错题数）④ `selectNamesWithMasteryByIds`（薄弱知识点名称 + 掌握度批量，
      无薄弱点时跳过）。**禁止循环查库（N+1）**，新增指标须复用批量查询
    - **只读无副作用**：不写库、不更新掌握度、不写学习记录、**不触发 LLM 与计划调整**，也不会触发考试结算后的信号检测
      （诊断与结算链路完全解耦）；重复调用结果不变；`exam_paper.grade_status` 仍由解析/结算负责
    - **归属校验统一 1136**：试卷不存在 / 非本人 / 非本租户一律 `EXAM_RESULT_NOT_FOUND(1136, "分析结果不存在")`，
      不区分存在性（与详情接口同口径，避免探测他人数据）
    - **口径只有后端一套实现**（详见产品规则 #4「诊断结果」）：正确率 = `correctCount / judgedCount`
      （0–1、scale=4、HALF_UP；`judgedCount=0 → accuracy=null`，不按 0 折算）；薄弱知识点按
      `wrongCount desc → masteryScore asc（null 视为 0）→ kpId asc` 截断 **Top 10**，`kpId` 为空的答错题不计入；
      错题数 = `source_type='exam' AND source_id=paperId AND status='active'`（负 ID 命名空间见约定 #26）。
      前端**不得自行重排/重算**，只做展示（×100 百分比、judged=0 显示「—」、得分按 `userScore/totalScore`）
    - **空数据不抛异常**：未解析完成 / 0 题返回 **200**（计数 0、`accuracy=null`、薄弱点空数组）；
      `confidenceNote` 由后端给固定文案（「基于 1 份试卷的诊断，仅供参考；建议持续完成每日作业后复看掌握度」），前端不拼接
    - 诊断 VO **不含 `status`**：前端在 `totalQuestions=0` 时用试卷详情接口的 `status` 区分解析中 / 解析失败 / 未提交，
      解析中每 2s 轮询（约 5 分钟上限），`onUnload` 必须清理定时器
- **影响范围**：`ExamPaperController#getDiagnosis`、`ExamPaperServiceImpl#getDiagnosis`、`ExamDiagnosisVO` / `ExamDiagnosisWeakKpVO`、
  `ErrorQuestionMapper#countActiveExamErrorsByPaper`、`KnowledgePointMapper#selectNamesWithMasteryByIds`、`ExamDiagnosisServiceTest`、
  `student-app/src/pages/exam-diagnosis/ExamDiagnosis.vue`、`student-app/src/api/exam.ts#getExamDiagnosis`
- **违反后果**：在诊断里触发 LLM / 结算 / 调整会产生「还没建目标就先调整计划」或重复写掌握度的副作用；前端自算 Top 10 / 正确率
  会与掌握度看板口径漂移；错题数不走约定 #26 的负 ID 命名空间会数错；越权返回 1136 之外的信息可探测他人试卷
- **发现时间**：2026-11-15（新用户引导方案 A：设置年级 → 试卷诊断 → 建目标预填）
- **正确做法**：诊断只聚合、不写库；排序 / 截断 / 文案全部由后端给；空数据 200；判分门禁：上传页等 grade_status=graded 再跳诊断页（2s×150，超时给出口），诊断页未判分（judgedCount=0）轮询 gradeStatus 到 graded 后刷新，judged=0 得分显示「—」 + 前端按详情 `status` 兜底；归属错误统一 1136

### 约定 #37：GLM-OCR 只做「文本真值」与 bbox 参考，且必须可降级

- **约定内容**：
    - 整卷解析前对每页调用 GLM-OCR（`POST /api/paas/v4/layout_parsing`，`model=glm-ocr`，file 支持 url/base64），
      把 `md_results`（Markdown，含表格 HTML）作为 **OCR 文本** 注入视觉解析 Prompt；`layout_details` 的
      `label=image` bbox 用于**吸附**题目 `figureRect`（`OcrFigureSnapper`，中心距 ≤0.35）
    - Prompt 规则：题干/选项/表格内容**以 OCR 文本为准**；表格必须内联进题干；**学生手写仍只从图片读**；
      OCR 缺失的页按图片识别
    - **降级（必须）**：无 API Key / 开关关闭 → 完全不启用；单页超时/非 200/非法响应 → 跳过该页；
      总预算（默认 90s）到点 → 停止剩余页；全部失败 → 写降级日志 `ocr_grounding_skip` 后按纯视觉解析；
      部分失败 → `ocr_grounding_partial`；单页注入字符上限 6000（防 prompt 爆炸）
    - Key 从环境变量 `ZHIPU_API_KEY` 或 `exam.parse.ocr.api-key` 读取，**不得写入仓库**
- **影响范围**：`ZhipuOcrClient`、`OcrPageResult`、`OcrFigureSnapper`、`ExamParsePaperContext.ocrText`、
  `exam_parse_paper_v1.txt`、`ExamParseServiceImpl.buildOcrGrounding`
- **违反后果**：OCR 不可用时若抛异常/阻塞，会把整个解析拖垮；把 OCR 里的手写当题干会污染题目；
  不截断 OCR 文本会导致 prompt 过大、模型超时
- **发现时间**：2026-11-17（低清照片导致 √7→√3、编题、跨页表格缺失）
- **正确做法**：OCR 只是"参考文本 + 精确 bbox"，任何失败都必须静默回退纯视觉解析

## 3. 维护规则

1. **新增约定**：在开发过程中发现新的隐性约定时，必须立即补充到本文件
2. **编号规则**：新增约定从 #38 起顺序编号；#5–#8 为空号，**不得复用**
3. **OpenSpec 对齐**：在 design.md 阶段，必须显式检查本文件中的约定
4. **评审依据**：reviewer 和 verify 阶段，将本文件作为对齐依据
5. **定期回顾**：每个迭代结束后，回顾是否有遗漏的隐性约定需要补充
6. **归档回写**：change 归档前，若本次变更推翻了某条约定的前提，必须在本文件标注「修订」并写明原因，不得直接删除条目（保留编号与历史结论）
