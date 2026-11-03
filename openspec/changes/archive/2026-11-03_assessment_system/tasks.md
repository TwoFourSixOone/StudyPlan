# Tasks：专项评测系统

> **Change 名称**：`2026-11-03_assessment_system`  
> **创建日期**：2026-11-03  
> **状态**：草案 v4（修复 BLOCK-7 + IMP-12~14）

---

## 任务总览

| 编号 | 任务 | 预估文件 | 依赖 |
|------|------|----------|------|
| T-00 | V5 DDL 脚本 + 实体变更 | V5__assessment_enhancement.sql, AssessmentResult.java, AssessmentSubmissionDetail.java | — | ✅ |
| T-01 | 错误码 + DTO/VO 数据结构 | ErrorCodes.java, CreateAssessmentRequest.java, SubmitAssessmentRequest.java, AnswerItem.java, AssessmentVO.java, AssessmentDetailVO.java, AssessmentQuestionVO.java, AssessmentResultVO.java, AssessmentQuestionWithAnswerVO.java, KpAccuracyVO.java, AssessmentHistoryVO.java | — | ✅ |
| T-02 | AssessmentQuestionSelector 出题工具类 | AssessmentQuestionSelector.java | T-00 | ✅ |
| T-03 | Mapper 扩展 | AssessmentResultMapper.java, AssessmentSubmissionDetailMapper.java, QuestionBankMapper.java | T-00 | ✅ |
| T-04 | AssessmentService 核心实现 | AssessmentService.java, AssessmentServiceImpl.java | T-01, T-02, T-03 | ✅ |
| T-05 | AssessmentController API 层 | AssessmentController.java | T-04 | ✅ |
| T-06 | 单元测试 | AssessmentServiceTest.java, AssessmentQuestionSelectorTest.java | T-04 | ✅ |
| T-07 | 集成测试 | AssessmentControllerTest.java | T-05 | ✅ |
| T-08 | 全量编译 + 测试验证 | — | T-06, T-07 | ✅ |

---

## T-00：V5 DDL 脚本 + 实体变更

### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/main/resources/db/V5__assessment_enhancement.sql` | 新建 |
| `src/main/java/com/jiang/model/entity/AssessmentResult.java` | 修改 |
| `src/main/java/com/jiang/model/entity/AssessmentSubmissionDetail.java` | 新建 |

### 任务描述

1. 创建 `V5__assessment_enhancement.sql`：
   - BLOCK-3/6/7 修复：Demo 阶段 assessment_result 表为空，无需数据迁移
   - `assessment_result` 新增 `subject_id` BIGINT NOT NULL（无 DEFAULT，业务必填，AFTER `student_id`）
   - `assessment_result` 新增 `status` VARCHAR(20) NOT NULL（无 DEFAULT，AFTER `total_score`，由 Service 显式写入 'pending'）
     （脚本头部注明：执行前确认 COUNT(*) = 0；若 > 0 改用 §2.1.1 回填方案）
   - `assessment_result` 修改 `assessed_at` 为可空（`MODIFY COLUMN assessed_at DATETIME DEFAULT NULL`）
   - `assessment_result` 新增索引 `idx_student_subject (tenant_id, student_id, subject_id)`
   - `assessment_result` 新增索引 `idx_status (tenant_id, status)`
   - 新建 `assessment_submission_detail` 表（字段见 design §2.2）
   - 脚本末尾附回滚方案（注释形式）

2. 修改 `AssessmentResult.java`：
   - 新增 `subjectId`（Long）字段
   - 新增 `status`（String）字段
   - `assessedAt` 类型保持 `LocalDateTime`（已为引用类型，天然可空）

3. 新建 `AssessmentSubmissionDetail.java`：
   - 字段：detailId, assessmentId, studentId, questionId, knowledgePointId, studentAnswer, isCorrect, difficulty, submittedAt
   - 继承 `BaseEntity`（含 tenantId, createTime, updateTime）

### 验收标准

- SQL 脚本语法正确，可在 MySQL 5.7 执行
- 实体字段与 DDL 列一一对应
- `@TableName`、`@TableId` 注解正确

