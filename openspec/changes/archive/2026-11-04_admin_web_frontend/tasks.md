# Tasks：管理后端前端（admin-web）

> **Change 名称**：`2026-11-04_admin_web_frontend`  
> **创建日期**：2026-11-04  
> **版本**：草案 v5（契约核对完成，参数修正落地，D1-D9 全部修正）  
> **状态**：✅ 已完成（2026-11-05，Task 1-19 全部完成，ESLint 通过，构建成功）

---

## 任务清单

### Task 1：项目初始化与基础配置

**描述**：
- 创建 `admin-web` 目录
- 初始化 Vue 3 + Vite 项目
- 安装依赖：Element Plus、Vue Router、Axios、dayjs（修复 N3：不安装 Pinia）
- 配置 Vite（代理、别名、构建）
- 配置 .gitignore
- 配置 ESLint + Prettier（修复 S9：明确配置，非“可选”）

**涉及文件**：
- `admin-web/package.json`
- `admin-web/vite.config.js`
- `admin-web/index.html`
- `admin-web/.gitignore`
- `admin-web/.eslintrc.cjs`（新增）
- `admin-web/.prettierrc`（新增）

**预估时间**：40 分钟（含 ESLint/Prettier 配置）

---

### Task 2：入口文件与全局样式

**描述**：
- 创建 `main.js`（引入 Element Plus、Router）（修复 N3：不引入 Pinia）
- 创建 `App.vue`（根组件）
- 创建全局样式 `global.css`

**涉及文件**：
- `admin-web/src/main.js`
- `admin-web/src/App.vue`
- `admin-web/src/assets/styles/global.css`

**预估时间**：20 分钟

---

### Task 3：通用布局组件（AppLayout.vue）

**描述**：
- 实现侧边栏导航（el-menu）
- 实现顶部栏（el-header）
- 实现面包屑（el-breadcrumb）
- 实现内容区域（router-view）

**涉及文件**：
- `admin-web/src/components/AppLayout.vue`

**预估时间**：40 分钟

---

### Task 4：通用搜索表单组件（SearchForm.vue）

**描述**：
- 实现动态搜索表单（支持 input、select、date-picker、date-range）
- date-range 产出 { startTime, endTime } 两个字段（当前无页面使用，作为通用能力保留）
- 实现搜索与重置按钮
- 支持 v-model 双向绑定

**涉及文件**：
- `admin-web/src/components/SearchForm.vue`

**预估时间**：50 分钟

---

### Task 5：通用数据表格组件（DataTable.vue）

**描述**：
- 实现数据表格（el-table）
- 实现分页组件（el-pagination）
- 支持加载状态、空数据提示
- 支持列配置化

**涉及文件**：
- `admin-web/src/components/DataTable.vue`

**预估时间**：50 分钟

---

### Task 6：文件上传组件（FileUploader.vue）

**描述**：
- 实现文件上传（el-upload）
- 支持拖拽上传
- 校验文件格式（.json）与大小（≤ 10MB）
- 发射 `file-selected` 事件（修复 R1：删除“上传进度显示”，进度属于页面 handleUpload 职责）

**涉及文件**：
- `admin-web/src/components/FileUploader.vue`

**预估时间**：40 分钟

---

### Task 7：Axios 实例与拦截器（request.js）

**描述**：
- 创建 Axios 实例（baseURL、timeout）
- 实现请求拦截器（注入固定 token，方案 B，修复 D9）
- 实现响应拦截器（统一解析 `Result<T>` 结构）
- 调用 `utils/message.js` 的错误提示封装（修复 O2：明确 message.js 是 request.js 的依赖）

**涉及文件**：
- `admin-web/src/api/request.js`
- `admin-web/src/utils/message.js`
- `admin-web/.env.example`（新增，含 VITE_DEMO_TOKEN 配置）

**预估时间**：30 分钟

---

### Task 8：路由配置

**描述**：
- 创建 `router/index.js`
- 配置路由（题库管理、知识点管理、先修关系、课程标准、导入日志）
- 实现路由懒加载
- 配置路由元信息（title）

