# Design：错题管理与艾宾浩斯周期

> **Change 名称**：`2026-11-02_error_question_management`  
> **创建日期**：2026-11-02  
> **状态**：草案 v8（修复 BLOCK-I + IMP-AG~AI）

---

## 1. 技术方案概述

本次 Change 实现错题管理与艾宾浩斯周期调度，包括：
1. 错题队列数据模型（新增 `error_question` 表）
2. 错题核心 Service（入队/推进/重置/暂停/重新入队）
3. 掌握度联动（暂停/重新入队触发）
4. 每日作业集成错题查询（含截断策略）
5. 学生端错题列表/统计查询接口

核心技术方案：
- **艾宾浩斯周期**：5 个阶段（1/3/7/15/30 天），枚举 `EbbinghausStage` 管理
- **错题状态机**：`active` → `paused`（掌握度 ≥ 0.8）→ `active`（掌握度 < 0.8）→ `completed`（stage > 5）
- **事务管理**：掌握度更新与错题联动在同一事务内完成
- **并发保护**：唯一索引 `(student_id, question_id, tenant_id)` + `DuplicateKeyException` 捕获回退；advanceStage 使用 `WHERE current_stage = #{currentStage}`（数据库旧值）状态机校验

> **前置依赖**：本 Change V3 DDL 依赖前置 Change V2（`V2__homework_uk_index.sql`）已 apply。

---

## 1.1 技术栈说明

本次 Change 不引入新技术栈，复用现有：
- Spring Boot 3.2.x + MyBatis-Plus
- MySQL 5.7+
- JUnit 5 + Mockito

> **PageResult 复用**：分页查询复用项目既有 `com.jiang.common.result.PageResult<T>`（字段：records/total/page/size）。

---

## 2. 分层设计

### 2.1 Controller 层

新增 `ErrorQuestionController`，提供学生端错题查询接口：

> **BLOCK-2 复用**：所有接口的 `studentId`/`tenantId` 均从 `StudentContext`（JWT 上下文）提取。

```java
@RestController
@RequestMapping("/api/error-questions")
public class ErrorQuestionController {

    @Autowired
    private ErrorQuestionService errorQuestionService;

    /**
     * 查询错题列表（分页）
     * GET /api/error-questions?knowledgePointId=xxx&status=active&page=1&size=10
     * IMP-F: page < 1 置 1，size > 100 截断至 100
     */
    @GetMapping
    public Result<PageResult<ErrorQuestionVO>> getErrorQuestions(
            @RequestParam(required = false) String knowledgePointId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        // IMP-F: 参数校验
        if (page < 1) page = 1;
        if (size > 100) size = 100;
        if (size < 1) size = 1;
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        PageResult<ErrorQuestionVO> result = errorQuestionService.getErrorQuestions(
                studentId, tenantId, knowledgePointId, status, page, size);
        return Result.success(result);
    }

    /**
     * 查询错题统计
     * GET /api/error-questions/statistics
     */
    @GetMapping("/statistics")
    public Result<ErrorQuestionStatisticsVO> getStatistics() {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        ErrorQuestionStatisticsVO stats = errorQuestionService.getStatistics(studentId, tenantId);
        return Result.success(stats);
    }
}
```

### 2.2 Service 层

新增 `ErrorQuestionService` 接口与 `ErrorQuestionServiceImpl` 实现：

```java
public interface ErrorQuestionService {

    // ===== 核心写操作 =====

    /**
     * 记录错题（答错时调用）
     * - 首次答错：创建 error_question，stage=1，next_due_date=wrongDate+1天
     * - 已在队列中：重置 stage=1，更新 last_wrong_date/next_due_date
     * - 并发保护：捕获 DuplicateKeyException → 回退到 UPDATE 路径
     *
     * BLOCK-1 修复：明确由 gradeHomework 调用（见 §2.4.2）
     * BLOCK-3 修复：sourceType 使用 ErrorQuestionSourceType 枚举
     */
    void recordWrongAnswer(Long studentId, String tenantId, Long questionId,
                           String knowledgePointId,
                           ErrorQuestionSourceType sourceType, Long sourceId,
                           LocalDate wrongDate);

    /**
     * 查询到期错题（按知识点分组，每组 ≤ 3 道）
     * - 条件：status=active AND next_due_date <= targetDate
     * BLOCK-A 修复：每个知识点最多取 3 道（按 next_due_date ASC, error_question_id ASC 排序后截断）
     * IMP-1 修复：返回 DueErrorQuestionVO 而非实体
     * BLOCK-H 修复：按知识点分别查询，每个知识点 LIMIT 3，彻底消除全局截断导致知识点覆盖不均的问题
     * IMP-AB 修复：返回 LinkedHashMap，key 顺序由 selectDueKnowledgePointIds 的 ORDER BY knowledge_point_id ASC 决定（知识点 ID 字典序）
     * BLOCK-I 修复：N+1 循环不纳入事务（只读查询，避免长事务）；接受 READ COMMITTED 下的数据漂移；
     *   步骤 2 返回空 List 的知识点不放入 Map，保证 Map 的 value 非空
     */
    Map<String, List<DueErrorQuestionVO>> getDueErrorQuestions(
            Long studentId, String tenantId, LocalDate targetDate);

    /**
     * 推进错题周期（重做答对时调用）
     * - stage++ → 更新 next_due_date = correctDate + interval(newStage)
     * - stage > 5 → status=completed（移出队列）
     * - IMP-2 修复：基准日期改为 correctDate（本次答对日期）
     * - IMP-A 修复：UPDATE 加 WHERE current_stage = #{currentStage}（数据库旧值）并发保护
     */
    void advanceStage(Long studentId, String tenantId, Long questionId,
                      LocalDate correctDate);

    /**
     * 重置错题周期（重做答错时调用）
     * - stage=1 → next_due_date = wrongDate + 1天
     */
    void resetStage(Long studentId, String tenantId, Long questionId, LocalDate wrongDate);

    /**
     * 掌握度联动：暂停/重新入队
     * - mastery >= 0.8 → status=paused（仅 active 记录参与）
     * - mastery < 0.8 → 原 paused 的记录 status=active（仅 paused 记录参与）
     * - BLOCK-2 修复：明确暂停语义 + 防抖 + stage 保留
     * - BLOCK-B 修复：recordWrongAnswer 后 syncPauseStatus 可能立即暂停——预期行为
     */
    void syncPauseStatus(Long studentId, String tenantId,
                          String knowledgePointId, BigDecimal masteryScore);

    // ===== 查询操作 =====

    /**
     * 错题列表分页查询
     * IMP-5 修复：使用自定义 JOIN SQL 避免 N+1
     * IMP-F 修复：page < 1 置 1，size > 100 截断至 100
     */
    PageResult<ErrorQuestionVO> getErrorQuestions(Long studentId, String tenantId,
            String knowledgePointId, String status, int page, int size);

    /**
     * 错题统计
     * BLOCK-C 修复：COALESCE 防 NULL → NPE
     */
    ErrorQuestionStatisticsVO getStatistics(Long studentId, String tenantId);
}
```

