# Design：学习记录查询与回溯系统（v3 修复版）

> **Change 名称**：`2026-11-03_learning_record_query`  
> **版本**：v3（修复 BLOCK-C）

---

## 0. 审查修复追溯

| 编号 | 问题 | 修复方案 |
|------|------|----------|
| BLOCK-10 | getRecordList 内存合并 + 内存分页 → OOM + 分页语义错误 | **v2 方案（废弃）**：三次分页查询 + 归并排序 | **v3 方案（BLOCK-C 修复）**：SQL UNION ALL 在数据库层合并三类记录，统一排序分页，彻底解决跨类归并分页不正确问题 |
| BLOCK-C | v2 的三次分页归并方案在 create_time 分布不均时，第 2 页起结果不正确且 total 虚高 | 改用 SQL UNION ALL 在数据库层合并三类记录，统一 ORDER BY + LIMIT/OFFSET；total 用 COUNT(UNION ALL) 查询 |
| NOTE-2 | UNION ALL homework 分支用 INNER JOIN 可能丢失孤儿记录 | 改为 LEFT JOIN daily_plan/learning_goal，subjectId 可能为 null 但不丢记录 |
| BLOCK-11 | recordType 用户输入未校验 + id 跨类型语义混淆 | recordType 用枚举 `RecordType.fromCode()` 校验；详情路径改为 `/records/{recordType}/{id}` |
| IMP-16 | BigDecimal 直接用 `-` 运算符 | 改为 `masteryAfter.subtract(masteryBefore)` |
| IMP-17 | getMasteryChangesBySource 方法缺失 | 补充方法定义 + Mapper SQL |
| IMP-18 | buildHomeworkDetail 等三个方法缺失 | 补充完整实现 |
| IMP-19 | V8 索引与 V5 重叠 | V8 仅添加 V5 未覆盖的索引（mastery_change_log 的 idx_kp_student） |
| 错误码 | 原 1140-1141 与计划调整冲突 | 改为 **1150-1159** 编号段 |

---

## 1. 核心流程（修复 BLOCK-C：废弃三次归并，改用 SQL UNION ALL）

### 1.1 SQL UNION ALL 统一分页

```
getRecordList(startDate, endDate, recordType, page, size):

1. 若 recordType 指定：只查该类记录，单表 SQL 分页（性能最优）
2. 若 recordType 为空：SQL UNION ALL 三类记录，统一排序分页
   a. 数据查询：SELECT ... FROM homework UNION ALL SELECT ... FROM assessment UNION ALL SELECT ... FROM exam
                ORDER BY create_time DESC LIMIT size OFFSET (page-1)*size
   b. 总数查询：SELECT COUNT(*) FROM (SELECT 1 FROM homework UNION ALL SELECT 1 FROM assessment UNION ALL SELECT 1 FROM exam) AS merged
   c. 后处理：批量查询 subjectName（selectNamesByIds IN + GROUP BY）
```

**优势**：
- 分页语义正确：数据库层统一排序 + LIMIT/OFFSET，保证每页数据准确
- total 准确：COUNT(UNION ALL) 反映实际可分页记录数
- 无内存压力：不需要将多类记录加载到内存归并

**边界处理**：
- homework 无 subject_id 列：通过 daily_plan → learning_goal 关联获取
- 某类表无数据时 UNION ALL 自然返回空集，不影响其他类

---

## 2. 分层设计

### 2.1 Controller 层（修复 BLOCK-11）

```java
@RestController
@RequestMapping("/api/student/records")
@Tag(name = "学习记录", description = "学习记录查询与回溯")
public class LearningRecordController {

    @GetMapping
    @Operation(summary = "查看学习记录列表")
    public Result<PageResult<LearningRecordListVO>> getRecordList(
            @RequestParam(value = "startDate", required = false) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) LocalDate endDate,
            @RequestParam(value = "recordType", required = false) String recordType,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        // 修复 BLOCK-11：recordType 枚举校验
        RecordType type = recordType != null ? RecordType.fromCode(recordType) : null;
        return Result.success(learningRecordService.getRecordList(startDate, endDate, type, page, size));
    }

    /**
     * 修复 BLOCK-11：路径改为 /records/{recordType}/{id}
     * recordType 成为路径一部分，id 语义明确
     */
    @GetMapping("/{recordType}/{id}")
    @Operation(summary = "查看学习记录详情")
    public Result<LearningRecordDetailVO> getRecordDetail(
            @PathVariable String recordType,
            @PathVariable Long id) {
        RecordType type = RecordType.fromCode(recordType);
        return Result.success(learningRecordService.getRecordDetail(type, id));
    }

    @GetMapping("/mastery-changes")
    @Operation(summary = "查看掌握度变更追溯链")
    public Result<List<MasteryChangeVO>> getMasteryChanges(
            @RequestParam Long knowledgePointId,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit) {
        return Result.success(learningRecordService.getMasteryChanges(knowledgePointId, limit));
    }
}
```