**涉及文件**：
- `admin-web/src/router/index.js`

**预估时间**：20 分钟

---

### Task 9：API 接口定义

**描述**：
- 定义题目相关接口（`question.js`）
- 定义知识点相关接口（`knowledgePoint.js`）
- 定义先修关系相关接口（`dependency.js`）
- 定义课程标准相关接口（`standard.js`）
- 定义数据导入相关接口（`import.js`）
- **修复 B2**：所有接口对照 Swagger 确认参数名与 dataType 枚举值

**涉及文件**：
- `admin-web/src/api/question.js`
- `admin-web/src/api/knowledgePoint.js`
- `admin-web/src/api/dependency.js`
- `admin-web/src/api/standard.js`
- `admin-web/src/api/import.js`

**预估时间**：40 分钟

---

### Task 9.5：契约核对（独立任务，修复 U2）

**前置条件**：
- 后端服务已启动（`mvn spring-boot:run`）
- Swagger 文档可访问（`/api/doc.html`）
- 前端 Task 9 API 接口定义已完成

**描述**：
- 对照 Swagger 文档（`/api/doc.html`）逐条验证 Task 9 定义的接口
- 验证项：
  - 接口路径（修复 B1：`/admin/questions` 而非 `/admin/question/list`）
  - 请求参数名与类型（修复 D8：DataImportController 已改为 `@RequestParam MultipartFile file` + `@RequestParam String dataType`）
  - dataType 枚举值（修复 B3：`question/topic/dependency/standard`）
  - 返回值结构（修复 B1：KnowledgePointDetailVO 含 prerequisites、dependents、questionCount）
  - 分页参数默认值（验证 size 默认 10 还是 20）（修复 U4）
  - 时间字段格式（验证是否已序列化为字符串）
  - **401 JWT 验证**（修复 D9）：验证 `/api/admin/*` 在无 token 时返回 401，带固定 token 返回 200
  - **导入返回体字段**（修复 U5）：确认 `POST /admin/import` 返回体的统计字段名（successCount/failCount/skipCount/importId）
  - **知识点详情字段**（修复 U6）：确认 `KnowledgePointDetailVO` 实际字段（subjectName/level/studentCount 等）
- 输出《契约核对报告》，记录差异与修正

**涉及文件**：
- `admin-web/src/api/*.js`（根据核对结果修正）
- `openspec/changes/2026-11-04_admin_web_frontend/api-contract-review.md`（更新，记录核对结果）

**预估时间**：40 分钟（含 D8/D9/U5/U6 验证）

---

### Task 10：题库管理页面（QuestionList.vue）

**描述**：
- 实现搜索表单（知识点 ID 选择器、难度、题型）（修复 D1/D2：知识点名称改为 knowledgePointId 选择器，题型参数名为 questionType）
- 实现数据表格（题目 ID、知识点名称、题干、难度、题型、创建时间）
- 实现分页（默认 size=20）
- 实现导入按钮与 FileUploader 集成
- 导入成功后在页面展示统计摘要 + 跳转错误记录链接（修复 S5/R2）
- 对接后端接口
  - 加载知识点列表作为下拉选项（修复 D2 工时+10min）
  - 参数名对照：`knowledgePointId`（知识点 ID）、`questionType`（题型）、`difficulty`（难度）

**涉及文件**：
- `admin-web/src/views/question/QuestionList.vue`

**预估时间**：1 小时 10 分钟（原 1 小时，因知识点 ID 选择器增加 10 分钟）

---

### Task 11：知识点管理页面（KnowledgePointList.vue）

**描述**：
- 实现搜索表单（知识点名称、状态、学科）（修复 D4：增加学科筛选）
- 实现数据表格（知识点 ID、名称、状态、先修关系数量、关联题目数量、创建时间）
- 实现分页（默认 size=10，修复 D3）
- 实现操作按钮：
  - 标记已校对（pending_review → active）
  - 启用/停用（active ↔ disabled）+ ElMessageBox.confirm 二次确认（修复 S4）
  - 查看详情（跳转 `/knowledge-points/:id`）
