# Tasks：题目与知识点年级维度过滤

> **Change 名称**：`2026-11-05_grade_level_filtering`
> **版本**：v2（2026-11-10 审核后修订）
> **状态**：待 apply（Task 0 已完成，其余未开工）

---

## 前置说明

v1 的状态是"待确认"，但核对代码后确认**零代码落地**：无 `GradeMatcher`、`knowledge_point` 无 `grade_range`、`question_bank` 无 `grade`、出题与评测无年级过滤。仅"个人中心展示年级"已由 `2026-11-05_student_business_pages` 完成（已归档），不属于本 change。

**Task 0 已完成**：产品规则 #11 已于 2026-11-10 落盘，边界阻塞解除，可从 Task 1 开始 apply。

> **验证策略（2026-11-10 调整）**：每个 Task 结束时**只跑 `mvn -q compile` 做快速编译校验**，不再逐个 Task 跑 `mvn test`——全量测试耗时约 1 分钟，逐 Task 执行会显著拖慢节奏。测试代码照常随 Task 编写，**统一在 Task 7 一次性执行** `mvn test` 与 `npm run build`。

---

## 任务清单

### Task 0：补产品规则（已完成）

- [x] 确认 `proposal.md` 附录 A 的「规则 #11：年级维度与内容匹配」文本
- [x] 写入 `docs/product/index.md`：规则 #11 + 术语表「年级」条目 + 维护规则第 8 条
- [x] 确认「严格优先 + 整体降级」口径（proposal §5）

**涉及文件**：
- `docs/product/index.md`（2026-11-10 已完成）

**验证**：文档结构为 `规则 #10 → 规则 #11 → §3 业务边界`，且与 proposal §5 口径一致

**实际耗时**：15 分钟

---

### Task 1：数据库 + Entity + GradeMatcher

- [x] 新建 `V11__add_grade_fields.sql`：两张表 `ALTER TABLE ADD COLUMN` + 注释形式的回滚 `DROP COLUMN`
- [x] `KnowledgePoint` 增 `gradeRange`（String）
- [x] `QuestionBank` 增 `grade`（Integer）
- [x] 新建 `GradeMatcher`：`normalizeStudentGrade` / `isValidRange` / `matchesRange` / `matchesQuestion`
- [x] `isValidRange` **同时校验语法与取值**（1–12、min ≤ max），不能只靠正则——否则 `"0"`/`"99"`/`"9-7"` 会入库成为"内容黑洞"
- [x] `GradeMatcherTest` 覆盖：数字/中文/学段归一化、单值/区间/列表、非法语法（倒序、尾逗号、负值、越界 `0`/`13`、全角符号）、空值短路

**涉及文件**：
- `src/main/resources/db/V11__add_grade_fields.sql`（新增）
- `src/main/java/com/jiang/model/entity/KnowledgePoint.java`
- `src/main/java/com/jiang/model/entity/QuestionBank.java`
- `src/main/java/com/jiang/common/util/GradeMatcher.java`（新增）
- `src/test/java/com/jiang/common/util/GradeMatcherTest.java`（新增）

**验证**：`mvn -q test-compile` 通过（主代码 + 测试代码编译）；测试执行统一在 Task 7

**实际耗时**：约 40 分钟

**预估时间**：1 小时（v1 估 40 分钟，低估了边界与归一化）

---

### Task 2：数据导入支持年级字段

- [x] 知识点导入解析 `gradeRange`：缺失写 NULL；`!isValidRange` → 该条失败写 `data_import_error_log`
- [x] 题目导入解析 `grade`：缺失写 NULL；非 1–12 整数 → 同样处理
- [x] **数据集字段兼容（2026-11-10 联调补充）**：知识点仅有单值 `grade`（无 `gradeRange`）时转为 `grade_range="N"`；两字段并存时 `gradeRange` 优先；13–16（大学学段）视为未标注落 NULL、不判失败
- [x] 失败不阻塞其他记录（沿用现有逐条校验机制，`ValidationResult(false, ...)` → `addError` → 继续下一条）
- [x] ~~示例数据补年级字段~~ **不执行**：`src/main/resources/json/` 为受保护目录，且本次联调已从用户提供的 `topics.json`（3108 条，自带 `grade`）导入验证，往仓库样例数据里塞年级没有实际收益

**涉及文件**：
- `src/main/java/com/jiang/service/impl/DataImportServiceImpl.java`
- `src/test/java/com/jiang/service/DataImportServiceTest.java`
- `src/main/resources/json/topics.json`（可选）
- `src/main/resources/json/questions.json`（可选）

**验证**：`mvn -q test-compile` 通过（主代码 + 测试代码编译）；测试执行统一在 Task 7

**实际耗时**：约 35 分钟

**预估时间**：1 小时

---

### Task 3：每日作业年级过滤

