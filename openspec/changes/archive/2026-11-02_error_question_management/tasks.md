# Tasks：错题管理与艾宾浩斯周期

> **Change 名称**：`2026-11-02_error_question_management`  
> **创建日期**：2026-11-02  
> **状态**：草案 v8（修复 BLOCK-I + IMP-AG~AI）

---

## Task 清单

### T-00：新增实体类、Mapper 与枚举

**任务描述**：
- 新增 `ErrorQuestion` 实体类，与 `error_question` 表对齐（继承 BaseEntity，含 tenantId/createTime/updateTime）
- 新增 `ErrorQuestionMapper` 接口，包含：
  - `selectDueKnowledgePointIds(studentId, tenantId, targetDate)` — **BLOCK-H 修复**：查询有到期错题的知识点 ID 列表（DISTINCT）
    - 走 idx_student_status_due 索引，返回 knowledge_point_id 列表
    - **IMP-AH 修复**：LIMIT 51 作为知识点数量上限保护，Service 层 size > 50 时 WARN + subList(0, 50)
  - `selectDueQuestionsByKnowledgePoint(studentId, tenantId, knowledgePointId, targetDate)` — **BLOCK-H 修复**：按知识点查询到期错题前 3 道
    - **IMP-D 修复**：ORDER BY `next_due_date ASC, error_question_id ASC`（二级排序保证截断稳定性）
    - **BLOCK-A 修复**：每个知识点 LIMIT 3，彻底消除全局截断导致知识点覆盖不均的问题
    - **IMP-I 修复**：注释引用索引 `idx_student_status_due`
  - `selectPageWithDetail(studentId, tenantId, knowledgePointId, status, offset, size)` — 分页 JOIN 查询
    - **IMP-5 修复**：JOIN knowledge_point + question_bank 避免 N+1
    - **IMP-G 修复**：`COALESCE(qb.content, '[题目已删除]')` 兆底 questionContent
    - **IMP-N 修复**：ORDER BY 改为 `first_wrong_date DESC, error_question_id DESC`（避免 syncPauseStatus 的 update_time=NOW() 导致分页跳动）
    - **BLOCK-F 修复**：first_wrong_date 只在 INSERT 时写入，UPDATE 不修改，排序键稳定
    - **IMP-U 修复**：该查询 ORDER BY first_wrong_date 无法被现有索引覆盖，会触发 filesort（TD-PAGINATION-FILESORT）
    - **IMP-I 修复**：注释引用索引 `idx_student_kp_status`
  - `selectCount(studentId, tenantId, knowledgePointId, status)` — 统计总数（配合分页）
    - **IMP-I 修复**：注释引用索引 `idx_student_kp_status`
  - `batchPauseByKnowledgePoint(studentId, tenantId, knowledgePointId)` — 批量暂停（WHERE status='active'）
    - **IMP-R 修复**：不更新 update_time（暂停/激活是派生状态变更）
  - `batchReactivateByKnowledgePoint(studentId, tenantId, knowledgePointId)` — 批量重新激活（WHERE status='paused'）
    - **IMP-R 修复**：不更新 update_time
  - `selectStatistics(studentId, tenantId, today)` — 错题统计
    - **BLOCK-C 修复**：`COALESCE(SUM(...), 0)` 防空集返回 NULL → NPE
    - **IMP-6 修复**：返回 `Map<String, Object>`，Service 层转 VO
  - `advanceStageWithCheck(errorQuestionId, tenantId, **currentStage**, newStage, nextDueDate)` — **BLOCK-E 修复**：参数名改为 currentStage（数据库旧值）
  - `completeStageWithCheck(errorQuestionId, tenantId, **currentStage**, newStage, completedDate)` — **BLOCK-E 修复**：同上