#### recordWrongAnswer 方法

> **BLOCK-4 修复**：INSERT 时捕获 DuplicateKeyException → 回退到 UPDATE 路径（幂等）。

```
1. 构造 ErrorQuestion 对象
2. 尝试 INSERT：
   - student_id, question_id, knowledge_point_id
   - source_type = sourceType.getCode()（枚举转字符串）
   - source_id = sourceId
   - first_wrong_date = wrongDate
   - last_wrong_date = wrongDate
   - current_stage = 1
   - next_due_date = wrongDate + 1 天
   - status = 'active'
   - completed_date = NULL
3. 捕获 DuplicateKeyException → 回退到 UPDATE：
   - UPDATE SET last_wrong_date = wrongDate, current_stage = 1,
     next_due_date = wrongDate + 1, status = 'active',
     source_type = sourceType.getCode(), source_id = sourceId,
     completed_date = NULL
   - WHERE student_id + question_id + tenant_id
   - BLOCK-D 修复：显式清空 completed_date
     （允许 completed → active，因为"重新答错"是合法的重入队触发）
   - BLOCK-F 修复：不修改 first_wrong_date（保留首次入队时间，保证统计/排序稳定性）
4. UPDATE 后重新查询确认记录存在，不存在则抛 BusinessException
```

> **IMP-11 修复：调用顺序**：`recordWrongAnswer` 先于 `syncPauseStatus` 执行。最终状态以 `syncPauseStatus` 为准（若掌握度仍 ≥ 0.8 则再次暂停）。

> **BLOCK-B 修复：预期行为说明**：若该知识点掌握度 ≥ 0.8，recordWrongAnswer 刚 INSERT 的 active 记录会被 syncPauseStatus 立即改为 paused。这是**预期行为**：
> - 错题记录保留（first_wrong_date 用于统计追溯）
> - status=paused 表示"暂不需重做"（掌握度已达标）
> - 若后续掌握度跌破 0.8，自动重新入队
> - 性能影响可忽略（一次额外 UPDATE）

> **IMP-M 修复：批量入队 + 掌握度跨阈值场景**：同一 gradeHomework 批次中，某知识点可能有多道新题答错（recordWrongAnswer × N，都 INSERT active），同时掌握度因本次批改从 < 0.8 跨越到 ≥ 0.8。流程：N 次 recordWrongAnswer（active）→ updateMasteryFromHomework（掌握度 0.82）→ syncPauseStatus（≥ 0.8，batchPause 全部 active → paused）。结果：N 道刚入队的错题全部 paused。这是**预期行为**：掌握度达标确实不需重做，学生端错题列表显示"已掌握，暂不需重做"。

#### advanceStage 方法

> **IMP-2 修复**：基准日期改为 `correctDate`（本次答对日期），而非 `last_wrong_date`。
> **BLOCK-E 修复**：参数语义修正——`currentStage` 为数据库当前旧值，`newStage = currentStage + 1`。
> **BLOCK-E 修复**：UPDATE 加 `WHERE current_stage = #{currentStage} AND status != 'completed'` 并发保护。

```
1. 查询 error_question WHERE student_id + question_id + tenant_id
2. 不存在 → 抛 ERROR_QUESTION_NOT_FOUND
3. 已存在且 status='completed' → 抛 ERROR_QUESTION_COMPLETED
4. currentStage = record.current_stage（旧值，从数据库读出）
   newStage = currentStage + 1
5. 若 newStage > 5：
   - completeStageWithCheck(..., currentStage, newStage, correctDate)
     WHERE error_question_id=#{id} AND current_stage=#{currentStage} AND status != 'completed'
6. 否则：
   - nextDueDate = correctDate + EbbinghausStage.fromStage(newStage).getIntervalDays()
   - advanceStageWithCheck(..., currentStage, newStage, nextDueDate)
     WHERE error_question_id=#{id} AND current_stage=#{currentStage} AND status != 'completed'
7. 检查 affected rows：若为 0 → 抛 ERROR_QUESTION_CONCURRENT_MODIFIED(1103)
```

#### resetStage 方法

```
1. 查询 error_question WHERE student_id + question_id + tenant_id
2. 不存在 → 抛 ERROR_QUESTION_NOT_FOUND
3. 已存在且 status='completed' → 抛 ERROR_QUESTION_COMPLETED
4. UPDATE SET current_stage=1, last_wrong_date=wrongDate,
   next_due_date=wrongDate+1, status='active',
   completed_date=NULL
   WHERE error_question_id=#{id} AND status != 'completed'
   BLOCK-F 修复：不修改 first_wrong_date（保留首次入队时间，IMP-N 排序稳定性依赖此字段不可变）
5. IMP-L 修复：affected rows == 0 时重新查询记录：
   - status='completed' → 抛 ERROR_QUESTION_COMPLETED
   - 记录不存在 → 抛 ERROR_QUESTION_NOT_FOUND（并发删除窗口）
   - 仍为 active → IMP-X 修复：精确判断值未变：
     record.currentStage == 1
     && Objects.equals(record.lastWrongDate, wrongDate)
     && Objects.equals(record.nextDueDate, wrongDate.plusDays(1))
     && "active".equals(record.status)
     → 视为幂等成功（debug 日志），正常返回
     IMP-AC 说明：LocalDate 比较使用 Objects.equals（null-safe），
     MyBatis 使用 LocalDateTypeHandler（MyBatis 3.4.5+ 默认支持），与 LocalDate.now() 的 equals 可靠
   - 否则（status=active 但字段值不同，不应发生）→ 抛 ERROR_QUESTION_CONCURRENT_MODIFIED
```

#### syncPauseStatus 方法

> **BLOCK-2 修复**：明确暂停语义 + 防抖说明。
> **BLOCK-B 修复**：recordWrongAnswer 后立即暂停的路径文档化。

```
1. 仅对 status IN ('active', 'paused') 的记录执行联动（completed 不参与）
2. 若 masteryScore >= 0.8：
   - batchPauseByKnowledgePoint：将 status='active' → 'paused'
3. 若 masteryScore < 0.8：
   - batchReactivateByKnowledgePoint：将 status='paused' → 'active'
   - 重新激活后 stage 保留原值（不重置），从当前 stage 的 next_due_date 继续
```

> **BLOCK-2 防抖说明**：掌握度在 0.8 附近震荡时，错题可能反复暂停/激活。这是已知限制，当前接受：
> - 每次批量 UPDATE 仅影响 status 字段，不重置 stage/next_due_date，代价很低
> - 若后续发现性能问题，可引入滞后阈值（≥ 0.8 暂停，< 0.75 激活）
> - 登记为技术债务 TD-PAUSE-OSCILLATION

> **BLOCK-E 跨事务一致性**：跨事务的掌握度更新与错题状态联动不保证强一致——以最后一次 `syncPauseStatus` 的掌握度快照为准。若需强一致，需引入分布式锁或事件溯源（当前 Change 不做，登记技术债务 TD-CROSS-TX-CONSISTENCY）。

