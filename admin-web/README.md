# 智学伴管理后端（admin-web）

> 智学伴（StudyMate）管理后端 PC 端前端，基于 Vue 3 + Element Plus + Vite 构建。  
> 供开发者进行数据管理、导入操作、查看日志等，属于 Demo 交付范围。

---

## 功能模块

| 模块 | 路由 | 说明 |
|------|------|------|
| 题库管理 | `/questions` | 题目列表查看、按学科/学段/知识点/难度/题型筛选、JSON 文件导入 |
| 知识点管理 | `/knowledge-points` | 知识点列表查看、状态筛选、JSON 文件导入、标记已校对、启用/停用、批量校验 |
| 知识点详情 | `/knowledge-points/:id` | 查看知识点基本信息、先修关系、被依赖关系、关联题目数量 |
| 先修关系管理 | `/dependencies` | 先修关系列表查看、JSON 文件导入 |
| 课程标准管理 | `/standards` | 课程标准列表查看、按学段/学科筛选、JSON 文件导入 |
| 导入日志 | `/import-logs` | 查看导入记录、按数据类型筛选、跳转错误记录 |
| 导入错误记录 | `/import-errors?importId=xxx` | 查看某次导入的错误详情 |

---

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.4+ | 前端框架 |
| Vite | 5.x | 构建工具 |
| Element Plus | 2.5+ | UI 组件库 |
| Vue Router | 4.x | 路由管理 |
| Axios | 1.x | HTTP 请求 |
| dayjs | 1.x | 日期格式化 |

---

## 快速开始

### 1. 安装依赖

```bash
cd admin-web
npm install
```

### 2. 配置环境变量

复制 `.env.example` 为 `.env`，配置 Demo token：

```bash
cp .env.example .env
```

`.env` 文件内容：

```bash
# Demo 阶段固定 token（通过 /api/auth/login 获取）
VITE_DEMO_TOKEN=your_demo_token_here
```

> **获取 token 方式**：先启动后端服务，调用 `POST /api/auth/login` 接口获取 JWT token，填入上述配置。

### 3. 启动后端服务

确保后端 Spring Boot 服务已启动（默认端口 8080）：

```bash
# 在项目根目录执行
mvn spring-boot:run
```

### 4. 启动前端开发服务器

```bash
npm run dev
```

访问 `http://localhost:3000`，Vite 会自动将 `/api` 请求代理到后端 `http://localhost:8080`。

---

## 构建

```bash
# 生产环境构建
npm run build

# 预览构建产物
npm run preview
```

构建产物输出到 `dist/` 目录。

---

## 代码规范

```bash
# ESLint 检查并自动修复
npm run lint

# Prettier 格式化
npm run format
```

---

## Demo 演示步骤

按以下顺序操作，完整展示管理后端功能：

### 前置准备

1. 启动后端服务：`mvn spring-boot:run`
2. 启动前端开发服务器：`npm run dev`
3. 确保 `.env` 中配置了有效的 Demo token

### 数据导入流程

> **注意**：导入顺序有依赖关系，必须按照以下步骤执行。

1. **导入课程标准**  
   进入「课程标准管理」页面 → 点击「导入课程标准」→ 选择 `cn-curriculum-standards.json` → 确认导入

2. **导入知识点**  
   进入「知识点管理」页面 → 点击「导入知识点」→ 选择 `topics.json` → 确认导入

3. **校对知识点**  
   在「知识点管理」页面 → 勾选待校对的知识点 → 点击「批量校验」→ 确认（将 `pending_review` 标记为 `active`）  
   > 校对后的知识点才会被启用，题目才能关联到有效知识点。

4. **导入先修关系**  
   进入「先修关系管理」页面 → 点击「导入先修关系」→ 选择 `dependencies.json` → 确认导入

5. **导入题目**  
   进入「题库管理」页面 → 点击「导入题目」→ 选择 `questions.json` → 确认导入  
   > 此时题目关联的知识点已校对，题目立即可用。

6. **查看导入日志**  
   进入「导入日志」页面 → 查看各次导入记录 → 点击「查看错误记录」了解失败详情

7. **知识点启停用演示**  
   回到「知识点管理」页面 → 对已启用的知识点执行「停用」操作 → 确认后再「启用」

---

## 项目结构

```
admin-web/
├─ src/
│  ├─ api/                    # API 接口定义
│  │  ├─ request.js           # Axios 实例与拦截器
│  │  ├─ question.js          # 题目相关接口
│  │  ├─ knowledgePoint.js    # 知识点相关接口
│  │  ├─ dependency.js        # 先修关系相关接口
│  │  ├─ standard.js          # 课程标准相关接口
│  │  └─ import.js            # 数据导入相关接口
│  ├─ assets/styles/          # 全局样式
│  ├─ components/             # 公共组件
│  │  ├─ AppLayout.vue        # 通用布局（侧边栏 + 顶部栏）
│  │  ├─ SearchForm.vue       # 搜索表单组件
│  │  ├─ DataTable.vue        # 数据表格组件（含分页）
│  │  └─ FileUploader.vue     # 文件上传组件
│  ├─ router/index.js         # 路由配置
│  ├─ utils/                  # 工具函数
│  │  ├─ date.js              # 日期格式化
│  │  ├─ message.js           # 消息提示封装
│  │  └─ file.js              # 文件校验
│  ├─ views/                  # 页面组件
│  │  ├─ question/QuestionList.vue
│  │  ├─ knowledge-point/
│  │  │  ├─ KnowledgePointList.vue
│  │  │  └─ KnowledgePointDetail.vue
│  │  ├─ dependency/DependencyList.vue
│  │  ├─ standard/StandardList.vue
│  │  ├─ import-log/
│  │  │  ├─ ImportLogList.vue
│  │  │  └─ ImportErrorList.vue
│  │  └─ Login.vue
│  ├─ App.vue
│  └─ main.js
├─ .env.example               # 环境变量示例
├─ vite.config.js             # Vite 配置
├─ package.json
└─ README.md
```

---

## 注意事项

- **租户隔离**：前端通过固定 token（方案 B）注入管理端上下文，后端从 token 解析 `tenant_id`
- **分页参数**：`page` 从 1 开始，知识点列表默认 `size=10`，其他列表默认 `size=20`
- **统一响应**：所有接口返回 `Result<T>` 结构（`code` / `message` / `data`），Axios 拦截器自动解析
- **文件上传**：仅支持 `.json` 格式，单文件不超过 10MB
- **只读不删**：导入日志与错误记录只读，不提供删除功能

---

## 相关文档

- 产品规则：`docs/product/index.md`
- 架构文档：`docs/architecture/index.md`
- 隐性约定：`docs/architecture/implicit-contracts.md`
- 变更工件：`openspec/changes/2026-11-04_admin_web_frontend/`
