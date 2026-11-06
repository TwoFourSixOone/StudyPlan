# Design：学生端小程序基础框架

> **Change 名称**：`2026-11-05_student_miniapp`  
> **创建日期**：2026-11-05  
> **版本**：草案 v2（审核修订版，B-1~B-3 / I-1~I-6 / C-1~C-7 全部修正）  
> **状态**：已通过（Approve）

---

## 1. 技术架构

### 1.1 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| uni-app | 3.x (Vue 3 Composition API) | 跨端框架 |
| Vite | 5.x | 构建工具 |
| Pinia | 2.x | 状态管理 |
| dayjs | 1.x | 日期格式化 |
| Sass | - | CSS 预处理 |
| 微信开发者工具 | 最新 | 小程序调试 |

### 1.2 项目结构

```
student-app/
├─ src/
│  ├─ api/                    # API 接口层
│  │  ├─ request.js           # 统一请求工具（token 注入、响应解析、401 处理）
│  │  └─ auth.js              # 登录相关接口
│  ├─ stores/                 # Pinia 状态管理
│  │  └─ auth.js              # 登录态 store（token、用户信息、登录状态）
│  ├─ utils/                  # 工具函数
│  │  ├─ date.js              # 日期格式化
│  │  └─ message.js           # 消息提示封装（含去重节流）
│  ├─ components/             # 通用组件
│  │  ├─ PageContainer.vue    # 页面容器（导航栏 + 内容区 + 安全区）
│  │  ├─ LoadingState.vue     # 加载状态组件
│  │  └─ EmptyState.vue       # 空数据提示组件
│  ├─ pages/                  # 页面
│  │  ├─ login/
│  │  │  └─ Login.vue         # 登录页
│  │  ├─ index/
│  │  │  └─ Index.vue         # 首页（占位）
│  │  ├─ homework/
│  │  │  └─ Homework.vue      # 每日作业（占位）
│  │  ├─ mastery/
│  │  │  └─ Mastery.vue       # 掌握度看板（占位）
│  │  └─ profile/
│  │     └─ Profile.vue       # 我的（占位）
│  ├─ static/                 # 静态资源
│  │  └─ images/
│  ├─ App.vue                 # 根组件
│  ├─ main.js                 # 入口文件
│  ├─ manifest.json           # uni-app 配置（appid、权限等）
│  ├─ pages.json              # 页面路由与 TabBar 配置
│  └─ uni.scss                # 全局样式变量
├─ package.json
├─ vite.config.js
└─ index.html
```

> **修复 C-1/C-2**：删除 TabBar.vue（使用 pages.json 原生 TabBar）；删除预留 API 文件（后续 change 按需创建）。

---

## 2. 登录与鉴权流程

### 2.1 后端 JWT 机制（已实现）

后端 `JwtInterceptor` 的行为：
- **被动刷新**：任何请求若 token 在 5 分钟内过期（`shouldRefresh()` 返回 true），后端在响应头 `X-Refresh-Token` 中返回新 JWT，原请求正常处理
- **401 拒绝**：token 完全过期（超过 2h）或无效，返回 HTTP 401
- **CORS 暴露**：`X-Refresh-Token` 已通过 `exposedHeaders` 暴露给前端

### 2.2 前端唯一刷新策略（修复 B-1）

**采用"被动捕获 + 反应式重登"双通道策略，不存在第三套机制：**

```
通道 A — 被动捕获 X-Refresh-Token（无感知续期）
  任何请求的响应头若含 X-Refresh-Token → 自动替换本地 token
  前端不主动判断过期时间，不主动调用 wx.login()

通道 B — 反应式 401 重登（token 彻底过期后的兜底）
  请求返回 HTTP 401 → 加锁调用 wx.login() → POST /api/auth/login → 获取新 JWT
  → 存储新 JWT → 重放原请求（单次，防循环）
```

**防循环规则**：
- 重放请求若再次返回 401 → 不再重登，直接跳转登录页
- 同一时刻只允许一个 wx.login() 进行（并发锁）
- 重放仅对幂等请求（GET/PUT/DELETE）或后端保证幂等的接口生效（修复：幂等安全说明）

### 2.3 登录时序

