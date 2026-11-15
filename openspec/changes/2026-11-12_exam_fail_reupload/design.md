# Design：试卷解析失败后允许当天重新上传

> **Change 名称**：`2026-11-12_exam_fail_reupload`

---

## 1. 状态机（改前 / 改后）

```
改前：
  (无记录) --create--> uploading --submit--> pending --parse--> completed | failed
                                                        │
  failed  --retry(≤2)--> pending                        └─ 当天再 create → 1131（卡死）

改后（仅「failed 且 0 题」新增一条边）：
  failed(0题) --create--> uploading（复用同一条 exam_paper，image_urls=[]）
  failed(有题) --create--> 1131
  completed / pending / processing --create--> 1131
```

## 2. D1：失败可重传（后端）

`ExamPaperServiceImpl.createPaper` 在 1131 之前加一个分支：

```java
if ("failed".equals(existing.getStatus())
        && !hasPersistedQuestions(existing.getId(), tenantId)
        && examPaperMapper.resetFailedPaperForReupload(existing.getId(), tenantId) > 0) {
    return ExamUploadVO.builder().paperId(existing.getId()).status("uploading")
            .imageCount(imageCount).reused(true).build();
}
```

SQL（条件即锁）：

```sql
UPDATE exam_paper
   SET image_urls = JSON_ARRAY(), status = 'uploading', grade_status = 'ungraded',
       remark = NULL, total_score = NULL, update_time = NOW()
 WHERE id = ? AND tenant_id = ? AND status = 'failed' AND grade_status = 'ungraded'
```

**为什么必须 0 题**：`persistQuestions`（TX-A）之后才会 `settle`（TX-B）写掌握度 / 错题 / 学习记录。
只有 0 题时重置才**没有任何已发生副作用**；有题说明 TX-A 已提交（可能 TX-B 也部分生效），
重置会造成"题目重复 + 掌握度重复记账"，因此继续用 1131 拦住。

**为什么复用同一条记录**：`exam_paper` 有 (student, subject, exam_date, tenant) 唯一键且零 DDL 不可改；
复用记录同时保留了 `id`（历史详情链接不失效）。旧的 `exam_analysis_task`（failed）保留不动，
新的 submit 会插入新 task，`selectLatestByPaper` 仍取最新。

**并发**：两个请求同时走重置，第一个影响 1 行，第二个影响 0 行 → 落到 1131（前端已 toast），不会双开。

## 3. D2：1131 的前端出口

`request.ts` 业务错误分支不再只丢 message，同时把 `body.code` 挂到 Error 上：

```ts
reject(businessError(body?.message, body?.code));
function businessError(message, code) { const e = new Error(message || '业务错误'); e.code = code; return e; }
```

`ExamUpload.vue`：

```js
if (e && e.code === 1131) { offerExamListAfterDuplicate(); return; }
```

弹窗：「该科目今天已有试卷 / 同一科目同一天只能上传一份试卷。可在「历史试卷」查看解析结果和得分，
或换一个考试日期再上传。」→ 确认跳 `/pages/exam-list/ExamList`。

**为什么不自动跳转**：学生可能只是想换个日期重传（弹窗里也这么写），自动跳会打断他的下一步操作。

## 4. D3：为什么 `completed` 不允许重传

试卷结算会：① 写 `exam_question.is_correct`；② 错题入队（负 ID 命名空间）；③ 更新掌握度
（`W_source=1.0`，答对答错都更）；④ 写学习记录（规则 #2：只读不删）；⑤ 触发 SIG 信号调整。

重解析必须先**回滚** ②③④⑤，而学习记录是"只读不删"的硬约束 → 只能做补偿流水，收益远低于风险。
因此：部分图失败（`completed` + `remark` 非空）**保持现状**，学生只能次日重新上传；
本次只解决"整卷失败 → 当天彻底卡死"这个更硬的死角。

## 5. 与"预算取消"的关系

`HTTP request interrupted` 是 `future.cancel(true)` 中断在途 HTTP 的伴生日志；
根因是单图耗时 > 60s（旧实例开着深度思考 + JSON 截断重试）。
`2026-11-12_llm_latency` 关闭思考后单图约 6.5s，预算不再触顶；本 change 只保证"真失败时能重来"。

## 6. 验证

- 单测：`failed+0题 → reused/uploading`；`failed+有题 → 1131`；`completed → 1131`（原有）
- 全局回归：`mvn test`（823）
- 前端：dev 构建热更新后 `dist/dev` 含 `offerExamListAfterDuplicate` 与 `businessError`