---

## T-01：错误码 + DTO/VO 数据结构

### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/main/java/com/jiang/common/exception/ErrorCodes.java` | 修改 |
| `src/main/java/com/jiang/dto/CreateAssessmentRequest.java` | 新建 |
| `src/main/java/com/jiang/dto/SubmitAssessmentRequest.java` | 新建 |
| `src/main/java/com/jiang/dto/AnswerItem.java` | 新建 |
| `src/main/java/com/jiang/vo/AssessmentVO.java` | 新建 |
| `src/main/java/com/jiang/vo/AssessmentDetailVO.java` | 新建 |
| `src/main/java/com/jiang/vo/AssessmentQuestionVO.java` | 新建 |
| `src/main/java/com/jiang/vo/AssessmentResultVO.java` | 新建 |
| `src/main/java/com/jiang/vo/AssessmentQuestionWithAnswerVO.java` | 新建 |
| `src/main/java/com/jiang/vo/KpAccuracyVO.java` | 新建 |
| `src/main/java/com/jiang/vo/AssessmentHistoryVO.java` | 新建 |

### 任务描述

1. 在 `ErrorCodes.java` 新增评测错误码（1120-1129 段）：
   - `ASSESSMENT_NOT_FOUND(1120, "评测不存在")`
   - `ASSESSMENT_INVALID_STATUS(1121, "评测状态非法")`
   - `ASSESSMENT_ALREADY_GRADED(1122, "评测已批改，请勿重复提交")`
   - `ASSESSMENT_CONCURRENT_MODIFIED(1123, "评测状态更新冲突，请重试")`

2. 创建请求 DTO（字段与校验注解见 design §5.1）：
   - `CreateAssessmentRequest`：`subjectId`（@NotNull）
   - `SubmitAssessmentRequest`：`answers`（@NotEmpty, @Valid）
   - `AnswerItem`：`questionId`（@NotNull）, `answer`（可空）

3. 创建响应 VO（字段见 design §5.2）：
   - `AssessmentVO`：评测概要（创建后返回，IMP-12 新增 warning 字段）
   - `AssessmentDetailVO`：评测详情（含题目列表，不含答案）
   - `AssessmentQuestionVO`：单题 VO（BLOCK-4 修复：字段白名单，不含 answer/analysis）
   - `AssessmentResultVO`：批改结果 VO（IMP-5 修复：含 questionsWithAnswers 字段，仅 graded 时非空）
   - `AssessmentQuestionWithAnswerVO`：含答案的题目 VO（IMP-5 新增）
   - `KpAccuracyVO`：知识点正确率 VO
   - `AssessmentHistoryVO`：历史列表 VO（IMP-6 修复：含 status 字段，前端根据状态区分展示）

### 验收标准

- 所有 DTO/VO 使用 `@Data` + `@Builder`（VO）或 `@Data`（DTO）
- 校验注解正确（`@NotNull`、`@NotEmpty`、`@Valid`）
- 错误码编号不冲突

---

## T-02：AssessmentQuestionSelector 出题工具类

### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/main/java/com/jiang/common/util/AssessmentQuestionSelector.java` | 新建 |

### 任务描述

实现静态工具类 `AssessmentQuestionSelector`：

```java
public final class AssessmentQuestionSelector {
    public static final int QUESTIONS_PER_KP = 3;
    public static final int MAX_KPS_PER_ASSESSMENT = 5;

    /**
     * 从候选题目中选取指定数量的题目（使用默认 Random）
     */
    public static List<QuestionBank> select(List<QuestionBank> candidates, int count);

    /**
     * IMP-1 修复：可注入 Random 实例，测试时传入固定种子 new Random(42) 保证可复现
     */
    public static List<QuestionBank> select(List<QuestionBank> candidates, int count, Random random);
}
```

选择逻辑：
1. 按 `difficulty` 分组（basic/standard/challenge）
2. 从每组使用 random 取 1 道（已选题目不重复）
3. 若已选数量 < count，从剩余候选题目使用 random 补齐
4. 若候选总数 < count，返回全部候选（调用方判断是否满足最低要求）

