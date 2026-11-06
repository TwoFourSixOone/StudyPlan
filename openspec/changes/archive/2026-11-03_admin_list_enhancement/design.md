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
- [ ] DDL 脚本可正常执行
- [ ] 索引创建成功
- [ ] 编译通过

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
- [ ] VO 类字段完整
- [ ] QuestionListVO.questionType 对应实体 QuestionBank.type
- [ ] StandardListVO.subjectName 对应实体 CurriculumStandard.subject
- [ ] 编译通过

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
- [ ] 题目列表查询列名使用 `type`
- [ ] 课程标准列表 `subject` 直接取 String 值
- [ ] keyword LIKE 转义 `%` 和 `_`
- [ ] size 最大限制 100
- [ ] 不包含 getKnowledgePointList（归 Change 9）
- [ ] 编译通过

---

### T-04：Mapper 扩展（修复 IMP-5）

**任务描述**：
1. 在 `KnowledgePointMapper` 中添加：
   - `selectNamesByIds()` — 批量查询知识点名称，返回 `Map<Long, String>`（修复 IMP-5：避免 N+1）
2. 确认现有 Mapper 方法满足列表查询需求

**涉及文件**：
- `src/main/java/com/jiang/mapper/KnowledgePointMapper.java`（修改）

**验收标准**：
- [ ] selectNamesByIds 使用 IN + GROUP BY 批量查询
- [ ] 编译通过

---

### T-05：Controller 实现（修复 BLOCK-1/N1 + IMP-4）