- 实现导入功能（选择文件后调用 `importData('topic')`）
- 导入成功后展示统计摘要 + 跳转错误记录链接（修复 S5）
- 对接后端接口

**涉及文件**：
- `admin-web/src/views/knowledge-point/KnowledgePointList.vue`

**预估时间**：1.5 小时（含二次确认与导入反馈）

---

### Task 12：知识点详情页面（KnowledgePointDetail.vue）

**描述**：
- 实现知识点详情展示（修复 B1/U6）：
  - 基本信息（id、name、subjectName、status、description、level、parentId）
  - 先修关系列表（prerequisites）
  - 被依赖知识点列表（dependents）
  - 关联题目数量（questionCount）
  - 学习该知识点的学生数量（studentCount）
- **字段来源**：以 Task 9.5 契约核对结果为准，缺失字段从页面删除（修复 U6）
- 实现返回按钮
- 对接后端接口（`GET /admin/knowledge-points/{id}`）

**涉及文件**：
- `admin-web/src/views/knowledge-point/KnowledgePointDetail.vue`

**预估时间**：1 小时（依赖 Task 9.5 核对结果）

---

### Task 13：先修关系管理页面（DependencyList.vue）

**描述**：
- 实现搜索表单（知识点 ID 选择器、先修知识点 ID 选择器、关系类型）（修复 D5/D6：名称改为 ID，增加关系类型筛选）
- 实现数据表格（知识点名称、先修知识点名称、关系类型、创建时间）
- 实现分页（默认 size=20）
- 实现导入功能
- 对接后端接口
  - 加载知识点列表作为下拉选项（修复 D5 工时+20min）
  - **VO 字段映射**：若后端 `DependencyListVO` 不含名称字段，用已加载的知识点 options 映射 ID → 名称（与 design §5.3 对齐）

**涉及文件**：
- `admin-web/src/views/dependency/DependencyList.vue`

**预估时间**：1 小时 20 分钟（原 1 小时，因知识点 ID 选择器增加 20 分钟）

---

### Task 14：课程标准管理页面（StandardList.vue）

**描述**：
- 实现搜索表单（学段、学科、标准编号）
- 实现数据表格（标准编号、学段、学科、内容描述、创建时间）
- 实现分页
- 实现导入功能
- 对接后端接口

**涉及文件**：
- `admin-web/src/views/standard/StandardList.vue`

**预估时间**：1 小时

---

### Task 15：导入日志页面（ImportLogList.vue）

**描述**：
- 实现搜索表单（仅数据类型筛选）（修复 D7：删除时间范围，后端不支持）
- 实现数据表格（导入 ID、数据类型、文件来源、成功数、失败数、跳过数、导入时间）
- 实现分页（默认 size=20）
- 实现查看错误记录按钮（跳转 `/import-errors?importId=xxx`）（修复 S6/R3：统一用 query 参数）
- 对接后端接口（`GET /admin/import/logs?dataType=xxx`）

**涉及文件**：
- `admin-web/src/views/import-log/ImportLogList.vue`

**预估时间**：50 分钟（原 1 小时，因删除时间范围功能减少 10 分钟）

---

### Task 16：导入错误记录页面（ImportErrorList.vue）

**描述**：
- 实现搜索表单（导入批次，从 URL query 读取 importId）（修复 S6/R3）
- 实现数据表格（记录 ID、失败原因、所属批次、创建时间）
- 实现分页
- 对接后端接口（`GET /admin/import/errors?importId=xxx`）

**涉及文件**：
- `admin-web/src/views/import-log/ImportErrorList.vue`

**预估时间**：50 分钟

---

### Task 17：工具函数封装

**描述**：
- 日期格式化工具（`date.js`）
- 消息提示封装（`message.js`）
- 文件校验工具（`file.js`）