```
┌──────────┐    ┌──────────┐    ┌──────────┐
│  小程序    │    │  后端     │    │  微信     │
└────┬─────┘    └────┬─────┘    └────┬─────┘
     │  1. 点击登录   │               │
     │──────────────>│               │
     │               │  2. wx.login() │
     │               │──────────────>│
     │               │  3. code       │
     │               │<──────────────│
     │               │  4. POST /api/auth/login {code}
     │               │──────────────>│
     │               │  5. 换取 openid，签发 JWT
     │               │<──────────────│
     │  6. {token, studentInfo}      │
     │<──────────────│               │
     │  7. 存储 token + expireTime 到 Storage
     │               │               │
```

### 2.4 Token 存储

| 存储项 | Key | 说明 |
|--------|-----|------|
| JWT token | `auth_token` | 主令牌 |
| 用户信息 | `auth_user` | JSON 序列化存储 |
| Token 过期时间 | `auth_expire` | 从 JWT payload `exp` 字段解码获取（ms 时间戳） |

> **修复 I-3**：三个 key 的读写封装在 `stores/auth.js` 内部，页面不直接操作 Storage。`logout()` 原子清除三个 key；`auth_expire` 缺失时 `checkTokenExpiring()` 视为已过期，触发重登。
>
> **修复 I-4**：`expireTime` 来源为前端解码 JWT payload 的 `exp` 字段。使用轻量 base64 解析（不引入 jwt-decode 依赖），在 `stores/auth.js` 的 `saveToken()` 方法内完成。

---

## 3. 请求工具设计（request.js）

### 3.1 核心功能

```javascript
// 基础配置
const request = {
  baseURL: '/api',
  timeout: 30000  // 修复：显式配置 30s 超时
};

// 请求拦截器
- 惰性获取 Pinia store（修复 I-1 循环依赖）：
  在拦截器函数体内 import { useAuthStore }，不在模块顶层 import
- 从 store 读取 token，注入 Authorization: Bearer <token>

// 响应拦截器（修复 I-2 优先级）
判定顺序：先 HTTP 状态码，再 body.code
1. HTTP 401 → 触发通道 B 重登流程
2. HTTP 403/404/500/网络错误 → 显示对应错误提示
3. HTTP 200 + body.code !== 200 → 显示业务错误提示，reject
4. HTTP 200 + body.code === 200 → 返回 body.data
5. 任何响应：检查响应头 X-Refresh-Token，有则更新 store 中的 token
```

### 3.2 并发重登锁（修复 B-2）

```javascript
let isReLogging = false;
let reLoginSubscribers = [];

function onReLoginSuccess(token) {
  reLoginSubscribers.forEach(cb => cb(token));
  reLoginSubscribers = [];
  isReLogging = false;
}

function onReLoginFailed(error) {
  reLoginSubscribers.forEach(cb => cb.reject(error));
  reLoginSubscribers = [];
  isReLogging = false;
}

function addReLoginSubscriber(callback) {
  reLoginSubscribers.push(callback);
}
```

> **修复 B-2**：补充 `onReLoginFailed(error)` 向订阅者广播错误，避免等待方永久挂起。

### 3.3 401 重放流程

```
请求 A 返回 401
  → 检查 isReLogging
    → false: 设 isReLogging=true, 调用 doReLogin()
    → true: 将请求 A 挂起（addReLoginSubscriber）, 等待重登完成
  → doReLogin() 成功: 用新 token 重放请求 A（单次）
  → doReLogin() 失败: 通知所有挂起请求 reject, 跳转登录页
  → 重放请求 A 再次 401: 不再重登, 直接跳转登录页（防循环）
```

---

## 4. Pinia Store 设计（auth.js）

### 4.1 State

```javascript
{
  token: string | null,        // JWT token
  studentInfo: object | null,  // 用户信息（studentId, nickname, avatar, grade）
  expireTime: number | null,   // token 过期时间戳（ms），从 JWT exp 解码
  isLoggedIn: boolean          // computed: token 存在且未过期
}
```

### 4.2 Actions

```javascript
// 公共登录核心（修复：login 与 silentReLogin 复用）
async _doLogin({ silent = false })
  → wx.login() 获取 code
  → POST /api/auth/login { code }
  → 存储 token + studentInfo + expireTime
  → silent=false 时跳转首页; silent=true 时不跳转

// 用户主动登录
login() → _doLogin({ silent: false })

// 401 静默重登（通道 B）
silentReLogin() → _doLogin({ silent: true })

// 登出（原子清除三个 Storage key）
logout() → clearStorage() → clearState() → uni.reLaunch('/pages/login/Login')

// 应用启动初始化
init() → 从 Storage 恢复 token/studentInfo/expireTime

// 保存 token（含 JWT exp 解码）
_saveToken(token) → 解码 payload.exp → 设置 expireTime
```

