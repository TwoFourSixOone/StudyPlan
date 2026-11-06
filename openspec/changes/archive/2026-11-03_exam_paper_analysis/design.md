# Design：试卷上传与异步解析系统（v3 修复版）

> **Change 名称**：`2026-11-03_exam_paper_analysis`  
> **版本**：v3（修复 BLOCK-A + IMP-25/26）

---

## 0. 审查修复追溯

| 编号 | 问题 | 修复方案 |
|------|------|----------|
| BLOCK-4 | uk_student_upload (student_id, create_time, tenant_id) 同一秒上传冲突 | 改为方案 C：`uk_student_exam (student_id, subject_id, exam_date, tenant_id)`——同一学生同一科目同一天只能传一份试卷 |
| BLOCK-5 | @Async + Redis 双异步导致状态竞争 | 去掉 `@Async`，Worker 同步调用 `processExamAnalysis` |
| BLOCK-6 | is_correct 从未设置，掌握度更新和错题入队是死代码 | **产品确认**：试卷上传后仅做 OCR 切题 + 知识点归因，**不自动批改**。学生需手动录入每道题的得分/对错，录入后触发掌握度更新和错题入队。新增 `POST /api/student/exam/result/{resultId}/grade` 接口 |
| BLOCK-7 | ADD COLUMN IF NOT EXISTS MySQL 5.7 不支持 | 去掉 IF NOT EXISTS，Flyway 保证只执行一次 |
| IMP-6 | fileStorageService.upload 在事务内，失败产生孤儿文件 | 文件上传移到事务外：先上传文件 → 再开事务写 DB |
| IMP-7 | maxRetry=2 语义歧义 | 统一为"最多重试 2 次"（总共 3 次尝试），变量名改为 `maxRetries=2`，循环 `retryCount <= maxRetries` |
| IMP-8 | 降级时 knowledgePointId=null 语义不明确 | DDL 注释明确 `attribution_status=pending_manual_review` 时 `knowledge_point_id` 允许 NULL |
| IMP-9 | generateSignature() 空方法 | 使用腾讯云 OCR 官方 Java SDK（`tencentcloud-sdk-java-ocr`），不手写签名 |
| IMP-10 | Worker 单线程消费瓶颈 | `@Scheduled` + 线程池（corePoolSize=3），从 Redis 批量拉取任务 |
| IMP-11 | exam_paper.status 与 exam_analysis_task.status 两套状态机 | 明确同步关系：task 状态变更时联动更新 paper 状态（paper.status = 所有关联 task 的聚合状态） |
| BLOCK-A | submitExamGrade 的 updateMasteryFromExam/recordWrongAnswers 签名冗余 request 参数 | 方法签名改为 `updateMasteryFromExam(examPaperId, questions)` 和 `recordWrongAnswers(examPaperId, questions)`，删除 request 参数；补充实现伪代码；knowledgePointId=null 时跳过掌握度更新 |
| IMP-25 | 掌握度更新先于错题入队，与 assessment Change 时序不一致 | 调整顺序：先错题入队（步骤 4），再掌握度更新（步骤 5），确保 syncPauseStatus 依赖的错题已入队 |
| IMP-26 | OCR 降级占位内容仍送入归因 Agent | processExamAnalysis 中判断 ocrResults 是否全部为降级占位（attributionStatus=pending_manual_review），若是则跳过归因 Agent，直接写库 |
| NOTE-1 | exam_question.knowledge_point_id 类型变更后需确认与 knowledge_point 主键匹配 | 已确认：knowledge_point.knowledge_point_id 为 VARCHAR(64)，与 exam_question 一致；T-01 验收标准已补充 |

---

## 1. 产品语义确认：试卷上传 ≠ 自动批改

> **关键决策**：试卷上传后，系统仅做 OCR 切题 + 知识点归因，**不自动识别学生答案、不自动批改**。

**理由**：
1. 试卷图片的手写体 OCR 识别精度不可靠（产品规则 §3 业务逻辑边界明确"不做试卷手写体高精度 OCR 的兜底人工录入界面"）
2. 归因 Agent 只做"题目 → 知识点"映射，不做"题目 → 答案 → 对错"判定
3. 考试场景下，学生/家长手动录入每道题的得分和对错状态更可靠

