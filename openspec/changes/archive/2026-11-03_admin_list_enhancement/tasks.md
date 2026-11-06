# Tasks：管理后端列表查询增强（v2 修复版）

> **Change 名称**：`2026-11-03_admin_list_enhancement`  
> **版本**：v2（修复 BLOCK-1/2/3 + IMP-1~5）

---

## DDL 版本声明（修复 BLOCK-3）

- 本 Change 使用 **V9__admin_list_index.sql**
- **V9 仅依赖 V1~V5 既有表**（question_bank、knowledge_point_dependency、curriculum_standard、data_import_log、data_import_error）
- **V9 不依赖 V6-V8**（exam_paper、plan_adjustment、learning_record_index）
- 若并行开发，V9 可独立 apply
- **建议合并顺序**：V6 → V7 → V8 → V9（但技术上 V9 无硬性依赖）

---

## 任务清单

### T-01：数据库索引优化（修复 BLOCK-3）

**任务描述**：
1. 创建 `V9__admin_list_index.sql` DDL 脚本，添加复合索引：
   - `question_bank` 添加 `idx_question_kp_difficulty`、`idx_question_type`（列名为 `type`，修复 IMP-1）、`idx_question_content`
   - `knowledge_point_dependency` 添加 `idx_dependency_kp`、`idx_dependency_prereq`
   - `curriculum_standard` 添加 `idx_standard_stage`、`idx_standard_subject`（列名为 `subject`，修复 IMP-5）、`idx_standard_description`
   - `data_import_log` 添加 `idx_import_log_type`、`idx_import_log_time`
   - `data_import_error` 添加 `idx_import_error_log`
2. **BLOCK-3 声明**：V9 仅依赖 V1-V5 既有表，不依赖 V6-V8

**涉及文件**：
- `src/main/resources/db/migration/V9__admin_list_index.sql`（新增）

**验收标准**：
- [x] DDL 脚本可正常执行
- [x] 索引创建成功
- [x] 编译通过

---

### T-02：DTO/VO 类定义

**任务描述**：
1. 创建 VO 类：
   - `QuestionListVO.java`（`questionType` 字段对应实体 `QuestionBank.type`，修复 IMP-1）
   - `DependencyListVO.java`
   - `StandardListVO.java`（`subjectName` 字段直接来自 `CurriculumStandard.subject`，修复 IMP-5）
   - `ImportLogListVO.java`
   - `ImportErrorVO.java`

**涉及文件**：
- `src/main/java/com/jiang/vo/QuestionListVO.java`（新增）
- `src/main/java/com/jiang/vo/DependencyListVO.java`（新增）
- `src/main/java/com/jiang/vo/StandardListVO.java`（新增）
- `src/main/java/com/jiang/vo/ImportLogListVO.java`（新增）
- `src/main/java/com/jiang/vo/ImportErrorVO.java`（新增）

**验收标准**：
- [x] VO 类字段完整
- [x] QuestionListVO.questionType 对应实体 QuestionBank.type
- [x] StandardListVO.subjectName 对应实体 CurriculumStandard.subject
- [x] 编译通过

---

### T-03：AdminListQueryService 核心实现（修复 IMP-1~5）

**任务描述**：
1. 创建 `AdminListQueryService` 接口
   - **修复 IMP-2**：不包含 `getKnowledgePointList`（归 Change 9 负责）
2. 实现 `AdminListQueryServiceImpl`，包含：
   - `getQuestionList()`：
     - 修复 IMP-1：QueryWrapper 使用 `eq("type", questionType)` 而非 `eq("question_type", ...)`
     - 修复 IMP-3：keyword LIKE 转义 `%` 和 `_`
     - 修复 IMP-4：`size = Math.min(size, 100)`
     - 修复 IMP-5：知识点名称批量查询，避免 N+1
   - `getDependencyList()`：
     - 修复 IMP-3：keyword LIKE 转义
     - 修复 IMP-5：知识点名称批量查询
   - `getStandardList()`：
     - 修复 IMP-5：`CurriculumStandard.subject` 是 String 字段，直接取值
     - 修复 IMP-3：keyword LIKE 转义
   - `getImportLogList()`：时间范围筛选
   - `getImportErrorList()`：按 logId 查询
3. **修复 BLOCK-1/N1**：所有接口使用 `StudentContext.getTenantId()`

**涉及文件**：
- `src/main/java/com/jiang/service/AdminListQueryService.java`（新增）
- `src/main/java/com/jiang/service/impl/AdminListQueryServiceImpl.java`（新增）

**验收标准**：
- [x] 题目列表查询列名使用 `type`
- [x] 课程标准列表 `subject` 直接取 String 值
- [x] keyword LIKE 转义 `%` 和 `_`
- [x] size 最大限制 100
- [x] 不包含 getKnowledgePointList（归 Change 9）
- [x] 编译通过