### 验收标准

- 3 种难度各有时，每种恰好选 1 道
- 某难度缺失时，从其他难度补齐
- 候选不足 3 道时，返回全部候选
- IMP-1 修复：测试时传入固定种子 new Random(42)，多次调用结果可复现

---

## T-03：Mapper 扩展

### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/main/java/com/jiang/mapper/AssessmentResultMapper.java` | 修改 |
| `src/main/java/com/jiang/mapper/AssessmentSubmissionDetailMapper.java` | 新建 |
| `src/main/java/com/jiang/mapper/QuestionBankMapper.java` | 修改 |

### 任务描述

1. `AssessmentResultMapper`：新增 `gradeWithCheck` 方法（design §4.5）
   - UPDATE 语句 SET score, total_score, results, status='graded', assessed_at
   - WHERE assessment_id = #{id} AND tenant_id = #{tenantId} AND status = 'pending'
   - 返回 affected rows
   - IMP-2 修复：results 参数为 String 类型（已序列化的 JSON），调用方负责用 ObjectMapper 序列化

2. `AssessmentSubmissionDetailMapper`：继承 `BaseMapper<AssessmentSubmissionDetail>`
   - 无自定义方法（使用 MyBatis-Plus 默认 CRUD）

3. `QuestionBankMapper`：新增 `selectActiveByKp` 方法
   - SELECT 题目全部字段
   - WHERE knowledge_point_id = #{kpId} AND tenant_id = #{tenantId}
   - ORDER BY difficulty ASC, question_id ASC（稳定排序）

### 验收标准

- SQL 语句参数化（`#{}` 占位符），无 SQL 注入风险
- `gradeWithCheck` 的 WHERE 条件包含 status='pending'（状态机并发保护）
- IMP-2 修复：`gradeWithCheck` 的 results 参数为 String 类型，调用方用 ObjectMapper 序列化
- `selectActiveByKp` 返回完整字段（不含 answer，避免泄露到出题阶段）

---

## T-04：AssessmentService 核心实现

### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/main/java/com/jiang/service/AssessmentService.java` | 新建 |
| `src/main/java/com/jiang/service/impl/AssessmentServiceImpl.java` | 新建 |

### 任务描述

实现 `AssessmentServiceImpl`，核心方法：

#### createAssessment

```
1. 校验 subjectId → SubjectMapper.selectById → 不存在抛 SUBJECT_NOT_FOUND
2. 查询 active 知识点 → KnowledgePointMapper.selectList(
     WHERE subject = #{subject.name} AND status = 'active' AND tenant_id = #{tenantId})
   - 空列表 → 抛 SUBJECT_NO_ACTIVE_KP
   - BLOCK-5 修复：超过 5 个 → 按学生掌握度 ASC 排序取最低的 5 个
     IMP-8 修复：采用 QueryWrapper 查询 StudentKnowledgeStateMapper（BaseMapper 内置方法）：
     new QueryWrapper<StudentKnowledgeState>()
         .eq("student_id", studentId).eq("tenant_id", tenantId)
         .in("knowledge_point_id", kpIds)
     （无记录视为 mastery=0，优先选中）
     若全部无记录，回退为 knowledge_point_id 字典序（稳定）
     IMP-12 补充：若所有 active 知识点掌握度均 ≥ 0.8，
     仍按 ASC 取前 5 个（相对最薄弱），但在 AssessmentVO 返回 warning 字段：
     "当前知识点掌握良好，评测可能偏简单"
3. 对每个知识点：
   - QuestionBankMapper.selectActiveByKp(kpId, tenantId)
   - AssessmentQuestionSelector.select(candidates, 3)
   - 无题 → WARN 日志，跳过该知识点
4. 所有知识点均无题 → 抛 QUESTION_NOT_FOUND
5. 构造 results JSON（题目列表，含 answer 供后续批改比对）
6. INSERT AssessmentResult（status=pending, score/totalScore/assessedAt=null）
7. 返回 AssessmentVO
```

#### getAssessmentDetail

