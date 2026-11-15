# Design：试卷上传「一次多选 + 点一次全部并行上传」

> **Change 名称**：`2026-11-12_exam_upload_batch`

---

## 0. 平台约束（决定方案的前提）

微信小程序 `wx.uploadFile` / `uni.uploadFile` **一次只能传一个文件**（`filePath` 单值，
`name` 为后端 multipart 字段名）。要"一个请求带多张图"只能：

1. 用 `wx.request` 手写 multipart body：需要 `FileSystemManager.readFile({encoding:'base64'})`
   把每张图读成 base64 常驻内存 → 8 张 × 2–4MB ≈ 16–32MB 峰值，30MB 上限场景极易 OOM；
2. 或后端改成 JSON + base64 数组（与现有单文件魔数校验、30MB 累计校验、分段锁全部要重写）。

因此本 change 的「一次性上传」= **一次点击 → 所有图片同时发出上传请求**，
而不是一个 HTTP 请求。已写入隐性约定 #30，避免后续有人再尝试"优化成单请求"。

---

## 1. D1：上传并发 2 → 图片张数（8）

```js
const UPLOAD_CONCURRENCY = MAX_IMAGES; // 8
```

`uploadPendingImages()` 的 worker 池逻辑不变：

```js
for (let i = 0; i < Math.min(UPLOAD_CONCURRENCY, queue.length); i += 1) workers.push(worker());
await Promise.all(workers);
```

- 队列 ≤8 时等价于"每个 worker 处理一张"→ 全部并行；
- 单张失败仍走 `img.status='failed'`，不阻塞其它张；
- 微信 uploadFile 并发上限 10，8 在安全区（后端同卷追加有分段锁串行化，不会写坏 `image_urls`）。

## 2. D2：满 8 张自动触发时的前端兜底

后端 `ExamPaperServiceImpl.uploadImage`：追加后 `uploadedCount >= 8` → `triggerAnalysis`（CAS
uploading → pending）。因此前端 8 张传完后**会话已不是 uploading**，此时 `submit` 必然 1186。

```js
const autoTriggered = uploadingCount.value >= MAX_IMAGES;
if (autoTriggered && (await recoverByDetailStatus())) return;   // 详情已是 pending/processing → 直接轮询
const res = await submitPaper(paperId.value, autoTriggered ? { showError: false } : {});
```

- `recoverByDetailStatus()` 已有实现：completed → 跳结果页；pending/processing → `startPaperPolling`；
- `showError:false` 只用于"满额 + 详情还没反映"的极小概率分支，避免在正确流程上弹
  「上传会话不存在或已过期」；
- 真正的失败仍由 `catch` 里的 `recoverByDetailStatus()` 兜底，失败文案进 `parseError` 展示。

## 3. D3：选图预处理反馈（内联提示，不用 showLoading）

`chooseImages` 的「取大小 → 压缩 → 上限校验」保持**逐张串行**（8 张同时压缩有内存峰值风险）。
反馈方式改为**内联提示 + 禁用"＋"**，而不是 `uni.showLoading`：

```html
<text class="size-tip" v-if="processing">正在处理所选图片（压缩 / 体积校验）…</text>
<view class="thumb thumb-add" v-if="images.length < MAX_IMAGES && !formLocked && !processing" ...>
```

原因：微信的 `showLoading` 与 `showToast` 共用同一个提示通道——单张超限的 `showError` 会把 loading
顶掉，而 `finally` 里的 `hideLoading` 又可能连带关闭刚弹出的错误提示，导致"超限提示一闪而过"。
内联提示不受该通道影响，且 `processing` 同时把"＋"挡掉，避免重复触发选图。

## 4. D4：文案

| 位置 | 改后 |
|------|------|
| 图片卡 tip | 一次可选 8 张，点一次「上传并解析」即全部同时上传；已上传的图片不能删除 |
| 主按钮（未建会话） | `一次性上传 8 张并解析` / `上传并解析`（1 张） |
| 主按钮（已建会话） | 有失败/待传 → `继续上传 N 张 / 提交解析`；否则 `提交解析` |
| 进度 | 上传中 X/Y（不变，X 现在会快速跳变） |

## 5. 测试与验证策略

前端无单测基建（`student-app` 只有 `dev/build` 脚本），因此：

1. `npm run build:mp-weixin` 必须成功（编译期唯一硬门槛）；
2. 构建产物核对（可自动断言）：
   - `ExamUpload.js` 中 `f=o`（`UPLOAD_CONCURRENCY = EXAM_MAX_IMAGE_COUNT`）
   - `Math.min(f,e.length)` 保留
   - `showError:!1` 存在（静默 submit 分支）
   - `api/exam.js` 中 `submitPaper=function(t,r={})`
3. 真机手测（见 verify.md §3，需人工在微信开发者工具执行）
