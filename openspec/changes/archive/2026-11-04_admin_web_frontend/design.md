# Design：管理后端前端（admin-web）

> **Change 名称**：`2026-11-04_admin_web_frontend`  
> **创建日期**：2026-11-04  
> **版本**：草案 v5（契约核对完成，D1-D9 修正落地）  
> **状态**：✅ 已完成（2026-11-05）

---

## 1. 技术选型

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.4+ | 前端框架 |
| Vite | 5.x | 构建工具 |
| Element Plus | 2.5+ | UI 组件库 |
| Vue Router | 4.x | 路由管理 |
| Axios | 1.x | HTTP 请求库 |
| dayjs | 1.x | 日期格式化 |

> **修复 N2/N3**：Demo 阶段不引入 TypeScript 与 Pinia，减少依赖。

---

## 2. 项目结构

```
admin-web/
├─ public/                    # 静态资源
├─ src/
│  ├─ api/                    # API 接口定义
│  │  ├─ question.js          # 题目相关接口
│  │  ├─ knowledgePoint.js    # 知识点相关接口
│  │  ├─ dependency.js        # 先修关系相关接口
│  │  ├─ standard.js          # 课程标准相关接口
│  │  ├─ import.js            # 数据导入相关接口
│  │  └─ request.js           # Axios 实例与拦截器
│  ├─ assets/                 # 资源文件（图片、样式）
│  │  └─ styles/
│  │     └─ global.css        # 全局样式
│  ├─ components/             # 公共组件
│  │  ├─ AppLayout.vue        # 通用布局（侧边栏 + 顶部栏）
│  │  ├─ SearchForm.vue       # 搜索表单组件
│  │  ├─ DataTable.vue        # 数据表格组件（含分页）
│  │  └─ FileUploader.vue     # 文件上传组件
│  ├─ router/                 # 路由配置
│  │  └─ index.js
│  ├─ utils/                  # 工具函数
│  │  ├─ date.js              # 日期格式化
│  │  └─ message.js           # 消息提示封装
│  ├─ views/                  # 页面组件
│  │  ├─ question/            # 题库管理
│  │  │  └─ QuestionList.vue
│  │  ├─ knowledge-point/     # 知识点管理
│  │  │  ├─ KnowledgePointList.vue
│  │  │  └─ KnowledgePointDetail.vue
│  │  ├─ dependency/          # 先修关系管理
│  │  │  └─ DependencyList.vue
│  │  ├─ standard/            # 课程标准管理
│  │  │  └─ StandardList.vue
│  │  └─ import-log/          # 导入日志
│  │     ├─ ImportLogList.vue
│  │     └─ ImportErrorList.vue
│  ├─ App.vue                 # 根组件
│  └─ main.js                 # 入口文件
├─ index.html
├─ vite.config.js             # Vite 配置
├─ package.json
└─ .gitignore
```

---

## 3. 核心组件设计

### 3.1 AppLayout.vue（通用布局）

**职责**：提供侧边栏导航、顶部栏、面包屑、内容区域

**结构**：
```vue
<template>
  <el-container>
    <el-aside width="200px">
      <el-menu :default-active="activeMenu" router>
        <el-menu-item index="/questions">题库管理</el-menu-item>
        <el-menu-item index="/knowledge-points">知识点管理</el-menu-item>
        <el-menu-item index="/dependencies">先修关系管理</el-menu-item>
        <el-menu-item index="/standards">课程标准管理</el-menu-item>
        <el-menu-item index="/import-logs">导入日志</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header>
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item>{{ currentMenu }}</el-breadcrumb-item>
        </el-breadcrumb>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
```

### 3.2 SearchForm.vue（搜索表单组件）

**职责**：通用搜索表单，支持动态配置搜索字段

**Props**：
- `fields`: Array - 搜索字段配置（`{ label, prop, type, options }`）
  - `type` 支持：`'input'` | `'select'` | `'date-picker'` | `'date-range'`（修复 U3）
  - `date-range` 类型产出 `{ startTime, endTime }` 两个字段，`value-format="YYYY-MM-DD"`
  - **当前无页面使用 date-range**（D7 删除导入日志时间筛选后），作为通用组件能力保留

**Emits**（修复 S2）：
- `search`: 搜索事件，参数为搜索条件对象
- `reset`: 重置事件

**示例**：
```vue
<SearchForm 
  :fields="[
    { label: '知识点名称', prop: 'name', type: 'input' },
    { label: '状态', prop: 'status', type: 'select', options: statusOptions }
  ]"
  @search="handleSearch"
  @reset="handleReset"
/>
```