**任务描述**：
1. = null) {
        // 修复 IMP-3：转义 % 和 _
        String escaped = keyword.replace("%", "\\%").replace("_", "\\_");
        wrapper.like("content", escaped);
    }

    wrapper.orderByDesc("create_time");

    Page<QuestionBank> result = questionBankMapper.selectPage(pageParam, wrapper);

    // 修复 IMP-5：批量查询知识点名称，避免 N+1
    List<Long> kpIds = result.getRecords().stream()
            .map(QuestionBank::getKnowledgePointId)
            .filter(Objects::nonNull)
            .map(Long::parseLong)
            .distinct().collect(Collectors.toList());
    Map<Long, String> kpNameMap = knowledgePointMapper.selectNamesByIds(kpIds, tenantId);

    List<QuestionListVO> voList = result.getRecords().stream()
            .map(q -> {
                QuestionListVO vo = new QuestionListVO();
                vo.setId(q.getQuestionId());
                vo.setContent(q.getContent());
                vo.setKnowledgePointName(kpNameMap.getOrDefault(q.getKnowledgePointId(), "未知"));
                vo.setDifficulty(q.getDifficulty());
                vo.setQuestionType(q.getType());  // 修复 IMP-1：字段名为 type
                vo.setCreateTime(q.getCreateTime());
                return vo;
            })
            .collect(Collectors.toList());

    return PageResult.of(voList, result.getTotal(), page, size);
}
```

### 3.2 先修关系列表查询

```java
@Override
public PageResult<DependencyListVO> getDependencyList(Long knowledgePointId, Long prerequisiteId,
                                                         String dependencyType, Integer page, Integer size) {
    String tenantId = StudentContext.getTenantId();

    Page<KnowledgePointDependency> pageParam = new Page<>(page, size);
    QueryWrapper<KnowledgePointDependency> wrapper = new QueryWrapper<>();
    wrapper.eq("tenant_id", tenantId);

    if (knowledgePointId != null) {
        wrapper.eq("knowledge_point_id", knowledgePointId);
    }
    if (prerequisiteId != null) {
        wrapper.eq("prerequisite_id", prerequisiteId);
    }
    if (dependencyType != null && !dependencyType.isEmpty()) {
        wrapper.eq("dependency_type", dependencyType);
    }

    wrapper.orderByDesc("create_time");

    Page<KnowledgePointDependency> result = dependencyMapper.selectPage(pageParam, wrapper);

    // 修复 IMP-5：批量查询知识点名称
    List<Long> allKpIds = result.getRecords().stream()
            .flatMap(d -> Stream.of(d.getKnowledgePointId(), d.getPrerequisiteId()))
            .filter(Objects::nonNull)
            .distinct().collect(Collectors.toList());
    Map<Long, String> kpNameMap = knowledgePointMapper.selectNamesByIds(allKpIds, tenantId);

    List<DependencyListVO> voList = result.getRecords().stream()
            .map(d -> {
                DependencyListVO vo = new DependencyListVO();
                vo.setId(d.getId());
                vo.setKnowledgePointName(kpNameMap.getOrDefault(d.getKnowledgePointId(), "未知"));
                vo.setPrerequisiteName(kpNameMap.getOrDefault(d.getPrerequisiteId(), "未知"));
                vo.setDependencyType(d.getDependencyType());
                vo.setCreateTime(d.getCreateTime());
                return vo;
            })
            .collect(Collectors.toList());

    return PageResult.of(voList, result.getTotal(), page, size);
}
```

### 3.3 课程标准列表查询（修复 IMP-1/5）

```java
@Override
public PageResult<StandardListVO> getStandardList(String stage, String subject,
                                                     String keyword, Integer page, Integer size) {
    String tenantId = StudentContext.getTenantId();

    Page<CurriculumStandard> pageParam = new Page<>(page, size);
    QueryWrapper<CurriculumStandard> wrapper = new QueryWrapper<>();
    wrapper.eq("tenant_id", tenantId);

    if (stage != null && !stage.isEmpty()) {
        wrapper.eq("stage", stage);
    }
    if (subject != null && !subject.isEmpty()) {
        // 修复 IMP-5：CurriculumStandard.subject 是 String 字段（非 subject_id）
        wrapper.eq("subject", subject);
    }
    if (keyword != null && !keyword.isEmpty()) {
        // 修复 IMP-3：转义 % 和 _
        String escaped = keyword.replace("%", "\\%").replace("_", "\\_");
        wrapper.like("description", escaped);
    }

    wrapper.orderByDesc("create_time");

    Page<CurriculumStandard> result = standardMapper.selectPage(pageParam, wrapper);

    List<StandardListVO> voList = result.getRecords().stream()
            .map(s -> {
                StandardListVO vo = new StandardListVO();
                vo.setId(s.getStandardId());
                vo.setCode(s.getCode());
                vo.setDescription(s.getDescription());
                vo.setStage(s.getStage());
                // 修复 IMP-5：subject 是 String 字段，直接取值，无需 N+1 查询
                vo.setSubjectName(s.getSubject());
                vo.setCreateTime(s.getCreateTime());
                return vo;
            })
            .collect(Collectors.toList());

    return PageResult.of(voList, result.getTotal(), page, size);
}
```

### 3.4 导入日志查询

```java
@Override
public PageResult<ImportLogListVO> getImportLogList(String dataType, LocalDate startDate,
                                                       LocalDate endDate, Integer page, Integer size) {
    String tenantId = StudentContext.getTenantId();

    Page<DataImportLog> pageParam = new Page<>(page, size);
    QueryWrapper<DataImportLog> wrapper = new QueryWrapper<>();
    wrapper.eq("tenant_id", tenantId);

    if (dataType != null && !dataType.isEmpty()) {
        wrapper.eq("data_type", dataType);
    }
    if (startDate != null) {
        wrapper.ge("import_time", startDate.atStartOfDay());
    }
    if (endDate != null) {
        wrapper.le("import_time", endDate.atTime(23, 59, 59));
    }

    wrapper.orderByDesc("import_time");

    Page<DataImportLog> result = importLogMapper.selectPage(pageParam, wrapper);

    List<ImportLogListVO> voList = result.getRecords().stream()
            .map(log -> {
                ImportLogListVO vo = new ImportLogListVO();
                vo.setId(log.getId());
                vo.setDataType(log.getDataType());
                vo.setFileName(log.getFileName());
                vo.setSuccessCount(log.getSuccessCount());
                vo.setFailureCount(log.getFailureCount());
                vo.setSkipCount(log.getSkipCount());
                vo.setImportTime(log.getImportTime());
                return vo;
            })
            .collect(Collectors.toList());

    return PageResult.of(voList, result.getTotal(), page, size);
}
```

### 3.5 导入错误记录查询

```java
@Override
public PageResult<ImportErrorVO> getImportErrorList(Long logId, Integer page, Integer size) {
    String tenantId = StudentContext.getTenantId();

    Page<DataImportError> pageParam = new Page<>(page, size);
    QueryWrapper<DataImportError> wrapper = new QueryWrapper<>();
    wrapper.eq("tenant_id", tenantId);
    wrapper.eq("log_id", logId);

    wrapper.orderByDesc("create_time");

    Page<DataImportError> result = importErrorMapper.selectPage(pageParam, wrapper);

    List<ImportErrorVO> voList = result.getRecords().stream()
            .map(err -> {
                ImportErrorVO vo = new ImportErrorVO();
                vo.setId(err.getId());
                vo.setLogId(err.getLogId());
                vo.setRecordId(err.getRecordId());
                vo.setErrorMessage(err.getErrorMessage());
                vo.setCreateTime(err.getCreateTime());
                return vo;
            })
            .collect(Collectors.toList());

    return PageResult.of(voList, result.getTotal(), page, size);
}
```

---

## 4. Mapper 扩展（修复 IMP-5）

### 4.1 KnowledgePointMapper 批量查询名称

```java
/**
 * 修复 IMP-5：批量查询知识点名称，避免 N+1
 */
