# Verify：试卷解析失败后允许当天重新上传

> **Change 名称**：`2026-11-12_exam_fail_reupload`
> **验证时间**：2026-11-12 15:39–15:55（Asia/Shanghai）
> **结论**：**实现与工件对齐**；已解释 `HTTP request interrupted` 与 1131 的成因，并补上"失败可重传"的死角

---

## 1. 现场数据（studyplan 库，student 9）

| 试卷 | 科目 | 考试日期 | 状态 | 图片 | 题数 | remark |
|------|------|----------|------|------|------|--------|
| 1 | 数学 | 2026-11-11 | completed / graded | 4 | 19 | — |
| 2 | 数学 | 2026-11-12 | completed / graded | 4 | **8** | 第 2、4 张超过解析预算（60s/图，180s/卷），已取消 |
| 3 | 数学 | 2026-11-13 | completed / graded | 4 | **8** | 第 2、3 张超过解析预算（60s/图，180s/卷），已取消 |

- 解析任务 1/2/3 均 `completed`，耗时分别 2m56s / 3m43s / 3m25s
- `HTTP request interrupted` = 预算到点 `future.cancel(true)` 中断在途 HTTP 的伴生日志
- 重传被 1131 拦住：因为记录已是 `completed`（部分图失败仍算 completed）

## 2. 代码与测试

```
mvn test
→ Tests run: 823, Failures: 0, Errors: 0, Skipped: 5
→ BUILD SUCCESS
```

新增用例（`ExamPaperServiceTest$UploadSessionTests`，19 条全绿）：

| 用例 | 断言 |
|------|------|
| 解析失败且 0 题 → 重置复用 | `reused=true`、`status=uploading`、调用 `resetFailedPaperForReupload`、不 insert 新记录 |
| 解析失败但已有题目 → 1131 | 抛 `EXAM_PAPER_DUPLICATE`，且**不**调用重置 |

## 3. 前端产物（dev 热更新）

- `dist/dev/mp-weixin/utils/request.js` 含 `businessError(...)`，业务错误对象带 `code`
- `dist/dev/mp-weixin/pages/exam-upload/ExamUpload.js` 含 `offerExamListAfterDuplicate` 与弹窗文案
- 后端 dev 构建无需前端重编（watcher 已在跑；微信开发者工具点「编译」即可）

## 4. 行为对照

| 场景 | 改前 | 改后 |
|------|------|------|
| 整卷失败（0 题）后当天重传 | 1131，卡死到次日 | 复用记录重置为 uploading，正常重传 |
| 失败但有部分题目 | 1131 | 1131（不变，防重复落题/结算） |
| completed / 解析中 | 1131（只 toast） | 1131 + 弹窗「去历史试卷」 |
| 前端其他业务错误 | 只有 message | message 不变，新增 `code` 供差异化处理 |

## 5. 遗留

| 项 | 说明 |
|----|------|
| 部分图失败的 completed 试卷 | 不能当天重做（需回滚掌握度/错题/学习记录），另立 change 评估 |
| 当前两份部分成功的试卷 | 建议次日重新上传，或换考试日期测试；如需清理旧数据要单独确认（涉及题目/错题/掌握度/学习记录） |
| 预算数值 | 未改（60s/图、180s/卷）；关思考后单图 ≈6.5s，余量足够 |
| 重启后端 | 需重启 80 端口实例才能带上本次后端改动 |