- 新增 `EbbinghausStage` 枚举（1/3/7/15/30 天），`fromStage` 统一抛 `BusinessException`（**IMP-10**）
- 新增 `ErrorQuestionStatus` 枚举（active/paused/completed）
- 新增 `ErrorQuestionSourceType` 枚举（homework/assessment/exam）（**BLOCK-3**）
- 在 `ErrorCodes` 中新增错题相关错误码（1100-1103）：
  - `ERROR_QUESTION_NOT_FOUND(1100)`
  - `ERROR_QUESTION_COMPLETED(1101)`
  - `ERROR_QUESTION_INVALID_STATUS(1102)`
  - **IMP-A 修复**：`ERROR_QUESTION_CONCURRENT_MODIFIED(1103)`
- 更新测试 `schema.sql`，新增 `error_question` 表定义（含唯一索引 `uk_student_question`）

**涉及文件**：
- `src/main/java/com/jiang/model/entity/ErrorQuestion.java`（新增）
- `src/main/java/com/jiang/mapper/ErrorQuestionMapper.java`（新增）
- `src/main/java/com/jiang/model/enums/EbbinghausStage.java`（新增）
- `src/main/java/com/jiang/model/enums/ErrorQuestionStatus.java`（新增）
- `src/main/java/com/jiang/model/enums/ErrorQuestionSourceType.java`（新增，**BLOCK-3**）
- `src/main/java/com/jiang/common/exception/ErrorCodes.java`（修改，新增 1100-1103）
- `src/test/resources/schema.sql`（修改，新增 error_question 表）
- `src/test/java/com/jiang/model/enums/EbbinghausStageTest.java`（新增，**IMP-B 修复**：覆盖 1-5 正常值 + 0/6/-1 异常值 + getIntervalDays）

**IMP-K 补充说明**：
- ErrorQuestion 不使用乐观锁 version 字段（状态机校验已提供并发保护）
- 手写 @Update SQL 不走 MyBatis-Plus 自动填充，update_time 显式 NOW()
- 若后续 BaseEntity 增加 updateBy 字段，需同步补手写 SQL

**预估工作量**：1 天

---

### T-01：V3 DDL 变更脚本

**任务描述**：
- 编写 V3 DDL 变更脚本，创建 `error_question` 表
- 包含主键、唯一索引 `uk_student_question (student_id, question_id, tenant_id)`（**BLOCK-4**）
- **IMP-I 修复**：新增以下普通索引：
  - `idx_student_status_due (student_id, tenant_id, status, next_due_date)` — 支撑 selectDueQuestions
  - `idx_student_kp_status (student_id, tenant_id, knowledge_point_id, status)` — 支撑 batchPause/batchReactivate + selectPageWithDetail
- **IMP-N 修复**：原 idx_student_update_time 不再需要（分页排序改为 first_wrong_date）
- **IMP-U 修复**：idx_student_kp_status 不覆盖 first_wrong_date 排序（会 filesort），当前数据量可接受，登记 TD-PAGINATION-FILESORT
- **IMP-AF 修复**：EXPLAIN 验证后，若 selectPageWithDetail 的 filesort 行数 > 5000（单学生错题上限预估 2000，5000 为 2.5 倍余量，超过说明数据异常或预估需修正），则补充 `idx_student_first_wrong (student_id, tenant_id, first_wrong_date)` 索引，并更新 TD-PAGINATION-FILESORT 状态
- **IMP-AI 修复**：补索引后需重新 EXPLAIN 验证 5 种查询组合的索引命中：① 仅 student_id+tenant_id；② +knowledgePointId；③ +status；④ +两者；⑤ 无筛选+offset=1500 深分页。若 ②③④ 仍 filesort，需评估是否补复合索引 (student_id, tenant_id, knowledge_point_id, first_wrong_date)
- **IMP-O 修复**：索引命名遵循项目既有规范（V1/V2 使用 idx_ 前缀 + 业务语义命名）
- 脚本命名遵循 Flyway 规范（`V3__error_question_table.sql`）
- 脚本位于受保护目录 `src/main/resources/db/`，需人工审批
- **IMP-13 修复**：回滚方案以注释形式保留 DROP TABLE，标注"仅开发环境可执行，生产需数据迁移"
- **IMP-15 修复**：注明依赖前置 Change V2 已 apply

