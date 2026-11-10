# Tasks：题目与知识点年级维度过滤

> **Change 名称**：`2026-11-05_grade_level_filtering`
> **创建日期**：2026-11-05
> **版本**：草案 v1
> **状态**：未开始（待确认；截至 2026-11-10 无任何代码落地）

---

## 状态校准（2026-11-10）

本 change 仍停留在草案阶段，未进入 apply。核对当前代码库：

| 本 change 的产出 | 现状 |
|------|------|
| `GradeMatcher` 工具类 | 不存在（全库无匹配） |
| `knowledge_point.grade_range` 列 | 不存在（`V1__init_schema.sql` 无该字段，也无新增脚本） |
| `question_bank.grade` 列 | 不存在（同上；仅 `student.grade` 存在） |
| 出题/评测的年级过滤 | 未实现 |
| 个人中心展示年级 | ✅ 已实现（`Profile.vue` 读取 `studentInfo.grade`） |

> 结论：verify 时除「个人中心展示年级」外，其余任务均无可核对实现，属正常未开工，不是工件与实现偏离。开工前需确认 Task 1 的 DDL 由谁审批（`src/main/resources/db/` 为受保护区域）。


---

## 任务清单

### Task 1：数据库 + Entity + 工具类

**描述**：
- 创建 DDL 脚本：`knowledge_point` 新增 `grade_range` 字段，`question_bank` 新增 `grade` 字段
- `KnowledgePoint.java` 新增 `gradeRange` 字段
- `QuestionBank.java` 新增 `grade` 字段
- 新建 `GradeMatcher.java` 工具类（年级匹配逻辑 + 降级策略）
- 为 `GradeMatcher` 编写单元测试（覆盖：单年级、范围、多年级、NULL、格式异常）

**涉及文件**：
- `src/main/resources/db/V13__add_grade_fields.sql`（新增）
- `src/main/java/com/jiang/model/entity/KnowledgePoint.java`（修改）
- `src/main/java/com/jiang/model/entity/QuestionBank.java`（修改）
- `src/main/java/com/jiang/common/util/GradeMatcher.java`（新增）
- `src/test/java/com/jiang/common/util/GradeMatcherTest.java`（新增）

**验证**：`mvn compile` + `mvn test` 通过

**预估时间**：40 分钟

---

### Task 2：VO 扩展 + 科目接口

**描述**：
- `SubjectVO` 新增 `availableGrades` 字段（`List<Integer>`）
- `SubjectServiceImpl.getAvailableSubjects()` 聚合该科目下所有 active 知识点的 `grade_range`，解析为年级列表
- `QuestionListVO` 新增 `grade` 字段
- 管理后端题目列表、知识点列表接口返回年级信息

**涉及文件**：
- `src/main/java/com/jiang/vo/SubjectVO.java`（修改）
- `src/main/java/com/jiang/vo/QuestionListVO.java`（修改）
- `src/main/java/com/jiang/service/impl/SubjectServiceImpl.java`（修改）
- `src/main/java/com/jiang/controller/AdminQuestionController.java`（修改，列表返回 grade）
- `src/main/java/com/jiang/controller/AdminKnowledgePointController.java`（修改，列表返回 gradeRange）

**验证**：`mvn compile` 通过

**预估时间**：30 分钟

---

### Task 3：出题逻辑年级过滤

**描述**：
- `HomeworkServiceImpl` 出题流程中，题目池筛选阶段增加年级过滤
- 获取学生年级：从 `StudentContext.getStudentId()` → 查 `Student.grade`
- 调用 `GradeMatcher.matchesQuestion()` 过滤题目
- 降级策略：本年级题目 < 2 道时扩展至相邻年级（±1），仍不足则取消过滤
- `AssessmentServiceImpl` 评测出题同样增加年级过滤

**涉及文件**：
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`（修改）
- `src/main/java/com/jiang/service/impl/AssessmentServiceImpl.java`（修改）

**验证**：`mvn compile` + 现有测试不回归

**预估时间**：1 小时

---

### Task 4：数据导入支持年级字段

**描述**：
- `DataImportServiceImpl` 知识点导入：解析 JSON 中可选的 `gradeRange` 字段
- `DataImportServiceImpl` 题目导入：解析 JSON 中可选的 `grade` 字段
- 字段缺失时写 NULL，不报错
- 更新初始 JSON 数据文件（`topics.json`、`questions.json`）的 schema 说明

**涉及文件**：
- `src/main/java/com/jiang/service/impl/DataImportServiceImpl.java`（修改）
- `src/main/resources/json/topics.json`（可选：补充示例数据的 gradeRange）
- `src/main/resources/json/questions.json`（可选：补充示例数据的 grade）

**验证**：`mvn compile` + 数据导入测试通过

**预估时间**：40 分钟

---

### Task 5：管理后端前端年级展示

**描述**：
- 知识点列表页：新增"年级"列，展示 `gradeRange`
- 题目列表页：新增"年级"列，展示 `grade`（NULL 时显示"继承知识点"）
- 搜索/筛选区域：新增年级筛选下拉框

**涉及文件**：
- `admin-web/src/views/KnowledgePointList.vue`（修改）
- `admin-web/src/views/QuestionList.vue`（修改）

**验证**：`npm run build` 通过

**预估时间**：1 小时

---

### Task 6：联调验证

**描述**：
- 准备测试数据：为部分知识点和题目标注年级
- 创建不同年级的测试学生
- 验证出题逻辑：三年级学生只看到三年级题目
- 验证降级策略：本年级题目不足时自动扩展
- 验证数据导入：带年级字段的 JSON 正常导入
- 验证管理后端：年级列正确展示，筛选功能正常

**涉及文件**：全部变更文件

**预估时间**：1 小时

---

## 任务依赖关系

```
Task 1（数据库 + Entity + 工具类）
  ├─ Task 2（VO 扩展 + 科目接口）
  ├─ Task 3（出题逻辑年级过滤）
  └─ Task 4（数据导入支持）

Task 2 完成后
  └─ Task 5（管理后端前端）

Task 1-5 完成后
  └─ Task 6（联调验证）
```

---

## 总预估时间

- **Task 1**：40 分钟
- **Task 2**：30 分钟
- **Task 3**：1 小时
- **Task 4**：40 分钟
- **Task 5**：1 小时
- **Task 6**：1 小时

**小计**：约 4 小时 40 分钟
**Buffer（20%）**：约 56 分钟
**总计**：约 5 小时 36 分钟（约 1 个工作日）

---

## 验收标准

1. `knowledge_point` 表有 `grade_range` 字段，`question_bank` 表有 `grade` 字段
2. `GradeMatcher` 工具类单元测试全部通过
3. 三年级学生调用每日作业接口，返回的题目均为三年级（或降级扩展后的相邻年级）
4. 未标注年级的知识点/题目对所有年级可见（向后兼容）
5. 数据导入支持 `gradeRange` 和 `grade` 可选字段
6. 管理后端知识点/题目列表展示年级信息
7. 编译无错误，现有测试不回归