### 3.3 DataTable.vue（数据表格组件）

**职责**：通用数据表格，含分页、加载状态、空数据提示

**Props**：
- `columns`: Array - 列配置（`{ label, prop, width, formatter }`）
- `data`: Array - 表格数据
- `loading`: Boolean - 加载状态
- `total`: Number - 总记录数
- `page`: Number - 当前页
- `size`: Number - 每页大小（默认 20，修复 U4）
- `pageSizes`: Array - 每页大小选项（默认 `[10, 20, 50, 100]`，修复 U4）

**Emits**（修复 S2）：
- `page-change`: 分页事件，参数为 `{ page, size }`

**示例**：
```vue
<DataTable
  :columns="columns"
  :data="tableData"
  :loading="loading"
  :total="total"
  :page="page"
  :size="size"
  @page-change="handlePageChange"
/>
```

### 3.4 FileUploader.vue（文件上传组件）

**职责**：文件选择与校验（修复 S3/R1）

**Props**：
- `accept`: String - 接受的文件类型（默认 `.json`）
- `maxSize`: Number - 最大文件大小（默认 10MB）

**Emits**：
- `file-selected`: 文件选择事件，参数为 File 对象（修复 R1：统一为 `@file-selected`）

> **修复 S3/R1**：FileUploader 只负责选文件 + 校验 + 抛出 File 对象，FormData 拼接与 API 调用放在各页面的 handleUpload 中。删除“上传进度显示”（进度属于页面职责）。

**示例**：
```vue
<FileUploader 
  accept=".json"
  :max-size="10 * 1024 * 1024"
  @file-selected="handleUpload"
/>
```

---

## 4. API 对接规范

### 4.1 request.js（Axios 实例与拦截器）

**职责**：
- 统一配置 baseURL、timeout
- 请求拦截器：注入固定 token（Demo 阶段，方案 B）
- 响应拦截器：统一解析 `Result<T>` 结构，错误提示

**实现**：
```javascript
import axios from 'axios';
import { ElMessage } from 'element-plus';

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

// 请求拦截器：注入固定 token（修复 D9/方案 B）
request.interceptors.request.use(
  (config) => {
    // 从环境变量获取 Demo token
    const token = import.meta.env.VITE_DEMO_TOKEN;
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// 响应拦截器
request.interceptors.response.use(
  (response) => {
    const { code, message, data } = response.data;
    if (code === 200) {
      return data;
    } else {
      ElMessage.error(message || '请求失败');
      return Promise.reject(new Error(message));
    }
  },
  (error) => {
    if (error.response?.status === 401) {
      ElMessage.error('未登录或登录已过期，请检查 token 配置');
    } else {
      ElMessage.error(error.message || '网络错误');
    }
    return Promise.reject(error);
  }
);

export default request;
```

**环境变量配置**（.env.example）：
```bash
# Demo 阶段固定 token（从 /api/auth/login 获取）
VITE_DEMO_TOKEN=your_demo_token_here
```

### 4.3 导入结果反馈（修复 S5/R2）

**设计**：
- 导入成功后，**页面的 handleImportResult** 弹出 ElMessageBox 展示统计摘要（成功数/失败数/跳过数 + 导入批次 ID）
- 提供“查看本次错误记录”按钮，跳转 `/import-errors?importId=xxx`

**实现**（修复 R2：UI 反馈在页面层，非 api 层）：
```javascript
// 在 QuestionList.vue 的 handleUpload 中
import { importData } from '@/api/import';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useRouter } from 'vue-router';

const router = useRouter();

async function handleUpload(file) {
  try {
    const result = await importData(file, 'question');
    // result 包含 successCount, failCount, skipCount, importId
    
    await ElMessageBox.alert(
      `导入完成！成功 ${result.successCount} 条，失败 ${result.failCount} 条，跳过 ${result.skipCount} 条。`,
      '导入结果',
      {
        confirmButtonText: '查看错误记录',
        cancelButtonText: '关闭',
        type: result.failCount > 0 ? 'warning' : 'success'
      }
    );
    
    // 用户点击“查看错误记录”
    router.push(`/import-errors?importId=${result.importId}`);
  } catch (error) {
    ElMessage.error('导入失败：' + error.message);
  }
}
```

### 4.2 API 接口定义示例

**question.js**（修复 O1）：
```javascript
import request from './request';

export function getQuestionList(params) {
  return request.get('/admin/questions', { params });
}

// 修复 O1：不在业务 api 文件重复定义 importData，统一从 import.js 导入
```

