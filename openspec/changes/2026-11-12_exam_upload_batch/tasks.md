# Tasks：试卷上传「一次多选 + 点一次全部并行上传」

> **Change 名称**：`2026-11-12_exam_upload_batch`
> **实施顺序**：T1 → T2 → T3 → T4 → T5 → T6

---

## T1 上传并发提到 8（全部并行）

- [x] `ExamUpload.vue`：`UPLOAD_CONCURRENCY` 2 → `MAX_IMAGES`，补注释（微信 uploadFile 并发上限 10）
- [x] `uploadPendingImages()` Javadoc：改成"全部并行"，说明为何不再限制 2
- **验收**：构建产物中 `UPLOAD_CONCURRENCY = EXAM_MAX_IMAGE_COUNT`，worker 数 `Math.min(f, queue.length)` ✅

## T2 满 8 张自动触发的前端兜底

- [x] `submitAndPoll()`：`autoTriggered = uploadingCount >= MAX_IMAGES` → 先 `recoverByDetailStatus()`
- [x] 必要时 `submitPaper(paperId, { showError: false })`，避免 1186 的误导性 toast
- [x] `api/exam.ts`：`submitPaper(paperId, options)` 透传 `showError`
- **验收**：构建产物含 `showError:!1`；`api/exam.js` 的 `submitPaper` 为双参 ✅

## T3 选图预处理反馈

- [x] `chooseImages()`：预处理段用 `processing` 标记 + 内联提示「正在处理所选图片…」，并禁用"＋"
- [x] 不用 `uni.showLoading`：与 `showToast` 共用通道，会把单张超限的错误提示顶掉
- **验收**：构建成功；`processing` 期间"＋"不可点、页面有明确文字反馈 ✅

## T4 文案与按钮

- [x] 图片卡 tip 改为"一次可选 8 张…全部同时上传"
- [x] 主按钮：未建会话 `一次性上传 N 张并解析`；已建会话按待传张数显示
- **验收**：构建产物 `ExamUpload.wxml` / `.js` 含新文案 ✅

## T5 文档回写

- [x] `docs/product/index.md` 规则 #4 边界：补"前端一次多选、点一次全部并行上传"
- [x] `docs/architecture/implicit-contracts.md`：新增 #30（小程序批量上传 = 并发单文件请求），
      维护规则编号起点 #30 → #31
- **验收**：文档描述与代码一致 ✅

## T6 构建验证

- [x] `npm run build:mp-weixin` 成功（exit 0）
- [x] 产物核对（见 verify.md §2）
- [ ] 真机手测（选 8 张 / 断网重试）—— 需人工在微信开发者工具执行