### 2.2 RecordType 枚举（修复 BLOCK-11）

```java
@Getter
public enum RecordType {
    HOMEWORK("homework"),
    ASSESSMENT("assessment"),
    EXAM("exam");

    private final String code;

    RecordType(String code) {
        this.code = code;
    }

    /**
     * 修复 BLOCK-11：非法值直接抛 400
     * 支持 trim + 大小写不敏感
     */
    public static RecordType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCodes.INVALID_RECORD_TYPE);
        }
        String trimmed = code.trim().toLowerCase();
        for (RecordType type : values()) {
            if (type.code.equals(trimmed)) {
                return type;
            }
        }
        throw new BusinessException(ErrorCodes.INVALID_RECORD_TYPE);
    }
}
```

### 2.3 Service 层（修复 BLOCK-C：UNION ALL 统一分页）

```java
@Service
public class LearningRecordServiceImpl implements LearningRecordService {

    /**
     * 修复 BLOCK-C：废弃三次归并，改用 SQL UNION ALL 统一分页
     */
    @Override
    public PageResult<LearningRecordListVO> getRecordList(LocalDate startDate, LocalDate endDate,
                                                            RecordType recordType, Integer page, Integer size) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        size = Math.min(size, 100); // IMP-4 分页上限

        // 指定类型：单类查询（性能最优）
        if (recordType != null) {
            return querySingleType(recordType, studentId, tenantId, startDate, endDate, page, size);
        }

        // 未指定类型：SQL UNION ALL 三类归并
        int offset = (page - 1) * size;

        // 1. 数据查询
        List<LearningRecordListVO> records = learningRecordMapper.selectUnionAllRecords(
                studentId, tenantId, startDate, endDate, offset, size);

        // 2. 总数查询
        long total = learningRecordMapper.countUnionAllRecords(
                studentId, tenantId, startDate, endDate);

        // 3. 后处理：批量查询 subjectName（防 N+1）
        List<Long> subjectIds = records.stream()
                .map(LearningRecordListVO::getSubjectId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (!subjectIds.isEmpty()) {
            Map<Long, String> subjectNameMap = subjectMapper.selectNamesByIds(subjectIds);
            records.forEach(r -> r.setSubjectName(subjectNameMap.get(r.getSubjectId())));
        }

        return PageResult.of(records, total, page, size);
    }

    private PageResult<LearningRecordListVO> querySingleType(RecordType type, Long studentId, String tenantId,
                                                               LocalDate startDate, LocalDate endDate,
                                                               Integer page, Integer size) {
        switch (type) {
            case HOMEWORK:
                return queryHomeworkRecords(studentId, tenantId, startDate, endDate, page, size);
            case ASSESSMENT:
                return queryAssessmentRecords(studentId, tenantId, startDate, endDate, page, size);
            case EXAM:
                return queryExamRecords(studentId, tenantId, startDate, endDate, page, size);
            default:
                throw new BusinessException(ErrorCodes.INVALID_RECORD_TYPE);
        }
    }

    /**
     * 修复 BLOCK-11：详情接口按 RecordType 分派
     */
    @Override
    public LearningRecordDetailVO getRecordDetail(RecordType type, Long id) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        switch (type) {
            case HOMEWORK:
                return buildHomeworkDetail(id, studentId, tenantId);
            case ASSESSMENT:
                return buildAssessmentDetail(id, studentId, tenantId);
            case EXAM:
                return buildExamDetail(id, studentId, tenantId);
            default:
                throw new BusinessException(ErrorCodes.INVALID_RECORD_TYPE);
        }
    }

    /**
     * 修复 IMP-18：补充 buildHomeworkDetail 完整实现
     */
    private LearningRecordDetailVO buildHomeworkDetail(Long id, Long studentId, String tenantId) {
        HomeworkSubmissionDetail hw = homeworkMapper.selectByIdAndStudentId(id, studentId, tenantId);
        if (hw == null) throw new BusinessException(ErrorCodes.RECORD_NOT_FOUND);

        LearningRecordDetailVO vo = new LearningRecordDetailVO();
        vo.setId(id);
        vo.setRecordType(RecordType.HOMEWORK);
        vo.setRecordDate(hw.getCreateTime().toLocalDate());
        vo.setSubjectName(getSubjectName(hw.getSubjectId()));
        vo.setTitle("每日作业");

        HomeworkDetailVO detail = new HomeworkDetailVO();
        detail.setTotalCount(hw.getTotalCount());
        detail.setCorrectCount(hw.getCorrectCount());
        detail.setAccuracy(hw.getAccuracy());
        // 查询逐题详情
        List<HomeworkQuestionDetailVO> questions = homeworkQuestionMapper.selectBySubmissionId(id, tenantId)
                .stream().map(this::toQuestionDetail).collect(Collectors.toList());
        detail.setQuestions(questions);
        vo.setDetail(detail);

        // 修复 IMP-17：关联掌握度变更
        vo.setMasteryChanges(getMasteryChangesBySource(id, "homework"));

        return vo;
    }

    // buildAssessmentDetail / buildExamDetail 类似实现...

    /**
     * 修复 IMP-17：补充 getMasteryChangesBySource 实现
     */
    private List<MasteryChangeVO> getMasteryChangesBySource(Long sourceId, String changeType) {
        String tenantId = StudentContext.getTenantId();
        List<MasteryChangeLog> changes = masteryChangeLogMapper.selectBySourceIdAndType(sourceId, changeType, tenantId);
        return changes.stream().map(this::toMasteryChangeVO).collect(Collectors.toList());
    }

    /**
     * 修复 IMP-16：BigDecimal 使用 subtract()
     */
    private MasteryChangeVO toMasteryChangeVO(MasteryChangeLog log) {
        MasteryChangeVO vo = new MasteryChangeVO();
        vo.setChangeId(log.getId());
        vo.setKnowledgePointId(log.getKnowledgePointId());
        vo.setKnowledgePointName(getKnowledgePointName(log.getKnowledgePointId()));
        vo.setChangeType(log.getChangeType());
        vo.setSourceId(log.getSourceId());
        // 修复 IMP-16：delta 已在 log 中存储，不需要计算
        vo.setDelta(log.getDelta());
        vo.setMasteryBefore(log.getMasteryBefore());
        vo.setMasteryAfter(log.getMasteryAfter());
        vo.setChangeTime(log.getCreateTime());
        return vo;
    }
}
```

