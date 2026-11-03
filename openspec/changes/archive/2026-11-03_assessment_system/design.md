# Design：专项评测系统

> **Change 名称**：`2026-11-03_assessment_system`  
> **创建日期**：2026-11-03  
> **状态**：草案 v4（修复 BLOCK-7 + IMP-12~14）

---

## 1. 技术方案概述

本次 Change 实现专项评测完整闭环：学生选科目 → 系统从题库抽题 → 学生作答 → 系统批改 → 掌握度更新 → 错题入队 → 历史查询。

核心技术方案：
- **出题策略**：按知识点从 `question_bank` 随机抽题，每知识点 3 道（basic/standard/challenge 各 1 道，不足则随机补齐）
- **批改策略**：按 `question_bank.type` 分流比对（详见 §4.2 步骤 3）；未作答判定为错（`isCorrect=0`）；仅支持客观题（choice/numeric）
- **掌握度更新**：逐题调用 `MasteryService.updateMasteryFromResult`，`W_source = 0.7`（ChangeType.ASSESSMENT）
- **错题入队**：答错题目调用 `ErrorQuestionService.recordWrongAnswer`，`sourceType = ASSESSMENT`，`sourceId = assessmentId`
- **状态机**：`pending`（出题完成）→ `graded`（批改完成），一次性提交，不支持中途中断

> **BLOCK-3/6/7 存量数据说明**：Demo 阶段 `assessment_result` 表为空（V1 DDL 建表后无业务写入），无需数据迁移。subject_id 和 status 均为业务必填字段，不设 DEFAULT，由 Service 显式写入。若表非空，需先执行回填 SQL（见 §2.1.1）。

**前置依赖**：`error_question_management` Change 已完成（错题入队接口可用）。

---

## 2. 数据库变更（V5 DDL）

### 2.1 assessment_result 表变更

```sql
-- V5__assessment_enhancement.sql
-- BLOCK-6/7 修复：subject_id 和 status 均为业务必填字段，不设 DEFAULT。
-- 执行前确认：SELECT COUNT(*) FROM assessment_result = 0
-- 若 > 0，改用 §2.1.1 的两步回填方案（本脚本不可直接执行）。

-- 1. 新增 subject_id 列（表为空，直接 NOT NULL 无 DEFAULT）
ALTER TABLE assessment_result
    ADD COLUMN `subject_id` BIGINT NOT NULL COMMENT '科目 ID（关联 subject.subject_id）'
    AFTER `student_id`;

-- 2. 新增 status 列（BLOCK-7 修复：无 DEFAULT，由 Service 显式写入 'pending'）
ALTER TABLE assessment_result
    ADD COLUMN `status` VARCHAR(20) NOT NULL COMMENT '状态：pending / graded'
    AFTER `total_score`;

-- 3. 存量数据回填（表为空时无需执行）
-- UPDATE assessment_result SET status = 'graded' WHERE assessed_at IS NOT NULL;

-- 4. assessed_at 改为可空（批改完成时才写入）
ALTER TABLE assessment_result
    MODIFY COLUMN `assessed_at` DATETIME DEFAULT NULL COMMENT '批改完成时间';

-- 5. 新增索引
ALTER TABLE assessment_result
    ADD INDEX `idx_student_subject` (`tenant_id`, `student_id`, `subject_id`),
    ADD INDEX `idx_status` (`tenant_id`, `status`);
```

#### 2.1.1 存量数据回填（仅表非空时执行，替代 §2.1 主脚本）

```sql
-- 步骤 A：先加可空列，避免 NOT NULL 无 DEFAULT 导致 ALTER 失败
ALTER TABLE assessment_result ADD COLUMN subject_id BIGINT NULL AFTER student_id;
ALTER TABLE assessment_result ADD COLUMN status VARCHAR(20) NULL AFTER total_score;
-- 步骤 B：回填 subject_id（从关联的 subject 表映射，按科目名称匹配）
-- UPDATE assessment_result a JOIN subject s ON ... SET a.subject_id = s.subject_id;
-- 步骤 C：回填 status（存量已批改数据）
UPDATE assessment_result SET status = 'graded'
WHERE assessed_at IS NOT NULL;
-- 存量未批改数据（assessed_at IS NULL）需删除或补批改，否则无法改为 NOT NULL
-- DELETE FROM assessment_result WHERE status IS NULL;
-- 步骤 D：改为 NOT NULL（回填完成后执行）
ALTER TABLE assessment_result MODIFY COLUMN subject_id BIGINT NOT NULL;
ALTER TABLE assessment_result MODIFY COLUMN status VARCHAR(20) NOT NULL;
-- 步骤 E：assessed_at 改为可空 + 新增索引（同 §2.1 步骤 4-5）
```