> **IMP-T1: 事务边界**：`syncPauseStatus` 由 `MasteryServiceImpl` 在同一 `@Transactional` 内调用，不单独开事务。

### 2.3 掌握度联动集成

修改 `MasteryServiceImpl`，在掌握度更新后调用错题暂停/重新入队：

```java
// MasteryServiceImpl.batchFlushStates 末尾追加：
for (StudentKnowledgeState state : stateCache.values()) {
    // ... 原有 updateById 逻辑 ...
    
    // 新增：错题联动
    errorQuestionService.syncPauseStatus(
            studentId, tenantId,
            state.getKnowledgePointId(), state.getMasteryScore());
}
```

> **IMP-3 修复：调用顺序与事务**：
> - `HomeworkServiceImpl.gradeHomework` 中，先执行错题推进（advanceStage/resetStage）+ 入队（recordWrongAnswer），再执行掌握度更新（updateMasteryFromHomework → syncPauseStatus）
> - 两者在 `gradeHomework` 的 `@Transactional` 内，同一事务保证一致性
> - 同一 question 在同一批次中先被 advanceStage 推进到 stage 2，再被 syncPauseStatus 因掌握度达标改为 paused——这是正确行为

> **BLOCK-B 闭环**：gradeHomework 完整执行顺序：
> ```
> 1. 遍历 submissions → 错题推进/入队（recordWrongAnswer / advanceStage / resetStage）
> 2. 收集 submissionIds
> 3. updateMasteryFromHomework → 掌握度更新 → syncPauseStatus（可能将刚入队的记录暂停）
> 4. updateStatus = completed
> ```

### 2.4 每日作业集成错题 + 批改触发入队

> **BLOCK-1 修复**：明确 recordWrongAnswer 的调用触发点。

#### 2.4.1 generateDailyHomework 集成（读错题）

> **BLOCK-A 修复**：明确截断策略 + Agent 输入语义。

修改 `HomeworkServiceImpl.generateDailyHomework`，在 Agent 调用前查询到期错题：

```
// 新增步骤（在构造 Agent 输入参数之前）：
1. 查询到期错题：errorQuestionService.getDueErrorQuestions(studentId, tenantId, planDate)
   - BLOCK-H 修复：内部按知识点分别查询，每个知识点 LIMIT 3，彻底消除全局截断问题
   - IMP-AB 修复：返回 LinkedHashMap，key 顺序由 selectDueKnowledgePointIds 的 ORDER BY knowledge_point_id ASC 决定（知识点 ID 字典序）
   - BLOCK-I 修复：步骤 2 返回空 List 的知识点不放入 Map，保证 Map 的 value 非空
   - IMP-AH 修复：SQL LIMIT 51，Service 层判断 size > 50 则 WARN 日志 + subList(0, 50)，只处理前 50 个知识点
   - 每个知识点最多 3 道（按 next_due_date ASC, error_question_id ASC 排序后取前 3）
   - 超出截断的错题顺延到下一日（不修改 next_due_date，仅当日不纳入）
2. 组装错题引用列表：
   [{"questionId": X, "questionSource": "bank", "taskType": "redo"}, ...]
3. 计算剩余配额：15（日上限）- 错题总数 = 新题配额
4. 将“已占用配额的错题 questionId 列表”传递给 Agent
   - BLOCK-A 语义明确：Agent 为剩余配额生成新题，避免与已占用 questionId 重复
   - 不是“避开整个知识点”，而是“避开已占用的具体题目”
5. Agent 返回新题后，合并错题引用 + 新题引用 → daily_plan.content
```

> **NOTE-2: generate 幂等声明**：重复调用 generate DailyHomework 以首次生成的 daily_plan 为准（错题集合可能因漂移不同），不重新查询错题。登记 TD-GENERATE-IDEMPOTENCY。

> **IMP-T2: 配额说明**：完整配额逻辑（4 题/知识点上限、极端场景兜底）在 Change 3（举一反三）中统一实现。当前简化规则：错题截断至 3 题/知识点，剩余配额给 Agent 新题。

#### 2.4.2 gradeHomework 集成（写错题 + 推进错题）

> **BLOCK-1 核心修复**：批改是错题入队的唯一触发点。

修改 `HomeworkServiceImpl.gradeHomework`，在批改循环中处理错题：

```
// 插入位置：在步骤 5（收集 submissionId）之后、步骤 8（更新 status=completed）之前
// IMP-8 修复：确保幂等（status 已 completed 时不推进）

// BLOCK-E 修复：批次内按 questionId 去重，同一 questionId 只处理一次
// IMP-Q 修复：redo 优先策略——同一 questionId 若同时存在 redo 和 non-redo submission，优先处理 redo
// IMP-W 修复：submissions 排序保证“同一 questionId 多条 redo 时取最后一条”语义稳定
// IMP-AD 修复：优先按 create_time ASC, submission_id ASC 排序
//   （已确认 HomeworkSubmissionDetail 继承 BaseEntity，含 createTime 字段，由 MyBatis-Plus FieldFill.INSERT 自动填充）
submissions.sort(Comparator.comparing(HomeworkSubmissionDetail::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(HomeworkSubmissionDetail::getSubmissionId));
Map<Long, HomeworkSubmissionDetail> questionSubmissionMap = new LinkedHashMap<>();
for (HomeworkSubmissionDetail submission : submissions) {
    Long qId = submission.getQuestionId();
    HomeworkSubmissionDetail existing = questionSubmissionMap.get(qId);
    if (existing == null || "redo".equals(submission.getQuestionType())) {
        questionSubmissionMap.put(qId, submission);
    } else {
        log.warn("[gradeHomework] 重复 questionId={}（非 redo 被跳过）", qId);
    }
}
for (HomeworkSubmissionDetail submission : questionSubmissionMap.values()) {
    boolean isCorrect = submission.getIsCorrect() != null && submission.getIsCorrect() == 1;
    boolean isRedo = "redo".equals(submission.getQuestionType());
    
    if (isRedo) {
        // 错题重做：答对 → advanceStage；答错 → resetStage
        if (isCorrect) {
            errorQuestionService.advanceStage(studentId, tenantId,
                    submission.getQuestionId(), gradeDate);
        } else {
            errorQuestionService.resetStage(studentId, tenantId,
                    submission.getQuestionId(), gradeDate);
        }
    } else {
        // 非 redo 题（bank/generated）：答错 → recordWrongAnswer 入队
        if (!isCorrect) {
            errorQuestionService.recordWrongAnswer(
                    studentId, tenantId,
                    submission.getQuestionId(),
                    submission.getKnowledgePointId(),
                    ErrorQuestionSourceType.HOMEWORK,
                    submission.getSubmissionId(),
                    gradeDate);
        }
    }
}

// 之后继续执行原有逻辑：
// → 收集 submissionIds
// → updateMasteryFromHomework（内部触发 syncPauseStatus）
// → updateStatus=completed
```

> **BLOCK-3 修复**：`sourceType = ErrorQuestionSourceType.HOMEWORK`（枚举），`sourceId = submission.getSubmissionId()`。assessment/exam 来源由后续 Change 接入。