**涉及文件**：
- `src/main/resources/db/V3__error_question_table.sql`（新增，受保护需人工审批）

**依赖**：无（可与 T-00 并行；生产部署前须确保 V2 已 apply）

**预估工作量**：0.75 天（含索引设计评审 + EXPLAIN 验证准备 + IMP-AI 补索引后多组合二次验证）

> **IMP-O 修复**：工作量由 0.25 天调整为 0.5 天，反映索引设计与 EXPLAIN 验证的额外工作。
> **IMP-AI 修复**：工作量由 0.5 天调整为 0.75 天，反映补索引后多组合验证的额外工作。

---

### T-02：新增 VO 类 ✅

**任务描述**：
- 新增 `DueErrorQuestionVO`（到期错题精简 VO，供 generateDailyHomework 使用）（**IMP-1**）
- 新增 `ErrorQuestionVO`（错题列表展示 VO，含 JOIN 字段）
  - **IMP-G 修复**：`questionContent` 注释说明"由 SQL COALESCE 兜底，不会为 null"
- 新增 `ErrorQuestionStatisticsVO`（错题统计 VO，字段名 `activeAndPausedCount`）（**IMP-12**）
  - **BLOCK-C 修复**：注释说明"所有字段由 COALESCE 保证非 null"

**涉及文件**：
- `src/main/java/com/jiang/vo/DueErrorQuestionVO.java`（新增，**IMP-1**）
- `src/main/java/com/jiang/vo/ErrorQuestionVO.java`（新增）
- `src/main/java/com/jiang/vo/ErrorQuestionStatisticsVO.java`（新增，**IMP-12**）

**预估工作量**：0.25 天

---

### T-03：实现 ErrorQuestionService 接口 ✅

**任务描述**：
- 新增 `ErrorQuestionService` 接口，定义核心方法：
  - `recordWrongAnswer(studentId, tenantId, questionId, knowledgePointId, ErrorQuestionSourceType, sourceId, wrongDate)` — **BLOCK-3: sourceType 使用枚举**
  - `getDueErrorQuestions(studentId, tenantId, targetDate)` — 返回 `Map<String, List<DueErrorQuestionVO>>`（**IMP-1**）
    - **BLOCK-A 修复**：注释说明"每组按 next_due_date ASC, error_question_id ASC 排序后截断至 ≤ 3 道"
  - `advanceStage(studentId, tenantId, questionId, correctDate)` — **IMP-2: 增加 correctDate 参数**
  - `resetStage(studentId, tenantId, questionId, wrongDate)`
  - `syncPauseStatus(studentId, tenantId, knowledgePointId, masteryScore)` — **BLOCK-2: 明确语义**
  - `getErrorQuestions(studentId, tenantId, knowledgePointId, status, page, size)` — 分页查询
    - **IMP-F 修复**：注释说明"page < 1 置 1，size > 100 截断至 100"
  - `getStatistics(studentId, tenantId)` — **BLOCK-C 修复**：注释说明"空结果集兜底"

**涉及文件**：
- `src/main/java/com/jiang/service/ErrorQuestionService.java`（新增）

**预估工作量**：0.25 天

---

### T-04a：实现 ErrorQuestionServiceImpl 写操作方法 ✅