```
1. 查询 AssessmentResult → 不存在抛 ASSESSMENT_NOT_FOUND
2. 归属校验：studentId 不匹配 → 抛 STUDENT_IDENTITY_MISMATCH
3. BLOCK-4 修复：解析 results JSON 时，使用字段白名单显式映射到 AssessmentQuestionVO：
   白名单字段：questionId, knowledgePointId, content, choices, difficulty, type
   禁止映射字段：answer, analysis（防止答案泄露）
   IMP-9 修复：实现方式：
   a) 解析 results JSON 为 List<Map<String, Object>>（或中间 DTO ResultQuestionItem）
   b) 遍历每个 Map，仅提取白名单字段构造 AssessmentQuestionVO
   c) answer/analysis 字段不读取、不设置
   IMP-14 补充：假设 question_bank.choices 为纯文本数组，不含 isCorrect 等敏感标记
4. 查询 Subject 获取 subjectName
5. 查询 KnowledgePoint 获取各知识点名称
6. 返回 AssessmentDetailVO（无论 pending 还是 graded，均不含答案）
```

#### submitAndGrade（核心方法，@Transactional）

```
1. 查询 AssessmentResult → 不存在/归属不匹配/状态非法 → 抛对应异常
2. status = 'graded' → 抛 ASSESSMENT_ALREADY_GRADED（幂等）
3. status != 'pending' → 抛 ASSESSMENT_INVALID_STATUS
4. 从 results JSON 解析题目列表
5. BLOCK-1 修复：逐题批改（按 question_bank.type 分流比对）：
   - 匹配 questionId → 从 question_bank 查正确答案
   - 未作答判定：studentAnswer 为 null 或空字符串 → isCorrect=0（判错）
   - choice 类型：答案转大写 + 去首尾空格 + 字符排序（"ba" → "AB"），再比对
     IMP-13 补充：假设选项为单个大写字母（A-Z），字符排序仅适用于多选；
     若答案含非字母字符，退化为字符串比对（忽略大小写+首尾空格）
   - numeric 类型：尝试解析为 BigDecimal 比对（"0.5" = "0.50"）；
     解析失败（如 "1/2"）→ 判错（isCorrect=0），WARN 日志
     IMP-10 补充：题库录入规范要求 numeric 题 answer 必须为纯数字
   - 其他类型（short/self）：字符串比对（忽略首尾空格 + 不区分大小写）
   - 记录 isCorrect
6. 计算得分（totalScore = 题目数, score = 正确数）
7. 构造各知识点正确率（KpAccuracyVO 列表）
8. 更新更新 results JSON（追加 answers + statistics）
9. IMP-3 修复：批量 INSERT AssessmentSubmissionDetail（使用 MyBatis-Plus saveBatch，15 道题量级可接受）
10. AssessmentResultMapper.gradeWithCheck（状态机更新）
    - IMP-2 修复：results 参数为 String 类型（已序列化的 JSON），调用方用 ObjectMapper 序列化
    - affected rows = 0 → 抛 ASSESSMENT_CONCURRENT_MODIFIED
11. BLOCK-2 修复：错题入队（先于掌握度更新，确保新错题可被后续 syncPauseStatus 暂停）：
    - 逐题错题入队（仅 isCorrect=0）：
      ErrorQuestionService.recordWrongAnswer(studentId, tenantId,
          questionId, kpId, ErrorQuestionSourceType.ASSESSMENT,
          assessmentId, LocalDate.now())
12. IMP-4 修复：掌握度更新（按 results JSON 中的题目顺序，稳定可复现）：
    - 逐题更新掌握度：
      MasteryService.updateMasteryFromResult(studentId, tenantId,
          kpId, ChangeType.ASSESSMENT, assessmentId, isCorrect, dNorm)
    - dNorm 映射：basic=0.3, standard=0.6, challenge=0.9
    - 同一知识点 3 道题连续更新，最终掌握度取决于更新顺序（TD-ASSESSMENT-ORDER-SENSITIVE）
13. IMP-5 修复：构造 AssessmentResultVO.questionsWithAnswers（仅 graded 时非空）
14. 返回 AssessmentResultVO
```