### 2.2 新增 assessment_submission_detail 表

```sql
CREATE TABLE IF NOT EXISTS `assessment_submission_detail` (
    `detail_id`         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '答题明细 ID',
    `assessment_id`     BIGINT        NOT NULL COMMENT '所属评测 ID',
    `student_id`        BIGINT        NOT NULL COMMENT '学生 ID',
    `question_id`       BIGINT        NOT NULL COMMENT '题目 ID（关联 question_bank）',
    `knowledge_point_id` VARCHAR(64)  NOT NULL COMMENT '关联知识点',
    `student_answer`    TEXT          DEFAULT NULL COMMENT '学生答案',
    `is_correct`        TINYINT       NOT NULL DEFAULT 0 COMMENT '是否正确：0=错误, 1=正确',
    `difficulty`        VARCHAR(20)   NOT NULL COMMENT '题目难度（冗余）：basic/standard/challenge',
    `submitted_at`      DATETIME      NOT NULL COMMENT '提交时间',
    `tenant_id`         VARCHAR(50)   NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`detail_id`),
    INDEX `idx_assessment` (`tenant_id`, `assessment_id`),
    INDEX `idx_student_kp` (`tenant_id`, `student_id`, `knowledge_point_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='评测答题明细表（只读不删）';
```

### 2.3 回滚方案

```sql
-- 回滚前确认：assessment_result 中 status='pending' 且 assessed_at IS NULL 的记录需先删除（回滚后 assessed_at 恢复 NOT NULL 约束）
-- DELETE FROM assessment_result WHERE status = 'pending' AND assessed_at IS NULL;
-- DROP TABLE IF EXISTS assessment_submission_detail;
-- ALTER TABLE assessment_result DROP INDEX idx_student_subject;
-- ALTER TABLE assessment_result DROP INDEX idx_status;
-- ALTER TABLE assessment_result MODIFY COLUMN assessed_at DATETIME NOT NULL;
-- ALTER TABLE assessment_result DROP COLUMN status;
-- ALTER TABLE assessment_result DROP COLUMN subject_id;
```

---

## 3. 实体变更

### 3.1 AssessmentResult（修改）

```java
@Data
@TableName(value = "assessment_result", autoResultMap = true)
public class AssessmentResult extends BaseEntity {
    @TableId("assessment_id")
    private Long assessmentId;

    private Long studentId;
    private Long subjectId;              // 【新增】科目 ID

    @TableField(typeHandler = JacksonTypeHandler.class)
    private String knowledgePointIds;    // 涉及的知识点 ID 列表（JSON 数组）

    private BigDecimal score;            // 得分（批改后写入）
    private BigDecimal totalScore;       // 总分（批改后写入）
    private String status;               // 【新增】pending / graded

    @TableField(typeHandler = JacksonTypeHandler.class)
    private String results;              // 详细结果 JSON（创建时写入题目，批改时追加答案）

    private LocalDateTime assessedAt;    // 【改为可空】批改完成时间
}
```

### 3.2 AssessmentSubmissionDetail（新建）

```java
@Data
@TableName("assessment_submission_detail")
public class AssessmentSubmissionDetail extends BaseEntity {
    @TableId("detail_id")
    private Long detailId;

    private Long assessmentId;
    private Long studentId;
    private Long questionId;
    private String knowledgePointId;
    private String studentAnswer;
    private Integer isCorrect;
    private String difficulty;           // basic / standard / challenge
    private LocalDateTime submittedAt;
}
```

---

## 4. 分层设计

### 4.1 Controller 层

新增 `AssessmentController`（学生端）：

```java
@RestController
@RequestMapping("/api/student/assessment")
@Tag(name = "专项评测", description = "学生端专项评测接口")
public class AssessmentController {

    @PostMapping
    @Operation(summary = "创建评测（系统自动出题）")
    public Result<AssessmentVO> createAssessment(@RequestBody @Valid CreateAssessmentRequest request);