**流程调整**：
```
上传试卷 → OCR 切题 → 归因 Agent → 存储归因结果
                                        ↓
                              学生手动录入每题得分/对错
                                        ↓
                              触发掌握度更新 + 错题入队
```

**新增接口**：
- `POST /api/student/exam/result/{resultId}/grade` — 学生录入考试结果（逐题对错/得分）
- 录入完成后触发 `updateMasteryFromExam()` + `recordWrongAnswers()`

---

## 2. 架构设计

### 2.1 系统架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        学生端小程序                               │
│  上传试卷 → 查看解析进度 → 录入考试结果 → 查看分析结果            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  ExamPaperController                              │
│  POST /api/student/exam/upload         上传试卷                  │
│  POST /api/student/exam/result/{id}/grade  录入考试结果          │
│  GET  /api/student/exam/task/{taskId}   查询解析进度              │
│  GET  /api/student/exam/result/{id}     查看分析结果详情          │
│  GET  /api/student/exam/results         查看分析结果历史列表      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  ExamPaperService                                 │
│  uploadExamPaper()       上传文件（事务外）→ 创建记录（事务内）   │
│  submitExamGrade()       学生录入成绩 → 错题入队 → 掌握度更新    │
│  processExamAnalysis()   同步处理（OCR + 归因），由 Worker 调用   │
│  getTaskProgress()       查询任务进度                             │
│  getResultDetail()       获取分析结果详情                         │
│  getResultHistory()      获取分析结果历史                         │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ FileStorageService│  │  OcrService      │  │ AttributionAgent │
│ 文件存储（策略模式）│  │  腾讯云 OCR SDK  │  │ 归因 Agent       │
└──────────────────┘  └──────────────────┘  └──────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              Redis Queue + Worker 线程池（coreSize=3）            │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 状态机同步关系（修复 IMP-11）

```
exam_paper.status 与 exam_analysis_task.status 联动规则：

paper.status 取值：
  pending     ← 刚上传，task 尚未开始
  processing  ← 任一 task 进入 processing
  completed   ← 所有 task 均为 completed
  failed      ← 任一 task 为 failed 且无 processing 中的 task

task.status 取值：
  pending     → processing → completed
                           → failed
```

**联动实现**：`processExamAnalysis` 方法在 task 状态变更时，重新计算 paper 的聚合状态并更新。

---

## 3. 数据库设计

### 3.1 新增数据表

#### exam_paper（试卷主表）

```sql
CREATE TABLE exam_paper (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '试卷ID',
    student_id BIGINT NOT NULL COMMENT '学生ID',
    subject_id BIGINT NOT NULL COMMENT '科目ID',
    exam_date DATE NOT NULL COMMENT '考试日期',
    total_score DECIMAL(5,2) COMMENT '总分（学生录入后回填）',
    paper_type VARCHAR(20) NOT NULL DEFAULT 'midterm' COMMENT '试卷类型：midterm/final/other',
    image_urls JSON NOT NULL COMMENT '试卷图片URL列表（JSON数组）',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态：pending/processing/completed/failed',
    grade_status VARCHAR(20) NOT NULL DEFAULT 'ungraded' COMMENT '批改状态：ungraded/graded',
    remark VARCHAR(500) COMMENT '备注',
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    -- 修复 BLOCK-4：唯一索引改为 (student_id, subject_id, exam_date, tenant_id)
    -- 语义：同一学生同一科目同一天只能传一份试卷（防重复上传同一份试卷）
    UNIQUE KEY uk_student_exam (student_id, subject_id, exam_date, tenant_id),
    INDEX idx_student_status (student_id, status, tenant_id),
    INDEX idx_subject (subject_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷主表';
```

#### exam_question（试卷题目表）