**任务描述**：
- 在 `ErrorQuestionServiceImpl` 中实现写操作方法：
  - `recordWrongAnswer`：
    1. 构造 ErrorQuestion 对象，尝试 INSERT
    2. **BLOCK-4 修复**：捕获 `DuplicateKeyException` → 回退到 UPDATE（幂等）
    3. **IMP-11 修复**：注释明确"先 record 后 sync，最终状态以 sync 为准"
    4. **BLOCK-B 修复**：注释说明"若掌握度 ≥ 0.8，INSERT 后 syncPauseStatus 立即暂停——预期行为"
  - `advanceStage`：
    1. 查询记录 → 校验 status != completed
    2. stage++ → stage > 5 则 `completeStageWithCheck`（status=completed + completed_date=correctDate）
    3. **IMP-2 修复**：否则 `advanceStageWithCheck`，next_due_date = **correctDate** + interval(newStage)
    4. **IMP-A 修复**：检查 affected rows，0 行 → 抛 ERROR_QUESTION_CONCURRENT_MODIFIED(1103)
  - `resetStage`：
    1. stage=1 → next_due_date = wrongDate + 1
    2. WHERE status != 'completed'，affected rows = 0 → 抛 ERROR_QUESTION_COMPLETED
  - `syncPauseStatus`：
    1. **IMP-7 修复**：批量 SQL 已有 status 条件兜底（active→paused / paused→active）
    2. **BLOCK-2 修复**：重新激活后 stage 保留原值
- 添加 `@Transactional(rollbackFor = Exception.class)` 到写操作方法
- 编写单元测试，覆盖：
  - 首次入队 / 重复入队（重置）/ **并发入队（DuplicateKeyException 捕获）**
  - 推进到 stage 2-5 / 推进到 completed / **completed 状态拒绝推进**
  - **IMP-A 修复**：**并发推进同一错题 → 第二次抛 ERROR_QUESTION_CONCURRENT_MODIFIED**
  - 重置周期
  - 暂停 / 重新入队 / **stage 保留验证**
  - **IMP-2 修复**：advanceStage 基准日期验证（correctDate vs last_wrong_date）
  - **BLOCK-B 修复**：**recordWrongAnswer 后 syncPauseStatus（掌握度 ≥ 0.8）→ status=paused，first_wrong_date 保留**
  - **BLOCK-D 修复**：**completed 记录被 recordWrongAnswer 触发 → status=active, completed_date=NULL（first_wrong_date 保留不变）**
  - **BLOCK-E 修复**：**advanceStage 正常推进（stage 1→2）→ affected rows=1，不抛异常**（v3 回归测试）
  - **BLOCK-E 修复**：**advanceStage 完成态（stage 5→completed）→ 正常完成，completed_date 正确写入**
  - **BLOCK-F 修复**：**多次 redo 答错后 first_wrong_date 保持首次入队值不变**
  - **IMP-L 修复**：**重复 resetStage 同一题（值未变）→ 幂等成功，不抛 COMPLETED**
  - **IMP-X 修复**：**resetStage 幂等精确判断：currentStage=1 AND lastWrongDate=wrongDate AND nextDueDate=wrongDate+1 AND status=active**
  - **IMP-AC 修复**：**幂等判断使用 Objects.equals（null-safe），测试数据通过 LocalDate.of(2026, 11, 2) 构造，避免 now() 的不确定性**

**涉及文件**：
- `src/main/java/com/jiang/service/impl/ErrorQuestionServiceImpl.java`（新增）
- `src/test/java/com/jiang/service/ErrorQuestionServiceTest.java`（新增，写操作测试）

**依赖**：T-00（实体/Mapper/枚举）、T-02（VO）、T-03（接口）

**预估工作量**：1.5 天

---

### T-04b：实现 ErrorQuestionServiceImpl 查询操作方法 ✅