- [x] `HomeworkServiceImpl.generateDailyHomework`：组装 `kpList` 后、调用 Agent 前按 `matchesRange` 过滤；过滤后为空则降级为不过滤并 WARN
- [x] 打通 `gradeRange` 数据来源：`selectActiveStatesWithKpInfo` 增加 `k.grade_range`，`StudentKnowledgeState` 增瞬态字段 `kpGradeRange`，`KnowledgePointWithMastery` 增 `gradeRange`
- [x] `HomeworkDegradationHandler.generateDefaultPlan`：新增 `Integer studentGrade` 参数，取题后按 `matchesQuestion` 过滤
- [x] 调用点同步传参；复用已有 `getStudentGrade`，不新增 Mapper 依赖
- [x] 错题重做链路保持不过滤（不修改 `selectSourceQuestionForSimilar`）
- [x] **处理错题与过滤的交互**：把被年级排除的知识点从传给配额计算器的 `errorKpIds` 中剔除（等价于不分配举一反三配额，但仍保留其原题重做）；相似题循环再补一道防御性 `gradeKpIds` 跳过；被排除知识点的原题照常进入当日题单
- [x] `HomeworkServiceTest`：补 3 例（过滤生效 / 空池降级 / 配额仍为 15 与 4），并修正 `generateDefaultPlan` stub 签名

**涉及文件**：
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`
- `src/main/java/com/jiang/agent/HomeworkDegradationHandler.java`
- `src/main/java/com/jiang/mapper/StudentKnowledgeStateMapper.java`
- `src/main/java/com/jiang/model/entity/StudentKnowledgeState.java`
- `src/main/java/com/jiang/dto/KnowledgePointWithMastery.java`
- `src/test/java/com/jiang/service/HomeworkServiceTest.java`

**验证**：`mvn -q test-compile` 通过（主代码 + 测试代码编译）；测试执行统一在 Task 7

**实际耗时**：约 1 小时 10 分钟

**预估时间**：2 小时（含 DTO/Mapper 透传与测试修正）

---

### Task 4：专项评测年级过滤

- [x] `AssessmentServiceImpl`：`selectActiveByKp` 结果按 `matchesQuestion` 过滤
- [x] 复用循环内已加载的 `KnowledgePoint` 实体（含 `gradeRange`），无需额外 Map 或查询
- [x] 过滤后**为空**才回退未过滤候选集；数量不足 `QUESTIONS_PER_KP` 时按实际数量抽题（避免过滤形同虚设）
- [x] `AssessmentServiceTest` 补 2 例（过滤生效 / 空池回退）

**涉及文件**：
- `src/main/java/com/jiang/service/impl/AssessmentServiceImpl.java`
- `src/test/java/com/jiang/service/AssessmentServiceTest.java`

**验证**：`mvn -q test-compile` 通过（主代码 + 测试代码编译）；测试执行统一在 Task 7

**实际耗时**：约 40 分钟

**预估时间**：45 分钟

---

### Task 5：管理端列表展示与筛选

- [x] `KnowledgePointListVO` 增 `gradeRange`；`KnowledgePointGovernanceServiceImpl.getKnowledgePointList` 填充（**仅展示，不做年级筛选**，range 字符串在 SQL 层无法可靠匹配且 Java 过滤会破坏分页）
- [x] `QuestionListVO` 增 `grade`；`AdminListQueryServiceImpl.getQuestionList` 填充并支持按 `grade` **精确**筛选（单值列，SQL 层可 WHERE）
- [x] 两个 Controller 透传 `grade` 查询参数（题目列表用；知识点列表不加）
- [x] 管理端页面：知识点列表、题目列表新增"年级"列；题目列表筛选区加年级下拉（空值时展示"全年级"/"继承知识点"）

**涉及文件**：
- `src/main/java/com/jiang/vo/KnowledgePointListVO.java`
- `src/main/java/com/jiang/vo/QuestionListVO.java`
- `src/main/java/com/jiang/service/impl/KnowledgePointGovernanceServiceImpl.java`
- `src/main/java/com/jiang/service/impl/AdminListQueryServiceImpl.java`
- `src/main/java/com/jiang/controller/AdminKnowledgePointController.java`
- `src/main/java/com/jiang/controller/AdminQuestionController.java`
- `admin-web/src/views/knowledge-point/KnowledgePointList.vue`
- `admin-web/src/views/question/QuestionList.vue`
- 对应测试：`KnowledgePointGovernanceServiceTest` / `AdminListQueryServiceTest`

> v1 只列了两个 Controller，漏了真正产出 VO 的两个 Service。

**验证**：`mvn -q test-compile` 通过；`cd admin-web && npm run build` 已在本次执行通过（6.65s）

**实际耗时**：约 50 分钟

**预估时间**：1 小时 15 分钟

---

### Task 6：文档与 spec 回写

- [x] `docs/architecture/index.md §5.4`：补 `grade_range` / `grade` 的字段映射说明
- [x] `openspec/specs/learning-loop.md`：在"每日作业生成"与"专项评测"两节补年级过滤步骤
- [x] 隐性约定无需新增（年级字段不参与唯一索引，已被 #13 覆盖）

**涉及文件**：
- `docs/architecture/index.md`
- `openspec/specs/learning-loop.md`
- `docs/architecture/implicit-contracts.md`（按需）

**验证**：文档与实现字段名、流程步骤一致

**实际耗时**：约 20 分钟

**预估时间**：30 分钟

---

### Task 7：全量测试与联调验证

- [x] **全量测试**：`mvn test` → 551 tests, 0 failures, 0 errors, 5 skipped（本次新增 30 例全绿）
- [x] **管理端构建**：`cd admin-web && npm run build` → 通过（6.65s）
- [x] **联调发现的缺陷（独立 fix）**：`deriveStage` 误判物理学段（`cps-2022:2.2.1` → 小学，实际 56 条物理课标被判小学）。已改为**优先用 `grade_range` 派生学段**（`GradeMatcher.stageOf/stageOfRange`），课标 code 解析降级为兜底；题目列表的学段不再强依赖课标表。全局测试 558 通过
- [x] **联调修复（管理端搜索）**：① 题库页「知识点」下拉被挂到「年级」上——原因是 `searchFields.value[2]` 写死下标，插入年级字段后错位，已改为按 `prop` 查找 ② 知识点下拉只加载 100 条 → 改为分页拉全量 + `filterable` ③ 知识点页补「年级」筛选（后端 `grade` 参数，按 `grade_range` 内存匹配后再分页，保证 total 正确）
- [x] **联调修复（筛选不生效）**：`SearchForm` 用 `v-model` 绑定父级 `const reactive`，赋值抛错且发生在 `emit('search')` 之前，导致**所有页面筛选条件都未回传**。已改为先 `emit('search', params)` 再同步表单，父页面 `handleSearch(params)` 合并参数；6 个页面统一修正
- [x] **联调修复（题库年级展示）**：题目未标注年级时显示"继承知识点"不够明确，改为展示实际继承结果：`9 年级（继承）` / `7-9（继承）` / `全年级`（后端 VO 增加 `gradeRange` 回传）
- [x] 造数：手工 SQL 给 2–3 个学生设置 `grade`（如 3 / 9 / 12），并给部分知识点/题目标注年级
- [x] 验证"设置年级前后"出题差异（同科目同日期，对比知识点集合）
- [x] 验证空池降级：把某年级唯一知识点的 `grade_range` 改到其他年级，观察 WARN 与降级结果
- [x] 验证评测过滤与回退
- [x] 验证导入：合法/非法年级字段各一组，检查 `data_import_error_log`
- [x] 验证管理端列与筛选

**联调结论（用户确认，2026-11-10）**：3108 个知识点、12579 道题导入成功；知识点年级筛选（精确匹配）、题库年级筛选（含继承）、年级列中文展示均正常；「无数据 / 筛选失效」等问题已在联调中逐项修复。

**涉及文件**：全部变更文件

**验证**：`mvn test` 全量通过 + `cd admin-web && npm run build` 通过 + 形成联调记录（含每条断言的实际输出）

> **联调说明**：自动化部分（全量测试 + 前端构建）已完成。剩余 6 项需真实 MySQL + 运行中的后端才能验证——尤其是"造数"必须先手工 `UPDATE student SET grade='3'`，否则学生年级为 NULL、年级过滤不会生效（功能会表现为"没变化"，容易误判为 bug）。这部分留给人工执行。

**预估时间**：1 小时 30 分钟

---

## 任务依赖关系

```
Task 0（产品规则）✅ 已完成
Task 1（DB + Entity + Matcher）
  ├─ Task 2（导入）
  ├─ Task 3（作业过滤，含错题交互）
  └─ Task 4（评测过滤）