### 2.4 Mapper XML（修复 BLOCK-C：UNION ALL SQL）

```xml
<!-- LearningRecordMapper.xml -->

<!-- BLOCK-C：UNION ALL 三类记录统一分页 -->
<select id="selectUnionAllRecords" resultType="com.jiang.vo.LearningRecordListVO">
    SELECT * FROM (
        -- 作业记录：通过 daily_plan → learning_goal 获取 subject_id
        -- NOTE-2 修复：使用 LEFT JOIN 防御性编程，避免孤儿记录丢失
        -- homework_submission_detail.plan_id 虽为 NOT NULL，但 LEFT JOIN 保证极端情况下仍不丢记录
        SELECT
            s.submission_id AS recordId,
            'homework'      AS recordType,
            s.create_time   AS createTime,
            g.subject_id    AS subjectId,
            '每日作业'       AS title,
            NULL            AS score
        FROM homework_submission_detail s
        LEFT JOIN daily_plan p ON s.plan_id = p.plan_id AND p.tenant_id = #{tenantId}
        LEFT JOIN learning_goal g ON p.goal_id = g.goal_id AND g.tenant_id = #{tenantId}
        WHERE s.student_id = #{studentId}
          AND s.tenant_id = #{tenantId}
          <if test="startDate != null">AND s.submitted_at &gt;= #{startDate}</if>
          <if test="endDate != null">AND s.submitted_at &lt;= #{endDate}</if>

        UNION ALL

        -- 评测记录：直接有 subject_id
        SELECT
            a.assessment_id AS recordId,
            'assessment'    AS recordType,
            a.create_time   AS createTime,
            a.subject_id    AS subjectId,
            '专项评测'       AS title,
            a.score         AS score
        FROM assessment_result a
        WHERE a.student_id = #{studentId}
          AND a.tenant_id = #{tenantId}
          AND a.status = 'graded'
          <if test="startDate != null">AND a.assessed_at &gt;= #{startDate}</if>
          <if test="endDate != null">AND a.assessed_at &lt;= #{endDate}</if>

        UNION ALL

        -- 考试记录：直接有 subject_id
        SELECT
            e.analysis_id   AS recordId,
            'exam'          AS recordType,
            e.create_time   AS createTime,
            e.subject_id    AS subjectId,
            e.exam_name     AS title,
            e.user_score    AS score
        FROM exam_analysis_result e
        WHERE e.student_id = #{studentId}
          AND e.tenant_id = #{tenantId}
          AND e.status = 'completed'
          <if test="startDate != null">AND e.analyzed_at &gt;= #{startDate}</if>
          <if test="endDate != null">AND e.analyzed_at &lt;= #{endDate}</if>
    ) AS merged
    ORDER BY createTime DESC
    LIMIT #{offset}, #{size}
</select>

<select id="countUnionAllRecords" resultType="long">
    SELECT COUNT(*) FROM (
        SELECT 1 FROM homework_submission_detail s
        LEFT JOIN daily_plan p ON s.plan_id = p.plan_id AND p.tenant_id = #{tenantId}
        LEFT JOIN learning_goal g ON p.goal_id = g.goal_id AND g.tenant_id = #{tenantId}
        WHERE s.student_id = #{studentId} AND s.tenant_id = #{tenantId}
          <if test="startDate != null">AND s.submitted_at &gt;= #{startDate}</if>
          <if test="endDate != null">AND s.submitted_at &lt;= #{endDate}</if>

        UNION ALL

        SELECT 1 FROM assessment_result a
        WHERE a.student_id = #{studentId} AND a.tenant_id = #{tenantId} AND a.status = 'graded'
          <if test="startDate != null">AND a.assessed_at &gt;= #{startDate}</if>
          <if test="endDate != null">AND a.assessed_at &lt;= #{endDate}</if>

        UNION ALL

        SELECT 1 FROM exam_analysis_result e
        WHERE e.student_id = #{studentId} AND e.tenant_id = #{tenantId} AND e.status = 'completed'
          <if test="startDate != null">AND e.analyzed_at &gt;= #{startDate}</if>
          <if test="endDate != null">AND e.analyzed_at &lt;= #{endDate}</if>
    ) AS merged
</select>
```