    @GetMapping("/{assessmentId}")
    @Operation(summary = "查看评测详情（含题目内容，不含答案）")
    public Result<AssessmentDetailVO> getAssessmentDetail(@PathVariable Long assessmentId);

    @PostMapping("/{assessmentId}/submit")
    @Operation(summary = "提交评测答案（一次性提交，触发批改）")
    public Result<AssessmentResultVO> submitAssessment(
            @PathVariable Long assessmentId,
            @RequestBody @Valid SubmitAssessmentRequest request);

    @GetMapping
    @Operation(summary = "评测历史列表（分页）")
    public Result<PageResult<AssessmentHistoryVO>> getAssessmentHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size);
}
```

### 4.2 Service 层

新增 `AssessmentService` 接口与 `AssessmentServiceImpl`：

```java
public interface AssessmentService {
    /**
     * 创建评测：选知识点 → 抽题 → 写入 assessment_result（status=pending）
     * BLOCK-1：知识点数量上限 5 个，每知识点 3 道题，总计最多 15 道
     */
    AssessmentVO createAssessment(Long studentId, String tenantId, Long subjectId);

    /**
     * 查看评测详情（含题目内容，不含答案）
     * BLOCK-2：归属校验——studentId 必须匹配
     */
    AssessmentDetailVO getAssessmentDetail(Long studentId, String tenantId, Long assessmentId);

    /**
     * 提交答案：批改 → 更新 assessment_result（status=graded）→ 掌握度更新 → 错题入队
     * BLOCK-3：状态机校验——仅 pending 状态可提交
     * BLOCK-4：幂等保护——已 graded 不可重复提交
     */
    AssessmentResultVO submitAndGrade(Long studentId, String tenantId,
                                       Long assessmentId, List<AnswerItem> answers);

    /**
     * 评测历史分页查询
     */
    PageResult<AssessmentHistoryVO> getAssessmentHistory(Long studentId, String tenantId,
                                                          int page, int size);
}
```

#### createAssessment 方法

```
1. 校验 subjectId 对应科目存在（否则抛 SUBJECT_NOT_FOUND）
2. 查询该科目下所有 active 知识点（KnowledgePointMapper.selectList）
   - 无 active 知识点 → 抛 SUBJECT_NO_ACTIVE_KP
   - BLOCK-5 修复：知识点数量 > 5 时，按学生掌握度 ASC 排序取最低的 5 个
     （无掌握度记录的知识点视为 mastery=0，优先选中）
     IMP-8 修复：查询方式采用 MyBatis-Plus QueryWrapper：
     new QueryWrapper<StudentKnowledgeState>()
         .eq("student_id", studentId)
         .eq("tenant_id", tenantId)
         .in("knowledge_point_id", kpIds)
     基于 StudentKnowledgeStateMapper.selectList(queryWrapper)（BaseMapper 内置方法，无需新增）
     若全部无记录，回退为 knowledge_point_id 字典序（稳定）
     IMP-12 补充：若所有 active 知识点掌握度均 ≥ 0.8（无薄弱点），
     仍按 ASC 取前 5 个（相对最薄弱），但在 AssessmentVO 返回 warning 字段：
     "当前知识点掌握良好，评测可能偏简单"
3. 对每个知识点，从 question_bank 抽 3 道题（AssessmentQuestionSelector.select）：
   - 优先 basic/standard/challenge 各 1 道
   - 不足 3 道时随机补齐
   - 该知识点无题 → 跳过该知识点（WARN 日志）
4. 所有知识点均无题 → 抛 QUESTION_NOT_FOUND（题库不足）
5. 构造 AssessmentResult：
   - studentId, subjectId, tenantId
   - knowledgePointIds = 实际选中的知识点 ID 列表（JSON）
   - results = 题目详情 JSON（含 questionId, knowledgePointId, content, choices, difficulty, type, answer）
     注意：answer 字段仅用于批改比对，getAssessmentDetail 返回时必须剔除（BLOCK-4）
   - status = "pending"
   - score/totalScore/assessedAt = null