Task 1 + Task 5 完成后
  └─ Task 6（文档 + spec 回写）
Task 2/3/4/5 完成后
  └─ Task 7（联调）
```

## 总预估时间

| 任务 | 时间 |
|------|------|
| Task 0 | 15 分钟（已完成） |
| Task 1 | 1 小时 |
| Task 2 | 1 小时 |
| Task 3 | 2 小时 |
| Task 4 | 45 分钟 |
| Task 5 | 1 小时 15 分钟 |
| Task 6 | 30 分钟 |
| Task 7 | 1 小时 30 分钟 |
| **小计** | **8 小时 15 分钟** |
| Buffer（20%） | 1 小时 39 分钟 |
| **总计** | **约 10 小时**（2 个工作日） |

> v1 估 5 小时 36 分，漏了导入校验、文档/spec 回写、测试修正、造数联调，以及 `gradeRange` 从 Mapper 到 DTO 的透传链路与错题配额交互。

---

## 验收标准

1. `V11__add_grade_fields.sql` 含正向 DDL 与回滚语句
2. `GradeMatcher` 单测覆盖全部边界（归一化 / 非法语法 / 空值短路）
3. 学生年级设为 3 时，传给 Agent 的知识点均满足 `grade_range`；过滤后为空则整体降级且有 WARN
4. 过滤后配额仍满足单日 ≤15、单知识点 ≤4
5. 评测候选集按年级过滤，空池回退
6. 错题重做不受年级限制
7. 导入非法年级字段写入 `data_import_error_log` 且不阻塞其他记录
8. 管理端列表展示年级并可筛选
9. `mvn test` 与 `cd admin-web && npm run build` 均通过
10. 产品规则 #11 已落盘（Task 0 完成）
