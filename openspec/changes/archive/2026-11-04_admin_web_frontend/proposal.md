# Proposal：管理后端前端（admin-web）

> **Change 名称**：`2026-11-04_admin_web_frontend`  
> **创建日期**：2026-11-04  
> **版本**：草案 v5（契约核对完成，D1-D9 修正落地）  
> **状态**：✅ 已完成（2026-11-05）

---

## 1. 需求背景

当前系统后端功能已全部实现（14 个 Change 已归档），包括：
- 后端基础设施（JWT 鉴权、统一响应结构、全局异常处理）
- 学生登录与认证
- 数据导入功能（题库、知识点、先修关系、课程标准）
- 学习目标管理
- 掌握度状态管理
- 每日作业核心流程
- 错题管理系统
- 举一反三新题生成
- 专项评测系统
- 试卷上传与异步解析系统
- 计划调整系统
- 学习记录查询与回溯系统
- 知识点治理功能
- 管理后端列表查询增强

后端已提供完整的管理端 API 接口（`/api/admin/*`），但**管理后端前端（PC 端）尚未实现**，开发者无法通过可视化界面进行数据管理、导入操作、查看日志等。

根据产品规则 §1.1(4)，管理后端属于 Demo 交付范围，需提供：
- 题库管理（列表查看、JSON 文件导入）
- 知识点管理（列表查看、导入、校对标记、启停用）
- 先修关系管理（列表查看、JSON 文件导入）
- 课程标准管理（列表查看、JSON 文件导入）
- 导入日志查看（导入记录、错误记录）

---

## 2. 核心目标

1. **搭建 admin-web 项目**：Vue 3 + Element Plus + Vite 初始化
2. **实现通用布局与路由**：侧边栏导航、顶部栏、路由配置
3. **实现题库管理页面**：题目列表（搜索、分页、筛选）、JSON 文件导入
4. **实现知识点管理页面**：知识点列表（搜索、分页、状态筛选）、JSON 文件导入、校对标记、启停用操作
5. **实现先修关系管理页面**：先修关系列表（搜索、分页）、JSON 文件导入
6. **实现课程标准管理页面**：课程标准列表（搜索、分页、筛选）、JSON 文件导入
7. **实现导入日志页面**：导入记录列表（时间范围、数据类型筛选）、错误记录查看
8. **对接后端 API**：统一请求拦截、响应处理、错误提示

---

## 3. 功能范围

### 包含（In Scope）

| 编号 | 功能点 | 对应后端接口 |
|------|--------|------------|
| F-1 | 项目初始化（Vue 3 + Element Plus + Vite） | - |
| F-2 | 通用布局（侧边栏、顶部栏、面包屑） | - |
| F-3 | 路由配置与权限控制 | - |
| F-4 | 题目列表页（搜索、分页、按知识点/难度/题型筛选） | `GET /api/admin/questions` |
| F-5 | 题目 JSON 文件导入 | `POST /api/admin/import`（dataType=question） |
| F-6 | 知识点列表页（搜索、分页、状态筛选） | `GET /api/admin/knowledge-points` |
| F-7 | 知识点 JSON 文件导入 | `POST /api/admin/import`（dataType=topic） |
| F-8 | 知识点校对标记（pending_review → active） | `PUT /api/admin/knowledge-points/{id}/review` |
| F-9 | 知识点启停用（active ↔ disabled） | `PUT /api/admin/knowledge-points/{id}/status` |
| F-10 | 知识点详情查看（含先修关系、被依赖关系、关联题目数量） | `GET /api/admin/knowledge-points/{id}` |
| F-11 | 先修关系列表页（搜索、分页） | `GET /api/admin/dependencies` |
| F-12 | 先修关系 JSON 文件导入 | `POST /api/admin/import`（dataType=dependency） |
| F-13 | 课程标准列表页（搜索、分页、按学段/学科筛选） | `GET /api/admin/standards` |
| F-14 | 课程标准 JSON 文件导入 | `POST /api/admin/import`（dataType=standard） |
| F-15 | 导入日志列表（仅数据类型筛选） | `GET /api/admin/import/logs` |
| F-16 | 导入错误记录查看（按批次筛选） | `GET /api/admin/import/errors` |
| F-17 | 统一请求拦截与响应处理 | - |
| F-18 | 全局错误提示与加载状态 | - |

### 不包含（Out of Scope）

| 编号 | 说明 | 后续 Change |
|------|------|------------|
| X-1 | 学生端小程序（uni-app） | 后续 Change |
| X-2 | 管理端用户权限系统（当前复用 StudentContext） | 不实现（Demo 阶段） |
| X-3 | 题目/知识点/先修关系的在线编辑（增删改） | 不实现（规则边界） |
| X-4 | 数据可视化图表 | 不实现（Demo 阶段仅列表） |
| X-5 | 后端 API 新增或修改 | 不在本次范围 |

**X-5 例外说明（D8 后端变更）**：
- **变更时间**：2026-11-04 17:09
- **变更内容**：后端 `DataImportController.importData()` 从 `@RequestBody ImportRequest` 修改为 `@RequestParam("file") MultipartFile file, @RequestParam("dataType") String dataType`
- **变更原因**：用户要求支持文件上传（原设计为 classpath 读取固定文件）
- **代码证据**：`DataImportController.java` 第 39-44 行（见契约核对报告 api-contract-review.md v5）
- **测试验证**：后端 `DataImportControllerTest` 7/7 通过，`DataImportServiceTest` 14/14 通过，合计 21/21 通过
- **影响**：前端按原设计使用 FormData 上传即可，无需调整

