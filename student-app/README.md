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
│  ├─ api/                    # API 接口层（按业务域拆分）
│  │  ├─ auth.ts              # 登录相关接口
│  │  ├─ goal.ts              # 学习目标
│  │  ├─ homework.ts          # 每日作业
│  │  ├─ exam.ts              # 试卷上传/解析/详情
│  │  ├─ mastery.ts           # 掌握度看板
│  │  ├─ plan.ts              # 计划总览与调整
│  │  ├─ record.ts            # 学习记录
│  │  └─ error.ts             # 错题
│  ├─ stores/                 # Pinia 状态管理
│  │  ├─ auth.ts              # 登录态 store
│  │  └─ app.ts               # 全局状态（科目、当前日期等）
│  ├─ utils/                  # 工具函数
│  │  ├─ request.ts           # 统一请求工具（JWT 注入、双通道刷新、401 重登、baseURL 解析）
│  │  ├─ upload.ts            # 上传工具（试卷图片等）
│  │  ├─ useAuthGuard.ts      # 页面级登录态兜底
│  │  ├─ date.ts              # 日期格式化
│  │  └─ message.ts           # 消息提示（含 500ms 去重节流）
│  ├─ components/             # 通用组件
│  │  ├─ PageContainer.vue    # 页面容器
│  │  ├─ LoadingState.vue     # 加载状态
│  │  └─ EmptyState.vue       # 空数据提示
│  ├─ pages/                  # 页面
│  │  ├─ login/Login.vue      # 登录页
│  │  ├─ index/Index.vue      # 首页
│  │  ├─ homework/Homework.vue # 每日作业
│  │  ├─ mastery/Mastery.vue   # 掌握度看板
│  │  └─ profile/Profile.vue   # 我的
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

> **前置**：先启动后端（端口 **80**，依赖 MySQL + Redis），见根目录 [README.md](../README.md)「本地启动（智学伴）」。
> 跨域需放行小程序来源时，后端 `app.cors.allowed-origins` 可配置。

### 1. 安装依赖

```bash
cd student-app
npm install
```

### 2. 接口地址配置

请求基址由 `src/utils/request.ts` / `src/utils/upload.ts` 解析：

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE_URL` | 本地/调试接口基址，缺省 `http://localhost:80/api`（与后端默认端口一致） |
| `VITE_CLOUD_BASE_URL` | 云托管环境必填；生产构建走云托管时若为空会直接报错提示配置 |

> 小程序真机调试时 `localhost` 不可用，需把 `VITE_API_BASE_URL` 改成电脑局域网 IP（如 `http://192.168.1.10:80/api`），并在小程序后台配置合法域名。

**云端上传通道（2026-11-23）**：云托管**默认域名**（`*.sh.run.tcloudbase.com`）被小程序后台明确拒绝
（"仅用作测试使用，不可用在正式环境下"），因此 `uni.uploadFile`（公网 HTTPS）在体验版/正式版真机上会被拦。
现有实现改为：
- **云端模式**（`VITE_USE_LOCAL=false` 且配置了 `VITE_CLOUD_ENV` + `VITE_CLOUD_SERVICE`）：试卷图片读成 base64，
  走 `wx.cloud.callContainer`（微信内网通道，**不需要 uploadFile 合法域名**）调用
  `POST /api/student/exam/papers/{paperId}/images/base64`；压缩目标收紧到 **900KB**（base64 约 1.2MB）
- **本地/已配域名**：仍走 `uni.uploadFile` multipart（压缩目标 1.8MB）
- 若 callContainer 不可用（未初始化/环境不匹配）自动降级到 multipart
- 服务端日志可区分通道：`上传试卷图片(base64): ...` vs `上传试卷图片: ...`

> 若将来绑定了**已备案自定义域名**，把它填进后台的 request/uploadFile/downloadFile 三类合法域名即可，
> multipart 通道会自动可用，无需改代码。
### 云端上线前检查清单（真机失败但「真机调试 / 开发者工具」正常时先看这里）

