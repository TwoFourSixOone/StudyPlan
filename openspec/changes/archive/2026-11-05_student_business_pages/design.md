# Design：学生端小程序全部业务页面

> **Change 名称**：`2026-11-05_student_business_pages`  
> **版本**：草案 v3（二次审查修正版）

---

## 1. 技术方案

### 1.1 整体架构

```
student-app/src/
├── api/                    # API 接口层（封装 request.js 调用）
│   ├── auth.js             # 已有
│   ├── goal.js             # 新增
│   ├── homework.js         # 新增
│   ├── mastery.js          # 新增
│   ├── exam.js             # 新增（仅 uploadExam，其余推迟）
│   ├── record.js           # 新增
│   └── plan.js             # 新增（仅 triggerAdjustment + getAdjustmentHistory）
├── utils/
│   ├── request.js          # 已有，统一请求工具
│   ├── message.js          # 已有，消息提示
│   ├── date.js             # 已有，日期格式化
│   └── upload.js           # 新增，带 JWT 的文件上传封装
├── composables/
│   └── useAuthGuard.js     # 新增，统一登录态守卫
├── stores/
│   ├── auth.js             # 已有，登录态
│   └── app.js              # 新增，全局状态（选中科目、日期等）
├── pages/
│   ├── login/Login.vue     # 已有，登录页
│   ├── index/Index.vue     # 改造，首页（活跃目标总览）
│   ├── homework/Homework.vue  # 改造，作业页（列表 + 生成 + 批改）
│   ├── homework-detail/HomeworkDetail.vue  # 新增，答题详情（逐题作答 + 提交）
│   ├── mastery/Mastery.vue    # 改造，掌握度看板
│   └── profile/Profile.vue    # 改造，个人中心
└── components/             # 已有通用组件
    ├── PageContainer.vue      # 自定义导航栏 + 安全区包裹（子页面使用，TabBar 页可选）
    ├── LoadingState.vue       # props: loading, text
    └── EmptyState.vue         # props: text, icon
```

### 1.2 新增 Pinia Store：app.js

管理跨页面共享状态：

| 字段 | 类型 | 说明 |
|------|------|------|
| `currentSubjectId` | `number \| null` | 当前选中的科目 ID，默认 null |
| `currentDate` | `string` | 当前查看的日期（YYYY-MM-DD），默认今天 |
| `subjectList` | `Array` | 科目列表缓存，初始空数组 |

**生命周期**：
- `subjectList`：在 App.vue 的 `onLaunch` 中，登录态恢复后立即调用 `GET /api/student/subject` 加载，缓存到 store。加载失败不阻塞登录流程，进入首页后重试。
- `currentSubjectId`：subjectList 加载完成后，自动设为第一个科目的 ID。用户可通过 Picker 切换。
- `currentDate`：默认今天，用户可通过日期选择器切换。

### 1.3 统一登录态守卫：useAuthGuard.js

为避免 4 个页面重复写 `onShow` 登录态校验，封装 composable：

```javascript
// composables/useAuthGuard.js
import { onShow } from '@dcloudio/uni-app';
import { useAuthStore } from '@/stores/auth';

export function useAuthGuard() {
  onShow(() => {
    const authStore = useAuthStore();
    if (!authStore.isLoggedIn) {
      uni.reLaunch({ url: '/pages/login/Login' });
    }
  });
}
```

每个 TabBar 页面只需调用 `useAuthGuard()` 一行代码。

### 1.4 页面间数据同步策略

**原则：只共享筛选参数（currentDate、currentSubjectId），不共享业务数据对象。**

- 作业页与答题详情页都读取 app store 的 `currentDate` 和 `currentSubjectId`，各自调用 `GET /api/homework/by-date` 获取数据；首页只查活跃目标，不参与作业数据流
- 好处：避免缓存一致性问题（提交答案后无需手动同步两份数据）
- 代价：切换 Tab 时多一次请求（可接受，数据量小）

### 1.5 文件上传封装：upload.js

`uni.uploadFile` 不走 `request.js`，需单独封装 JWT 注入和错误处理：

