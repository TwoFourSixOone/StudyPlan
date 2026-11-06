# Tasks：试卷上传与异步解析系统（v3 修复版）

> **Change 名称**：`2026-11-03_exam_paper_analysis`  
> **版本**：v3（修复 BLOCK-A + IMP-25/26）

---

## DDL 版本声明

- 本 Change 使用 **V6__exam_paper_analysis.sql**
- V6 仅依赖 V1~V5 既有表（knowledge_point、subject 等），不依赖 V7+
- 后续 Change 依赖关系：V7（plan_adjustment）→ V8（learning_record_index）→ V9（admin_list_index）
- **合并顺序**：V6 必须先于 V7/V8/V9 apply

---

## 任务清单

### T-01：数据库 DDL 脚本与实体类创建 ✅

**任务描述**：
1. 创建 `V6__exam_paper_analysis.sql` DDL 脚本（MySQL 5.7 兼容，不用 IF NOT EXISTS）
2. 创建实体类：`ExamPaper`、`ExamQuestion`、`ExamAnalysisTask`
3. 创建 Mapper 接口：`ExamPaperMapper`、`ExamQuestionMapper`、`ExamAnalysisTaskMapper`
4. 唯一索引：`uk_student_exam (student_id, subject_id, exam_date, tenant_id)`（BLOCK-4 修复）
5. `exam_paper` 新增 `grade_status` 字段（ungraded/graded）
6. `exam_question.knowledge_point_id` 允许 NULL（IMP-8 修复：DDL 注释明确）
7. `exam_question.knowledge_point_id` 类型为 `VARCHAR(64)`（NOTE-1：与 `knowledge_point.knowledge_point_id` 类型一致，外键可正常建立）

**涉及文件**：
- `src/main/resources/db/migration/V6__exam_paper_analysis.sql`（新增）
- `src/main/java/com/jiang/model/entity/ExamPaper.java`（新增）
- `src/main/java/com/jiang/model/entity/ExamQuestion.java`（新增）
- `src/main/java/com/jiang/model/entity/ExamAnalysisTask.java`（新增）
- `src/main/java/com/jiang/mapper/ExamPaperMapper.java`（新增）
- `src/main/java/com/jiang/mapper/ExamQuestionMapper.java`（新增）
- `src/main/java/com/jiang/mapper/ExamAnalysisTaskMapper.java`（新增）

---

### T-02：DTO/VO 类与错误码定义 ✅

**任务描述**：
1. 创建 DTO：`ExamQuestionOcrDTO`、`ExamQuestionAttributionDTO`、`ExamGradeRequest`、`ExamGradeItemVO`
2. 创建 VO：`ExamUploadVO`、`ExamTaskProgressVO`、`ExamResultDetailVO`、`ExamQuestionDetailVO`、`ExamResultListVO`
3. 错误码（**1130-1139 编号段**，避免与 assessment 1120-1129 冲突）：
   - `EXAM_PAPER_NOT_FOUND(1130)`
   - `EXAM_PAPER_DUPLICATE(1131)`
   - `EXAM_PAPER_ALREADY_GRADED(1132)`
   - `EXAM_PAPER_NOT_READY_FOR_GRADE(1133)`
   - `EXAM_QUESTION_NOT_FOUND(1134)`
   - `EXAM_TASK_NOT_FOUND(1135)`
   - `EXAM_RESULT_NOT_FOUND(1136)`
   - `OCR_FAILED(1137)`
   - `FILE_UPLOAD_FAILED(1138)`
   - `FILE_TYPE_INVALID(1139)`

**涉及文件**：
- `src/main/java/com/jiang/dto/` 下多个 DTO（新增）
- `src/main/java/com/jiang/vo/` 下多个 VO（新增）
- `src/main/java/com/jiang/common/exception/ErrorCodes.java`（修改）

---

### T-03：文件存储服务实现（策略模式） ✅

**任务描述**：
1. 创建 `FileStorageService` 接口
2. 实现 `LocalFileStorageServiceImpl`（本地存储）
3. 实现 `CosFileStorageServiceImpl`（腾讯云 COS，预留）
4. `FileStorageConfig` 配置类（`@ConditionalOnProperty` 策略选择）
5. 文件上传安全校验（大小 ≤ 10MB、类型 jpg/jpeg/png）