#### getAssessmentHistory

```
1. 分页查询 AssessmentResult（WHERE student_id + tenant_id，ORDER BY create_time DESC）
2. IMP-6 修复：返回所有状态（含 pending 和 graded），前端根据 status 字段区分展示：
   - pending → 显示“未完成”标记，score/totalScore/assessedAt 为 null
   - graded → 显示得分和批改时间
3. 转换为 AssessmentHistoryVO 列表
4. 返回 PageResult<AssessmentHistoryVO>
```

### 验收标准

- `submitAndGrade` 方法标注 `@Transactional(rollbackFor = Exception.class)`
- IMP-11 补充：状态机校验——仅 pending 可提交，graded 抛 ALREADY_GRADED，其他状态抛 INVALID_STATUS
- BLOCK-1 修复：批改逻辑按 question_bank.type 分流，未作答判错
- BLOCK-2 修复：错题入队先于掌握度更新
- BLOCK-4 修复：getAssessmentDetail 不泄露 answer/analysis
- BLOCK-5 修复：createAssessment 按掌握度 ASC 选点
- IMP-2 修复：gradeWithCheck 的 results 参数为 String 类型
- IMP-3 修复：批量插入使用 saveBatch
- IMP-4 修复：掌握度更新按 results JSON 顺序
- IMP-5 修复：AssessmentResultVO 含 questionsWithAnswers（graded 时非空）
- IMP-6 修复：getAssessmentHistory 返回所有状态

---

## T-05：AssessmentController API 层

### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/main/java/com/jiang/controller/AssessmentController.java` | 新建 |

### 任务描述

实现学生端评测接口（design §4.1）：

- `POST /api/student/assessment`：创建评测
  - 从 `StudentContext` 提取 studentId/tenantId
  - 请求体：`CreateAssessmentRequest`（@Valid）
  - 返回：`Result<AssessmentVO>`

- `GET /api/student/assessment/{assessmentId}`：评测详情
  - 从 `StudentContext` 提取 studentId/tenantId
  - 返回：`Result<AssessmentDetailVO>`

- `POST /api/student/assessment/{assessmentId}/submit`：提交答案
  - 从 `StudentContext` 提取 studentId/tenantId
  - 请求体：`SubmitAssessmentRequest`（@Valid）
  - 返回：`Result<AssessmentResultVO>`

- `GET /api/student/assessment`：历史列表
  - 从 `StudentContext` 提取 studentId/tenantId
  - 参数：page（默认 1）、size（默认 10）
  - 返回：`Result<PageResult<AssessmentHistoryVO>>`

### 验收标准

- Controller 无业务逻辑（仅参数提取 + Service 调用 + 响应封装）
- 所有接口从 `StudentContext` 提取身份（不信任前端传入）
- 返回统一 `Result<T>` 结构

---

## T-06：单元测试

### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/test/java/com/jiang/service/AssessmentServiceTest.java` | 新建 |
| `src/test/java/com/jiang/common/util/AssessmentQuestionSelectorTest.java` | 新建 |

### 任务描述

#### AssessmentServiceTest（Mockito + Mock Mapper）