```sql
CREATE TABLE exam_question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '题目ID',
    exam_paper_id BIGINT NOT NULL COMMENT '试卷ID',
    question_index INT NOT NULL COMMENT '题目序号',
    question_content TEXT NOT NULL COMMENT '题目内容（OCR识别结果）',
    question_image_url VARCHAR(500) COMMENT '题目图片URL（可选）',
    knowledge_point_id VARCHAR(64) COMMENT '归因的知识点ID（待人工归因时为NULL）',
    -- 修复 IMP-8：DDL 注释明确 pending_manual_review 时 knowledge_point_id 允许 NULL
    attribution_status VARCHAR(30) NOT NULL DEFAULT 'pending' COMMENT '归因状态：pending/manual_review/completed。manual_review 时 knowledge_point_id 为 NULL',
    student_answer TEXT COMMENT '学生答案（学生手动录入）',
    is_correct TINYINT(1) COMMENT '是否正确：0-错误，1-正确，NULL-未录入',
    score DECIMAL(5,2) COMMENT '得分（学生手动录入）',
    full_score DECIMAL(5,2) COMMENT '满分（学生手动录入）',
    question_type VARCHAR(20) COMMENT '题型：choice/numeric/short',
    difficulty VARCHAR(20) COMMENT '难度：basic/standard/challenge',
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (exam_paper_id) REFERENCES exam_paper(id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_point_id) REFERENCES knowledge_point(knowledge_point_id),
    INDEX idx_paper_index (exam_paper_id, question_index, tenant_id),
    INDEX idx_knowledge_point (knowledge_point_id, tenant_id),
    INDEX idx_attribution_status (attribution_status, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷题目表';
```

#### exam_analysis_task（解析任务表）

```sql
CREATE TABLE exam_analysis_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '任务ID',
    exam_paper_id BIGINT NOT NULL COMMENT '试卷ID',
    task_type VARCHAR(30) NOT NULL DEFAULT 'ocr_attribution' COMMENT '任务类型：ocr_attribution/ocr_only/attribution_only',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态：pending/processing/completed/failed',
    ocr_retry_count INT NOT NULL DEFAULT 0 COMMENT 'OCR重试次数',
    attribution_retry_count INT NOT NULL DEFAULT 0 COMMENT '归因重试次数',
    error_message TEXT COMMENT '错误信息',
    started_at DATETIME COMMENT '开始处理时间',
    completed_at DATETIME COMMENT '完成时间',
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default' COMMENT '租户ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (exam_paper_id) REFERENCES exam_paper(id) ON DELETE CASCADE,
    INDEX idx_paper_status (exam_paper_id, status, tenant_id),
    INDEX idx_status (status, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷解析任务表';
```

### 3.2 DDL 变更脚本（修复 BLOCK-7）

创建 `V6__exam_paper_analysis.sql`：

```sql
-- V6: 试卷上传与异步解析系统
-- 创建日期：2026-11-03
-- 说明：新增试卷主表、题目表、解析任务表
-- 注意：MySQL 5.7 不支持 IF NOT EXISTS 语法，Flyway 保证脚本只执行一次

-- 依赖：V1~V5 已 apply（knowledge_point、subject 等表已存在）
-- 被依赖：V7（plan_adjustment）、V8（learning_record_index）、V9（admin_list_index）

-- 1. 创建 exam_paper 表
CREATE TABLE exam_paper (
    -- 见 §3.1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷主表';

-- 2. 创建 exam_question 表
CREATE TABLE exam_question (
    -- 见 §3.1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷题目表';

-- 3. 创建 exam_analysis_task 表
CREATE TABLE exam_analysis_task (
    -- 见 §3.1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷解析任务表';
```

---

## 4. 分层设计

### 4.1 Controller 层

#### ExamPaperController