### 2.5 DAO 层

新增 `ErrorQuestionMapper`：

> **IMP-5 修复**：新增 `selectPageWithDetail` 自定义 JOIN SQL，避免 N+1。
> **IMP-6 修复**：`selectStatistics` 返回 `Map<String, Object>`，Service 层转为 VO。
> **BLOCK-C 修复**：`selectStatistics` 使用 COALESCE 防 NULL。
> **BLOCK-H 修复**：原 `selectDueQuestions` 拆分为 `selectDueKnowledgePointIds` + `selectDueQuestionsByKnowledgePoint`，彻底消除全局截断问题。
> **IMP-G 修复**：`selectPageWithDetail` 使用 COALESCE 兆底 questionContent。
> **IMP-I 修复**：Mapper 方法注释引用索引名。

```java
@Mapper
public interface ErrorQuestionMapper extends BaseMapper<ErrorQuestion> {

    /**
     * BLOCK-H 修复：查询有到期错题的知识点 ID 列表（去重）
     * 走 idx_student_status_due 索引，返回 knowledge_point_id 列表
     * IMP-AH 修复：LIMIT 51 作为知识点数量上限保护，Service 层 size > 50 时 WARN + subList(0, 50)
     */
    @Select("SELECT DISTINCT knowledge_point_id FROM error_question " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND status = 'active' AND next_due_date <= #{targetDate} " +
            "ORDER BY knowledge_point_id ASC " +
            "LIMIT 51")
    List<String> selectDueKnowledgePointIds(@Param("studentId") Long studentId,
                                             @Param("tenantId") String tenantId,
                                             @Param("targetDate") LocalDate targetDate);

    /**
     * BLOCK-H 修复：按知识点查询到期错题前 3 道
     * IMP-D: 排序 next_due_date ASC, error_question_id ASC 保证截断稳定性
     * 索引命中：idx_student_status_due (student_id, tenant_id, status, next_due_date)
     */
    @Select("SELECT error_question_id, student_id, question_id, knowledge_point_id, " +
            "source_type, source_id, first_wrong_date, last_wrong_date, " +
            "current_stage, next_due_date, status, completed_date, " +
            "tenant_id, create_time, update_time " +
            "FROM error_question " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND knowledge_point_id = #{knowledgePointId} " +
            "AND status = 'active' AND next_due_date <= #{targetDate} " +
            "ORDER BY next_due_date ASC, error_question_id ASC " +
            "LIMIT 3")
    List<ErrorQuestion> selectDueQuestionsByKnowledgePoint(
            @Param("studentId") Long studentId,
            @Param("tenantId") String tenantId,
            @Param("knowledgePointId") String knowledgePointId,
            @Param("targetDate") LocalDate targetDate);

    /**
     * 分页查询错题列表（JOIN knowledge_point + question_bank，避免 N+1）
     * IMP-5 修复
     * IMP-G: COALESCE 兆底 questionContent
     * IMP-N 修复：排序改为 first_wrong_date DESC, error_question_id DESC
     *   （避免 syncPauseStatus 的 update_time=NOW() 导致分页记录跳动）
     *   BLOCK-F 修复：first_wrong_date 只在 INSERT 时写入，UPDATE 不修改，排序键稳定
     * IMP-U 说明：该查询 ORDER BY first_wrong_date 无法被现有索引覆盖，会触发 filesort；
     *   当前数据量可接受，登记 TD-PAGINATION-FILESORT
     * 索引命中：idx_student_kp_status (student_id, tenant_id, knowledge_point_id, status) — WHERE 筛选
     */
    @Select("<script>" +
            "SELECT eq.error_question_id, eq.question_id, eq.knowledge_point_id, " +
            "kp.name AS kp_name, eq.source_type, eq.first_wrong_date, eq.last_wrong_date, " +
            "eq.current_stage, eq.next_due_date, eq.status, " +
            "COALESCE(qb.content, '[题目已删除]') AS question_content, " +
            "COALESCE(qb.difficulty, 'unknown') AS question_difficulty " +
            "FROM error_question eq " +
            "LEFT JOIN knowledge_point kp ON eq.knowledge_point_id = kp.knowledge_point_id " +
            "  AND kp.tenant_id = eq.tenant_id " +
            "LEFT JOIN question_bank qb ON eq.question_id = qb.question_id " +
            "  AND qb.tenant_id = eq.tenant_id " +
            "WHERE eq.student_id = #{studentId} AND eq.tenant_id = #{tenantId} " +
            "<if test='knowledgePointId != null'> AND eq.knowledge_point_id = #{knowledgePointId} </if>" +
            "<if test='status != null'> AND eq.status = #{status} </if>" +
            "ORDER BY eq.first_wrong_date DESC, eq.error_question_id DESC " +
            "LIMIT #{offset}, #{size}" +
            "</script>")
    List<ErrorQuestionVO> selectPageWithDetail(@Param("studentId") Long studentId,
                                                @Param("tenantId") String tenantId,
                                                @Param("knowledgePointId") String knowledgePointId,
                                                @Param("status") String status,
                                                @Param("offset") int offset,
                                                @Param("size") int size);

    /**
     * 统计总数（配合分页）
     * 索引命中：idx_student_kp_status (student_id, tenant_id, knowledge_point_id, status)
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM error_question " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "<if test='knowledgePointId != null'> AND knowledge_point_id = #{knowledgePointId} </if>" +
            "<if test='status != null'> AND status = #{status} </if>" +
            "</script>")
    int selectCount(@Param("studentId") Long studentId,
                    @Param("tenantId") String tenantId,
                    @Param("knowledgePointId") String knowledgePointId,
                    @Param("status") String status);

    /**
     * 按知识点批量暂停
     * IMP-7 修复：WHERE 条件明确 status='active'，仅活跃记录参与
     * IMP-R 修复：不更新 update_time（暂停/激活是派生状态变更，不视为内容修改）
     * 索引命中：idx_student_kp_status
     */
    @Update("UPDATE error_question SET status = 'paused' " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND knowledge_point_id = #{knowledgePointId} AND status = 'active'")
    int batchPauseByKnowledgePoint(@Param("studentId") Long studentId,
                                    @Param("tenantId") String tenantId,
                                    @Param("knowledgePointId") String knowledgePointId);

    /**
     * 按知识点批量重新激活
     * IMP-7 修复：WHERE 条件明确 status='paused'，仅暂停记录参与
     * IMP-R 修复：不更新 update_time（同上）
     */
    @Update("UPDATE error_question SET status = 'active' " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND knowledge_point_id = #{knowledgePointId} AND status = 'paused'")
    int batchReactivateByKnowledgePoint(@Param("studentId") Long studentId,
                                         @Param("tenantId") String tenantId,
                                         @Param("knowledgePointId") String knowledgePointId);

    /**
     * 错题统计
     * BLOCK-C 修复：COALESCE 防 SUM 空集返回 NULL → NPE
     * IMP-6 修复：返回 Map，Service 层转 VO
     */
    @Select("SELECT " +
            "COALESCE(COUNT(*), 0) AS totalCount, " +
            "COALESCE(SUM(CASE WHEN status = 'active' AND next_due_date <= #{today} THEN 1 ELSE 0 END), 0) AS dueCount, " +
            "COALESCE(SUM(CASE WHEN status = 'paused' THEN 1 ELSE 0 END), 0) AS pausedCount " +
            "FROM error_question " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND status != 'completed'")
    Map<String, Object> selectStatistics(@Param("studentId") Long studentId,
                                          @Param("tenantId") String tenantId,
                                          @Param("today") LocalDate today);

    /**
     * BLOCK-E 修复：advanceStage 并发保护 UPDATE
     * IMP-K 说明：手写 SQL 不走 MyBatis-Plus 自动填充，update_time 显式 NOW()；
     *   ErrorQuestion 不使用乐观锁 version（理由：状态机校验已提供并发保护）。
     * WHERE current_stage = #{currentStage}（数据库旧值） AND status != 'completed'
     */
    @Update("UPDATE error_question SET current_stage = #{newStage}, " +
            "next_due_date = #{nextDueDate}, update_time = NOW() " +
            "WHERE error_question_id = #{errorQuestionId} AND tenant_id = #{tenantId} " +
            "AND current_stage = #{currentStage} AND status != 'completed'")
    int advanceStageWithCheck(@Param("errorQuestionId") Long errorQuestionId,
                               @Param("tenantId") String tenantId,
                               @Param("currentStage") int currentStage,
                               @Param("newStage") int newStage,
                               @Param("nextDueDate") LocalDate nextDueDate);

    /**
     * BLOCK-E 修复：advanceStage 完成态 UPDATE
     * IMP-K 说明：同 advanceStageWithCheck，手写 SQL 显式 NOW()，不走自动填充。
     * WHERE current_stage = #{currentStage}（数据库旧值） AND status != 'completed'
     */
    @Update("UPDATE error_question SET current_stage = #{newStage}, " +
            "status = 'completed', completed_date = #{completedDate}, " +
            "next_due_date = NULL, update_time = NOW() " +
            "WHERE error_question_id = #{errorQuestionId} AND tenant_id = #{tenantId} " +
            "AND current_stage = #{currentStage} AND status != 'completed'")
    int completeStageWithCheck(@Param("errorQuestionId") Long errorQuestionId,
                                @Param("tenantId") String tenantId,
                                @Param("currentStage") int currentStage,
                                @Param("newStage") int newStage,
                                @Param("completedDate") LocalDate completedDate);
}
```