微信小程序的网络请求在**真机（预览/体验版/正式版）**上会走域名白名单校验，而**开发者工具与「真机调试」不校验**——
所以"本地好、真机调试好、正式版失败"通常不是代码问题，而是域名配置。三类域名要**分别**配置（缺一不可）：

| 后台配置项 | 用途 | 本项目会用到 |
|---|---|---|
| request 合法域名 | `wx.request`（普通接口） | 所有 `/api/**` 接口 |
| **uploadFile 合法域名** | `wx.uploadFile`（图片上传） | 试卷图片上传 |
| downloadFile 合法域名 | `wx.downloadFile` | 试卷图片预览/下载 |

配置位置：微信公众平台 → 开发管理 → 开发设置 → 服务器域名 → 修改。

要求与坑位：

- 必须是 **HTTPS** 且域名**已备案**（`http://` 或带端口的地址在真机会被直接拒绝）；
- `VITE_CLOUD_BASE_URL` 只填域名（如 `https://xxx-yyy.sh.run.tcloudbase.com`），代码会自动拼 `/api`；
- 云托管**默认域名**若无法配置为合法域名，请在云托管「自定义域名」绑定自有已备案域名后使用；
- 修改域名后建议在小程序里**清缓存并重启**（或重新扫码进入）再验证；
- 服务端侧可对照日志判断请求是否到达：图片上传成功会打印
  `上传试卷图片: paperId=..., size=..., contentType=..., pageNo=...`；若只有 `创建试卷上传会话` 而没有这条，
  说明图片请求**没到服务器**（客户端被拦截）。
### 3. 开发模式（H5）

```bash
npm run dev:h5
```

H5 默认 `http://localhost:5173`，`/api` 代理到后端 `http://localhost:80`。

### 4. 编译微信小程序

```bash
npm run dev:mp-weixin     # 开发模式，产物 dist/dev/mp-weixin（支持热更新）
npm run build:mp-weixin   # 生产构建，产物 dist/build/mp-weixin
```

### 5. 微信开发者工具调试

1. 打开微信开发者工具
2. 导入项目：开发模式选 `dist/dev/mp-weixin`，生产构建选 `dist/build/mp-weixin`
3. 填写微信小程序 AppID（在 `src/manifest.json` 中配置）

## 登录与权限说明

- 学生端登录**不需要账号密码**：调用 `wx.login()` 拿 code → `POST /api/auth/login` → 后端按 openid 自动建档，签发 `role=student` 的 JWT；
- 学生 token **不能**访问管理端接口（`/api/admin/**` 一律返回 HTTP 403），管理端账号（`admin_user` 表 + `ADMIN_BOOTSTRAP_*` 初始化）只用于 `admin-web`；
- token 过期由双通道刷新兜底（见下文「核心机制」）。

## 核心机制

### JWT 双通道刷新

- **通道 A（被动捕获）**：后端响应头 `X-Refresh-Token` 携带新 token 时，自动更新本地存储
- **通道 B（反应式重登）**：请求返回 HTTP 401 时，自动调用 `wx.login()` 重新登录并重放原请求（单次，防循环）

### 启动与页面级登录态校验

- `App.vue onLaunch`：从 Storage 恢复登录态，未登录跳转登录页
- TabBar 页面 `onShow`：兜底校验登录态

### 并发重登锁

多个请求同时返回 401 时，只触发一次 `wx.login()`，其余请求挂起等待，含失败回调避免永久挂起。

## 已实现模块

| Change | 功能 | 状态 |
|--------|------|------|
| `student_goal` | 学习目标设定 | ✅ 已实现 |
| `student_homework` | 每日作业（核心页面） | ✅ 已实现 |
| `student_mastery_dashboard` | 掌握度看板 | ✅ 已实现 |
| `student_plan` | 计划总览与调整 | ✅ 已实现 |
| `student_exam` | 试卷上传与分析 | ✅ 已实现 |
| `student_assessment` | 专项评测 | ✅ 已实现 |

## 构建验证

```bash
npm run build:mp-weixin   # 小程序生产构建
npm run build:h5          # H5 生产构建
```
