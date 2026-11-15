# Tasks：试卷解析失败后允许当天重新上传

> **Change 名称**：`2026-11-12_exam_fail_reupload`

---

## T1 后端：失败（0 题）可重传

- [x] `ExamPaperMapper.resetFailedPaperForReupload`（`failed + ungraded` → `uploading`，清空图片/备注/总分）
- [x] `ExamPaperServiceImpl.createPaper`：失败且 `countByPaper == 0` → 重置复用、返回 `reused=true`；否则维持 1131
- **验收**：单测「失败且 0 题 → reused」通过 ✅

## T2 后端：有题的失败记录仍拦住

- [x] 条件里先查题目数，有题直接走 1131
- **验收**：单测「失败但已有题目 → 1131」通过 ✅

## T3 前端：错误码透出

- [x] `utils/request.ts` 业务错误 → `businessError(message, code)`（新增只读 `code`，message/toast 行为不变）
- **验收**：dev 构建产物 `utils/request.js` 含 `businessError` ✅

## T4 前端：1131 给出可操作入口

- [x] `ExamUpload.vue`：`e.code === 1131` → 弹窗「该科目今天已有试卷」→「去历史试卷」
- [x] 文案说明"可换一个考试日期再上传"
- **验收**：dev 构建产物含 `offerExamListAfterDuplicate` ✅

## T5 文档

- [x] 产品规则 #4 边界补「解析失败后可当天重新上传」的条件与边界
- **验收**：文档与代码一致 ✅

## T6 回归

- [x] `mvn test`：**823 tests / 0 failure / 5 skipped**（新增 2 条）
- [x] 前端 dev 构建热更新（watcher 在跑）
- **验收**：全绿 ✅