6. INSERT assessment_result → 返回 AssessmentVO
```

#### submitAndGrade 方法

```
1. 查询 assessment_result（归属校验 + 状态校验）
   - 不存在 → 抛 NOT_FOUND
   - studentId 不匹配 → 抛 STUDENT_IDENTITY_MISMATCH
   - status = 'graded' → 抛 ASSESSMENT_ALREADY_GRADED（幂等保护）
   - status != 'pending' → 抛 ASSESSMENT_INVALID_STATUS
2. 从 results JSON 解析题目列表
3. BLOCK-1 修复：逐题批改（按 question_bank.type 分流比对）：
   - 匹配 questionId → 从 question_bank 查正确答案
   - 未作答判定：studentAnswer 为 null 或空字符串 → isCorrect=0（判错，计入错题入队）
   - choice 类型：答案转大写 + 去首尾空格 + 字符排序（"ba" → "AB"），再比对
     IMP-13 补充：假设选项为单个大写字母（A-Z），字符排序仅适用于多选（如 'BA' → 'AB'）；
     若答案含非字母字符（如 "A1"、"10"），退化为字符串比对（忽略大小写+首尾空格）
   - numeric 类型：尝试解析为 BigDecimal 比对（"0.5" = "0.50"）；
     解析失败（如 "1/2"）→ 判错（isCorrect=0），WARN 日志
     IMP-10 补充：题库录入规范要求 numeric 题的 answer 必须为纯数字（整数/小数/科学计数法），
     不允许含单位或特殊字符；学生答案同样适用此规则
   - 其他类型（short/self）：字符串比对（忽略首尾空格 + 不区分大小写）
   - 记录 isCorrect（0/1）
4. 计算得分：
   - totalScore = 题目总数（每题 1 分）
   - score = 正确题数
5. 构造 results JSON（追加 answers 和 statistics）
6. 批量插入 assessment_submission_detail（IMP-3：使用 MyBatis-Plus saveBatch，15 道题量级可接受）
7. 更新 assessment_result：
   - score, totalScore, results（含答案）, status = 'graded', assessedAt = now
   - WHERE assessment_id = #{id} AND status = 'pending'（状态机并发保护）
   - affected rows = 0 → 抛 ASSESSMENT_CONCURRENT_MODIFIED
8. BLOCK-2 修复：错题入队（先于掌握度更新，确保新错题可被后续 syncPauseStatus 暂停）：
   - 对每道 isCorrect=0 的题调用 ErrorQuestionService.recordWrongAnswer(
       studentId, tenantId, questionId, knowledgePointId,
       ErrorQuestionSourceType.ASSESSMENT, assessmentId, LocalDate.now())
9. IMP-4 修复：掌握度更新（逐题，按 results JSON 中的题目顺序，稳定可复现）：
   - 对每道题调用 MasteryService.updateMasteryFromResult(
       studentId, tenantId, knowledgePointId,
       ChangeType.ASSESSMENT, assessmentId,
       isCorrect == 1, dNorm)
   - dNorm 映射：basic=0.3, standard=0.6, challenge=0.9（来自 question_bank.difficulty）
   - 同一知识点 3 道题连续更新，最终掌握度取决于更新顺序（已知限制，登记 TD-ASSESSMENT-ORDER-SENSITIVE）
10. 返回 AssessmentResultVO
```

> **BLOCK-6 事务边界**：步骤 6~9 在同一 `@Transactional(rollbackFor = Exception.class)` 内执行。
> 若掌握度更新或错题入队失败，整个批改回滚，assessment_result 保持 pending 状态。

> **BLOCK-2 时序说明**：步骤 8（错题入队）先于步骤 9（掌握度更新）执行。
> 这样每次 `updateMasteryFromResult` 内部触发 `syncPauseStatus` 时，新错题已在队列中（status=active），
> 若掌握度 ≥ 0.8 则新错题也被暂停——与错题管理 Change 的 BLOCK-B（“入队即暂停”）语义一致。

> **BLOCK-7 掌握度更新顺序**：按 results JSON 中的题目顺序逐题更新。
> 每道题独立调用 `updateMasteryFromResult`（内部有 `@Transactional`，因 propagation=REQUIRED 加入当前事务）。
> 同一知识点的 3 次 `syncPauseStatus` 中间状态可能反复，最终状态以最后一次为准。

### 4.3 getAssessmentDetail 方法

```
1. 查询 AssessmentResult → 不存在抛 ASSESSMENT_NOT_FOUND
2. 归属校验：studentId 不匹配 → 抛 STUDENT_IDENTITY_MISMATCH
3. BLOCK-4 修复：解析 results JSON 时，使用字段白名单显式映射到 AssessmentQuestionVO：
   白名单字段：questionId, knowledgePointId, content, choices, difficulty, type
   禁止映射字段：answer, analysis（防止答案泄露）
   IMP-9 修复：实现方式为：
   a) 解析 results JSON 为 List<Map<String, Object>>（或中间 DTO ResultQuestionItem）
   b) 遍历每个 Map，仅提取白名单字段构造 AssessmentQuestionVO
   c) answer/analysis 字段不读取、不设置
   禁止整体 JSON 透传（如 @JsonIgnoreProperties 方式），避免字段名变更时意外泄露
   IMP-14 补充：假设 question_bank.choices 为纯文本数组（如 ["A. 1", "B. 2", "C. 3"]），
   不含 isCorrect 等敏感标记；答案独立存于 answer 字段。若题库结构变化，需重新评估白名单。