**任务描述**：
- 在 `ErrorQuestionServiceImpl` 中实现查询操作方法：
  - `getDueErrorQuestions`：
    1. **BLOCK-H 修复**：调用 `selectDueKnowledgePointIds` 获取有到期错题的知识点 ID 列表
    2. **IMP-AH 修复**：SQL 返回 LIMIT 51，若 knowledgePointIds.size() > 50，记录 WARN 日志 + subList(0, 50) 截断，只处理前 50 个知识点（按 knowledge_point_id ASC）
    3. 对每个知识点调用 `selectDueQuestionsByKnowledgePoint` 获取前 3 道
    4. **BLOCK-I 修复**：返回空 List 的知识点不放入 Map，保证 Map 的 value 非空
    5. **IMP-AG 修复**：返回 `LinkedHashMap`，key 顺序由 selectDueKnowledgePointIds 的 ORDER BY knowledge_point_id ASC 决定（知识点 ID 字典序）
    6. **BLOCK-I 修复**：N+1 循环不纳入事务（只读查询，避免长事务），接受 READ COMMITTED 下的数据漂移；漂移影响仅限于单次 generateDailyHomework 的题目组成，不影响数据一致性
    7. 超出截断的错题顺延到下一日（不修改 next_due_date，仅当日不纳入）
  - `getErrorQuestions`：
    1. **IMP-F 修复**：参数校验 page < 1 → 置 1；size > 100 → 截断至 100；size < 1 → 置 1
    2. 调用 `selectPageWithDetail` + `selectCount` → 封装为 `PageResult<ErrorQuestionVO>`
    3. **IMP-G 修复**：VO 转换时若 questionContent 仍为 null（防御性兜底），设为 "[题目已删除]"
  - `getStatistics`：
    1. 调用 `selectStatistics` → 从 Map 取值
    2. **BLOCK-C 修复**：显式判空兜底（null → 0），防止 COALESCE 失效场景
    3. 封装为 `ErrorQuestionStatisticsVO`
- 编写单元测试，覆盖：
  - 到期查询（含边界值）
  - **BLOCK-A 修复**：**到期错题数 > 单知识点配额（3 题）时的截断与顺延行为**
  - **BLOCK-C 修复**：**零错题学生查询统计 → activeAndPausedCount=0, dueCount=0, pausedCount=0**
  - **IMP-F 修复**：**page=0/-1 → 置 1；size=0/101 → 截断**
  - **IMP-G 修复**：**question_bank 记录缺失时 questionContent 兆底为 "[题目已删除]"**
  - **IMP-J 修复**：**单知识点到期错题 > 3 道时的截断行为（BLOCK-H 后每知识点 LIMIT 3，原全局 LIMIT 用例已废弃）**
  - **IMP-P 修复**：**多知识点均衡：3 个知识点各 5 道到期错题 → 每个知识点都能查到 3 道**
  - **BLOCK-H 修复**：**30 个知识点各 100 道到期错题（共 3000 道）→ 每个知识点都能查到 3 道（按知识点分别查询，无全局截断）**
  - **IMP-AB 修复**：**同一数据多次调用 getDueErrorQuestions → 返回的 LinkedHashMap 顺序一致**
  - **IMP-V 修复**：**同一知识点内 next_due_date 相同的错题 → 按 error_question_id ASC 稳定排序（SQL 层已排序，不再重新排序）**
  - **BLOCK-I 修复**：**N+1 循环期间并发 syncPauseStatus → 最终 Map 不含空 value（过滤空结果）**
  - **IMP-AH 修复**：**到期错题分布在 > 50 个知识点（SQL LIMIT 51，Service 层 size > 50 截断）→ 只处理前 50 个 + WARN 日志**

**涉及文件**：
- `src/main/java/com/jiang/service/impl/ErrorQuestionServiceImpl.java`（续 T-04a）
- `src/test/java/com/jiang/service/ErrorQuestionServiceTest.java`（续 T-04a，补充查询测试）

**依赖**：T-00（实体/Mapper/枚举）、T-02（VO）、T-03（接口）

**预估工作量**：0.75 天

> **IMP-H 修复说明**：T-04 拆分为 T-04a（写操作，1.5 天）+ T-04b（查询操作，0.75 天）。T-04b 可与 T-05 并行（T-05 仅依赖写操作）。