```java
@RestController
@RequestMapping("/api/student/exam")
@Tag(name = "试卷管理", description = "试卷上传、成绩录入与解析结果查询")
public class ExamPaperController {

    /**
     * 上传试卷（不含批改）
     * 修复 IMP-6：文件上传在事务外，仅 DB 写入在事务内
     */
    @PostMapping("/upload")
    @Operation(summary = "上传试卷")
    public Result<ExamUploadVO> uploadExamPaper(
            @RequestParam("subjectId") Long subjectId,
            @RequestParam("examDate") LocalDate examDate,
            @RequestParam(value = "paperType", defaultValue = "midterm") String paperType,
            @RequestParam("files") List<MultipartFile> files) {
        return Result.success(examPaperService.uploadExamPaper(subjectId, examDate, paperType, files));
    }

    /**
     * 修复 BLOCK-6：新增成绩录入接口
     * 学生手动录入每道题的对错/得分，触发掌握度更新和错题入队
     */
    @PostMapping("/result/{resultId}/grade")
    @Operation(summary = "录入考试成绩")
    public Result<Void> submitExamGrade(
            @PathVariable Long resultId,
            @RequestBody @Valid ExamGradeRequest request) {
        examPaperService.submitExamGrade(resultId, request);
        return Result.success();
    }

    @GetMapping("/task/{taskId}")
    @Operation(summary = "查询解析进度")
    public Result<ExamTaskProgressVO> getTaskProgress(@PathVariable Long taskId) {
        return Result.success(examPaperService.getTaskProgress(taskId));
    }

    @GetMapping("/result/{resultId}")
    @Operation(summary = "查看分析结果详情")
    public Result<ExamResultDetailVO> getResultDetail(@PathVariable Long resultId) {
        return Result.success(examPaperService.getResultDetail(resultId));
    }

    @GetMapping("/results")
    @Operation(summary = "查看分析结果历史列表")
    public Result<PageResult<ExamResultListVO>> getResultHistory(
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        return Result.success(examPaperService.getResultHistory(page, size));
    }
}
```

### 4.2 Service 层

#### ExamPaperServiceImpl（修复 BLOCK-5/6 + IMP-6/7）