### 2.6 Model 层

新增实体类：

> **IMP-9 修复**：BaseEntity 包含 `tenantId`、`createTime`、`updateTime`（已确认），ErrorQuestion 不重复声明。
> **IMP-C 修复**：sourceType/sourceId 注释明确语义由 sourceType 决定。
> **IMP-K 修复**：ErrorQuestion **不使用乐观锁 version 字段**——理由：advanceStage/resetStage 已通过状态机校验（WHERE current_stage = #{currentStage}）提供并发保护，无需额外 version 字段。手写 SQL 不走 MyBatis-Plus 自动填充，update_time 显式 NOW()。

```java
/**
 * 错题队列实体（艾宾浩斯周期调度）
 * 继承 BaseEntity（含 tenantId, createTime, updateTime）
 */
@Data
@TableName("error_question")
public class ErrorQuestion extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long errorQuestionId;

    private Long studentId;
    private Long questionId;           // 关联 question_bank.question_id
    private String knowledgePointId;

    /**
     * IMP-C 修复：sourceId 含义由 sourceType 决定
     * - HOMEWORK → homework_submission_detail.submission_id
     * - ASSESSMENT → （待 Change 4 定义）
     * - EXAM → （待 Change 4 定义）
     */
    private String sourceType;         // 来自 ErrorQuestionSourceType 枚举 code
    private Long sourceId;             // 来源 ID（语义由 sourceType 决定）

    private LocalDate firstWrongDate;  // 首次答错日期（艾宾浩斯起算点）——BLOCK-F: 只在 INSERT 时写入，UPDATE 不修改
    private LocalDate lastWrongDate;   // 最近一次答错日期（周期重置基准）
    private Integer currentStage;      // 当前阶段（1-5）
    private LocalDate nextDueDate;     // 下次到期日期

    private String status;             // active / paused / completed
    private LocalDate completedDate;   // 完成日期（stage > 5 时记录）
}
```

新增枚举：

> **BLOCK-3 修复**：新增 `ErrorQuestionSourceType` 枚举。
> **IMP-10 修复**：`EbbinghausStage.fromStage` 统一抛 `BusinessException`。
> **IMP-C 修复**：ASSESSMENT/EXAM 标注"暂不写入，sourceId 语义待定"。

```java
/**
 * 艾宾浩斯阶段枚举
 * IMP-B: fromStage 覆盖 1-5 正常值 + 0/6/-1 异常值测试
 */
@Getter
public enum EbbinghausStage {
    STAGE_1(1, 1, "1天后重做"),
    STAGE_2(2, 3, "3天后重做"),
    STAGE_3(3, 7, "7天后重做"),
    STAGE_4(4, 15, "15天后重做"),
    STAGE_5(5, 30, "30天后重做");

    private final int stage;
    private final int intervalDays;
    private final String description;

    EbbinghausStage(int stage, int intervalDays, String description) {
        this.stage = stage;
        this.intervalDays = intervalDays;
        this.description = description;
    }

    public static EbbinghausStage fromStage(int stage) {
        for (EbbinghausStage s : values()) {
            if (s.stage == stage) return s;
        }
        throw new BusinessException(ErrorCodes.BAD_REQUEST, "Unknown EbbinghausStage: " + stage);
    }

    /**
     * IMP-4 修复：删除返回 null 的 calculateNextDueDate 方法。
     * stage > 5 的分支由 advanceStage 显式处理（设 completed）。
     */
    public static int getIntervalDays(int stage) {
        return fromStage(stage).getIntervalDays();
    }
}

/**
 * 错题状态枚举
 */
@Getter
public enum ErrorQuestionStatus {
    ACTIVE("active", "活跃"),
    PAUSED("paused", "暂停"),
    COMPLETED("completed", "已完成");

    private final String code;
    private final String description;

    ErrorQuestionStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static ErrorQuestionStatus fromCode(String code) {
        for (ErrorQuestionStatus s : values()) {
            if (s.code.equals(code)) return s;
        }
        throw new BusinessException(ErrorCodes.BAD_REQUEST, "Unknown ErrorQuestionStatus: " + code);
    }
}

/**
 * BLOCK-3 修复：错题来源类型枚举
 * IMP-C 修复：ASSESSMENT/EXAM 暂不写入，sourceId 语义待后续 Change 定义
 */
@Getter
public enum ErrorQuestionSourceType {
    HOMEWORK("homework", "每日作业"),
    ASSESSMENT("assessment", "专项评测"),  // 暂不写入，待 Change 4
    EXAM("exam", "考试");                  // 暂不写入，待 Change 4

    private final String code;
    private final String description;

    ErrorQuestionSourceType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static ErrorQuestionSourceType fromCode(String code) {
        for (ErrorQuestionSourceType t : values()) {
            if (t.code.equals(code)) return t;
        }
        throw new BusinessException(ErrorCodes.BAD_REQUEST, "Unknown ErrorQuestionSourceType: " + code);
    }
}
```