| 测试方法 | 场景 |
|----------|------|
| `should_createAssessment_when_subjectHasActiveKps` | 正常创建评测 |
| `should_throwException_when_subjectNotFound` | 科目不存在 |
| `should_throwException_when_noActiveKp` | 科目下无 active 知识点 |
| `should_throwException_when_questionBankEmpty` | 题库无题 |
| `should_limitKpsToFive_when_subjectHasMore` | 知识点超过 5 个时截断 |
| BLOCK-5 修复：`should_selectLowestMasteryKps_when_multipleKps` | 知识点超过 5 个时优先选掌握度最低的 |
| IMP-12 修复：`should_returnWarning_when_allMasteryAboveThreshold` | 所有知识点掌握度 ≥ 0.8 时返回 warning 提示 |
| `should_getDetail_when_assessmentExists` | 正常查看详情 |
| `should_throwException_when_studentMismatch` | 归属校验失败 |
| BLOCK-4 修复：`should_notLeakAnswer_when_getDetail` | getAssessmentDetail 返回的 VO 中无 answer/analysis 字段 |
| `should_gradeAndReturn_when_submitCorrectly` | 正常提交批改 |
| `should_throwException_when_alreadyGraded` | 重复提交（幂等） |
| `should_throwException_when_statusInvalid` | 状态非法 |
| BLOCK-1 修复：`should_judgeWrong_when_answerBlank` | 未作答判错 |
| BLOCK-1 修复：`should_compareChoiceCaseInsensitive` | choice 类型大小写+排序比对 |
| BLOCK-1 修复：`should_compareNumericByValue` | numeric 类型数值比对（"0.5" = "0.50"） |
| BLOCK-1 修复：`should_judgeWrong_when_numericParseFail` | numeric 解析失败判错（"1/2"） |
| `should_updateMastery_when_gradeCorrect` | 答对题掌握度上升 |
| `should_updateMastery_when_gradeIncorrect` | 答错题掌握度下降 |
| BLOCK-2 修复：`should_pauseNewErrorQuestion_when_masteryRiseAboveThreshold` | 掌握度从 0.7 升到 0.85 → 新入队错题也 paused |
| `should_recordErrorQuestion_when_gradeIncorrect` | 答错入队 |
| `should_returnHistory_when_hasAssessments` | 历史列表 |
| IMP-6 修复：`should_returnAllStatus_when_getHistory` | 历史列表含 pending 和 graded |

#### AssessmentQuestionSelectorTest

| 测试方法 | 场景 |
|----------|------|
| `should_selectOneFromEachDifficulty_when_allAvailable` | 三种难度各有题 |
| `should_fillRandomly_when_oneDifficultyMissing` | 某难度缺题时随机补齐 |
| `should_returnAll_when_candidatesLessThanCount` | 候选不足 3 道 |
| `should_throwException_when_candidatesEmpty` | 无候选题目 |
| IMP-1 修复：`should_returnSameResult_when_fixedSeed` | 固定种子 new Random(42) 多次调用结果可复现 |

### 验收标准

- Service 测试使用 `@ExtendWith(MockitoExtension.class)` + `@Mock` + `@InjectMocks`
- 每个测试方法有明确断言
- 覆盖正常 + 异常 + 边界场景

---

## T-07：集成测试

### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/test/java/com/jiang/controller/AssessmentControllerTest.java` | 新建 |

### 任务描述

使用 `@SpringBootTest` + `MockMvc` 测试 Controller 层：

| 测试方法 | 场景 |
|----------|------|
| `should_createAssessment_when_validRequest` | 正常创建 |
| `should_return400_when_subjectIdNull` | subjectId 为空 |
| `should_getDetail_when_assessmentExists` | 正常查看详情 |
| `should_return404_when_assessmentNotFound` | 评测不存在 |
| `should_submitAndGrade_when_validAnswers` | 正常提交 |
| `should_return400_when_answersEmpty` | 答案列表为空 |
| `should_getHistory_when_hasAssessments` | 历史列表 |
| `should_returnDefaultPage_when_noParams` | 分页默认值 |

### 验收标准

- 验证响应状态码和结构
- 验证 `Result<T>` 统一响应格式
- 覆盖参数校验异常场景

---

## T-08：全量编译 + 测试验证

### 任务描述

```bash
mvn clean compile
mvn test
```

### 验收标准

- 编译通过（无 ERROR）
- 全部测试通过（0 failures, 0 errors）
- 无新增 warning（除已知 WARN 日志外）

---

## 任务依赖关系

```
T-00 ──┐
T-01 ──┼──→ T-02 ──┐
       │           ├──→ T-04 ──→ T-05 ──→ T-07 ──┐
       └──→ T-03 ──┘                           └──→ T-08
                T-06 ──────────────────────────────┘
```

T-00/T-01 可并行；T-02/T-03 依赖 T-00；T-04 依赖 T-01/T-02/T-03；T-05/T-06 依赖 T-04；T-07 依赖 T-05；T-08 最后执行。