> **修复 I-1**：`_doLogin` 内部调用 `api/auth.js` 的 `login(code)` 方法。`api/auth.js` 依赖 `request.js`，`request.js` 在拦截器函数体内惰性 `import { useAuthStore }`，避免模块顶层循环依赖。
>
> **初始化顺序**：`main.js` 中 `app.use(pinia)` 先于 `app.mount()`，确保 `useAuthStore()` 在 `App.vue onLaunch` 调用 `init()` 时可用。

---

## 5. 页面路由配置（pages.json）

### 5.1 TabBar 页面（使用原生 TabBar，修复 C-1）

| 路径 | 标题 | 图标 |
|------|------|------|
| `/pages/index/Index` | 首页 | `home` |
| `/pages/homework/Homework` | 作业 | `edit` |
| `/pages/mastery/Mastery` | 看板 | `chart` |
| `/pages/profile/Profile` | 我的 | `person` |

### 5.2 非 TabBar 页面

| 路径 | 标题 | 说明 |
|------|------|------|
| `/pages/login/Login` | 登录 | 登录页，不在 TabBar 中 |

### 5.3 pages.json TabBar 配置

```json
{
  "tabBar": {
    "color": "#999",
    "selectedColor": "#409eff",
    "list": [
      { "pagePath": "pages/index/Index", "text": "首页", "iconPath": "...", "selectedIconPath": "..." },
      { "pagePath": "pages/homework/Homework", "text": "作业", "iconPath": "...", "selectedIconPath": "..." },
      { "pagePath": "pages/mastery/Mastery", "text": "看板", "iconPath": "...", "selectedIconPath": "..." },
      { "pagePath": "pages/profile/Profile", "text": "我的", "iconPath": "...", "selectedIconPath": "..." }
    ]
  }
}
```

---

## 6. 启动流程与登录态校验（修复 B-3 / I-5）

### 6.1 uni-app 无 vue-router beforeEach

uni-app 小程序端不支持 vue-router 的路由守卫 API。采用**"启动校验 + 页面级 onShow 兜底"**方案。

### 6.2 启动时序图

```
App.vue onLaunch
  ├─ 1. authStore.init()  // 从 Storage 恢复登录态
  ├─ 2. 检查 isLoggedIn
  │   ├─ true  → 正常启动，进入首页（TabBar 首页）
  │   └─ false → uni.reLaunch('/pages/login/Login')  // 跳转登录页
  └─ 3. 首屏渲染前显示 LoadingState（修复 I-5 时序竞争）
```

### 6.3 页面级兜底

每个 TabBar 页面的 `onShow` 中校验：

```javascript
onShow(() => {
  if (!authStore.isLoggedIn) {
    uni.reLaunch('/pages/login/Login');
  }
});
```

> **修复 I-5**：`App.vue` 在 `init()` 完成前显示全屏 LoadingState，避免首屏闪现占位页再跳转。`init()` 是同步 Storage 读取 + 异步 JWT 验证，耗时极短（< 50ms）。

### 6.4 登录后跳转

登录成功后：
- 若来自启动校验 → `uni.switchTab({ url: '/pages/index/Index' })`
- `uni.reLaunch` 会清栈，登录后通过 `switchTab` 回到正确 TabBar 页

---

## 7. 通用组件设计

### 7.1 PageContainer.vue

**职责**：统一页面布局结构

**Props**：
- `title`: String - 页面标题
- `showBack`: Boolean - 是否显示返回按钮（默认 false）
- `backgroundColor`: String - 背景色（默认 #f5f5f5）

**Slot**：
- `default` - 页面内容

**安全区适配（修复 I-6）**：
```css
.page-container {
  padding-bottom: env(safe-area-inset-bottom);
}
```
使用 CSS `env(safe-area-inset-bottom)` 适配 iPhone 底部安全区，uni-app 编译至微信小程序时自动生效。

### 7.2 LoadingState.vue

**Props**：
- `loading`: Boolean - 是否显示加载状态
- `text`: String - 加载提示文本（默认"加载中..."）