4. 查询 Subject 获取 subjectName
5. 查询 KnowledgePoint 获取各知识点名称
6. 返回 AssessmentDetailVO（无论 pending 还是 graded，均不含答案）
```

> **IMP-5 说明**：getAssessmentDetail 始终不含答案。已 graded 评测的答案查看通过
> AssessmentResultVO 的 `questionsWithAnswers` 字段返回（仅 graded 时非空），
> 该字段在 submitAndGrade 返回时填充，不在 detail 接口中暴露。

### 4.4 getAssessmentHistory 方法

```
1. 分页查询 AssessmentResult（WHERE student_id + tenant_id，ORDER BY create_time DESC）
2. IMP-6 修复：返回所有状态（含 pending 和 graded），前端根据 status 字段区分展示：
   - pending → 显示“未完成”标记，score/totalScore/assessedAt 为 null
   - graded → 显示得分和批改时间
3. 转换为 AssessmentHistoryVO 列表
4. 返回 PageResult<AssessmentHistoryVO>
```

### 4.5 DAO 层

#### AssessmentResultMapper（扩展）

```java
@Mapper
public interface AssessmentResultMapper extends BaseMapper<AssessmentResult> {

    /**
     * 状态机更新：pending → graded
     * WHERE status = 'pending' 保证并发安全
     * IMP-2 修复：results 参数为 String 类型（已序列化的 JSON 字符串），
     * 调用方负责用 ObjectMapper 序列化，不经过 JacksonTypeHandler
     */
    @Update("UPDATE assessment_result SET " +
            "score = #{score}, total_score = #{totalScore}, " +
            "results = #{results}, status = 'graded', " +
            "assessed_at = #{assessedAt}, update_time = NOW() " +
            "WHERE assessment_id = #{assessmentId} AND tenant_id = #{tenantId} " +
            "AND status = 'pending'")
    int gradeWithCheck(@Param("assessmentId") Long assessmentId,
                       @Param("tenantId") String tenantId,
                       @Param("score") BigDecimal score,
                       @Param("totalScore") BigDecimal totalScore,
                       @Param("results") String results,
                       @Param("assessedAt") LocalDateTime assessedAt);
}
```

#### AssessmentSubmissionDetailMapper（新建）

```java
@Mapper
public interface AssessmentSubmissionDetailMapper extends BaseMapper<AssessmentSubmissionDetail> {
    // 使用 MyBatis-Plus 默认 CRUD，无需自定义 SQL
}
```

#### QuestionBankMapper（扩展）

```java
/**
 * 查询指定知识点的有效题目（用于出题）
 * 索引命中：idx_kp_difficulty (knowledge_point_id, tenant_id, difficulty)
 */
@Select("SELECT question_id, knowledge_point_id, content, choices, answer, " +
        "analysis, difficulty, type, tenant_id, create_time, update_time " +
        "FROM question_bank " +
        "WHERE knowledge_point_id = #{kpId} AND tenant_id = #{tenantId} " +
        "ORDER BY difficulty ASC, question_id ASC")
List<QuestionBank> selectActiveByKp(@Param("kpId") String kpId,
                                     @Param("tenantId") String tenantId);