**import.js**（修复 R2/O1）：
```javascript
import request from './request';

/**
 * 执行数据导入
 * @param {File} file - JSON 文件对象
 * @param {string} dataType - 数据类型（question/topic/dependency/standard）
 * @returns {Promise<ImportResultDTO>}
 */
export function importData(file, dataType) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('dataType', dataType);
  return request.post('/admin/import', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  });
}
```

> **修复 R2**：importData 只返回 Promise，不做 UI 副作用（ElMessageBox + router.push）。UI 反馈放在各页面的 handleImportResult 中。  
> **修复 R2/B2 澄清（v5 更新）**：后端已修改为接收 `@RequestParam("file") MultipartFile file, @RequestParam("dataType") String dataType`，前端使用 FormData 上传文件 + dataType 参数。

---

## 5. 页面设计

### 5.1 QuestionList.vue（题库管理）

**功能**：
- 搜索表单：
  - 知识点（knowledgePointId，下拉选择器，加载知识点列表）（修复 D1/D2）
  - 难度（basic/standard/challenge）
  - 题型（questionType：numeric/choice，下拉选择）（修复 D1）
- 数据表格：题目 ID、知识点名称、题干（截断显示）、难度、题型、创建时间
- 分页组件：page/size 控制（默认 size=20）
- 导入按钮：触发 FileUploader，选择文件后调用 `importData('question')`

**接口**：
- `GET /admin/questions` - 查询列表（修复 B1：实际接口路径）
  - 参数：`knowledgePointId`（知识点 ID）、`questionType`（题型）、`difficulty`（难度）
- `POST /admin/import` - 导入题目（dataType=question）

### 5.2 KnowledgePointList.vue（知识点管理）

**功能**：
- 搜索表单：
  - 知识点名称
  - 状态（pending_review/active/disabled）
  - 学科（subject，下拉选择）（修复 D4）
- 数据表格：知识点 ID、名称、状态、先修关系数量、关联题目数量、创建时间
- 分页组件：size 初始值 10（覆盖组件默认 20，修复 D3）
- 操作按钮：
  - 标记已校对（pending_review → active）
  - 启用/停用（active ↔ disabled）+ 二次确认（修复 S4）
  - 查看详情
- 导入按钮

**接口**：
- `GET /admin/knowledge-points` - 查询列表（修复 B1：实际接口路径）
  - 参数：`subject`（学科）
- `PUT /admin/knowledge-points/{id}/review` - 标记已校对
- `PUT /admin/knowledge-points/{id}/status` - 启停用
- `GET /admin/knowledge-points/{id}` - 查看详情（修复 B1：返回 KnowledgePointDetailVO，含 prerequisites、dependents、questionCount）
- `POST /admin/import` - 导入知识点（dataType=topic）

### 5.3 DependencyList.vue（先修关系管理）

**功能**：
- 搜索表单：
  - 知识点 ID（knowledgePointId，下拉选择器）（修复 D5）
  - 先修知识点 ID（prerequisiteId，下拉选择器）（修复 D5）
  - 关系类型（dependencyType：hard/soft，下拉选择）（修复 D6）
- 数据表格：知识点名称、先修知识点名称、关系类型（hard/soft）、创建时间
- 分页组件（默认 size=20）
- 导入按钮

**接口**：
- `GET /admin/dependencies` - 查询列表（修复 B1：实际接口路径）
  - 参数：`knowledgePointId`、`prerequisiteId`、`dependencyType`
- `POST /admin/import` - 导入先修关系（dataType=dependency）

**数据加载**：
- 页面初始化时调用 `GET /admin/knowledge-points?size=100` 获取知识点列表作为下拉选项（修复 D5 工时+20min）

**VO 字段说明**：
- 数据表格展示“知识点名称、先修知识点名称”，但后端 `DependencyListVO` 可能仅返回 `knowledgePointId` + `prerequisiteId`
- 若 VO 不含名称字段，前端用已加载的知识点 options 映射 ID → 名称（与 D5 数据加载天然契合）

### 5.4 StandardList.vue（课程标准管理）

**功能**：
- 搜索表单：学段、学科、标准编号
- 数据表格：标准编号、学段、学科、内容描述、创建时间
- 分页组件
- 导入按钮

**接口**：
- `GET /admin/standards` - 查询列表（修复 B1：实际接口路径）
- `POST /admin/import` - 导入课程标准（dataType=standard）

### 5.5 ImportLogList.vue（导入日志）

