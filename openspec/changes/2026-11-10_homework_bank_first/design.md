# Design：每日作业优先从题库抽题

> **Change 名称**：`2026-11-10_homework_bank_first`
> **版本**：v1

---

## 1. 组件设计

```
HomeworkServiceImpl.generateDailyHomework
  ├─ Step 7 举一反三：BankQuestionPicker.pick(kpId, N) → 不足部分 SimilarQuestionService（Agent）
  └─ Step 8 知识回顾：BankQuestionPicker.pick(kpId, 剩余配额) → 不足部分 QuestionGenAgent
```

`BankQuestionPicker`（新建，`@Component`）：

```java
List<QuestionBank> pick(String kpId, String tenantId, int count,
                        Integer studentGrade, String kpGradeRange, Set<Long> excluded)
```

规则：

1. `questionBankMapper.selectActiveByKp(kpId, tenantId)` 取该知识点可用题
2. 年级过滤：`GradeMatcher.matchesQuestion(studentGrade, q.getGrade(), kpGradeRange)`
3. 排除：`excluded`（本次计划已选 + 该学生历史已做）
4. 按 `questionId` 升序取前 `count` 道

**为什么独立成类而不是写进 `HomeworkServiceImpl`**：该 Service 已有 1166 行 / 20 个依赖，继续内联会让"抽出题"这段逻辑无法单测。独立类可纯 Mockito 测试。

## 2. 已做题目查询

```sql
SELECT DISTINCT hsd.question_id
FROM homework_submission_detail hsd
JOIN daily_plan dp ON dp.plan_id = hsd.plan_id AND dp.tenant_id = hsd.tenant_id
JOIN learning_goal lg ON lg.goal_id = dp.goal_id
WHERE lg.student_id = #{studentId} AND hsd.tenant_id = #{tenantId}
  AND hsd.question_source = 'bank'
```

放在 `HomeworkSubmissionDetailMapper`（已存在），返回 `List<Long>`，Service 转 `Set`。

## 3. 与配额的衔接

- **举一反三**：配额仍是 `quota.getSimilarQuotaPerKp()`（每知识点 1~3 道）。先从题库取 `targetCount` 道，不足部分调用 `similarQuestionService.generateWithValidation(...)`，传入 `remaining` 数量。
- **知识回顾**：配额 `quota.getReviewQuota()`。遍历 `reviewKpList`（掌握度升序）逐知识点取题，直到配额用尽；剩余配额交给 `questionGenAgent.generate(...)`。
- 两条路径都保留原有的 Agent 重试与降级（降级处理器）。

## 4. 引用顺序

`daily_plan.content` 的顺序为：错题重做 → 举一反三（题库题在前、生成题在后）→ 知识回顾（同理）。保持现有前端解析兼容（只依赖 `taskType` 字段）。

## 5. 失败处理

| 场景 | 行为 |
|------|------|
| 题库查询异常 | 记 ERROR，视为抽到 0 道，走 Agent 补足 |
| 题库不足 | Agent 补足剩余数量 |
| Agent 失败 | 沿用现有重试 + 降级处理器 |
| 已做题查询异常 | 记 ERROR，`excluded` 退化为仅本次计划内去重（不阻塞生成） |

## 6. 隐性约定对齐

| 约定 | 落实 |
|------|------|
| #1 统一响应 / #3 分页 | 不涉及接口变更 |
| #4 时间格式 | `planDate` 不变 |
| 产品规则 #3 | 更新"举一反三/知识回顾"的题目来源表述 |
| 产品规则 #11 | 题目年级判定复用 `matchesQuestion` |
| #13 索引 | 新 SQL 走 `idx_plan` / `idx_knowledge_point`；`learning_goal.student_id` 已有索引 |

## 7. 测试策略

| 层 | 用例 |
|----|------|
| `BankQuestionPickerTest` | 正常取 N 道 / 年级不匹配被排除 / 已答题被排除 / count 超过可用数 |
| `HomeworkServiceTest` | ① picker 返回 0 → 仍调用 Agent（现有行为不回归）② picker 返回 1 → 只补 1 道 Agent 题 ③ picker 抛异常 → 降级为全 Agent |

## 8. 产品文档改动

- 规则 #3「举一反三细则」：题目来源改为**题库优先 + Agent 补足**
- 规则 #3 术语：`generated_question` 与 `question_bank` 的关系说明
