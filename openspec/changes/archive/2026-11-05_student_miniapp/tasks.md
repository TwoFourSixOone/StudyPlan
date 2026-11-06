# Tasks：学生端小程序基础框架

> **Change 名称**：`2026-11-05_student_miniapp`  
> **创建日期**：2026-11-05  
> **版本**：草案 v2（审核修订版）  
> **状态**：已完成（Task 1~12 全部完成）

---

## 任务清单

### Task 1：uni-app 项目初始化

**描述**：
- 创建 `student-app` 目录
- 初始化 uni-app (Vue 3 + Vite) 项目
- 安装依赖：Pinia、dayjs、sass
- 配置 `vite.config.js`（代理、别名）
- 配置 `manifest.json`（微信小程序 appid、权限声明）
- 配置 `.gitignore`

**涉及文件**：
- `student-app/package.json`
- `student-app/vite.config.js`
- `student-app/index.html`
- `student-app/.gitignore`
- `student-app/src/manifest.json`

**预估时间**：40 分钟

---

### Task 2：入口文件与全局样式

**描述**：
- 创建 `main.js`（引入 Pinia、全局样式）
- 创建 `App.vue`（根组件，初始化登录态）
- 创建全局样式 `uni.scss`（主题色、间距、字体）
- 创建 `pages.json`（页面路由 + TabBar 配置）

**涉及文件**：
- `student-app/src/main.js`
- `student-app/src/App.vue`
- `student-app/src/uni.scss`
- `student-app/src/pages.json`

**预估时间**：30 分钟

---

### Task 3：Pinia 登录态 Store

**描述**：
- 创建 `stores/auth.js`
- 实现 State：`token`、`studentInfo`、`expireTime`
- 实现 Actions：
  - `_doLogin({ silent })` — 公共登录核心（wx.login → POST /api/auth/login → 存储）
  - `login()` → `_doLogin({ silent: false })` — 用户主动登录
  - `silentReLogin()` → `_doLogin({ silent: true })` — 401 静默重登（通道 B）
  - `logout()` — 原子清除三个 Storage key（auth_token / auth_user / auth_expire）+ 清 state + 跳转登录页
  - `init()` — 从 Storage 恢复登录态
  - `_saveToken(token)` — 存储 token 并解码 JWT payload.exp 设置 expireTime
- 实现 Storage 持久化（`uni.setStorageSync` / `uni.getStorageSync`），页面不直接操作 Storage
- 实现 Computed：`isLoggedIn`（token 存在且未过期）
- `auth_expire` 缺失时视为已过期

**涉及文件**：
- `student-app/src/stores/auth.js`

**预估时间**：45 分钟

---

### Task 4：统一请求工具（request.js）

**描述**：
- 创建 `api/request.js`
- 封装 `uni.request` 为 Promise 风格，显式配置 `timeout: 30000`（30s 超时）
- 请求拦截器：
  - **惰性获取 Pinia store**（避免循环依赖）：在拦截器函数体内 `import { useAuthStore }`，不在模块顶层 import
  - 从 store 读取 token，注入 `Authorization: Bearer <token>`
- 响应拦截器（**判定顺序：先 HTTP 状态码，再 body.code**）：
  1. HTTP 401 → 触发通道 B 重登流程
  2. HTTP 403/404/500/网络错误 → 调用 `utils/message.js` 提示
  3. HTTP 200 + body.code !== 200 → 显示业务错误提示，reject
  4. HTTP 200 + body.code === 200 → 返回 body.data
  5. 任何响应：检查响应头 `X-Refresh-Token`，有则更新 store 中的 token（通道 A）
- 并发重登锁：`isReLogging` + `reLoginSubscribers` 模式，含成功/失败双回调（`onReLoginFailed` 广播错误避免等待方挂起）
- 401 重放防循环：重放请求若再次 401 → 不再重登，直接跳转登录页
- 重放仅对幂等请求（GET/PUT/DELETE）或后端保证幂等的接口生效
- 错误提示去重：`utils/message.js` 实现 500ms 节流，相同文案只弹一次

**涉及文件**：
- `student-app/src/api/request.js`
- `student-app/src/utils/message.js`

**预估时间**：1 小时 15 分钟

---

### Task 5：登录 API 接口