---

## 3. DDL 变更（修复 IMP-19）

### 3.1 V8__learning_record_index.sql

```sql
-- V8: 学习记录查询索引优化
-- 修复 IMP-19：仅添加 V5 未覆盖的索引，避免重复

-- 1. mastery_change_log 索引（V5 未覆盖）
CREATE INDEX idx_mastery_log_kp_student ON mastery_change_log(knowledge_point_id, student_id, create_time, tenant_id);
CREATE INDEX idx_mastery_log_source ON mastery_change_log(source_id, change_type, tenant_id);

-- 注意：homework_submission_detail / assessment_result / exam_analysis_result 的
-- idx_student_date 索引已在 V5（assessment_system Change）中添加，此处不重复
```

---

## 4. 错误码（修复 BLOCK-2 全局分配）

```java
// 学习记录相关（1150-1159）
RECORD_NOT_FOUND(1150, "学习记录不存在"),
INVALID_RECORD_TYPE(1151, "无效的记录类型，合法值：homework/assessment/exam"),
RECORD_ALREADY_EXISTS(1152, "学习记录已存在，不可重复创建");
```

---

## 5. 隐性约定对齐

| 约定 | 实现方式 |
|------|----------|
| #1 统一响应 | Controller 返回 `Result<T>` |
| #2 异常处理 | Service 抛 `BusinessException`，Controller 不 try-catch |
| #3 分页参数 | `page` 从 1 开始，`size` 默认 10 |
| #7 tenant_id 隔离 | 所有查询携带 tenant_id |
| N1 管理端上下文 | Demo 阶段复用 `StudentContext.getTenantId()` |

---

## 6. 数据只读不删保障

- Service 层不提供 delete 方法
- Controller 层不暴露 delete 接口
- Mapper 层不提供 delete SQL
- 所有更新操作仅限状态字段（如 assessment_result.status: pending→graded）

---

**版本**：v3（修复 BLOCK-C）  
**创建日期**：2026-11-03  
**最后更新**：2026-11-04