```java
@Service
@Slf4j
public class ExamPaperServiceImpl implements ExamPaperService {

    // ... 依赖注入

    /**
     * 上传试卷
     * 修复 IMP-6：文件上传在事务外，仅 DB 写入在事务内
     */
    @Override
    public ExamUploadVO uploadExamPaper(Long subjectId, LocalDate examDate,
                                         String paperType, List<MultipartFile> files) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 1. 校验科目存在
        Subject subject = subjectMapper.selectById(subjectId);
        if (subject == null) {
            throw new BusinessException(ErrorCodes.SUBJECT_NOT_FOUND);
        }

        // 2. 校验文件大小与类型（限制单张 ≤ 10MB，仅 jpg/jpeg/png）
        validateFiles(files);

        // 3. 文件上传（事务外）
        List<String> imageUrls = files.stream()
                .map(file -> fileStorageService.upload(file, "exam-papers/" + studentId))
                .collect(Collectors.toList());

        // 4. 事务内写入 DB
        try {
            return transactionTemplate.execute(status -> {
                // 4a. 创建 exam_paper 记录
                ExamPaper examPaper = new ExamPaper();
                examPaper.setStudentId(studentId);
                examPaper.setSubjectId(subjectId);
                examPaper.setExamDate(examDate);
                examPaper.setPaperType(paperType);
                examPaper.setImageUrls(JSON.toJSONString(imageUrls));
                examPaper.setStatus("pending");
                examPaper.setGradeStatus("ungraded");
                examPaper.setTenantId(tenantId);
                examPaperMapper.insert(examPaper);

                // 4b. 创建 exam_analysis_task 记录
                ExamAnalysisTask task = new ExamAnalysisTask();
                task.setExamPaperId(examPaper.getId());
                task.setTaskType("ocr_attribution");
                task.setStatus("pending");
                task.setTenantId(tenantId);
                examAnalysisTaskMapper.insert(task);

                // 4c. 推入 Redis 队列
                redisTemplate.opsForList().leftPush("exam_analysis_queue", task.getId());

                // 4d. 返回
                ExamUploadVO vo = new ExamUploadVO();
                vo.setTaskId(task.getId());
                vo.setExamPaperId(examPaper.getId());
                vo.setStatus("pending");
                return vo;
            });
        } catch (DuplicateKeyException e) {
            // 修复 BLOCK-4：同一学生同一科目同一天重复上传
            log.warn("试卷重复上传: studentId={}, subjectId={}, examDate={}", studentId, subjectId, examDate);
            throw new BusinessException(ErrorCodes.EXAM_PAPER_DUPLICATE);
        }
    }

    /**
     * 修复 BLOCK-6：学生录入考试成绩
     * 录入完成后触发掌握度更新和错题入队
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitExamGrade(Long resultId, ExamGradeRequest request) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 1. 查询试卷
        ExamPaper examPaper = examPaperMapper.selectByIdWithTenant(resultId, studentId, tenantId);
        if (examPaper == null) {
            throw new BusinessException(ErrorCodes.EXAM_PAPER_NOT_FOUND);
        }
        if ("graded".equals(examPaper.getGradeStatus())) {
            throw new BusinessException(ErrorCodes.EXAM_PAPER_ALREADY_GRADED);
        }
        if (!"completed".equals(examPaper.getStatus())) {
            throw new BusinessException(ErrorCodes.EXAM_PAPER_NOT_READY_FOR_GRADE);
        }

        // 2. 逐题更新成绩
        List<ExamQuestion> questions = examQuestionMapper.selectByExamPaperId(resultId, tenantId);
        for (ExamGradeItemVO item : request.getItems()) {
            ExamQuestion question = questions.stream()
                    .filter(q -> q.getQuestionIndex().equals(item.getQuestionIndex()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCodes.EXAM_QUESTION_NOT_FOUND));

            question.setStudentAnswer(item.getStudentAnswer());
            question.setIsCorrect(item.getIsCorrect());
            question.setScore(item.getScore());
            question.setFullScore(item.getFullScore());
            examQuestionMapper.updateById(question);
        }

        // 3. 更新试卷状态
        examPaper.setGradeStatus("graded");
        examPaper.setTotalScore(request.getTotalScore());
        examPaperMapper.updateById(examPaper);

        // 4. 错题入队（修复 IMP-25：先于掌握度更新，与 assessment Change 时序一致）
        recordWrongAnswers(resultId, questions);

        // 5. 更新掌握度（W_source=1.0）
        updateMasteryFromExam(resultId, questions);
    }

    /**
     * 修复 BLOCK-5：去掉 @Async，由 Worker 同步调用
     * Worker 本身就是异步消费者（@Scheduled 定时从 Redis 拉取）
     */
    @Override
    public void processExamAnalysis(Long taskId) {
        // 1. 查询任务
        ExamAnalysisTask task = examAnalysisTaskMapper.selectById(taskId);
        if (task == null || !"pending".equals(task.getStatus())) {
            return;
        }

        // 2. 更新 task 状态为 processing，联动 paper 状态
        updateTaskAndPaperStatus(task, "processing");

        try {
            // 3. OCR 切题（修复 IMP-7：最多重试 2 次 = 总共 3 次尝试）
            List<ExamQuestionOcrDTO> ocrResults = ocrWithRetry(task, 2);

            // 修复 IMP-26：判断 OCR 是否全部降级
            boolean allOcrDegraded = ocrResults.stream()
                    .allMatch(r -> "pending_manual_review".equals(r.getAttributionStatus()));

            List<ExamQuestionAttributionDTO> attributionResults;
            if (allOcrDegraded) {
                // OCR 全部失败，跳过归因 Agent，直接标记待人工处理
                log.warn("OCR 全部降级，跳过归因 Agent: taskId={}", task.getId());
                attributionResults = ocrResults.stream()
                        .map(ocr -> {
                            ExamQuestionAttributionDTO dto = new ExamQuestionAttributionDTO();
                            dto.setQuestionContent(ocr.getQuestionContent());
                            dto.setAttributionStatus("pending_manual_review");
                            dto.setKnowledgePointId(null);
                            return dto;
                        })
                        .collect(Collectors.toList());
            } else {
                // 4. 归因 Agent（最多重试 2 次）
                attributionResults = attributionWithRetry(ocrResults, 2);
            }

            // 5. 事务内写入数据库
            transactionTemplate.execute(status -> {
                saveExamQuestions(task.getExamPaperId(), attributionResults);
                updateTaskAndPaperStatus(task, "completed");
                return null;
            });

            // 6. 不再自动触发掌握度更新和错题入队
            // 等待学生通过 submitExamGrade 手动录入成绩后触发

        } catch (Exception e) {
            log.error("试卷解析失败: taskId={}", taskId, e);
            updateTaskAndPaperStatus(task, "failed");
            // 记录降级日志
            logDegradation(task, e);
        }
    }

    /**
     * 修复 IMP-7：重试语义统一为"最多重试 N 次"（总共 N+1 次尝试）
     */
    private List<ExamQuestionOcrDTO> ocrWithRetry(ExamAnalysisTask task, int maxRetries) {
        int retryCount = 0;
        Exception lastException = null;

        ExamPaper examPaper = examPaperMapper.selectById(task.getExamPaperId());
        List<String> imageUrls = JSON.parseArray(examPaper.getImageUrls(), String.class);

        while (retryCount <= maxRetries) {
            try {
                List<ExamQuestionOcrDTO> results = new ArrayList<>();
                for (String imageUrl : imageUrls) {
                    results.addAll(ocrService.recognize(imageUrl));
                }
                return results;
            } catch (OcrException e) {
                lastException = e;
                retryCount++;
                task.setOcrRetryCount(retryCount);
                examAnalysisTaskMapper.updateById(task);
                log.warn("OCR 切题失败，重试 {}/{}: taskId={}", retryCount, maxRetries, task.getId());
            }
        }

        // 重试耗尽：降级处理——标记所有题目为待人工归因
        log.error("OCR 切题重试耗尽，降级为待人工归因: taskId={}", task.getId(), lastException);
        return imageUrls.stream()
                .map(url -> {
                    ExamQuestionOcrDTO dto = new ExamQuestionOcrDTO();
                    dto.setQuestionContent("[OCR 识别失败，待人工处理]");
                    dto.setAttributionStatus("pending_manual_review");
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /**
     * 修复 IMP-11：task 状态变更时联动更新 paper 状态
     */
    private void updateTaskAndPaperStatus(ExamAnalysisTask task, String newTaskStatus) {
        task.setStatus(newTaskStatus);
        if ("processing".equals(newTaskStatus)) {
            task.setStartedAt(LocalDateTime.now());
        } else if ("completed".equals(newTaskStatus) || "failed".equals(newTaskStatus)) {
            task.setCompletedAt(LocalDateTime.now());
        }
        examAnalysisTaskMapper.updateById(task);

        // 联动更新 paper 状态
        ExamPaper paper = examPaperMapper.selectById(task.getExamPaperId());
        if (paper != null) {
            paper.setStatus(aggregatePaperStatus(paper.getId()));
            examPaperMapper.updateById(paper);
        }
    }

    /**
     * 修复 BLOCK-A：删除 request 参数，仅使用 questions
     * questions 中的 is_correct 已在 submitExamGrade 步骤 2 中原地更新
     */
    private void updateMasteryFromExam(Long examPaperId, List<ExamQuestion> questions) {
        for (ExamQuestion q : questions) {
            // 修复 BLOCK-A：attribution_status=pending_manual_review 时 knowledgePointId 为 null
            // 无知识点无法更新掌握度，跳过
            if (q.getKnowledgePointId() == null || q.getIsCorrect() == null) {
                continue;
            }
            // 调用 MasteryService.updateMasteryFromResult(...)
            // 参数：knowledgePointId, studentId, isCorrect, difficulty(→D_norm), W_source=1.0, sourceId=examPaperId, changeType=EXAM
            masteryService.updateMasteryFromResult(
                    q.getKnowledgePointId(),
                    StudentContext.getStudentId(),
                    q.getIsCorrect() == 1,
                    q.getDifficulty(),
                    1.0,
                    examPaperId,
                    "exam"
            );
        }
    }

    /**
     * 修复 BLOCK-A：删除 request 参数，仅使用 questions
     */
    private void recordWrongAnswers(Long examPaperId, List<ExamQuestion> questions) {
        for (ExamQuestion q : questions) {
            if (q.getIsCorrect() != null && q.getIsCorrect() == 0) {
                // 调用 ErrorQuestionService.recordWrongAnswer(...)
                // 参数：studentId, questionId(来自题库的关联), sourceType=EXAM, sourceId=examPaperId
                errorQuestionService.recordWrongAnswerFromExam(
                        StudentContext.getStudentId(),
                        q.getKnowledgePointId(),
                        examPaperId,
                        StudentContext.getTenantId()
                );
            }
        }
    }

    private String aggregatePaperStatus(Long examPaperId) {
        List<ExamAnalysisTask> tasks = examAnalysisTaskMapper.selectByExamPaperId(examPaperId);
        boolean hasFailed = tasks.stream().anyMatch(t -> "failed".equals(t.getStatus()));
        boolean hasProcessing = tasks.stream().anyMatch(t -> "processing".equals(t.getStatus()));
        boolean allCompleted = tasks.stream().allMatch(t -> "completed".equals(t.getStatus()));

        if (allCompleted) return "completed";
        if (hasProcessing) return "processing";
        if (hasFailed) return "failed";
        return "pending";
    }
}
```