@Select("<script>" +
        "SELECT id, name FROM knowledge_point WHERE tenant_id = #{tenantId} AND id IN " +
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>" +
        "#{id}" +
        "</foreach>" +
        "</script>")
List<Map<String, Object>> selectNamesByIds(@Param("ids") List<Long> ids,
                                             @Param("tenantId") String tenantId);
```

---

## 5. 索引优化（修复 BLOCK-3）

### 5.1 V9__admin_list_index.sql

```sql
-- V9: 管理后端列表查询索引优化
-- 创建日期：2026-11-03
-- 说明：为管理后端列表查询添加复合索引
--
-- 修复 BLOCK-3：V9 仅依赖 V1~V5 既有表（question_bank、knowledge_point_dependency、
-- curriculum_standard、data_import_log、data_import_error），不依赖 V6-V8。
-- 若并行开发，V9 可独立 apply。

-- 1. 题目表索引（修复 IMP-1：列名为 type 而非 question_type）
CREATE INDEX idx_question_kp_difficulty ON question_bank(knowledge_point_id, difficulty, tenant_id);
CREATE INDEX idx_question_type ON question_bank(type, tenant_id);
CREATE INDEX idx_question_content ON question_bank(content(100), tenant_id);

-- 2. 先修关系表索引
CREATE INDEX idx_dependency_kp ON knowledge_point_dependency(knowledge_point_id, tenant_id);
CREATE INDEX idx_dependency_prereq ON knowledge_point_dependency(prerequisite_id, tenant_id);

-- 3. 课程标准表索引（修复 IMP-5：列名为 subject 而非 subject_id）
CREATE INDEX idx_standard_stage ON curriculum_standard(stage, tenant_id);
CREATE INDEX idx_standard_subject ON curriculum_standard(subject(50), tenant_id);
CREATE INDEX idx_standard_description ON curriculum_standard(description(100), tenant_id);

-- 4. 导入日志表索引
CREATE INDEX idx_import_log_type ON data_import_log(data_type, tenant_id);
CREATE INDEX idx_import_log_time ON data_import_log(import_time, tenant_id);

-- 5. 导入错误记录表索引
CREATE INDEX idx_import_error_log ON data_import_error(log_id, tenant_id);
```

---

## 6. 隐性约定对齐

| 约定 | 实现方式 |
|------|----------|
| #1 统一响应 | Controller 返回 `Result<T>` |
| #2 异常处理 | Service 抛 `BusinessException`，Controller 不 try-catch |
| #3 分页参数 | `page` 从 1 开始，`size` 默认 20（管理端），最大 100（修复 IMP-4） |
| #4 时间格式 | 使用 `LocalDateTime`/`LocalDate` |
| #7 tenant_id 隔离 | 所有查询携带 tenant_id |
| N1 管理端上下文 | Demo 阶段复用 `StudentContext.getTenantId()`（与 `AdminGoalController` 一致） |

---

## 7. 错误码（修复 BLOCK-2 全局分配）

```java
// 管理后端列表相关（1170-1179）——目前为只读查询，预留错误码
IMPORT_LOG_NOT_FOUND(1170, "导入日志不存在"),
IMPORT_ERROR_LOG_NOT_FOUND(1171, "导入错误记录不存在");
```

---

## 8. DDL 版本声明（修复 BLOCK-3）

- 本 Change 使用 **V9__admin_list_index.sql**
- **V9 仅依赖 V1~V5 既有表**（question_bank、knowledge_point_dependency、curriculum_standard、data_import_log、data_import_error）
- **V9 不依赖 V6-V8**（exam_paper、plan_adjustment、learning_record_index）
- 若并行开发，V9 可独立 apply
- **建议合并顺序**：V6 → V7 → V8 → V9（但技术上 V9 无硬性依赖）

---

**版本**：v2（修复 BLOCK-1/2/3 + IMP-1~5）  
**创建日期**：2026-11-03  
**最后更新**：2026-11-03
