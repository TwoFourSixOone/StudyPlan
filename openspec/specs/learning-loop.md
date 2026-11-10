# 系统工作原理：学习闭环

> **维护触发**：change 归档时，若改变了闭环中任一步骤的触发条件、接口、落库表或状态迁移，必须在同一轮更新本文件。
> **冲突处理**：行为边界以 `docs/product/index.md` 为准，本文件只描述"代码实际怎么做"。

---

## 1. 闭环总览

```
设定目标 → 生成每日计划/作业 → 逐题作答 → 批改并更新掌握度
                                  ↓
            专项评测 ────────────┤
            试卷上传/解析 ────────┘
                                  ↓
                          触发计划调整 → 生成 diff → 确认生效
```

| # | 步骤 | 入口 | 主要落库 | 规则 |
|---|------|------|---------|------|
| 1 | 设定目标 | `POST /api/student/goal`（`GoalServiceImpl`） | `learning_goal` | #1 |
| 2 | 生成每日作业 | `POST /api/homework/generate`（`HomeworkServiceImpl.generateDailyHomework`） | `daily_plan`、`generated_question` | #3 |
| 3 | 逐题作答 | `POST /api/homework/submit`（`submitAnswer`） | `homework_submission_detail` | #9 |
| 4 | 批改 | `POST /api/homework/grade`（`gradeHomework`） | 同上 + `error_question` + 掌握度 | #2/#3 |
| 5 | 专项评测 | `AssessmentController`（`AssessmentServiceImpl`） | `assessment_result`、`assessment_submission_detail` | #9 |
| 6 | 试卷上传/解析 | `ExamPaperController`（`ExamPaperServiceImpl`） | `exam_paper`、`exam_question`、`exam_analysis_task` | #4 |
| 7 | 计划调整 | `POST /api/student/plan/adjust`（`PlanAdjustmentServiceImpl`） | `plan_adjustment_diff`、`plan_adjustment_log` | #5 |

---

## 2. 每日作业生成

`generateDailyHomework(studentId, tenantId, subjectId, planDate)` 的顺序：

1. 取该科目活跃目标（无目标 → `LEARNING_GOAL_NOT_FOUND`）
2. 查当日计划：已是 `in_progress` / `completed` → `HOMEWORK_ALREADY_EXISTS`
3. 查到期错题（`errorQuestionService.getDueErrorQuestions`）与活跃知识点掌握度
4. **配额计算**（`HomeworkQuotaCalculator`）：
   - 单日上限 15 题（硬上限）、单知识点上限 4 题
   - 到期原题（错题重做）优先占位，不可压缩
   - 到期错题 ≥ 15 → **极端模式**：错题独占全部配额，举一反三与知识回顾当日全部暂停
   - 有错题的知识点至少 1 道举一反三；配额不足按掌握度从低到高分配
   - 无错题知识点按掌握度从低到高分配常规新题
5. 调用 `QuestionGenAgent.generate(kpList, quota)`；失败重试 2 次后走 `HomeworkDegradationHandler` 生成默认题单
6. 举一反三新题写 `generated_question`（题干相似度 > 0.8 触发重试，最多 2 次）
7. 序列化题目引用到 `daily_plan.content`，状态 `pending_generation` → `pending`（`updateContentAndStatus` 带状态机校验）

> `daily_plan` 按 `goal_id + plan_date + tenant_id` 唯一，并发插入靠唯一索引 + 捕获 `DuplicateKeyException` 兜底（隐性约定 #11）。

---

## 3. 提交与批改

**提交**（`submitAnswer`）：

- 按 `planId` 查计划，校验计划归属当前学生（`STUDENT_IDENTITY_MISMATCH`）
- 允许 `pending` / `in_progress` 状态提交，重复提交覆盖同题记录
- 首次提交把计划从 `pending` 推进到 `in_progress`

**批改**（`gradeHomework`）：

