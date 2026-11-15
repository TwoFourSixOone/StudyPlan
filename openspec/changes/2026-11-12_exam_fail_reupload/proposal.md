# Proposal：试卷解析失败后允许当天重新上传

> **Change 名称**：`2026-11-12_exam_fail_reupload`
> **状态**：已实施，验证通过（见 `verify.md`），待归档

---

## 1. 背景

联调现象（2026-11-12 15:00–15:20）：

1. 上传 4 张试卷图 → 解析约 **3.5 分钟**才完成，只落了 **8 道题**（前一次 4 张图能出 19 题）。
2. 日志出现 `HTTP transport error: HTTP request interrupted`（`DashScopeHttpClient`）。
3. 重新上传同一科目同一天 → 后端 1131「同一科目同一天只能上传一份试卷」，学生当天彻底卡住。

### 1.1 实测数据（`exam_paper.remark`）

| 试卷 | 图数 | 题目 | remark |
|------|------|------|--------|
| 2 | 4 | 8 | 第 2、4 张超过解析预算（60s/图，180s/卷），已取消 |
| 3 | 4 | 8 | 第 2、3 张超过解析预算（60s/图，180s/卷），已取消 |

- `HTTP request interrupted` 的**来源**：`ExamParseServiceImpl` 在单图/整卷预算到点时 `future.cancel(true)`
  中断工作线程，Reactor / OkHttp 在途请求被中断 → SDK 打印 ERROR。**是取消的伴生日志，不是根因**。
- 真正原因：单图解析耗时超过 60s 预算 → 被取消。旧实例（13:25 启动）仍开着**深度思考**，
  JSON 被截断后触发重试，单图 3 次调用轻松超过 60s（详见 `2026-11-12_llm_latency`）。
- 1131 是产品规则 #4 的设计（同学生+科目+日期唯一），但**整卷失败时没有重传出口**，
  且失败记录一旦变成 `completed`（部分图失败仍算 completed）就再也不能重来。

## 2. 目标

1. **整卷失败（0 题落库）时，当天可以重新上传**：复用同一条 `exam_paper` 记录并重置为上传会话，
   不新增记录、不触发 1131；已有题目的失败记录仍拦住（防止重复落题 / 重复结算）。
2. **1131 不再是无路可走**：前端弹窗给出「去历史试卷」入口，并说明"可换考试日期再上传"。
3. 记录失败原因，避免下次再把"预算取消"误判成网络故障。

## 3. 范围

### 包含

- `ExamPaperMapper.resetFailedPaperForReupload`：`failed + ungraded` → `uploading`，清空 `image_urls` / `remark` / `total_score`
- `ExamPaperServiceImpl.createPaper`：失败且 `exam_question` 为 0 时重置复用；否则维持 1131
- `student-app/utils/request.ts`：业务错误对象带上 `code`（不改变原有 message / toast 行为）
- `ExamUpload.vue`：捕获 1131 → 弹窗「该科目今天已有试卷」→「去历史试卷」
- 单测：`ExamPaperServiceTest$UploadSessionTests` 新增 2 条
- 文档：产品规则 #4 边界补「失败可当天重传」的条件与边界

### 不包含

- 不支持对 **completed（含部分图失败）** 的试卷重新解析：那会重复写掌握度 / 错题 / 学习记录，
  属于"回滚已结算数据"，成本与风险都高，另立 change 评估
- 不改单图 60s / 整卷 180s 预算（关思考后单图约 7s，预算有 8 倍余量）
- 不改 1131 的"同科目同日唯一"规则

## 4. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 重置失败的记录时其实已有题目 | 低 | 先 `countByPaper == 0` 才允许；SQL 再带 `grade_status='ungraded'` 双保险 |
| 失败记录里有部分题目（persist 成功但 settle 失败） | 低 | 有题 → 直接 1131，不放行 |
| 并发两次建会话同时重置 | 低 | 重置 SQL 带 `status='failed'` 条件，第二次影响 0 行 → 走 1131 |
| 前端 `code` 字段影响其他调用方 | 低 | 只在 Error 上新增只读属性，现有代码无 `e.code` 依赖 |