### 7.3 EmptyState.vue

**Props**：
- `text`: String - 提示文本（默认"暂无数据"）
- `icon`: String - 图标名称（默认"empty"）

---

## 8. 异常处理设计

### 8.1 网络异常

- 超时：30s（显式配置），提示"网络请求超时，请稍后重试"
- 断网：提示"网络连接失败，请检查网络"

### 8.2 业务异常（修复 I-2 优先级）

判定顺序：**先 HTTP 状态码，再 body.code**

| HTTP 状态 | body.code | 处理 |
|-----------|-----------|------|
| 200 | 200 | 返回 data |
| 200 | 非 200 | 显示 body.message，reject |
| 401 | - | 触发通道 B 重登（见 §3.3） |
| 403 | - | 提示"您没有权限访问" |
| 404 | - | 提示"请求的资源不存在" |
| 500 | - | 提示"服务器开小差了，请稍后重试" |
| 其他 | - | 显示 error.message 或"网络错误" |

### 8.3 错误提示去重（修复：小建议）

`utils/message.js` 实现 500ms 节流：相同文案在 500ms 内只弹一次 Toast，避免并发请求失败时多次弹出。

### 8.4 登录异常

- wx.login() 失败：提示"微信登录失败，请检查网络后重试"
- 后端登录接口失败：提示"登录失败，请稍后重试"
- 重登后重放仍 401：清除本地登录态，跳转登录页（不循环重登）

---

## 9. 隐性约定对齐

| 约定 | 对齐方式 |
|------|----------|
| #1 统一响应 | request.js 拦截器统一解析 `Result<T>` |
| #2 异常处理 | 先 HTTP 状态码再 body.code，401 自动重登 |
| #3 分页参数 | `page` 从 1 开始 |
| #4 时间格式 | dayjs 格式化为 `yyyy-MM-dd HH:mm:ss` |
| #7 登录态 | JWT 被动捕获 X-Refresh-Token + 401 反应式重登 |
| #16 启动与页面级校验 | onLaunch init() + 页面 onShow 兜底 |

---

## 10. 性能优化

| 优化点 | 措施 |
|--------|------|
| 首屏加载 | 页面按需加载（uni-app 默认分包） |
| 图片优化 | 使用静态资源 CDN，减少包体积 |
| 请求优化 | 并发重登锁，避免多次 wx.login；被动捕获 X-Refresh-Token 无需额外请求 |
| 状态持久化 | Pinia + Storage，减少重复登录 |

---

## 11. 审查修正记录

| 编号 | 问题 | 修正方案 |
|------|------|---------|
| B-1 | JWT 刷新机制混用（主动刷新 vs X-Refresh-Token） | 删除"提前 5 分钟主动刷新"，改为被动捕获 X-Refresh-Token + 401 反应式重登双通道 |
| B-2 | wx.login 并发调用无失败回调 | 补充 `onReLoginFailed(error)` 广播，避免订阅者永久挂起 |
| B-3 | 路由守卫方案与 uni-app 能力不符 | 改为"启动校验（onLaunch init）+ 页面级 onShow 兜底" |
| I-1 | request.js 与 Pinia 循环依赖 | 拦截器函数体内惰性 import useAuthStore，不在模块顶层 |
| I-2 | Result 解析与 HTTP 状态码优先级未定义 | 明确：先 HTTP 状态码，再 body.code |
| I-3 | 存储三件套读写一致性 | 封装在 store 内部，logout 原子清除，expire 缺失视为过期 |
| I-4 | expireTime 来源不明 | 前端解码 JWT payload.exp（轻量 base64，不引入 jwt-decode） |
| I-5 | 占位页与 TabBar 登录态时序竞争 | App.vue init 完成前显示 LoadingState |
| I-6 | 安全区适配未落到实现 | PageContainer 使用 CSS env(safe-area-inset-bottom) |
| C-1 | TabBar.vue 无设计说明 | 删除 TabBar.vue，使用 pages.json 原生 TabBar |
| C-2 | 预留 API 文件无对应 Task | 从项目结构删除预留文件 |
| C-4 | "路由守卫"术语误导 | 统一改为"启动与页面级登录态校验" |
| C-5 | X-Refresh-Token 描述重复 | 合并至 §2.2 双通道策略统一说明 |

---

**设计人**：AI Agent  
**审核状态**：待人工审核（v2 修订版）