---

### T-05：掌握度联动集成 ✅

**任务描述**：
- 修改 `MasteryServiceImpl`：
  - 注入 `ErrorQuestionService`
  - 在 `batchFlushStates` 方法末尾，遍历 stateCache 调用 `errorQuestionService.syncPauseStatus`
  - 在 `updateMasteryFromResult` 方法末尾，同样调用 `syncPauseStatus`
  - 确保在 `@Transactional` 内执行（现有事务覆盖）
- **IMP-3 修复**：编写单元测试，验证：
  - 掌握度 ≥ 0.8 时错题暂停
  - 掌握度 < 0.8 时错题重新入队
  - 事务一致性
  - **同一 question 在同一批次既被推进又被暂停**（advanceStage → syncPauseStatus 最终状态为 paused）
- **BLOCK-B 修复**：增加测试用例：
  - **非 redo 答错 + 掌握度 ≥ 0.8 → recordWrongAnswer 后 syncPauseStatus 入队即暂停**
- **IMP-M 修复**：增加测试用例：
  - **同批次 3 道错题入队 + 掌握度从 0.75→0.82 → 3 道全部 paused**
- **IMP-E 修复**：测试说明标注"本 Change 仅测试同事务内顺序，不测试跨事务并发（TD-CROSS-TX-CONSISTENCY）"

**涉及文件**：
- `src/main/java/com/jiang/service/impl/MasteryServiceImpl.java`（修改）
- `src/test/java/com/jiang/service/MasteryServiceTest.java`（修改，补充联动测试）

**依赖**：T-04a（ErrorQuestionServiceImpl 写操作）

**预估工作量**：0.5 天

---

### T-06：每日作业集成错题 + 批改触发入队 ✅

**任务描述**：

**Part A: generateDailyHomework 集成（读错题）**
- **BLOCK-A 修复**：明确截断策略 + Agent 输入语义
- 修改 `HomeworkServiceImpl.generateDailyHomework`：
  - 注入 `ErrorQuestionService`
  - 在 Agent 调用前查询到期错题：`errorQuestionService.getDueErrorQuestions(studentId, tenantId, planDate)`
    - 内部已按 knowledge_point_id 分组，每组 ≤ 3 道（截断策略在 Service 层完成）
  - 将到期错题组装为 `DailyPlanQuestionRef`（taskType=redo, questionSource=bank）
  - **BLOCK-A 语义明确**：计算剩余配额 = 15（日上限）- 错题总数，将"已占用配额的错题 questionId 列表"传递给 Agent
    - Agent 为剩余配额生成新题，避免与已占用 questionId 重复
    - **不是"避开整个知识点"，而是"避开已占用的具体题目"**
  - Agent 返回新题后，合并错题引用 + 新题引用 → daily_plan.content

**Part B: gradeHomework 集成（写错题 + 推进错题）**
- **BLOCK-E 修复**：批改是错题入队的唯一触发点
- **BLOCK-E 修复**：批次内按 questionId 去重（IMP-Q: redo 优先策略 + IMP-W: 多条 redo 取最后一条）
- **IMP-8 修复**：错题推进逻辑插入在步骤 5（收集 submissionId）之后、步骤 8（更新 status=completed）之前
- 修改 `HomeworkServiceImpl.gradeHomework`：
  ```
  // IMP-W 修复：submissions 排序保证“最后一条 redo”语义稳定
  // IMP-AD 修复：优先按 create_time ASC, submission_id ASC 排序（避免雪花算法 submissionId 非严格递增）
  submissions.sort(Comparator.comparing(
      HomeworkSubmissionDetail::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(HomeworkSubmissionDetail::getSubmissionId));
  // IMP-Q 修复：LinkedHashMap + redo 优先去重
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
      isCorrect = submission.getIsCorrect() == 1;
      isRedo = "redo".equals(submission.getQuestionType());
      
      if (isRedo) {
          // 错题重做：答对 → advanceStage(correctDate); 答错 → resetStage(wrongDate)
          if (isCorrect) advanceStage(studentId, tenantId, questionId, gradeDate);
          else resetStage(studentId, tenantId, questionId, gradeDate);
      } else {
          // 非 redo 题答错 → recordWrongAnswer 入队
          if (!isCorrect) recordWrongAnswer(studentId, tenantId, questionId, kpId,
              ErrorQuestionSourceType.HOMEWORK, submission.getSubmissionId(), gradeDate);
      }
  }
  // 之后继续：updateMasteryFromHomework（触发 syncPauseStatus）→ updateStatus=completed
  ```