### 4.3 Redis Worker（修复 IMP-10）

```java
@Component
@Slf4j
public class ExamAnalysisWorker {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private ExamPaperService examPaperService;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    /**
     * 修复 IMP-10：使用线程池并发消费
     * 每秒检查队列，最多同时处理 3 个任务
     */
    @Scheduled(fixedDelay = 1000)
    public void processTasks() {
        // 批量拉取任务（最多 3 个）
        for (int i = 0; i < 3; i++) {
            Object taskIdObj = redisTemplate.opsForList().rightPop("exam_analysis_queue");
            if (taskIdObj == null) break;

            Long taskId = Long.parseLong(taskIdObj.toString());
            executor.submit(() -> {
                try {
                    log.info("开始处理试卷解析任务: taskId={}", taskId);
                    examPaperService.processExamAnalysis(taskId);
                } catch (Exception e) {
                    log.error("试卷解析任务失败: taskId={}", taskId, e);
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
```

### 4.4 OCR 服务（修复 IMP-9）

```java
@Service
public class TencentOcrServiceImpl implements OcrService {

    // 修复 IMP-9：使用腾讯云官方 SDK，不手写签名
    private final OcrClient ocrClient;

    @PostConstruct
    public void init() {
        Cred cred = new Cred(
                System.getenv("TENCENT_OCR_SECRET_ID"),
                System.getenv("TENCENT_OCR_SECRET_KEY"));
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setHttpProfile(new HttpProfile());
        ocrClient = new OcrClient(cred, "ap-shanghai");
    }

    @Override
    public List<ExamQuestionOcrDTO> recognize(String imageUrl) {
        GeneralHandwritingOCRRequest req = new GeneralHandwritingOCRRequest();
        req.setImageUrl(imageUrl);

        GeneralHandwritingOCRResponse resp = ocrClient.GeneralHandwritingOCR(req);
        return parseResponse(resp);
    }

    private List<ExamQuestionOcrDTO> parseResponse(GeneralHandwritingOCRResponse resp) {
        // 按题目编号（1. 2. 3. 等）切etHandwritingOCRResponse resp) {
        // 按题目编号（1. 2. 3. 等）切分
    }
}
```