1. 取活跃目标 → 按 `goal_id + planDate` 查计划；`completed` → `HOMEWORK_ALREADY_GRADED`；非 `in_progress` → `HOMEWORK_NOT_SUBMITTED`
2. 校验所有计划内题目均已提交，否则报出缺失题目 ID
3. 逐题判定：
   - **选择题**：字符串精确比较（trim + 忽略大小写），不调用 AI
   - **填空题/解答题**：`aiGradeQuestion()` 调 LLM 判对错；LLM 失败或返回无法解析 → **降级为字符串比较**
4. 并行生成每题解析（`generateAnalysis`，失败仅置空，不影响批改结果）
5. 错题入队：redo 题答对 `advanceStage` / 答错 `resetStage`；非 redo 题答错 `recordWrongAnswer`
6. 调 `MasteryService.updateMasteryFromHomework` 批量更新掌握度（并触发错题暂停/激活同步）
7. 计划状态 `in_progress → completed`，`updateStatusWithCheck` 影响行数为 0 → `HOMEWORK_CONCURRENT_CONFLICT`

> 该方法是**整段 `@Transactional`**（单学生单日 ≤15 题，事务宽度可接受）；涉及 LLM 的生成/调整路径才使用 `TransactionTemplate` 缩窄事务（约定 #10）。

---

## 4. 专项评测

- 题目由 `AssessmentQuestionSelector` 从题库按知识点挑选，结果落 `assessment_result` + `assessment_submission_detail`
- 掌握度来源权重 `W_source = 0.7`
- 评测结果计入学习记录（规则 #9），`status = graded` 后才出现在学习记录 UNION 查询中

---

## 5. 试卷上传与解析（当前不可用）

设计链路：上传图片 → `FileStorageService` 落盘 → 写 `exam_paper` + `exam_analysis_task` → Redis 队列 → OCR 切题 → `AttributionAgent` 逐题归因 → 按 `W_source = 1.0` 更新掌握度并写入错题。

**实际现状**：`TencentOcrServiceImpl.recognize()` 直接抛 `OcrException("OCR 服务尚未实际接入")`，切题环节无实现，链路在第一步即中断。`ExamPaperServiceImpl` 对 `OcrException` 有捕获分支，但当前没有可用的降级产出。

---

## 6. 计划调整

`PlanAdjustmentServiceImpl.triggerAdjustment(goalId)`：

1. 应用层防重：同 goal 已有 `pending` diff → `PLAN_ADJUST_CONFLICT`
2. `SignalDetectorImpl.detectSignals` 检测四类信号（任一命中才继续）：
   - SIG-1：窗口内连续 N 次作业正确率 < 阈值
   - SIG-2：单次考试掌握度下降绝对值 > 0.15（按 **学生+科目** 查询，`exam_paper` 无 `goal_id`）
   - SIG-3：连续 N 天未完成作业
   - SIG-4：剩余天数 × 每日容量 × 效率 < 未覆盖知识点数
3. 无信号 → `AdjustTriggerVO.noAdjustment()`（"暂无需调整"），**不调用 Agent**
4. 组装上下文 `AdjustmentContextDTO`：目标详情 + 未来 7 天真实计划（含原 content）+ 未覆盖知识点（≤30 条）
5. 事务外调用 `AdjustmentAgent`；`AdjustmentException` → 写 `agent_degradation_log` 后降级为空 diff
6. 事务内 `saveDiff` 落 `plan_adjustment_diff`

**确认**（`PlanDiffServiceImpl.applyDiff`）：只处理 `date >= today` 的条目；`newContent` 为空（null/空白/`[]`）→ 计划置 `pending_generation`，否则置 `pending`；随后 diff 置 `confirmed` 并写 `plan_adjustment_log`。

---

## 7. 关键不变量

1. 学生数据隔离：所有查询的 `student_id` 来自 JWT，不接受客户端传参
2. 每日作业 ≤15 题、单知识点 ≤4 题，极端场景下错题优先
3. 掌握度只能由后端按公式更新，Agent 只产出题目与归因，不产出掌握度数值
4. 学习记录与系统日志只追加，不更新不删除
5. 计划状态迁移必须带 `WHERE status = 期望旧值`，靠影响行数检测并发
6. 凡调用 LLM 的路径必须有超时、降级与 `agent_degradation_log` 记录