---

### T-04：Mapper 扩展（修复 IMP-5）

**任务描述**：
1. 在 `KnowledgePointMapper` 中添加：
   - `selectNamesByIds()` — 批量查询知识点名称，返回 `Map<Long, String>`（修复 IMP-5：避免 N+1）
2. 确认现有 Mapper 方法满足列表查询需求

**涉及文件**：
- `src/main/java/com/jiang/mapper/KnowledgePointMapper.java`（修改）

**验收标准**：
- [x] selectNamesByIds 使用 IN + GROUP BY 批量查询
- [x] 编译通过

---

### T-05：Controller 实现（修复 BLOCK-1/N1 + IMP-4）

**任务描述**：
1. 创建 `AdminQuestionController`
2. 创建 `AdminDependencyController`
3. 创建 `AdminStandardController`
4. 创建 `AdminImportLogController`
5. **修复 BLOCK-1/N1**：所有管理端接口使用 `StudentContext.getTenantId()`
6. **修复 IMP-4**：所有 Controller 中 `size = Math.min(size, 100)`

**涉及文件**：
- `src/main/java/com/jiang/controller/AdminQuestionController.java`（新增）
- `src/main/java/com/jiang/controller/AdminDependencyController.java`（新增）
- `src/main/java/com/jiang/controller/AdminStandardController.java`（新增）
- `src/main/java/com/jiang/controller/AdminImportLogController.java`（新增）

**验收标准**：
- [x] 接口可正常访问
- [x] 参数校验正确
- [x] size 最大限制 100
- [x] N1 标注：Demo 阶段复用 StudentContext
- [x] 编译通过

---

### T-06：单元测试

**任务描述**：
1. `AdminListQueryServiceTest`：覆盖核心业务逻辑
   - `should_returnQuestionList_when_validFilters`（验证列名为 `type`）
   - `should_escapeKeyword_when_keywordContainsPercent`（验证 IMP-3 转义）
   - `should_limitSizeTo100_when_sizeExceeds100`（验证 IMP-4 限制）
   - `should_returnDependencyList_when_validFilters`
   - `should_returnStandardList_when_validFilters`（验证 `subject` 为 String）
   - `should_returnImportLogList_when_validFilters`
   - `should_returnImportErrorList_when_validLogId`
   - `should_returnEmptyList_when_noData`

**涉及文件**：
- `src/test/java/com/jiang/service/AdminListQueryServiceTest.java`（新增）

**验收标准**：
- [x] 单元测试覆盖率 ≥ 80%
- [x] 所有测试通过
- [x] 编译通过

---

### T-07：集成测试

**任务描述**：
1. `AdminQuestionControllerTest`：覆盖题目列表接口
2. `AdminDependencyControllerTest`：覆盖先修关系列表接口
3. `AdminStandardControllerTest`：覆盖课程标准列表接口
4. `AdminImportLogControllerTest`：覆盖导入日志与错误记录接口

**涉及文件**：
- `src/test/java/com/jiang/controller/AdminQuestionControllerTest.java`（新增）
- `src/test/java/com/jiang/controller/AdminDependencyControllerTest.java`（新增）
- `src/test/java/com/jiang/controller/AdminStandardControllerTest.java`（新增）
- `src/test/java/com/jiang/controller/AdminImportLogControllerTest.java`（新增）

**验收标准**：
- [x] 集成测试覆盖率 ≥ 60%
- [x] 所有测试通过
- [x] 编译通过

---

### T-08：全量编译与测试验证

**任务描述**：
1. 执行 `mvn clean compile`，确保编译通过
2. 执行 `mvn test`，确保所有测试通过
3. 执行 `mvn package -DskipTests`，确保打包成功
4. 检查代码质量，无警告

**涉及文件**：
- 所有变更文件

**验收标准**：
- [x] 编译通过，无错误
- [x] 所有测试通过
- [x] 打包成功
- [x] 代码质量检查通过

---

## 任务依赖关系

```
T-01（数据库索引优化）
    ↓
T-02（DTO/VO 类定义）
    ↓
T-03（AdminListQueryService 核心实现）
    ↓
T-04（Mapper 扩展）
    ↓
T-05（Controller 实现）
    ↓
T-06（单元测试）
    ↓
T-07（集成测试）
    ↓
T-08（全量编译与验证）
```

---

## 预估工时

| 任务 | 预估工时 |
|------|----------|
| T-01 | 0.5h |
| T-02 | 0.5h |
| T-03 | 3h |
| T-04 | 0.5h |
| T-05 | 2h |
| T-06 | 2h |
| T-07 | 2h |
| T-08 | 1h |
| **总计** | **11.5h** |

---

**版本**：v2（修复 BLOCK-1/2/3 + IMP-1~5）  
**创建日期**：2026-11-03  
**最后更新**：2026-11-04（全部完成）