**范围缩减说明（D7 时间范围筛选移除）**：
- **原计划**：F-15 导入日志列表支持“时间范围 + 数据类型”筛选
- **实际情况**：后端 `GET /admin/import/logs` 仅支持 `dataType` 参数，不支持时间范围筛选
- **决策**：移除时间范围筛选功能，F-15 修正为“仅数据类型筛选”
- **影响**：前端 §5.5 ImportLogList.vue 删除 startTime/endTime 字段

---

## 4. 关键业务约束

| 约束 | 来源 | 说明 |
|------|------|------|
| 列表页搜索框 | 架构文档 §2 | 所有列表页面必须包含搜索框区域 |
| 分页参数 | 约定 #3 | `page` 从 1 开始，DataTable 组件默认 20，知识点列表按后端默认 10（修复 D3/A6），最大 100 |
| 统一响应结构 | 约定 #1 | 前端统一解析 `Result<T>`（code/message/data） |
| 错误处理 | 约定 #2 | 根据 `code` 判断错误类型，展示对应提示 |
| 时间格式 | 约定 #4 | 前端展示为 `yyyy-MM-dd HH:mm:ss` |
| 租户隔离 | 约定 #15 | 前端注入固定 token（方案 B），后端从 token 解析 tenant_id |
| 文件上传 | 规则 #10 | JSON 文件导入，追加模式，不覆盖已有记录 |
| 只读不删 | 规则 #9 | 导入日志与错误记录只读不删 |

---

## 5. 隐性约定检查

| 约定 | 影响 |
|------|------|
| #1 统一响应 | 前端需统一解析 `Result<T>` 结构 |
| #2 异常处理 | 根据 `code` 展示错误提示，非 200 状态码统一拦截 |
| #3 分页参数 | `page` 从 1 开始，前端分页组件需对齐 |
| #4 时间格式 | 使用 `dayjs` 或 `date-fns` 格式化为 `yyyy-MM-dd HH:mm:ss` |
| #15 管理端上下文 | 前端注入固定 token（方案 B），后端从 token 解析 tenant_id |

---

## 5.1 dataType 枚举值对照表（修复 B3）

| dataType 值 | 规则 #10 数据类型 | JSON 文件名 | 后端枚举 |
|-------------|------------------|------------|---------|
| `question` | 题目 | `questions.json` | `DataTypeEnum.QUESTION` |
| `topic` | 知识点 | `topics.json` | `DataTypeEnum.TOPIC` |
| `dependency` | 先修关系 | `dependencies.json` | `DataTypeEnum.DEPENDENCY` |
| `standard` | 课程标准 | `cn-curriculum-standards.json` | `DataTypeEnum.STANDARD` |

> **注意**：以后端 `DataTypeEnum` 实际定义为准，前端需与后端保持一致。

---

## 6. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 后端接口未对齐 | 中 | 先通过 Swagger 文档验证所有接口可用性（Task 9.5 契约核对） |
| 文件上传大小限制 | 低 | 前端限制 JSON 文件 ≤ 10MB |
| 分页性能问题 | 低 | 后端已添加复合索引（V9），前端限制单页最大 100 条 |
| 跨域问题 | 中 | 开发环境配置 Vite 代理，生产环境由后端托管静态资源 |
| Element Plus 版本兼容 | 低 | 锁定最新稳定版，避免 breaking changes |

---

## 7. 前置依赖

- 后端管理端 API 已全部实现（`AdminQuestionController`、`AdminKnowledgePointController`、`AdminDependencyController`、`AdminStandardController`、`DataImportController`）
- V1~V9 DDL 脚本已 apply
- Swagger 文档可用（`/api/doc.html`）

---

## 8. 验收标准

1. admin-web 项目成功初始化（Vue 3 + Element Plus + Vite）
2. 通用布局正确显示（侧边栏、顶部栏、面包屑）
3. 路由配置正确，各页面可正常跳转
4. 题目列表页正确展示（搜索、分页、筛选功能可用）
5. 知识点列表页正确展示（搜索、分页、状态筛选、校对标记、启停用操作）
6. 先修关系列表页正确展示（搜索、分页）
7. 课程标准列表页正确展示（搜索、分页、筛选）
8. 导入日志页正确展示（数据类型筛选、错误记录查看）（修复 D7：删除时间范围）
9. JSON 文件导入功能可用（题库、知识点、先修关系、课程标准）
10. 统一请求拦截与错误处理正确
11. 开发环境可正常联调（Vite 代理配置正确）
12. **契约核对完成**（Task 9.5）：所有接口路径、参数、返回值与 Swagger 文档一致  
13. **前端注入固定 token 后接口返回业务成功（code=200）**（修复 D9/方案 B）：无 token 时返回 HTTP 401（符合契约核对结论）  
14. **ESLint 检查通过**（修复 U7）：Task 18 联调结束时执行 `npm run lint` 无 error

---

## 9. 后续规划

本次 Change 完成后，后续工作：

- 学生端小程序开发（uni-app）
- 系统集成测试
- 性能优化与上线准备

---

**提案人**：AI Agent  
**审核状态**：待人工审核