```javascript
// utils/upload.js
import { useAuthStore } from '@/stores/auth';
import { showError } from '@/utils/message.js';

// 与 request.ts 共用同一套环境变量（.env.development / .env.production）
const UPLOAD_BASE_URL = USE_LOCAL
  ? import.meta.env.VITE_API_BASE_URL
  : import.meta.env.VITE_CLOUD_BASE_URL + '/api';

/**
 * 带 JWT 认证的文件上传（含 401 自动重登 + 单次重试）
 * @param {string} url - 上传路径（如 /student/exam/upload）
 * @param {string} filePath - 本地文件路径
 * @param {string} name - 文件字段名（默认 'files'）
 * @param {Object} formData - 额外表单数据
 * @returns {Promise<Object>} 响应 data
 */
export function uploadWithAuth(url, filePath, name = 'files', formData = {}) {
  return _doUpload(url, filePath, name, formData, false);
}

function _doUpload(url, filePath, name, formData, isRetry) {
  return new Promise((resolve, reject) => {
    const authStore = useAuthStore();
    uni.uploadFile({
      url: UPLOAD_BASE_URL + url,
      filePath,
      name,
      formData,
      header: {
        'Authorization': `Bearer ${authStore.token}`
      },
      success: (res) => {
        // 401 未授权：尝试静默重登后重试一次
        if (res.statusCode === 401 && !isRetry) {
          authStore.silentReLogin()
            .then(() => _doUpload(url, filePath, name, formData, true))
            .then(resolve)
            .catch(() => {
              uni.reLaunch({ url: '/pages/login/Login' });
              reject(new Error('登录已过期，请重新登录'));
            });
          return;
        }
        // 解析响应（res.data 为字符串，需 JSON.parse）
        let data;
        try {
          data = JSON.parse(res.data);
        } catch (e) {
          showError('上传失败：响应格式异常');
          reject(new Error('上传响应解析失败'));
          return;
        }
        if (data.code === 200) {
          resolve(data.data);
        } else {
          showError(data.message || '上传失败');
          reject(new Error(data.message || '上传失败'));
        }
      },
      fail: (err) => {
        showError('上传失败，请检查网络');
        reject(new Error(err.errMsg || '上传失败'));
      }
    });
  });
}
```

**说明**：`uni.uploadFile` 的 `success` 回调在 HTTP 4xx/5xx 时也会进入（`fail` 仅在网络连接异常时触发），因此 401 检查放在 `success` 内。`res.data` 为字符串需 `JSON.parse`，已包 try/catch。重试通过内部 `_doUpload(url, filePath, name, formData, true)` 递归实现，`isRetry=true` 时不再重试，避免无限循环。

### 1.6 错误提示规范

与 `utils/message.js` 对齐：

| 错误类型 | UI 展示 | 说明 |
|---------|---------|------|
| 网络错误 | Toast："网络连接失败，请检查网络" | request.js 统一处理 |
| 业务错误（body.code !== 200） | Toast：后端返回的 message | request.js 统一处理 |
| 401 未授权 | 无提示，自动重登 | request.js 通道 B 处理 |
| 空数据 | EmptyState 组件 | 页面内展示 |
| 加载中 | LoadingState 组件 | 页面内展示 |
| 文件上传失败 | Toast | uploadWithAuth 处理（含 401 自动重登 + 单次重试） |

---

## 2. 隐性约定对齐

| 约定/规则 | 对齐方式 |
|------|---------|
| 隐性约定 #1 统一响应结构 | 所有 API 通过 `request.js` 调用，自动解析 `Result<T>` |
| 隐性约定 #7 JWT 登录态 | 所有页面通过 `useAuthGuard()` composable 统一校验 |
| 隐性约定 #18 `api/` 目录异常 | API 文件放 `src/api/` 目录；若编译报错则移至 `utils/`（同 request.js 处理） |
| 隐性约定 #19 完整 URL | 请求与上传均拼完整 URL；地址由 `.env.*` 注入（`VITE_API_BASE_URL` / `VITE_CLOUD_BASE_URL`），源码不再硬编码 |
| 产品规则 #3 分页参数从 1 开始 | 列表页分页默认 `page=1` |

---

## 3. 异常处理

（见 §1.6 错误提示规范）

---

## 4. 分页 UI 策略

- 掌握度看板：后端返回全量知识点列表（通常 ≤ 50 个），**前端不分页**，直接展示
- 学习记录：后端分页返回，前端使用**上拉加载**（`onReachBottom`），每次加载 10 条
- 变更历史：后端分页返回，前端使用**上拉加载**，每次加载 10 条

---

## 5. 不涉及后端改动

本 change 为纯前端开发，后端 API 已全部就绪。不需要修改任何后端代码、数据库脚本或配置文件。
