# Verify：试卷上传「一次多选 + 点一次全部并行上传」

> **Change 名称**：`2026-11-12_exam_upload_batch`
> **验证时间**：2026-11-12 14:44–15:05（Asia/Shanghai）
> **结论**：**实现与工件对齐**；构建与产物核对通过，真机手测待人工执行（见 §3）

---

## 1. 范围与任务对照

| 任务 | 状态 | 证据 |
|------|------|------|
| T1 并发 2 → 8 | ✅ | `ExamUpload.vue` `UPLOAD_CONCURRENCY = MAX_IMAGES` |
| T2 满额自动触发兜底 | ✅ | `submitAndPoll` 的 `autoTriggered` 分支 + `submitPaper(..., {showError:false})` |
| T3 选图预处理反馈 | ✅ | `processing` 标记 + 内联提示「正在处理所选图片…」+ 禁用"＋"（不用 showLoading，避免顶掉错误提示） |
| T4 文案与按钮 | ✅ | tip + 主按钮 `一次性上传 N 张并解析` / `继续上传 N 张 / 提交解析` |
| T5 文档回写 | ✅ | 规则 #4 边界 + 隐性约定 #30 |
| T6 构建验证 | ✅/⏳ | 构建通过；真机手测待人工 |

---

## 2. 构建与产物核对

```
cd student-app && npm run build:mp-weixin
→ DONE  Build complete.
→ exit code 0
```

| 核对项 | 期望 | 实测 |
|--------|------|------|
| `pages/exam-upload/ExamUpload.js` 并发常量 | `f=o`（`o = EXAM_MAX_IMAGE_COUNT`） | ✅ `f=o` |
| worker 数计算 | `Math.min(f, queue.length)` | ✅ 存在 |
| 静默 submit 分支 | `showError:!1` | ✅ 存在 |
| `api/exam.js` submit 签名 | `function(t,r={})` | ✅ `submitPaper=function(t,r={}){...post(...,void 0,r)}` |
| 新文案 | 「一次性上传」「一次可选」 | ✅ 出现在 `ExamUpload.js` / `ExamUpload.wxml` |
| `pages/exam-upload/ExamUpload.wxml` | 卡片 tip 更新 | ✅ |

产物：`student-app/dist/build/mp-weixin/`（`pages/exam-upload/*`、`api/exam.js`）

---

## 3. 真机/开发者工具手测清单（未执行，需人工）

| 场景 | 步骤 | 期望 |
|------|------|------|
| 一次多选 8 张 | 「＋」→ 相册多选 8 张 → 点「一次性上传 8 张并解析」 | 8 个角标**同时**变「上传中」，X/Y 快速递增，随后自动进入解析轮询 |
| 满额自动触发不误报 | 同上，观察 toast | 只出现「已提交解析」/「试卷解析完成」，**不出现**「上传会话不存在或已过期」 |
| 单张失败可重试 | 上传中关闭网络 → 恢复网络 → 点「重试失败张（N）」 | 只重传失败张，成功后可「少传几张也解析」 |
| 单张超限 | 选一张 >9.5MB 的图 | toast 提示单张超限，其它图仍可正常加入列表 |
| 页面返回 | 上传/解析中返回上一页 | 轮询与跳转定时器已清理（`onUnload`），无重复请求 |

---

## 4. 文档一致性

| 文档 | 改动 |
|------|------|
| `docs/product/index.md` 规则 #4 | 边界补：前端一次多选、点一次即全部并行上传（≤8，微信并发上限 10） |
| `docs/architecture/implicit-contracts.md` | 新增 #30：小程序批量上传 = 并发单文件请求，不存在"单请求多文件"；维护规则编号起点 → #31 |

---

## 5. 遗留

| 项 | 说明 |
|----|------|
| 真机手测 | 需在微信开发者工具/真机执行 §3；本轮仅完成构建与产物核对 |
| 上传并发可观测性 | 目前无埋点；若后续出现弱网失败率，可考虑加"上传耗时/失败原因"统计（另立 change） |
| 批量接口 | 后端保持逐张追加；若未来改为"单请求多图"，需同时改单文件魔数校验、30MB 累计校验与分段锁（不建议） |
