# Tasks：学习记录查询与回溯系统（v3 修复版）

> **Change 名称**：`2026-11-03_learning_record_query`  
> **版本**：v3（修复 BLOCK-C）

---

## DDL 版本声明

- 本 Change 使用 **V8__learning_record_index.sql**
- V8 仅添加 V5 未覆盖的索引（修复 IMP-19：避免与 V5 重叠）
- V8 依赖 V6（exam_paper_analysis）和 V7（plan_adjustment）已 apply
- **合并顺序**：V6 → V7 → V8 → V9

---

## 任务清单

### T-01：数据库索引优化（修复 IMP-19）✅

**任务描述**：
1. 创建 `V8__learning_record_index.sql`（仅添加 V5 未覆盖的索引）
2. `mastery_change_log` 添加 `idx_mastery_log_kp_student` 和 `idx_mastery_log_source`
3. **不重复添加** V5 已有的 homework/assessment/exam 的 `idx_student_date` 索引

**涉及文件**：
- `src/main/resources/db/migration/V8__learning_record_index.sql`（新增）

---

### T-02：RecordType 枚举 + DTO/VO + 错误码 ✅

**任务描述**：
1. 创建 `RecordType` 枚举（含 `fromCode()` 校验，BLOCK-11 修复）
2. 创建 VO：`LearningRecordListVO`、`LearningRecordDetailVO`、`HomeworkDetailVO`、`AssessmentDetailVO`、`ExamDetailVO`、`HomeworkQuestionDetailVO`、`MasteryChangeVO`
3. 错误码（**1150-1159 编号段**）：
   - `RECORD_NOT_FOUND(1150)`
   - `INVALID_RECORD_TYPE(1151)`

**涉及文件**：
- `src/main/java/com/jiang/model/enums/RecordType.java`（新增）
- `src/main/java/com/jiang/vo/` 下多个 VO（新增）
- `src/main/java/com/jiang/common/exception/ErrorCodes.java`（修改）

---

### T-03：Mapper 扩展（修复 IMP-17 + BLOCK-C）✅

**任务描述**：
1. `HomeworkSubmissionDetailMapper`：`selectByStudentIdAndDateRange()`、`selectByIdAndStudentId()`
2. `AssessmentResultMapper`：`selectByStudentIdAndDateRange()`、`selectByIdAndStudentId()`
3. `ExamAnalysisResultMapper`：`selectByStudentIdAndDateRange()`、`selectByIdAndStudentId()`
4. `MasteryChangeLogMapper`：`selectByKnowledgePointIdAndStudentId()`、`selectBySourceIdAndType()`（IMP-17 修复）
5. `HomeworkQuestionMapper`：`selectBySubmissionId()`（IMP-18 修复）
6. `LearningRecordMapper`（新增）：`selectUnionAllRecords()`、`countUnionAllRecords()`（BLOCK-C 修复：UNION ALL 三类记录统一分页）
   - homework 分支使用 LEFT JOIN daily_plan/learning_goal（NOTE-2 修复：防御性编程，避免孤儿记录丢失）
7. `SubjectMapper` 扩展：`selectNamesByIds(List<Long> ids)` 批量查询科目名称（防 N+1）

**涉及文件**：
- 多个 Mapper 接口（修改）
- `src/main/java/com/jiang/mapper/LearningRecordMapper.java`（新增）
- `src/main/resources/mapper/LearningRecordMapper.xml`（新增）

---

### T-04：LearningRecordService 核心实现（修复 BLOCK-C/11 + IMP-17/18）✅

**任务描述**：
1. `getRecordList()`：SQL UNION ALL 统一分页（BLOCK-C 修复：废弃三次归并方案）
2. `getRecordDetail(RecordType, id)`：按类型分派（BLOCK-11 修复）
3. `buildHomeworkDetail()` / `buildAssessmentDetail()` / `buildExamDetail()`：完整实现（IMP-18 修复）
4. `getMasteryChangesBySource()`：补充实现（IMP-17 修复）
5. `toMasteryChangeVO()`：BigDecimal 使用 subtract()（IMP-16 修复）

**涉及文件**：
- `src/main/java/com/jiang/service/LearningRecordService.java`（新增）
- `src/main/java/com/jiang/service/impl/LearningRecordServiceImpl.java`（新增）

---

### T-05：LearningRecordController 实现（修复 BLOCK-11）✅

**任务描述**：
1. `GET /api/student/records`：recordType 参数用 `RecordType.fromCode()` 校验
2. `GET /api/student/records/{recordType}/{id}`：路径含 recordType（BLOCK-11 修复）
3. `GET /api/student/records/mastery-changes`

**涉及文件**：
- `src/main/java/com/jiang/controller/LearningRecordController.java`（新增）

---

### T-06：单元测试

**任务描述**：
1. `RecordTypeTest`：
   - `should_parseValidCode_when_homeworkAssessmentExam`
   - `should_throwException_when_invalidCode`（BLOCK-11）
   - `should_trimAndIgnoreCase_when_inputHasSpaces`
2. `LearningRecordServiceTest`：
   - `should_unionAllThreeTypes_when_noRecordTypeSpecified`（BLOCK-C）
   - `should_correctTotalCount_when_usingUnionAll`（BLOCK-C：total 准确）
   - `should_returnDetail_when_recordExists`
   - `should_returnMasteryChanges_when_sourceIdExists`（IMP-17）
   - `should_useSubtract_when_calculatingDelta`（IMP-16）

**涉及文件**：
- `src/test/java/com/jiang/model/enums/RecordTypeTest.java`（新增）
- `src/test/java/com/jiang/service/LearningRecordServiceTest.java`（新增）

---

### T-07：集成测试

**涉及文件**：
- `src/test/java/com/jiang/controller/LearningRecordControllerTest.java`（新增）

---

### T-08：全量编译与测试验证 ✅

**验收标准**：
- [x] `mvn clean compile` 通过
- [x] `mvn test` 全部通过（447 tests, 0 failures, 0 errors, 5 skipped）
- [x] `mvn package -DskipTests` 成功

---

## 预估工时

| 任务 | 预估工时 |
|------|----------|
| T-01 | 0.5h |
| T-02 | 1h |
| T-03 | 1.5h |
| T-04 | 4h |
| T-05 | 1.5h |
| T-06 | 2.5h |
| T-07 | 1.5h |
| T-08 | 1h |
| **总计** | **13.5h** |

---

**版本**：v3  
**创建日期**：2026-11-03  
**最后更新**：2026-11-04
