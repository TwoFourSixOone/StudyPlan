# Tasks：每日作业优先从题库抽题

> **Change 名称**：`2026-11-10_homework_bank_first`
> **状态**：待实施

---

### Task 1：题库抽题组件

- [x] 新建 `BankQuestionPicker`（`@Component`）：按 kp + 年级 + 排除集合取 N 道
- [x] 年级判定复用 `GradeMatcher.matchesQuestion`
- [x] 查询异常时返回空列表并记 ERROR（不阻塞出题）

**涉及文件**：
- `src/main/java/com/jiang/service/impl/BankQuestionPicker.java`（新增）
- `src/test/java/com/jiang/service/BankQuestionPickerTest.java`（新增）

**验证**：`mvn -q test-compile`

---

### Task 2：学生已做题库题查询

- [x] `HomeworkSubmissionDetailMapper` 增加 `selectAnsweredBankQuestionIds(studentId, tenantId)`
- [x] JOIN `daily_plan` + `learning_goal`，只取 `question_source = 'bank'`

**涉及文件**：
- `src/main/java/com/jiang/mapper/HomeworkSubmissionDetailMapper.java`

**验证**：`mvn -q test-compile`

---

### Task 3：接入举一反三（Step 7）

- [x] 遍历 `similarQuotaPerKp`：先 `pick(...)`，命中部分生成 `bank/similar` 引用
- [x] 剩余数量调用 `similarQuestionService.generateWithValidation(...)`
- [x] 题库已覆盖时跳过源题查询与 Agent 调用
- [x] 排除集合 = 已作答题库题 ∪ **近 30 天同一目标已排入计划的题库题**（联调发现：两天都没作答时会抽到同一题）

**涉及文件**：
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`

**验证**：`mvn -q test-compile`

---

### Task 4：接入知识回顾（Step 8）

- [x] 按掌握度升序遍历 `reviewKpList`，逐知识点取题直到配额用尽
- [x] 剩余配额走 `questionGenAgent.generate(...)`，保留重试与降级
- [x] 引用顺序：题库题在前、生成题在后

**涉及文件**：
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`

**验证**：`mvn -q test-compile`

---

### Task 5：测试

- [x] `BankQuestionPickerTest`：取 N 道 / 年级不符排除 / 已做排除 / 不足返回实际数量
- [x] `HomeworkServiceTest`：补充 `@Mock BankQuestionPicker`、`@Mock HomeworkSubmissionDetailMapper`
- [x] 既有用例不回归（picker 默认返回空 → 全走 Agent）
- [x] 新增用例：picker 返回 1 道 → 只补 1 道 Agent 题

**涉及文件**：
- `src/test/java/com/jiang/service/BankQuestionPickerTest.java`
- `src/test/java/com/jiang/service/HomeworkServiceTest.java`

**验证**：`mvn test`

---

### Task 6：文档

- [x] 产品规则 #3：题目来源改为"题库优先 + Agent 补足"
- [x] `openspec/specs/learning-loop.md`：每日作业环节标注题目来源

**涉及文件**：
- `docs/product/index.md`
- `openspec/specs/learning-loop.md`

---

### Task 7：构建与联调

- [x] `mvn test` 全量通过 → **576 tests, 0 failures, 5 skipped**
- [ ] 联调：生成作业后检查 `daily_plan.content`，`questionSource` 应出现 `bank`（similar/review）
- [ ] 联调：连续两天生成，第二天不应重复出现同一道题库题

> 联调需运行中的后端与真实学生账号，留给人工执行。