---

## 5. 文件存储服务设计（策略模式）

### 5.1 FileStorageService 接口

```java
public interface FileStorageService {
    String upload(MultipartFile file, String path);
    void delete(String url);
}
```

### 5.2 application.yml 配置

```yaml
file:
  storage:
    type: local  # local / cos
    local:
      base-path: ${FILE_STORAGE_LOCAL_BASE_PATH:./uploads}
      base-url: ${FILE_STORAGE_LOCAL_BASE_URL:http://localhost:8080/uploads}
    cos:
      secret-id: ${COS_SECRET_ID}
      secret-key: ${COS_SECRET_KEY}
      region: ${COS_REGION:ap-shanghai}
      bucket: ${COS_BUCKET}
```

### 5.3 策略选择

```java
@Configuration
public class FileStorageConfig {
    @Bean
    @ConditionalOnProperty(name = "file.storage.type", havingValue = "local", matchIfMissing = true)
    public FileStorageService localStorageService() {
        return new LocalFileStorageServiceImpl();
    }

    @Bean
    @ConditionalOnProperty(name = "file.storage.type", havingValue = "cos")
    public FileStorageService cosStorageService() {
        return new CosFileStorageServiceImpl();
    }
}
```

---

## 6. DTO/VO 层

### 6.1 ExamGradeRequest（修复 BLOCK-6 新增）