- 编写单元测试，验证：
  - 生成作业时包含到期错题（taskType=redo）
  - **BLOCK-A 修复**：**到期错题数 > 单知识点配额（3 题）时的截断行为**
  - **BLOCK-A 修复**：**Agent 输入语义验证——传入 questionId 列表（非知识点列表），新题避开已占用题目**
  - 批改后非 redo 题答错 → 入队（recordWrongAnswer 被调用）
  - 批改后 redo 题答对 → advanceStage
  - 批改后 redo 题答错 → resetStage
  - **幂等性：status 已 completed 时不重复推进**
  - **IMP-Q 修复**：**同一 questionId 同时出现 redo 和 non-redo submission → redo 优先处理**
  - **IMP-W 修复**：**同一 questionId 两条 redo（一答对一答错）→ 取最后一条（按 create_time ASC, submissionId ASC）**
  - **IMP-AD 修复**：**submissionId 非严格递增时（create_time 优先排序）→ 排序结果仍稳定（前提：已确认 HomeworkSubmissionDetail 继承 BaseEntity，createTime 由 MyBatis-Plus FieldFill.INSERT 自动填充）**

**涉及文件**：
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`（修改）
- `src/test/java/com/jiang/service/HomeworkServiceTest.java`（修改，补充错题集成测试）

**依赖**：T-04a（ErrorQuestionServiceImpl 写操作）、T-04b（查询操作）

**预估工作量**：1.5 天

---

### T-07：实现 ErrorQuestionController ✅

**任务描述**：
- 新增 `ErrorQuestionController`，提供 2 个学生端接口：
  - `GET /api/error-questions`：错题列表分页查询（支持 knowledgePointId/status 筛选）
    - **IMP-F 修复**：参数校验 page < 1 → 置 1；size > 100 → 截断至 100；size < 1 → 置 1
  - `GET /api/error-questions/statistics`：错题统计
- 所有接口从 `StudentContext` 提取 studentId/tenantId
- 所有接口返回 `Result<T>` 统一响应
- **IMP-F 修复**：`PageResult` 复用项目既有 `com.jiang.common.result.PageResult<T>`（字段：records/total/page/size）
- 编写 Controller 单元测试，覆盖：
  - 正常分页查询
  - **IMP-F 修复**：**page=0/-1、size=0/101 的边界测试**
  - 统计查询

**涉及文件**：
- `src/main/java/com/jiang/controller/ErrorQuestionController.java`（新增）
- `src/test/java/com/jiang/controller/ErrorQuestionControllerTest.java`（新增）

**依赖**：T-03（Service 接口）、T-02（VO）

**预估工作量**：0.5 天

---

### T-08：集成测试 ✅

**实际产出**：
- `ErrorQuestionControllerTest.java`（10 个测试用例）：覆盖正常分页、知识点筛选、IMP-F 边界（page=0/-1、size=0/101）、未登录 401、统计查询、BLOCK-C 零值
- `ErrorQuestionServiceTest.java`（26 个测试用例）：覆盖 recordWrongAnswer（首次/重复/completed 重激活/BLOCK-F）、advanceStage（1→2/5→completed/completed 拒绝/并发冲突）、resetStage（正常/completed 拒绝/IMP-L 幂等）、syncPauseStatus（暂停/激活/边界 0.8）、getDueErrorQuestions（空/正常/IMP-AG 顺序/BLOCK-I 空过滤/IMP-AH 截断）、getErrorQuestions（IMP-F 边界/IMP-G 兜底）、getStatistics（正常/零值/null 兜底）

**全量测试**：320 tests, 0 failures, 0 errors, 5 skipped（既有）

**依赖**：T-05, T-06, T-07

**预估工作量**：1 天

---

## 依赖关系图

> **IMP-H 修复**：标注关键路径（粗体）。

```
T-00（实体类 + Mapper + 枚举 + EbbinghausStageTest）
T-01（V3 DDL 脚本 + 索引）
  ⇅ （并行，无依赖关系）
  ↓