**涉及文件**：
- `src/main/java/com/jiang/service/FileStorageService.java`（新增）
- `src/main/java/com/jiang/service/impl/LocalFileStorageServiceImpl.java`（新增）
- `src/main/java/com/jiang/service/impl/CosFileStorageServiceImpl.java`（新增）
- `src/main/java/com/jiang/config/FileStorageConfig.java`（新增）

---

### T-04：OCR 服务实现（使用腾讯云官方 SDK） ✅

**任务描述**：
1. 创建 `OcrService` 接口
2. 实现 `TencentOcrServiceImpl`（使用 `tencentcloud-sdk-java-ocr` SDK，不手写签名）
3. 创建 `OcrException` 异常类
4. pom.xml 添加腾讯云 OCR SDK 依赖
5. 密钥通过环境变量 `TENCENT_OCR_SECRET_ID` / `TENCENT_OCR_SECRET_KEY` 注入

**涉及文件**：
- `src/main/java/com/jiang/service/OcrService.java`（新增）
- `src/main/java/com/jiang/service/impl/TencentOcrServiceImpl.java`（新增）
- `src/main/java/com/jiang/common/exception/OcrException.java`（新增）
- `pom.xml`（修改，添加 SDK 依赖）

---

### T-05：归因 Agent 实现 ✅

**任务描述**：
1. 创建 `AttributionAgent` 接口
2. 实现 `AttributionAgentImpl`
3. 创建 `AttributionException` 异常类

**涉及文件**：
- `src/main/java/com/jiang/agent/AttributionAgent.java`（新增）
- `src/main/java/com/jiang/agent/impl/AttributionAgentImpl.java`（新增）
- `src/main/java/com/jiang/common/exception/AttributionException.java`（新增）

---

### T-06：Redis 异步队列 + Worker 线程池 ✅

**任务描述**：
1. 创建 `RedisConfig` 配置类
2. 创建 `ExamAnalysisWorker`：`@Scheduled(fixedDelay=1000)` + `ExecutorService(corePoolSize=3)`
3. Worker 从队列批量拉取任务，提交到线程池执行
4. **不使用 @Async**（BLOCK-5 修复）：Worker 同步调用 `processExamAnalysis`

**涉及文件**：
- `src/main/java/com/jiang/config/RedisConfig.java`（新增）
- `src/main/java/com/jiang/worker/ExamAnalysisWorker.java`（新增）

---

### T-07：ExamPaperService 核心实现 ✅

**任务描述**：
1. 创建 `ExamPaperService` 接口
2. 实现 `ExamPaperServiceImpl`，包含：
   - `uploadExamPaper()`：先上传文件（事务外）→ 再开事务写 DB（IMP-6 修复）
   - `processExamAnalysis()`：同步处理（OCR + 归因），由 Worker 调用（BLOCK-5 修复）
   - `submitExamGrade()`：学生录入成绩 → 错题入队 → 掌握度更新（BLOCK-6 + IMP-25 修复：错题先于掌握度）
   - `getTaskProgress()` / `getResultDetail()` / `getResultHistory()`
3. 重试语义统一为"最多重试 2 次 = 总共 3 次尝试"（IMP-7 修复）
4. 状态机联动：task 状态变更时聚合更新 paper 状态（IMP-11 修复）
5. OCR 全部降级时跳过归因 Agent，直接标记待人工处理（IMP-26 修复）
6. DuplicateKeyException 捕获 → 返回 EXAM_PAPER_DUPLICATE（BLOCK-4 修复）

**涉及文件**：
- `src/main/java/com/jiang/service/ExamPaperService.java`（新增）
- `src/main/java/com/jiang/service/impl/ExamPaperServiceImpl.java`（新增）

---

### T-08：ExamPaperController 实现 ✅

**任务描述**：
1. 创建 `ExamPaperController`，包含：
   - `POST /api/student/exam/upload`：上传试卷
   - `POST /api/student/exam/result/{resultId}/grade`：录入成绩（BLOCK-6 新增）
   - `GET /api/student/exam/task/{taskId}`：查询解析进度
   - `GET /api/student/exam/result/{resultId}`：查看分析结果详情
   - `GET /api/student/exam/results`：查看分析结果历史列表

**涉及文件**：
- `src/main/java/com/jiang/controller/ExamPaperController.java`（新增）

---

### T-09：掌握度更新与错题入队集成 ✅