新增 VO 类：

> **IMP-1 修复**：新增 `DueErrorQuestionVO`，Service 层返回 VO 而非实体。
> **IMP-12 修复**：`ErrorQuestionStatisticsVO.totalCount` 改名为 `activeAndPausedCount`。

```java
/**
 * IMP-1 修复：到期错题 VO（供 generateDailyHomework 使用）
 */
@Data
@Builder
public class DueErrorQuestionVO {
    private Long errorQuestionId;
    private Long questionId;
    private String knowledgePointId;
    private Integer currentStage;
    private LocalDate nextDueDate;
}

/**
 * 错题列表 VO（供 Controller 返回）
 * IMP-G: questionContent 由 SQL COALESCE 兜底，不会为 null
 */
@Data
@Builder
public class ErrorQuestionVO {
    private Long errorQuestionId;
    private Long questionId;
    private String knowledgePointId;
    private String knowledgePointName;   // JOIN knowledge_point.name
    private String sourceType;
    private LocalDate firstWrongDate;
    private LocalDate lastWrongDate;
    private Integer currentStage;
    private LocalDate nextDueDate;
    private String status;
    private String questionContent;      // JOIN question_bank.content，COALESCE 兜底
    private String questionDifficulty;   // JOIN question_bank.difficulty，COALESCE 兜底
}

/**
 * IMP-12 修复：错题统计 VO
 * BLOCK-C: 所有字段由 COALESCE 保证非 null
 */
@Data
@Builder
public class ErrorQuestionStatisticsVO {
    private Integer activeAndPausedCount;  // 活跃+暂停总数（不含 completed）
    private Integer dueCount;              // 今日到期数
    private Integer pausedCount;           // 暂停数
}
```

---

## 3. 异常处理

### 3.1 业务异常

> **错误码编号**：1100+ 段，延续项目编号风格。

| 异常场景 | 错误码 | 编号 | 处理方式 |
|----------|--------|------|----------|
| 错题不存在 | `ERROR_QUESTION_NOT_FOUND` | 1100 | 返回 404 |
| 错题已完成（无法推进） | `ERROR_QUESTION_COMPLETED` | 1101 | 返回提示 |
| 错题状态无效 | `ERROR_QUESTION_INVALID_STATUS` | 1102 | 返回提示 |
| **IMP-A: 错题并发修改冲突** | **`ERROR_QUESTION_CONCURRENT_MODIFIED`** | **1103** | **返回重试提示** |

---

## 4. 隐性约定对齐

| 约定编号 | 约定内容 | 本次实现 |
|----------|----------|----------|
| #1 | 统一响应结构 | Controller 所有方法返回 `Result<T>` |
| #2 | 异常处理规范 | Service 抛出 `BusinessException`，Controller 不 try-catch |
| #3 | 分页参数规范 | 错题列表分页，`page` 从 1 开始，`size` 默认 10 |
| #4 | 时间格式规范 | 实体使用 `LocalDateTime`/`LocalDate` |
| #9 | @Transactional rollbackFor | `recordWrongAnswer`/`advanceStage`/`resetStage` 显式指定 |
| #10 | 事务边界精确控制 | 本次无外部 API 调用，使用 `@Transactional` |
| #11 | 并发写入保护 | `error_question` 唯一索引 + DuplicateKeyException 捕获；advanceStage 使用状态机校验 |
| #12 | Agent 调用超时降级 | 本次不涉及 |

---

## 5. 测试策略

### 5.1 单元测试

| 测试类 | 测试内容 |
|--------|----------|
| `ErrorQuestionServiceTest` | 入队/推进/重置/暂停/重新入队/到期查询/并发捕获/统计空集 |
| **BLOCK-D: completed 重入队** | completed 记录被 recordWrongAnswer 触发 → status=active, completed_date=NULL（first_wrong_date 保留不变） |
| **BLOCK-E: advanceStage 正常推进** | stage 1→2 → affected rows=1，不抛异常（v3 回归测试） |
| **BLOCK-E: stage 5→completed** | 正常完成，completed_date 正确写入 |
| **BLOCK-E: 批次内去重** | 同一 questionId 重复提交 → 只推进一次 |
| **BLOCK-F: first_wrong_date 不可性** | 多次 redo 答错后 first_wrong_date 保持首次入队值不变 |
| **IMP-L: resetStage 幂等** | 重复 resetStage 同一题（值未变）→ 幂等成功，不抛 COMPLETED |
| **IMP-X: resetStage 幂等精确判断** | currentStage=1 AND lastWrongDate=wrongDate AND nextDueDate=wrongDate+1 AND status=active → 幂等成功 |
| `EbbinghausStageTest` | **IMP-B**: 阶段枚举 1-5 正常值 + 0/6/-1 异常值 + getIntervalDays |

### 5.2 集成测试

> **IMP-14 修复**：补充暂停/激活抖动 + 状态机冲突 + 基准日期 + BLOCK-A 截断 + BLOCK-B 入队即暂停 测试用例。