```

### 4.6 出题工具类

```java
/**
 * 专项评测出题选择器
 * 策略：每知识点 3 道题，优先 basic/standard/challenge 各 1 道，不足随机补齐
 * IMP-1 修复：接受可注入的 Random 实例，测试时注入固定种子保证可复现
 */
public final class AssessmentQuestionSelector {

    public static final int QUESTIONS_PER_KP = 3;
    public static final int MAX_KPS_PER_ASSESSMENT = 5;

    /**
     * 从候选题目中选取指定数量的题目（使用默认 Random）
     */
    public static List<QuestionBank> select(List<QuestionBank> candidates, int count) {
        return select(candidates, count, new Random());
    }

    /**
     * IMP-1 修复：可注入 Random 实例，测试时传入固定种子 new Random(42) 保证可复现
     * @param candidates 按难度排序的候选题目列表
     * @param count      需选取数量（默认 3）
     * @param random     随机数生成器（测试时注入固定种子）
     * @return 选中的题目列表
     * @throws IllegalArgumentException 候选题目不足
     */
    public static List<QuestionBank> select(List<QuestionBank> candidates, int count, Random random) {
        // 1. 按 difficulty 分组
        // 2. 优先从 basic/standard/challenge 各取 1 道（使用 random 选择）
        // 3. 不足 count 道时，从剩余题目使用 random 随机补齐
        // 4. 仍不足 → 返回已选题目（调用方判断是否满足最低要求）
    }
}
```

---

## 5. DTO / VO 设计

### 5.1 请求 DTO

```java
@Data
public class CreateAssessmentRequest {
    @NotNull(message = "科目 ID 不能为空")
    private Long subjectId;
}

@Data
public class SubmitAssessmentRequest {
    @NotEmpty(message = "答案列表不能为空")
    @Valid
    private List<AnswerItem> answers;
}

@Data
public class AnswerItem {
    @NotNull(message = "题目 ID 不能为空")
    private Long questionId;
    private String answer;   // 学生答案，允许为空（表示未作答）
}
```

### 5.2 响应 VO

```java
@Data @Builder
public class AssessmentVO {
    private Long assessmentId;
    private Long subjectId;
    private String subjectName;
    private List<String> knowledgePointIds;
    private Integer questionCount;
    private String status;
    private String warning;               // IMP-12 新增：无薄弱点时的提示信息
    private LocalDateTime createdAt;
}

@Data @Builder
public class AssessmentDetailVO {
    private Long assessmentId;
    private Long subjectId;
    private String subjectName;
    private String status;
    private List<AssessmentQuestionVO> questions;   // 题目列表（不含答案）
    private Integer totalQuestions;
    private LocalDateTime createdAt;
}

@Data @Builder
public class AssessmentQuestionVO {
    private Long questionId;
    private String knowledgePointId;
    private String knowledgePointName;
    private String content;              // 题干
    private List<String> choices;        // 选项（选择题）
    private String difficulty;
    private String type;                 // numeric / choice
}

@Data @Builder
public class AssessmentResultVO {
    private Long assessmentId;
    private BigDecimal score;
    private BigDecimal totalScore;
    private String status;
    private List<KpAccuracyVO> knowledgePointResults;
    // IMP-5 修复：graded 时返回含答案的题目列表，pending 时为 null
    private List<AssessmentQuestionWithAnswerVO> questionsWithAnswers;
    private LocalDateTime assessedAt;
}

@Data @Builder
public class AssessmentQuestionWithAnswerVO {
    private Long questionId;
    private String knowledgePointId;
    private String content;
    private String difficulty;
    private String type;
    private String studentAnswer;      // 学生答案
    private String correctAnswer;     // 正确答案（仅 graded 时填充）
    private boolean isCorrect;         // 是否正确
    private String analysis;       // 解析（仅 graded 时填充）
}

@Data @Builder
public class KpAccuracyVO {
    private String knowledgePointId;
    private String knowledgePointName;
    private Integer totalQuestions;
    private Integer correctCount;
    private BigDecimal accuracy;         // 正确率 0.00~1.00
}