T-02（VO 类）
  ↓
T-03（Service 接口）
  ↓
**T-04a（写操作 Service 实现 + 单元测试）**  ← 关键路径
  ↓
T-04b（查询操作 Service 实现 + 单元测试）  ← 可与 T-05 并行
T-05（掌握度联动集成）← T-04a
T-06（每日作业集成 + 批改触发入队）← T-04a, T-04b
  ↓
T-07（Controller）← T-02, T-03
  ↓
T-08（集成测试）← T-05, T-06, T-07
```

---

## 工作量汇总

| Task | 预估工作量 |
|------|----------|
| T-00 | 1 天 |
| T-01 | 0.75 天 |
| T-02 | 0.25 天 |
| T-03 | 0.25 天 |
| T-04a | 1.5 天 |
| T-04b | 0.75 天 |
| T-05 | 0.5 天 |
| T-06 | 1.5 天 |
| T-07 | 0.5 天 |
| T-08 | 1 天 |
| **总计** | **8.0 天** |

> **IMP-H 修复**：T-04 拆分为 T-04a（1.5 天）+ T-04b（0.75 天），总计由原 2 天调整为 2.25 天，更贴合实际复杂度。

---

## 执行顺序建议

**第一阶段（基础设施）**：
- T-00 → T-01 → T-02（并行：T-01 与 T-00 无依赖）

**第二阶段（核心 Service）**：
- T-03 → T-04a（**关键路径**）

**第三阶段（查询 + 并行集成）**：
- T-04b + T-05（可并行，T-04b 依赖 T-04a，T-05 仅依赖 T-04a）

**第四阶段（作业集成）**：
- T-06（依赖 T-04a + T-04b）

**第五阶段（Controller + 测试）**：
- T-07 → T-08

---

## 技术债务登记（与 design.md §7 对齐）

| 编号 | 描述 | 登记位置 |
|------|------|----------|
| TD-PAUSE-OSCILLATION | 掌握度 0.8 附近震荡导致错题反复暂停/激活 | design.md §2.2 |
| TD-CROSS-TX-CONSISTENCY | 跨事务的掌握度更新与错题状态不保证强一致 | design.md §2.2 |
| TD-ORPHAN-ERROR-QUESTION | 题目删除后错题记录成为孤儿 | design.md §2.5 |
| TD-SOURCE-ID-SEMANTICS | ASSESSMENT/EXAM 来源的 sourceId 映射规则未定义 | design.md §2.6 |
| **TD-COMPLETED-HISTORY** | completed_date 在重新激活时清空，历史完成事实不可追溯 | design.md §7 |
| **TD-PAGINATION-FILESORT** | selectPageWithDetail 的 ORDER BY first_wrong_date 无法被索引覆盖 | design.md §7 |
| **TD-GENERATE-IDEMPOTENCY** | generateDailyHomework 重复调用时错题集合可能因漂移不同 | design.md §2.4.1 |

---

**任务制定人**：AI Agent  
**审核状态**：待人工复审