| 测试场景 | 验证点 |
|----------|--------|
| 完整错题生命周期 | 入队 → 推进 → 暂停 → 重新入队 → 完成 |
| 掌握度联动 | 掌握度 ≥ 0.8 暂停，< 0.8 重新入队 |
| 掌握度抖动 | 掌握度在 0.8 附近震荡时，暂停/激活正确切换，stage 保留 |
| 每日作业集成 | 生成作业时包含到期错题 |
| **BLOCK-A: 截断与顺延** | 到期错题数 > 单知识点配额（3 题）时的截断行为 |
| 批改触发入队 | gradeHomework 答错非 redo 题 → 入队；答错 redo 题 → resetStage；答对 redo 题 → advanceStage |
| 同一批次推进+暂停 | advanceStage 后 syncPauseStatus 将同一记录改为 paused |
| advanceStage 基准日期 | 验证 next_due_date = correctDate + interval（非 last_wrong_date + interval） |
| **BLOCK-B: 入队即暂停** | 非 redo 答错 + 掌握度 ≥ 0.8 → recordWrongAnswer 后 syncPauseStatus 立即暂停 |
| **IMP-M: 批量入队+跨阈值** | 同批次 3 道错题入队 + 掌握度从 0.75→0.82 → 3 道全部 paused |
| 并发入队 | DuplicateKeyException 捕获 → 回退 UPDATE |
| **BLOCK-E: 并发推进** | 同一错题并发 advanceStage → 第二次抛 ERROR_QUESTION_CONCURRENT_MODIFIED |
| **BLOCK-C: 零统计** | 零错题学生查询统计 → activeAndPausedCount=0, dueCount=0, pausedCount=0 |
| 越权查看 | studentId 归属校验 |
| **IMP-F: page 越界** | page=0/-1 → 置 1；size=0/101 → 截断 |
| **IMP-J: LIMIT 保护** | BLOCK-H 修复后每个知识点 LIMIT 3，不再有全局 LIMIT；原用例调整为验证单知识点 > 3 道时的截断行为 |
| **IMP-P: 多知识点均衡** | 3 个知识点各 5 道到期错题 → 每个知识点都能查到 3 道 |
| **IMP-N: 分页稳定性** | 分页过程中触发 syncPauseStatus → 记录不跳动（排序基于 first_wrong_date） |
| **IMP-Q: redo 优先** | 同一 questionId 同时出现 redo 和 non-redo submission → redo 优先处理 |
| **IMP-S: completed 重入队路径** | completed → 重新激活 → 再次完成 → 验证 completed_date 正确更新 |
| **BLOCK-H: 多知识点均衡（根治）** | 30 个知识点各 100 道到期错题（共 3000 道）→ 每个知识点都能查到 3 道（按知识点分别查询，无全局截断） |
| **BLOCK-I: N+1 数据漂移** | N+1 循环期间并发 syncPauseStatus → 最终 Map 不含空 value（过滤空结果） |
| **IMP-AB: Map 顺序稳定性** | 同一数据多次调用 getDueErrorQuestions → 返回的 LinkedHashMap key 顺序一致（按 knowledge_point_id 字典序） |
| **IMP-AC: LocalDate 幂等判断** | resetStage 幂等判断使用 Objects.equals，测试数据通过 LocalDate.of 构造 |
| **IMP-AD: 排序稳定性** | submissionId 非严格递增时（create_time 优先排序）→ 排序结果仍稳定 |
| **IMP-AH: 知识点上限保护** | 到期错题分布在 > 50 个知识点 → 只处理前 50 个 + WARN 日志 |
| **IMP-V: 分组排序稳定性** | 同一知识点内 next_due_date 相同的错题 → 按 error_question_id ASC 稳定排序 |
| **IMP-W: 多条 redo** | 同一 questionId 两条 redo（一答对一答错）→ 取最后一条（按 create_time ASC, submissionId ASC） |

---

## 6. 安全考虑

| 安全风险 | 缓解措施 |
|----------|----------|
| 越权查看他人错题 | Controller 从 StudentContext 提取 studentId，Service 层校验归属 |
| 并发入队冲突 | 唯一索引 + DuplicateKeyException 捕获 → 幂等 UPDATE |
| 并发推进冲突 | advanceStage UPDATE WHERE current_stage = currentStage（旧值） → affected rows 校验 |

---

## 7. 技术债务登记

| 编号 | 描述 | 登记原因 |
|------|------|----------|
| TD-PAUSE-OSCILLATION | 掌握度 0.8 附近震荡导致错题反复暂停/激活 | 当前代价低（仅 status UPDATE），后续可引入滞后阈值 |
| TD-CROSS-TX-CONSISTENCY | 跨事务的掌握度更新与错题状态不保证强一致 | 当前以最后一次 syncPauseStatus 快照为准 |
| TD-ORPHAN-ERROR-QUESTION | 题目删除后错题记录成为孤儿 | SQL COALESCE 兜底，后续需定期清理 |
| TD-SOURCE-ID-SEMANTICS | ASSESSMENT/EXAM 来源的 sourceId 映射规则未定义 | 待 Change 4（专项评测/试卷解析）接入时定义 |
| **TD-COMPLETED-HISTORY** | completed_date 在重新激活时清空，历史完成事实不可追溯 | 当前 Change 不做 completed_count 字段，待 Change 5（计划调整）需要时补充 |
| **TD-GENERATE-IDEMPOTENCY** | generateDailyHomework 重复调用时错题集合可能因漂移不同 | NOTE-2: 以首次生成的 daily_plan 为准，不重新查询错题 |
| **TD-PAGINATION-FILESORT** | selectPageWithDetail 的 ORDER BY first_wrong_date 无法被索引覆盖 | 预估单学生错题上限 2000 条，filesort 在 2000 条内耗时 < 20ms（IMP-AE: T-01 EXPLAIN 验证后若 filesort 行数 > 5000 则补索引；IMP-AI: 补索引后需验证 5 种查询组合：① student_id+tenant_id；② +knowledgePointId；③ +status；④ +两者；⑤ 无筛选+offset=1500 深分页。若 ②③④ 仍 filesort 需评估复合索引；补索引仅解决无筛选场景，带筛选场景留待数据量增长后评估） |
| **TD-HOMEWORK-SERVICE-FAT** | HomeworkServiceImpl 过胖：12 个注入依赖（阈值 8）、generateDailyHomework ~150 行（阈值 50）、gradeHomework ~140 行（阈值 50） | 架构分层检查发现，当前 Demo 阶段可接受。建议拆分为 HomeworkPlanGenerator（查询+Agent+降级）+ HomeworkGrader（批改+错题+掌握度）+ 轻量 HomeworkServiceImpl（编排）。待题量增长或新 Change 接入时实施 |

---

## 8. 审查修正记录

### 第一轮审查（4 BLOCK + 8 IMP）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-1 | 阻断 | recordWrongAnswer 调用触发点缺失 | §2.4.2 gradeHomework 集成 |
| BLOCK-2 | 阻断 | 暂停/重新入队语义 + 防抖 | §2.2 syncPauseStatus + TD-PAUSE-OSCILLATION |
| BLOCK-3 | 阻断 | sourceType/sourceId 映射未定义 | §2.6 ErrorQuestionSourceType 枚举 + §2.4.2 |
| BLOCK-4 | 阻断 | 并发保护未在实现层体现 | §2.2 recordWrongAnswer DuplicateKeyException |
| IMP-1~8 | 重要/改进 | VO 暴露/基准日期/调用顺序/N+1 等 | 各对应章节 |

### 第二轮审查（3 BLOCK + 9 IMP）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-A | 阻断 | getDueErrorQuestions 缺截断 + Agent 语义不明 | §2.4.1 截断至 3 题/知识点 + 明确 Agent 输入语义 |
| BLOCK-B | 阻断 | recordWrongAnswer → syncPauseStatus "入队即暂停"未文档化 | §2.2 + §2.3 + §5.2 测试用例 |
| BLOCK-C | 阻断 | selectStatistics SUM 空集返回 NULL → NPE | §2.5 COALESCE + §5.2 零统计测试 |
| IMP-A | 重要 | advanceStage 并发竞态 | §2.2 + §2.5 advanceStageWithCheck + 错误码 1103 |
| IMP-B | 重要 | EbbinghausStageTest 未在 tasks 中 | §5.1 + tasks T-00 |
| IMP-C | 重要 | ASSESSMENT/EXAM 枚举无写入路径 | §2.6 标注"暂不写入" + TD-SOURCE-ID-SEMANTICS |
| IMP-D | 改进 | selectDueQuestions 排序不稳定 | §2.5 增加 error_question_id ASC |
| IMP-E | 改进 | 跨事务一致性未说明 | §2.2 TD-CROSS-TX-CONSISTENCY |
| IMP-F | 改进 | PageResult 未定义 + page 越界 | §1.1 + §2.1 参数校验 + §5.2 测试 |
| IMP-G | 改进 | questionContent LEFT JOIN 缺失时 null | §2.5 COALESCE + TD-ORPHAN-ERROR-QUESTION |
| IMP-H | 改进 | T-04 工作量失衡 | tasks T-04 拆分为 T-04a + T-04b |
| IMP-I | 改进 | V3 DDL 缺索引 | tasks T-01 补充 3 个索引 |