```java
@Data
public class ExamGradeRequest {
    @NotNull
    private BigDecimal totalScore;
    @NotEmpty
    private List<ExamGradeItemVO> items;
}
```

### 6.2 ExamGradeItemVO

```java
@Data
public class ExamGradeItemVO {
    @NotNull
    private Integer questionIndex;
    private String studentAnswer;
    @NotNull
    private Integer isCorrect;  // 0-错误，1-正确
    private BigDecimal score;
    private BigDecimal fullScore;
}
```

---

## 7. 错误码（修复 BLOCK-2 全局分配）

```java
// 试卷相关（1130-1139）
EXAM_PAPER_NOT_FOUND(1130, "试卷不存在"),
EXAM_PAPER_DUPLICATE(1131, "同一科目同一天已上传过试卷"),
EXAM_PAPER_ALREADY_GRADED(1132, "试卷已批改，请勿重复提交"),
EXAM_PAPER_NOT_READY_FOR_GRADE(1133, "试卷解析尚未完成，无法录入成绩"),
EXAM_QUESTION_NOT_FOUND(1134, "题目序号不存在"),
EXAM_TASK_NOT_FOUND(1135, "解析任务不存在"),
EXAM_RESULT_NOT_FOUND(1136, "分析结果不存在"),
OCR_FAILED(1137, "OCR 识别失败，请重试"),
FILE_UPLOAD_FAILED(1138, "文件上传失败"),
FILE_TYPE_INVALID(1139, "不支持的文件类型");
```

---

## 8. 隐性约定对齐

| 约定 | 实现方式 |
|------|----------|
| #1 统一响应 | Controller 返回 `Result<T>` |
| #2 异常处理 | Service 抛 `BusinessException`，Controller 不 try-catch |
| #3 分页参数 | `page` 从 1 开始，`size` 默认 10 |
| #4 时间格式 | 使用 `LocalDateTime`/`LocalDate` |
| #7 tenant_id 隔离 | 所有查询携带 tenant_id |
| #9 rollbackFor | `@Transactional(rollbackFor = Exception.class)` |
| #10 事务边界 | 文件上传/OCR/Agent 调用在事务外，仅 DB 写入在事务内 |
| #11 并发保护 | `uk_student_exam` 唯一索引 + `DuplicateKeyException` 捕获 |
| #12 超时降级 | OCR/Agent 超时 30s，触发降级 |
| #13 UNIQUE 索引 | `uk_student_exam (student_id, subject_id, exam_date, tenant_id)` |
| #14 Agent 异常分层 | 技术异常与业务异常分层处理 |
| N1 管理端上下文 | Demo 阶段复用 `StudentContext.getTenantId()`（与 `AdminGoalController` 保持一致） |

---

## 9. 安全设计

### 9.1 文件上传安全

- 限制单张图片大小：≤ 10MB
- 限制文件类型：仅允许 jpg/jpeg/png
- 文件内容校验：通过 `file.getContentType()` 校验 MIME 类型
- 路径穿越防护：文件名使用 UUID 重命名，禁止原始文件名直接作为路径

### 9.2 敏感信息管理

- 腾讯云 OCR 密钥通过环境变量 `TENCENT_OCR_SECRET_ID` / `TENCENT_OCR_SECRET_KEY` 注入
- COS 密钥通过环境变量注入
- 严禁硬编码在代码或配置文件中

---

**版本**：v2（修复 BLOCK-4/5/6/7 + IMP-6~11）  
**创建日期**：2026-11-03  
**最后更新**：2026-11-03