**任务描述**：
1. 在 `MasteryService` 中添加 `updateMasteryFromExam(examPaperId, questions)` 方法（BLOCK-A 修复：删除 request 参数）
2. 在 `ErrorQuestionService` 中添加 `recordWrongAnswerFromExam(examPaperId, questions)` 方法（BLOCK-A 修复：删除 request 参数）
3. `knowledgePointId=null` 的题目跳过掌握度更新（BLOCK-A：attribution_status=pending_manual_review 时无知识点）
3. 新增 `ErrorQuestionSourceType.EXAM` 枚举值

**涉及文件**：
- `src/main/java/com/jiang/service/MasteryService.java`（修改）
- `src/main/java/com/jiang/service/impl/MasteryServiceImpl.java`（修改）
- `src/main/java/com/jiang/service/ErrorQuestionService.java`（修改）
- `src/main/java/com/jiang/service/impl/ErrorQuestionServiceImpl.java`（修改）

**实现说明**：
- 已复用现有 `MasteryService.updateMasteryFromResult()` 和 `ErrorQuestionService.recordWrongAnswer()` 方法
- `ErrorQuestionSourceType.EXAM` 枚举已存在
- 在 `ExamPaperServiceImpl` 中实现 `updateMasteryFromExam()` 和 `recordWrongAnswers()` 私有方法

---

### T-10：单元测试

**任务描述**：
1. `ExamPaperServiceImplTest`：
   - `should_createTaskAndUploadFile_when_uploadExamPaper`
   - `should_returnDuplicateError_when_sameSubjectSameDay`（BLOCK-4）
   - `should_processOcrAndAttribution_when_taskPending`
   - `should_notUpdateMastery_when_processCompleted`（BLOCK-6：解析完成不触发掌握度更新）
   - `should_updateMasteryAndRecordError_when_submitExamGrade`（BLOCK-6：录入成绩后触发）
   - `should_throwException_when_submitGradeBeforeProcessCompleted`
   - `should_retryTotal3Times_when_ocrFailed`（IMP-7）
   - `should_markManualReview_when_ocrRetriesExhausted`
   - `should_skipAttribution_when_allOcrDegraded`（IMP-26）
   - `should_recordWrongBeforeMasteryUpdate_when_submitExamGrade`（IMP-25）
   - `should_skipMasteryUpdate_when_knowledgePointIdNull`（BLOCK-A）
   - `should_syncPaperStatus_when_taskStatusChanged`（IMP-11）
2. `FileStorageServiceTest`、`OcrServiceTest`、`AttributionAgentTest`

**涉及文件**：
- `src/test/java/com/jiang/service/ExamPaperServiceImplTest.java`（新增）
- `src/test/java/com/jiang/service/FileStorageServiceTest.java`（新增）
- `src/test/java/com/jiang/service/OcrServiceTest.java`（新增）
- `src/test/java/com/jiang/service/AttributionAgentTest.java`（新增）

---

### T-11：集成测试

**任务描述**：
1. `ExamPaperControllerTest`：覆盖所有接口（含新增的 grade 接口）
2. 事务边界测试：验证文件上传在事务外
3. 并发测试：验证唯一索引防重（同一科目同一天）

**涉及文件**：
- `src/test/java/com/jiang/controller/ExamPaperControllerTest.java`（新增）

---

### T-12：全量编译与测试验证 ✅

**涉及文件**：所有变更文件

**验收标准**：
- [x] `mvn clean compile` 通过
- [x] `mvn test` 全部通过（434 tests, 0 failures, 0 errors）
- [x] `mvn package -DskipTests` 成功

---

## 任务依赖关系

```
T-01（数据库与实体类）
    ↓
T-02（DTO/VO 与错误码）
    ↓
T-03（文件存储服务）← 可并行
T-04（OCR 服务）← 可并行
T-05（归因 Agent）← 可并行
T-06（Redis Worker）← 可并行
    ↓
T-07（ExamPaperService 核心实现）
    ↓
T-08（ExamPaperController）
    ↓
T-09（掌握度与错题集成）
    ↓
T-10（单元测试）
    ↓
T-11（集成测试）
    ↓
T-12（全量编译与验证）
```

---

## 预估工时

| 任务 | 预估工时 |
|------|----------|
| T-01 | 2h |
| T-02 | 1h |
| T-03 | 2h |
| T-04 | 2h |
| T-05 | 2h |
| T-06 | 1.5h |
| T-07 | 5h |
| T-08 | 2h |
| T-09 | 2h |
| T-10 | 4h |
| T-11 | 3h |
| T-12 | 1h |
| **总计** | **27.5h** |

---

**版本**：v3  
**创建日期**：2026-11-03  
**最后更新**：2026-11-04
