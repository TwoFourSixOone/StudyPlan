# 智学伴学生端（student-app）

> 微信小程序 — AI 自适应学习辅助平台学生端入口

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| uni-app | 3.x (Vue 3) | 跨端框架 |
| Vite | 5.x | 构建工具 |
| Pinia | 2.x | 状态管理 |
| dayjs | 1.x | 日期格式化 |
| Sass | - | CSS 预处理 |

## 项目结构

```
student-app/
├─ src/
│  ├─ api/                    # API 接口层
│  │  ├─ request.js           # 统一请求工具（JWT 注入、双通道刷新、401 重登）
│  │  └─ auth.js              # 登录相关接口
│  ├─ stores/                 # Pinia 状态管理
│  │  └─ auth.js              # 登录态 store
│  ├─ utils/                  # 工具函数
│  │  ├─ date.js              # 日期格式化
│  │  └─ message.js           # 消息提示（含 500ms 去重节流）
│  ├─ components/             # 通用组件
│  │  ├─ PageContainer.vue    # 页面容器
│  │  ├─ LoadingState.vue     # 加载状态
│  │  └─ EmptyState.vue       # 空数据提示
│  ├─ pages/                  # 页面
│  │  ├─ login/Login.vue      # 登录页
│  │  ├─ index/Index.vue      # 首页（占位）
│  │  ├─ homework/Homework.vue # 每日作业（占位）
│  │  ├─ mastery/Mastery.vue   # 掌握度看板（占位）
│  │  └─ profile/Profile.vue   # 我的（占位）
│  ├─ App.vue                 # 根组件
│  ├─ main.js                 # 入口文件
│  ├─ manifest.json           # uni-app 配置
│  ├─ pages.json              # 页面路由与 TabBar
│  └─ uni.scss                # 全局样式变量
├─ package.json
├─ vite.config.js
└─ index.html
```

## 安装与运行

### 1. 安装依赖

```bash
cd student-app
npm install
```

### 2. 开发模式（H5）

```bash
npm run dev:h5
```

### 3. 编译微信小程序

```bash
npm run build:mp-weixin
```

编译产物在 `dist/build/mp-weixin` 目录。

### 4. 微信开发者工具调试

1. 打开微信开发者工具
2. 导入项目，选择 `dist/build/mp-weixin` 目录
3. 填写微信小程序 AppID（在 `src/manifest.json` 中配置）

## 核心机制

### JWT 双通道刷新

- **通道 A（被动捕获）**：后端响应头 `X-Refresh-Token` 携带新 token 时，自动更新本地存储
- **通道 B（反应式重登）**：请求返回 HTTP 401 时，自动调用 `wx.login()` 重新登录并重放原请求（单次，防循环）

### 启动与页面级登录态校验

- `App.vue onLaunch`：从 Storage 恢复登录态，未登录跳转登录页
- TabBar 页面 `onShow`：兜底校验登录态

### 并发重登锁

多个请求同时返回 401 时，只触发一次 `wx.login()`，其余请求挂起等待，含失败回调避免永久挂起。

## 后续开发计划

| Change | 功能 |
|--------|------|
| `student_goal` | 学习目标设定 |
| `student_homework` | 每日作业（核心页面） |
| `student_mastery_dashboard` | 掌握度看板 |
| `student_plan` | 计划总览与调整 |
| `student_exam` | 试卷上传与分析 |
| `student_assessment` | 专项评测 |