@Data @Builder
public class AssessmentHistoryVO {
    private Long assessmentId;
    private Long subjectId;
    private String subjectName;
    private BigDecimal score;
    private BigDecimal totalScore;
    private String status;
    private Integer questionCount;
    private LocalDateTime assessedAt;
    private LocalDateTime createdAt;
}
```

---

## 6. API 设计

| 方法 | 路径 | 说明 | 请求体 | 响应体 |
|------|------|------|--------|--------|
| POST | `/api/student/assessment` | 创建评测 | `CreateAssessmentRequest` | `Result<AssessmentVO>` |
| GET | `/api/student/assessment/{id}` | 评测详情 | — | `Result<AssessmentDetailVO>` |
| POST | `/api/student/assessment/{id}/submit` | 提交答案 | `SubmitAssessmentRequest` | `Result<AssessmentResultVO>` |
| GET | `/api/student/assessment` | 历史列表 | page, size | `Result<PageResult<AssessmentHistoryVO>>` |

---

## 7. 异常处理

| 异常场景 | 错误码 | 编号 | HTTP 状态 |
|----------|--------|------|-----------|
| 科目不存在 | `SUBJECT_NOT_FOUND` | 1090 | 404 |
| 科目下无已启用知识点 | `SUBJECT_NO_ACTIVE_KP` | 1091 | 400 |
| 题库不足（某知识点无题） | `QUESTION_NOT_FOUND` | 1020 | 400 |
| 评测不存在 | `ASSESSMENT_NOT_FOUND` | 1120 | 404 |
| 评测状态非法（非 pending 提交） | `ASSESSMENT_INVALID_STATUS` | 1121 | 400 |
| 评测已批改（重复提交） | `ASSESSMENT_ALREADY_GRADED` | 1122 | 400 |
| 评测并发冲突 | `ASSESSMENT_CONCURRENT_MODIFIED` | 1123 | 409 |
| 学生身份不匹配 | `STUDENT_IDENTITY_MISMATCH` | 1053 | 403 |

> 编号段：1120-1129 预留给专项评测。

---

## 8. 隐性约定对齐

| 约定 | 实现 |
|------|------|
| #1 统一响应 | Controller 全部返回 `Result<T>` |
| #2 异常处理 | Service 抛 `BusinessException`，Controller 不 try-catch |
| #3 分页参数 | 历史列表 `page` 从 1 开始，`size` 默认 10 |
| #4 时间格式 | 实体使用 `LocalDateTime`/`LocalDate` |
| #9 rollbackFor | `submitAndGrade` 使用 `@Transactional(rollbackFor = Exception.class)` |
| #10 事务边界 | 本次无外部 Agent 调用，可用 `@Transactional` 包裹整个方法 |
| #11 并发保护 | `gradeWithCheck` WHERE status = 'pending' 状态机校验 |
| #13 UNIQUE 索引 | 本次无新增唯一索引 |

---

## 9. 测试策略

### 9.1 单元测试

| 测试类 | 覆盖场景 |
|--------|----------|
| `AssessmentServiceTest` | 创建评测（正常/知识点不足/科目不存在/掌握度优先选点）、提交批改（正常/重复提交/状态非法/未作答判错）、掌握度联动、错题入队（BLOCK-2 时序验证）、答案泄露检查（BLOCK-4） |
| `AssessmentQuestionSelectorTest` | 均衡选题（basic/standard/challenge 各 1）、不足 3 道时随机补齐、无题目时抛异常、固定种子可复现（IMP-1） |
| `AssessmentControllerTest` | 接口入参校验、归属校验、状态码返回 |

### 9.2 集成测试

| 场景 | 验证点 |
|------|--------|
| 完整评测生命周期 | 创建 → 查看 → 提交 → 批改 → 掌握度更新 → 错题入队 → 历史查询 |
| 掌握度公式对齐 | W_source=0.7，D_norm 来自 question_bank.difficulty |
| 错题入队 | source_type='assessment'，source_id=assessment_id |
| 并发批改 | 同一评测并发提交 → 第二次抛 ASSESSMENT_CONCURRENT_MODIFIED |
| 归属校验 | 学生 A 查看学生 B 的评测 → 抛 STUDENT_IDENTITY_MISMATCH |
| 幂等保护 | 已 graded 评测重复提交 → 抛 ASSESSMENT_ALREADY_GRADED |
| 数据不可变 | 批改后 assessment_result 和 assessment_submission_detail 只读 |
| BLOCK-2 时序验证 | 掌握度从 0.7 升到 0.85 的评测 → 该知识点新入队错题也 paused |
| BLOCK-4 答案泄露 | getAssessmentDetail 返回的 VO 中无 answer/analysis 字段 |
| BLOCK-1 批改分流 | choice 类型大小写+排序比对、numeric 类型数值比对、未作答判错 |

---

## 10. 技术债务登记

| 编号 | 描述 | 登记原因 |
|------|------|----------|
| TD-ASSESSMENT-RANDOM-SELECTION | 出题为随机抽题，非 Agent 智能选题 | Demo 阶段可接受，后续引入 QuestionGenAgent |
| TD-ASSESSMENT-NO-PAUSE | 评测不支持中途中断与恢复 | 一次性提交，后续可扩展 |
| TD-ASSESSMENT-NO-DUPLICATE-CHECK | 同一知识点短时间内重复创建评测无限制 | Demo 阶段不限制，后续可加冷却期 |
| TD-ASSESSMENT-SUBJECT-KP-MAPPING | KnowledgePoint.subject 为字符串匹配，非外键关联 | 历史设计，后续可优化为 subject_id FK |
| TD-ASSESSMENT-ORDER-SENSITIVE | IMP-4：同一知识点 3 道题逐题更新掌握度，最终值取决于更新顺序 | 按 results JSON 顺序更新，稳定可复现；后续可优化为批量聚合更新 |

---

## 11. 文件清单

| 文件路径 | 操作 | 说明 |
|---------|------|------|
| `src/main/resources/db/V5__assessment_enhancement.sql` | 新建 | DDL 迁移脚本 |
| `src/main/java/com/jiang/model/entity/AssessmentResult.java` | 修改 | 新增 subjectId、status 字段 |
| `src/main/java/com/jiang/model/entity/AssessmentSubmissionDetail.java` | 新建 | 评测答题明细实体 |
| `src/main/java/com/jiang/mapper/AssessmentResultMapper.java` | 修改 | 新增 gradeWithCheck |
| `src/main/java/com/jiang/mapper/AssessmentSubmissionDetailMapper.java` | 新建 | 答题明细 Mapper |
| `src/main/java/com/jiang/mapper/QuestionBankMapper.java` | 修改 | 新增 selectActiveByKp |
| `src/main/java/com/jiang/common/util/AssessmentQuestionSelector.java` | 新建 | 出题选择器工具类 |
| `src/main/java/com/jiang/common/exception/ErrorCodes.java` | 修改 | 新增 1120-1123 错误码 |
| `src/main/java/com/jiang/controller/AssessmentController.java` | 新建 | 学生端评测 Controller |
| `src/main/java/com/jiang/service/AssessmentService.java` | 新建 | 评测 Service 接口 |
| `src/main/java/com/jiang/service/impl/AssessmentServiceImpl.java` | 新建 | 评测 Service 实现 |
| `src/main/java/com/jiang/dto/CreateAssessmentRequest.java` | 新建 | 创建评测请求 DTO |
| `src/main/java/com/jiang/dto/SubmitAssessmentRequest.java` | 新建 | 提交答案请求 DTO |
| `src/main/java/com/jiang/dto/AnswerItem.java` | 新建 | 单题答案 DTO |
| `src/main/java/com/jiang/vo/AssessmentVO.java` | 新建 | 评测概要 VO |
| `src/main/java/com/jiang/vo/AssessmentDetailVO.java` | 新建 | 评测详情 VO |
| `src/main/java/com/jiang/vo/AssessmentQuestionVO.java` | 新建 | 题目 VO |
| `src/main/java/com/jiang/vo/AssessmentResultVO.java` | 新建 | 批改结果 VO |
| `src/main/java/com/jiang/vo/AssessmentQuestionWithAnswerVO.java` | 新建 | 含答案的题目 VO（IMP-5） |
| `src/main/java/com/jiang/vo/KpAccuracyVO.java` | 新建 | 知识点正确率 VO |
| `src/main/java/com/jiang/vo/AssessmentHistoryVO.java` | 新建 | 历史列表 VO |
| `src/test/java/com/jiang/service/AssessmentServiceTest.java` | 新建 | Service 单元测试 |
| `src/test/java/com/jiang/common/util/AssessmentQuestionSelectorTest.java` | 新建 | 出题器单元测试 |
| `src/test/java/com/jiang/controller/AssessmentControllerTest.java` | 新建 | Controller 集成测试 |