**描述**：
- 创建 `api/auth.js`
- 实现 `login(code)` - 调用后端登录接口
- 实现 `getCurrentUser()` - 获取当前用户信息

**涉及文件**：
- `student-app/src/api/auth.js`

**预估时间**：15 分钟

---

### Task 6：工具函数封装

**描述**：
- 创建 `utils/date.js`（日期格式化，使用 dayjs）
- 创建 `utils/message.js`（消息提示封装，使用 uni.showToast / uni.showModal）

**涉及文件**：
- `student-app/src/utils/date.js`
- `student-app/src/utils/message.js`

**预估时间**：20 分钟

---

### Task 7：通用页面组件

**描述**：
- 创建 `PageContainer.vue`（页面容器：导航栏 + 内容区 + 底部安全区）
  - 安全区使用 CSS `env(safe-area-inset-bottom)` 适配 iPhone 底部安全区
- 创建 `LoadingState.vue`（加载状态组件）
- 创建 `EmptyState.vue`（空数据提示组件）

**涉及文件**：
- `student-app/src/components/PageContainer.vue`
- `student-app/src/components/LoadingState.vue`
- `student-app/src/components/EmptyState.vue`

**预估时间**：40 分钟

---

### Task 8：登录页面（Login.vue）

**描述**：
- 创建 `pages/login/Login.vue`
- 实现微信一键登录按钮
- 调用 `authStore.login()` → 内部 `_doLogin({ silent: false })`
  - `wx.login()` 获取 code → POST /api/auth/login → 存储 token + expireTime
- 登录成功后跳转首页（`uni.switchTab({ url: '/pages/index/Index' })`）

**涉及文件**：
- `student-app/src/pages/login/Login.vue`

**预估时间**：35 分钟

---

### Task 9：TabBar 占位页面

**描述**：
- 创建首页 `pages/index/Index.vue`（占位，显示"首页开发中"）
- 创建作业页 `pages/homework/Homework.vue`（占位）
- 创建看板页 `pages/mastery/Mastery.vue`（占位）
- 创建我的页 `pages/profile/Profile.vue`（占位，显示用户信息）

**涉及文件**：
- `student-app/src/pages/index/Index.vue`
- `student-app/src/pages/homework/Homework.vue`
- `student-app/src/pages/mastery/Mastery.vue`
- `student-app/src/pages/profile/Profile.vue`

**预估时间**：30 分钟

---

### Task 10：启动校验与页面级登录态兜底

**描述**：
- 在 `App.vue` 的 `onLaunch` 中：
  1. 显示全屏 LoadingState（避免首屏闪现占位页）
  2. 调用 `authStore.init()` 从 Storage 恢复登录态
  3. 检查 `isLoggedIn`：true → 进入首页；false → `uni.reLaunch('/pages/login/Login')`
- 在每个 TabBar 页面的 `onShow` 中兜底校验：
  ```javascript
  onShow(() => {
    if (!authStore.isLoggedIn) {
      uni.reLaunch('/pages/login/Login');
    }
  });
  ```
- 登录成功后通过 `uni.switchTab` 回到正确 TabBar 页（`uni.reLaunch` 清栈，需 switchTab 恢复 TabBar）

> **注意**：uni-app 小程序端不支持 vue-router 的 `beforeEach`，不使用"路由守卫"方案。

**涉及文件**：
- `student-app/src/App.vue`（onLaunch 启动校验 + LoadingState）
- `student-app/src/pages/index/Index.vue`（onShow 兜底）
- `student-app/src/pages/homework/Homework.vue`（onShow 兜底）
- `student-app/src/pages/mastery/Mastery.vue`（onShow 兜底）
- `student-app/src/pages/profile/Profile.vue`（onShow 兜底）

**预估时间**：30 分钟

---

### Task 11：联调与测试

**描述**：
- 启动后端服务（`mvn spring-boot:run`）
- 启动微信开发者工具，导入 uni-app 编译产物
- 测试登录流程（wx.login → 后端 → JWT 存储）
- 测试 TabBar 导航
- 测试启动校验与页面级兜底（未登录跳转）
- 测试通道 A：被动捕获 X-Refresh-Token（token 在 5 分钟内过期时后端响应头携带新 token）
- 测试通道 B：401 静默重登 + 单次重放原请求
- 测试并发重登锁（多请求同时 401 只触发一次 wx.login）
- 测试重登失败回调（onReLoginFailed 广播错误）
- 验证错误提示去重（500ms 节流）
- 验证微信小程序真机预览（可选）