**功能**：
- 搜索表单：数据类型（question/topic/dependency/standard，下拉选择）（修复 D7：删除时间范围）
- 数据表格：导入 ID、数据类型、文件来源、成功数、失败数、跳过数、导入时间
- 分页组件（默认 size=20）
- 查看错误记录按钮（跳转 `/import-errors?importId=xxx`）（修复 S6/R3：统一用 query 参数）

**接口**：
- `GET /admin/import/logs` - 查询导入日志
  - 参数：`dataType`（仅支持数据类型筛选，不支持时间范围）

### 5.6 ImportErrorList.vue（导入错误记录）

**功能**：
- 搜索表单：导入批次（importId，从 URL query 读取）（修复 S6/R3：统一用 query）
- 数据表格：记录 ID、失败原因、所属批次、创建时间
- 分页组件

**接口**：
- `GET /admin/import/errors?importId=xxx` - 查询错误记录（修复 S6/R3：支持 importId query 参数）

---

## 6. 路由配置

**router/index.js**：
```javascript
import { createRouter, createWebHistory } from 'vue-router';
import AppLayout from '@/components/AppLayout.vue';

const routes = [
  {
    path: '/',
    component: AppLayout,
    redirect: '/questions',
    children: [
      {
        path: '/questions',
        name: 'QuestionList',
        component: () => import('@/views/question/QuestionList.vue'),
        meta: { title: '题库管理' }
      },
      {
        path: '/knowledge-points',
        name: 'KnowledgePointList',
        component: () => import('@/views/knowledge-point/KnowledgePointList.vue'),
        meta: { title: '知识点管理' }
      },
      {
        path: '/knowledge-points/:id',
        name: 'KnowledgePointDetail',
        component: () => import('@/views/knowledge-point/KnowledgePointDetail.vue'),
        meta: { title: '知识点详情' }
      },
      {
        path: '/dependencies',
        name: 'DependencyList',
        component: () => import('@/views/dependency/DependencyList.vue'),
        meta: { title: '先修关系管理' }
      },
      {
        path: '/standards',
        name: 'StandardList',
        component: () => import('@/views/standard/StandardList.vue'),
        meta: { title: '课程标准管理' }
      },
      {
        path: '/import-logs',
        name: 'ImportLogList',
        component: () => import('@/views/import-log/ImportLogList.vue'),
        meta: { title: '导入日志' }
      },
      {
        path: '/import-errors',
        name: 'ImportErrorList',
        component: () => import('@/views/import-log/ImportErrorList.vue'),
        meta: { title: '导入错误记录' }
      }
    ]
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

export default router;
```

---

## 7. Vite 配置

**vite.config.js**：
```javascript
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'path';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets'
  }
});
```

---

## 8. 隐性约定对齐

| 约定 | 对齐方式 |
|------|----------|
| #1 统一响应 | request.js 拦截器统一解析 `Result<T>` |
| #2 异常处理 | 根据 `code` 展示错误提示，非 200 状态码统一拦截 |
| #3 分页参数 | `page` 从 1 开始，DataTable 组件对齐 |
| #4 时间格式 | dayjs 格式化为 `yyyy-MM-dd HH:mm:ss` |
| #15 管理端上下文 | 前端注入固定 token（方案 B），后端从 token 解析 tenant_id |

---

## 9. 异常处理设计

### 9.1 网络错误
- 超时：30s，提示"网络请求超时，请稍后重试"
- 断网：提示"网络连接失败，请检查网络"

### 9.2 业务错误
- 根据后端返回的 `code` 展示对应 `message`
- 401（未授权）：前端注入固定 token（方案 B），若仍返回 401 则提示"token 缺失/失效，检查 .env 配置"
- 403（禁止访问）：提示"您没有权限访问"
- 404（资源不存在）：提示"请求的资源不存在"
- 500（服务器错误）：提示"服务器开小差了，请稍后重试"

### 9.3 表单校验
- 必填字段：前端校验，提示"请填写 XXX"
- 文件格式：仅接受 `.json`，提示"仅支持 JSON 文件"
- 文件大小：≤ 10MB，提示"文件大小不能超过 10MB"

---

## 10. 性能优化（修复 B5/N1）

| 优化点 | 措施 |
|--------|------|
| 首屏加载 | 路由懒加载（`() => import(...)`） |
| 打包体积 | Tree-shaking、按需引入 Element Plus 组件 |

> **修复 B5**：删除 Axios 响应缓存（导入后需立即看到新数据）。  
> **修复 N1**：删除虚拟滚动（Demo 阶段数据量 < 1000，分页 size 上限 100 已足够）。

---

**设计人**：AI Agent  
**审核状态**：待人工审核