### 第三轮审查（2 BLOCK + 6 IMP）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-D | 阻断 | recordWrongAnswer 回退 UPDATE 将 completed 重新激活但未清空 completed_date | §2.2 recordWrongAnswer 步骤 3 清空 completed_date（BLOCK-F 修正：不重置 first_wrong_date） |
| BLOCK-E | 阻断 | advanceStage SQL 参数语义错误（expectedStage 应为 currentStage 旧值）+ 批次内无去重 | §2.2 参数修正 + §2.4.2 批次去重 + §2.5 SQL 修正 |
| IMP-J | 重要 | selectDueQuestions 无 SQL 层 LIMIT 保护 | §2.5 增加 LIMIT 2000（IMP-P 修正：由 500 调整为 2000） |
| IMP-K | 重要 | 手写 SQL 自动填充注意事项未文档化 | §2.5 + §2.6 明确不使用 version，显式 NOW() |
| IMP-L | 重要 | resetStage affected rows=0 误报 COMPLETED | §2.2 改为重查判断 |
| IMP-M | 改进 | 批量入队 + 掌握度跨阈值场景未文档化 | §2.2 BLOCK-B 补充批量场景说明 |
| IMP-N | 改进 | 分页排序用 update_time 与 batchPause 的 NOW() 冲突 | §2.5 改为 first_wrong_date DESC + 索引调整 |
| IMP-O | 改进 | T-01 工作量未反映索引设计 | tasks T-01 调整工作量 + 索引命名对齐 |

### 第四轮审查（1 BLOCK + 6 IMP）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-F | 阻断 | resetStage/recordWrongAnswer 回退 UPDATE 重置 first_wrong_date 与字段语义矛盾 | §2.2 两条路径去掉 first_wrong_date 修改；§2.6 实体注释明确不可变性 |
| IMP-P | 重要 | LIMIT 500 在多知识点场景下可能导致部分知识点完全查不到 | §2.5 LIMIT 调整为 2000；§5.2 增加多知识点均衡测试 |
| IMP-Q | 重要 | 批次去重未考虑同一 questionId 的 redo/non-redo 冲突 | §2.4.2 改为 redo 优先策略（LinkedHashMap） |
| IMP-R | 改进 | batchPause/batchReactivate 不应更新 update_time | §2.5 去掉 update_time = NOW() |
| IMP-S | 改进 | completed 历史不可追溯 | §7 登记 TD-COMPLETED-HISTORY |
| IMP-T | 改进 | proposal §6/§7 未同步 BLOCK-F/IMP-Q | proposal §6/§7 同步更新 |
| IMP-U | 改进 | 分页排序索引覆盖判断错误 | §2.5 说明 filesort + §7 登记 TD-PAGINATION-FILESORT |

### 第五轮审查（1 BLOCK + 6 IMP）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-G | 阻断 | LIMIT 2000 在多知识点分布极不均时仍会截断部分知识点 | §2.5 LIMIT 调整为 10000（仅 OOM 保护）+ §2.2 WARN 日志 |
| IMP-V | 重要 | Java 层分组截断是否重新排序未说明 | §2.2 明确依赖 SQL 层 ORDER BY，分组后不重新排序 |
| IMP-W | 重要 | 同一 questionId 多条 redo submission 时保留哪条未定义 | §2.4.2 submissions 按 submissionId ASC 排序，保留最后一条 |
| IMP-X | 改进 | resetStage 幂等判断“值未变”标准未定义 | §2.2 精确到 4 个字段比较 |
| IMP-Y | 改进 | proposal §5 索引名未同步删除 idx_student_update_time | proposal §5 同步更新 |
| IMP-Z | 改进 | tasks T-06 代码片段未同步 LinkedHashMap 去重 | tasks T-06 Part B 同步 |
| IMP-AA | 改进 | tasks T-00 LIMIT 数值未同步 | tasks T-00 同步 |

### 第六轮审查（1 BLOCK + 6 IMP）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-H | 阻断 | LIMIT 10000 未根治全局截断问题，某些知识点可能永远轮不到 | §2.5 拆分为 selectDueKnowledgePointIds + selectDueQuestionsByKnowledgePoint（每知识点 LIMIT 3）；§2.2/§2.4.1 同步 |
| IMP-AB | 重要 | getDueErrorQuestions 返回 Map 实现类未指定，顺序不稳定 | §2.2 明确返回 LinkedHashMap；tasks T-04b 同步 |
| IMP-AC | 改进 | resetStage 幂等判断 LocalDate.equals 可靠性未说明 | §2.2 改用 Objects.equals + 补充 MyBatis TypeHandler 说明 |
| IMP-AD | 改进 | submissions 排序假设 submissionId 递增，雪花算法下不可靠 | §2.4.2 改为 create_time ASC, submission_id ASC 排序 |
| IMP-AE | 改进 | TD-PAGINATION-FILESORT “当前数据量可接受”缺乏量化依据 | §7 补充量化预估（单学生 < 2000 条，filesort < 20ms） |
| IMP-AF | 改进 | tasks T-01 缺 EXPLAIN 决策路径 | tasks T-01 补充“若 filesort 严重则补索引”条件分支 |

### 第七轮审查（1 BLOCK + 5 IMP）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-I | 阻断 | N+1 循环数据漂移 + 空结果未处理 | §2.2 明确过滤空值 + 接受 READ COMMITTED 漂移；§5.2 增加测试 |
| IMP-AG | 重要 | Map 顺序描述“首次出现”不准确 | §2.2 修正为“knowledge_point_id 字典序”；selectDueKnowledgePointIds ORDER BY 保证 |
| IMP-AH | 重要 | 知识点数量无上限保护，N+1 可能失控 | §2.5 selectDueKnowledgePointIds 增加 LIMIT 51，Service 层 size > 50 时 WARN + subList(0, 50)；§5.2 增加测试 |
| IMP-AI | 改进 | T-01 EXPLAIN 阈值 1000 偏低 + 未覆盖多组合验证 | §7 TD-PAGINATION-FILESORT 阈值调整为 5000 + 补索引后多组合验证 |
| IMP-AD 确认 | 说明 | create_time 字段存在性已确认 | §2.4.2 补充确认说明（HomeworkSubmissionDetail 继承 BaseEntity） |
| IMP-AG 索引 | 说明 | selectDueKnowledgePointIds 的回表开销 | 当前数据量可接受（DISTINCT + LIMIT 50），不补覆盖索引 |

---

**设计人**：AI Agent  
**审核状态**：待人工复审
