# Proposal：试卷上传改「一次多选 + 点一次全部并行上传」

> **Change 名称**：`2026-11-12_exam_upload_batch`
> **状态**：已实施，验证通过（见 `verify.md`），待归档

---

## 1. 背景

学生端「试卷上传」页（`student-app/src/pages/exam-upload/ExamUpload.vue`）当前流程：

```
一次多选（chooseImage count=剩余额度）→ 预览缩略图 → 点「开始上传」→ 建会话 → 并发 2 逐张上传 → 自动 submit
```

三个体感问题：

1. **上传排队**：`UPLOAD_CONCURRENCY = 2`，8 张图要排 4 轮；单张 2–4MB 时用户明显感觉"一张一张传"，
   与"选完一次性上传"的预期不符。
2. **满 8 张时必弹误导性错误**：后端在最后一张追加成功后**自动触发解析**（design §2.3 兜底），
   前端随后仍显式 `submit` → 后端 1186（会话已不是 uploading）→ `request.ts` 统一 toast
   「上传会话不存在或已过期」，而实际上解析已经开始。
3. **选图无反馈**：多选后要逐张「取大小 → 压缩 → 上限校验」，8 张大图耗时 2–4s，期间没有任何 loading。

## 2. 目标

1. 一次多选后，点一次即**全部图片同时上传**（并发 2 → 图片张数，最多 8）。
2. 满 8 张自动触发解析时，前端**不再弹误导性错误**，直接接管轮询。
3. 选图预处理期间给出明确 loading。
4. 按钮与提示文案体现"一次性上传"。

## 3. 范围

### 包含（纯前端）

- `student-app/src/pages/exam-upload/ExamUpload.vue`
    - `UPLOAD_CONCURRENCY` 2 → `MAX_IMAGES`（8）
    - `submitAndPoll`：满额时先按详情探活（`recoverByDetailStatus`），必要时以 `showError:false` 调 submit
    - `chooseImages`：预处理加 `uni.showLoading('处理图片…')`
    - 文案：tip / 主按钮「一次性上传 N 张并解析」/ 已建会话时「继续上传 N 张 / 提交解析」
- `student-app/src/api/exam.ts`：`submitPaper(paperId, { showError })` 透传静默选项

### 不包含

- **后端不改**：仍为「建会话 → 逐张追加（multipart 字段 `file`）→ submit」，接口与 DDL 零变更
- **不做"一个 HTTP 请求带多张图"**：微信小程序 `uni.uploadFile` 只支持单文件；
  手写 multipart 需要把文件读成 base64 常驻内存，30MB 场景易 OOM，且与后端单文件校验（魔数校验）
  不匹配。批量语义 = **并发单文件请求**（见隐性约定 #30）
- 不改压缩策略（≤800KB 不压、>800KB quality 85）、不改后端并发/锁设计

## 4. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 8 个并发上传在弱网/低端机上超时 | 中 | 微信 uploadFile 并发上限 10，8 在安全区；失败仍是**单张独立**，可逐张重试，不整批回滚 |
| 并发压缩导致内存峰值 | 低 | 压缩仍逐张串行（只并行网络上传），未改 |
| 满额自动触发与显式 submit 的竞态 | 低 | 先查详情；查不到才 submit 且静默错误，再由 `recoverByDetailStatus` 兜底 |
| 后端分段锁被打满 | 低 | 后端 `uploadLock` 按 paperId 分段串行，追加是单条 JSON_ARRAY_APPEND，8 并发只影响等待时间 |

## 5. 验收口径

- `npm run build:mp-weixin` 成功，产物中 `UPLOAD_CONCURRENCY = EXAM_MAX_IMAGE_COUNT`、`showError:!1` 存在
- 真机/开发者工具：选 8 张 → 点一次 → 8 个"上传中"角标同时出现 → 自动进入解析且**无错误 toast**
- 失败注入：断开网络后 8 张全部失败 → 恢复后点「重试失败张」可逐张重试