**涉及文件**：
- 全部前端文件

**预估时间**：1 小时 30 分钟

---

### Task 12：文档与 README

**描述**：
- 编写 `student-app/README.md`（项目介绍、安装、运行、构建）
- 补充关键代码注释
- 编写 Demo 演示步骤

**涉及文件**：
- `student-app/README.md`

**预估时间**：20 分钟

---

## 任务依赖关系

```
Task 1（项目初始化）
  ├─ Task 2（入口文件）
  │   ├─ Task 3（Pinia Store）
  │   │   ├─ Task 4（请求工具）
  │   │   │   ├─ Task 5（登录 API）
  │   │   │   └─ Task 8（登录页面）
  │   │   └─ Task 10（启动校验与页面级兜底）
  │   ├─ Task 6（工具函数）
  │   └─ Task 7（通用组件）
  └─ Task 9（占位页面）

Task 3-10（基础组件与配置）完成后
  └─ Task 11（联调测试）

Task 11（联调测试）完成后
  └─ Task 12（文档）
```

---

## 总预估时间

- **基础配置**（Task 1-2）：约 1 小时 10 分钟
- **核心模块**（Task 3-6）：约 2 小时 30 分钟
- **页面开发**（Task 7-10）：约 2 小时 20 分钟
- **联调测试**（Task 11）：约 1 小时 30 分钟
- **文档**（Task 12）：约 20 分钟

**总计**：约 7 小时 50 分钟（1 个工作日）

---

## 验收标准

1. 所有 Task 完成
2. uni-app 项目可成功编译至微信小程序
3. 微信开发者工具中可正常运行
4. 登录流程完整（wx.login → 后端 → JWT 存储 → 跳转首页）
5. JWT 双通道刷新机制生效：
   - 通道 A：被动捕获响应头 X-Refresh-Token 自动续期
   - 通道 B：401 拦截后自动重登并重放原请求（单次，防循环）
6. 并发重登锁正确（多请求同时 401 只触发一次 wx.login）
7. 重登失败时正确广播错误并跳转登录页
8. TabBar 导航正确显示（4 个 Tab 页）
9. 启动校验（onLaunch init）+ 页面级兜底（onShow）拦截未登录访问
10. 占位页面正确显示
11. 错误提示去重生效（500ms 节流）
12. 请求超时配置为 30s

---

---

## 审查修正记录

| 编号 | 修正内容 |
|------|----------|
| B-1 | JWT 刷新策略改为双通道（被动捕获 X-Refresh-Token + 401 反应式重登），删除"提前 5 分钟主动刷新" |
| B-2 | 并发重登锁补充失败回调 `onReLoginFailed`，避免等待方永久挂起 |
| B-3 | Task 10 从"路由守卫"改为"启动校验（onLaunch）+ 页面级 onShow 兜底" |
| I-1 | Task 3/4 明确循环依赖解耦：拦截器函数体内惰性 import useAuthStore |
| I-2 | Task 4 明确响应解析优先级：先 HTTP 状态码，再 body.code |
| I-3 | Task 3 明确存储三件套原子清除，页面不直接操作 Storage |
| I-4 | Task 3 明确 expireTime 来源为前端解码 JWT payload.exp |
| I-5 | Task 10 明确 init 完成前显示 LoadingState 避免首屏时序竞争 |
| I-6 | Task 7 明确安全区使用 CSS env(safe-area-inset-bottom) |
| C-2 | 删除预留 API 文件，项目结构与 Task 对齐 |
| C-3 | Task 3 显式包含 F-3 存储持久化 |
| C-4 | 术语"路由守卫"统一改为"启动与页面级登录态校验" |
| C-6 | Task 11 工时上调至 1.5h，总预估调整为 7h50m |
| 小建议 | Task 4 补充 timeout:30000、错误提示去重、重放幂等说明；Task 8 复用 _doLogin |

---

**任务制定人**：AI Agent  
**审核状态**：待人工审核（v2 修订版）