**涉及文件**：
- `admin-web/src/utils/date.js`
- `admin-web/src/utils/message.js`
- `admin-web/src/utils/file.js`

**预估时间**：20 分钟

---

### Task 18：联调与测试

**描述**：
- 启动后端服务与前端开发服务器
- 测试所有页面功能（搜索、分页、导入、操作）
- **运行 ESLint 检查并修复**（修复 U7）：执行 `npm run lint`，修复所有 error
- 修复联调问题
- 验证 Vite 代理配置

**涉及文件**：
- 全部前端文件

**预估时间**：2.5 小时（含 ESLint 修复）

---

### Task 19：文档与 README

**描述**：
- 编写 `admin-web/README.md`（项目介绍、安装、运行、构建）
- 补充关键代码注释
- 编写 Demo 演示步骤（修复 N4/U8）：
  1. 启动后端服务（`mvn spring-boot:run`）
  2. 启动前端开发服务器（`npm run dev`）
  3. 导入课程标准（`cn-curriculum-standards.json`）
  4. 导入知识点（`topics.json`）
  5. **在知识点管理页执行校对**（标记已校对，使知识点生效）（修复 U8：先校对再导入题目）
  6. 导入先修关系（`dependencies.json`）
  7. 导入题目（`questions.json`）（修复 U8：此时题目关联的知识点已校对，题目立即可用）
  8. 查看导入日志与错误记录
  9. 展示知识点启停用操作

> **修复 U8**：调整导入顺序，符合规则 #10 的治理流程（topics 校对后再导入 questions）。

**涉及文件**：
- `admin-web/README.md`

**预估时间**：40 分钟（含 Demo 演示脚本）

---

## 任务依赖关系

```
Task 1（项目初始化）
  ├─ Task 2（入口文件）
  │   ├─ Task 3（通用布局）
  │   ├─ Task 4（搜索表单）
  │   ├─ Task 5（数据表格）
  │   ├─ Task 6（文件上传）
  │   └─ Task 7（Axios 实例）
  ├─ Task 8（路由配置）
  ├─ Task 9（API 接口）
  └─ Task 17（工具函数）

Task 9（API 接口）完成后
  └─ Task 9.5（契约核对）（修复 U2：独立任务，含 U1/U5/U6 验证）

Task 3-9.5（基础组件与配置）完成后
  ├─ Task 10（题库管理）
  ├─ Task 11（知识点管理）
  ├─ Task 12（知识点详情）
  ├─ Task 13（先修关系）
  ├─ Task 14（课程标准）
  ├─ Task 15（导入日志）
  └─ Task 16（导入错误记录）

Task 10-16（页面开发）完成后
  └─ Task 18（联调测试）

Task 18（联调测试）完成后
  └─ Task 19（文档）
```

---

## 总预估时间

- **基础组件与配置**（Task 1-9, 17）：约 5 小时
- **契约核对**（Task 9.5）：约 40 分钟（修复 U2：独立任务）
- **页面开发**（Task 10-16）：约 8 小时 10 分钟（调整：Task 10/13 增加知识点选择器工时，Task 15 删除时间范围减少工时）
- **联调测试**（Task 18）：约 2.5 小时（含 ESLint 修复）
- **文档**（Task 19）：约 40 分钟

**总计**：约 16.5 小时（2 个工作日，原 15.5 小时，增加 1 小时用于知识点 ID 选择器开发）

---

## 验收标准

1. 所有 Task 完成
2. 代码通过 ESLint 检查（修复 S9：ESLint 已配置）
3. 所有页面功能正常（搜索、分页、导入、操作）
4. 与后端 API 对接成功（Task 9.5 契约核对完成）
5. 开发环境可正常运行（`npm run dev`）
6. 生产环境可正常构建（`npm run build`）
7. 导入功能含结果反馈（修复 S5）
8. 知识点启停用含二次确认（修复 S4）

---

**任务制定人**：AI Agent  
**完成时间**：2026-11-05  
**完成状态**：✅ 全部完成
