# Design：知识点与题库数据导入

> **Change ID**: `2026-10-31_data_import`  
> **关联 Proposal**: proposal.md

---

## 1. 技术方案

### 1.1 技术栈

| 技术 | 说明 |
|------|------|
| Jackson ObjectMapper | JSON 文件解析 |
| Spring TransactionTemplate | 细粒度事务控制（每条记录独立事务） |
| MyBatis-Plus | 数据持久化 |
| Spring Boot | Web 框架 |
| MySQL 5.7+ | 数据库 |

### 1.2 包结构

```
com.jiang
├── controller
│   └── DataImportController          # 数据导入接口
├── service
│   ├── DataImportService             # 数据导入业务逻辑
│   └── impl
│       └── DataImportServiceImpl
├── dto
│   ├── ImportRequest                 # 导入请求 DTO
│   └── ImportResultDTO               # 导入结果 DTO
├── vo
│   └── ImportLogVO                   # 导入日志 VO
└── mapper
    ├── DataImportLogMapper           # 已存在
    └── DataImportErrorLogMapper      # 已存在
```

---

## 2. 核心流程设计

### 2.1 导入流程

```
开发者                    DataImportController        DataImportService              DB
  |                            |                            |                        |
  |-- POST /api/admin/import ->|                            |                        |
  |   { dataType: "topic" }    |                            |                        |
  |                            |-- 校验参数 --------------->|                        |
  |                            |                            |-- 读取 JSON 文件 ------->|
  |                            |                            |<-- 文件内容 ------------|
  |                            |                            |                        |
  |                            |                            |── 写入 import_log ─────>|
  |                            |                            |   (初始计数=0)           |
  |                            |                            |                        |
  |                            |                            |── 逐条校验 ────────────>|
  |                            |                            |   (TransactionTemplate) |
  |                            |                            |── [校验通过]            |
  |                            |                            |── insert (独立事务) ───>|
  |                            |                            |── [ID 冲突] skip        |
  |                            |                            |── [校验失败]            |
  |                            |                            |── 记录 error_log ──────>|
  |                            |                            |                        |
  |                            |                            |── 批量写 error_log ────>|
  |                            |                            |── 更新 import_log ─────>|
  |                            |                            |   (最终计数)             |
  |                            |<-- ImportResultDTO ───────|                        |
  |<-- Result<ImportResultDTO>-|                            |                        |
```

### 2.2 四种数据类型的导入策略

| 数据类型 | JSON 文件 | 目标表 | 初始状态 | 校验要点 |
|---------|----------|--------|---------|---------|
| 知识点 | topics.json | knowledge_point | pending_review | id 非空、name 非空、level 推导合理 |
| 题目 | questions.json | question_bank | 立即生效 | topicId 外键存在、prompt 非空、answer 非空、difficulty ∈ {basic, standard, challenge}、type ∈ {numeric, choice, short, self} |
| 先修关系 | dependencies.json | knowledge_point_dependency | 立即生效 | topicId/prerequisiteId 外键存在、strength ∈ {hard, soft} |
| 课程标准 | cn-curriculum-standards.json | curriculum_standard | 只读参考 | id 非空、text 非空、subject/stage 可从 id 推导 |

### 2.3 导入顺序约束

由于外键依赖关系，**必须按以下顺序导入**：

1. **知识点**（topics.json）→ knowledge_point
2. **课程标准**（cn-curriculum-standards.json）→ curriculum_standard
3. **先修关系**（dependencies.json）→ knowledge_point_dependency（依赖知识点已存在）
4. **题目**（questions.json）→ question_bank（依赖知识点已存在）

外键缺失时：记录到 error_log，跳过该条，不阻塞其他记录。

### 2.4 本 change 不导入的表

- `knowledge_point_standard`（知识点-课程标准关联表）：JSON 数据中无关联字段，不在本 change 导入范围

---

## 3. 详细设计

### 3.1 DataImportService 接口

```java
public interface DataImportService {
    /**
     * 执行数据导入
     * @param dataType 数据类型：topic / question / dependency / standard
     * @return 导入结果（成功数、失败数、跳过数、错误摘要）
     */
    ImportResultDTO importData(String dataType);
    
    /**
     * 查询导入日志列表
     */
    PageResult<ImportLogVO> getImportLogs(String dataType, int page, int size);
    
    /**
     * 查询导入错误记录
     */
    PageResult<DataImportErrorLog> getImportErrors(Long importId, int page, int size);
}
```

### 3.2 ImportResultDTO

```java
@Data
@Builder
public class ImportResultDTO {
    private Long importId;
    private String dataType;
    private String fileSource;
    private int successCount;
    private int failCount;
    private int skipCount;
    private List<String> errorSamples;  // 前 5 条错误摘要
    private LocalDateTime importedAt;
}
```

**errorSamples 格式规范**：每条格式为 `"[recordId] 原因"`，例如：
- `"[cnm_p001] ID 冲突，已跳过"`
- `"[cnm_p005_q2] topicId=cnm_p999 不存在"`
- `"[cms-2022:1.NA.01] 必填字段 text 为空"`

### 3.3 ImportLogVO 字段映射

| ImportLogVO 字段 | DataImportLog 实体字段 | 说明 |
|-----------------|----------------------|------|
| id | importId | 主键 |
| dataType | dataType | 数据类型 |
| fileSource | fileSource | 文件来源 |
| successCount | successCount | 成功数 |
| failCount | failCount | 失败数 |
| skipCount | skipCount | 跳过数 |
| importedAt | importedAt | 导入时间 |

> **注**：当前 `data_import_log` 表无 `status` / `error_message` 列。导入状态通过计数推断：importedAt 已设置且 successCount+failCount+skipCount > 0 表示已完成。错误明细通过 `/errors` 接口按 importId 查询。

### 3.4 JSON 文件读取方式

```java
// 使用 ClassPathResource 读取 classpath:json/ 下的文件
Resource resource = new ClassPathResource("json/topics.json");
try (InputStream is = resource.getInputStream()) {
    JsonNode root = objectMapper.readTree(is);
    // topics.json / questions.json / dependencies.json 直接是数组
    // cn-curriculum-standards.json 需读取 root.get("items")
}
```

### 3.5 导入实现核心逻辑（事务策略）

```java
@Service
@Slf4j
public class DataImportServiceImpl implements DataImportService {
    
    private final TransactionTemplate transactionTemplate;  // 注入
    
    // 文件路径映射
    private static final Map<String, String> FILE_PATH_MAP = Map.of(
        "topic", "json/topics.json",
        "question", "json/questions.json",
        "dependency", "json/dependencies.json",
        "standard", "json/cn-curriculum-standards.json"
    );
    
    @Override
    public ImportResultDTO importData(String dataType) {
        // 并发控制：Demo 阶段专用锁对象（生产环境需改为分布式锁）
        synchronized (importLock) {
            return doImport(dataType);
        }
    }
    
    private ImportResultDTO doImport(String dataType) {
        String filePath = FILE_PATH_MAP.get(dataType);
        if (filePath == null) {
            throw new BusinessException(ErrorCodes.PARAM_ERROR, "不支持的数据类型: " + dataType);
        }
        
        // 1. 读取 JSON 文件
        List<?> records = readJsonFile(filePath, dataType);
        
        // 2. 先创建导入日志（初始计数=0，纳入独立事务）
        DataImportLog importLog = new DataImportLog();
        importLog.setDataType(dataType);
        importLog.setFileSource(filePath);
        importLog.setSuccessCount(0);
        importLog.setFailCount(0);
        importLog.setSkipCount(0);
        importLog.setImportedAt(LocalDateTime.now());
        transactionTemplate.executeWithoutResult(status -> {
            importLogMapper.insert(importLog);
        });
        
        // 3. 逐条校验并导入（每条记录独立事务）
        int successCount = 0, failCount = 0, skipCount = 0;
        List<DataImportErrorLog> errors = new ArrayList<>();
        List<String> errorSamples = new ArrayList<>();
        
        for (Object record : records) {
            ValidationResult result = validate(record, dataType);
            if (!result.isValid()) {
                failCount++;
                DataImportErrorLog errorLog = buildErrorLog(importLog.getImportId(), result);
                errors.add(errorLog);
                if (errorSamples.size() < 5) {
                    errorSamples.add(String.format("[%s] %s", result.getRecordId(), result.getMessage()));
                }
                continue;
            }
            
            // 每条记录独立事务（REQUIRES_NEW 语义）
            try {
                transactionTemplate.execute(status -> {
                    insertRecord(record, dataType);
                    return null;
                });
                successCount++;
            } catch (DuplicateKeyException e) {
                skipCount++;
                log.warn("ID 冲突，跳过: dataType={}, id={}", dataType, getRecordId(record));
            } catch (DataIntegrityViolationException e) {
                // 外键校验预查失败（如 topicId 不存在）或字段超长等
                failCount++;
                String recordId = getRecordId(record);
                // 脱敏：不直接暴露 SQL 异常信息，使用固定描述
                String msg = String.format("[%s] 数据完整性约束违反（可能为外键缺失或字段超长）", recordId);
                log.warn("导入记录数据完整性异常: dataType={}, recordId={}", dataType, recordId, e);
                errors.add(buildErrorLog(importLog.getImportId(), recordId, msg));
                if (errorSamples.size() < 5) errorSamples.add(msg);
            } catch (Exception e) {
                failCount++;
                String msg = String.format("[%s] %s", getRecordId(record), e.getMessage());
                errors.add(buildErrorLog(importLog.getImportId(), getRecordId(record), e.getMessage()));
                if (errorSamples.size() < 5) errorSamples.add(msg);
            }
        }
        
        // 4. 批量写入错误记录（独立事务）
        if (!errors.isEmpty()) {
            transactionTemplate.execute(status -> {
                errorLogMapper.batchInsert(errors);
                return null;
            });
        }
        
        // 5. 更新导入日志（独立事务）
        importLog.setSuccessCount(successCount);
        importLog.setFailCount(failCount);
        importLog.setSkipCount(skipCount);
        transactionTemplate.execute(status -> {
            importLogMapper.updateById(importLog);
            return null;
        });
        
        return ImportResultDTO.builder()
            .importId(importLog.getImportId())
            .dataType(dataType)
            .fileSource(filePath)
            .successCount(successCount)
            .failCount(failCount)
            .skipCount(skipCount)
            .errorSamples(errorSamples)
            .importedAt(LocalDateTime.now())
            .build();
    }
}
```

### 3.6 字段映射表（JSON → 数据库）—— 基于真实 DB Schema 核实

#### 3.6.1 知识点（topics.json → knowledge_point）

**DB 表结构**（V1__init_schema.sql）：
`knowledge_point_id(PK)`, `subject`, `name`, `level`(1-3), `parent_id`, `status`, `standards`(JSON), `mastery_criteria`(JSON), `diagnostic_question`(JSON), `tenant_id`, `create_time`, `update_time`

**JSON 实际字段**（topics.json 样例核实）：
`id`, `name`, `domain`, `grade`, `semester`, `chapter`, `type`(conceptual/procedural), `description`, `mastery_criteria`(数组), `diagnostic_question`(对象), `external`(布尔), `standards`(数组), `root_reason`(可选)

| JSON 字段 | DB 列 | 映射说明 |
|-----------|-------|---------|
| id | knowledge_point_id | 直接映射（VARCHAR(64)，如 "cnm_p001"） |
| — | subject | 固定值 `"数学"`（所有数据均为数学） |
| name | name | 直接映射 |
| — | level | **需从 chapter 字段推导**：按 grade 推断（grade 1-6 → level=1 章节，grade 7-9 → level=1，高中 → level=1）；同 grade 内按 domain+chapter 分组细化 level=2/3。**推导规则见下方** |
| — | parent_id | **需从 chapter 层级推导**（level≥2 时取上级 chapter 对应的 knowledge_point_id）。无直接 JSON 字段 |
| — | status | 固定值 `"pending_review"` |
| standards | standards | JSON 数组序列化为字符串存储 |
| mastery_criteria | mastery_criteria | JSON 数组序列化为字符串存储 |
| diagnostic_question | diagnostic_question | JSON 对象序列化为字符串存储 |

**JSON 中存在但 DB 无对应列的字段（数据丢弃）**：

| JSON 字段 | 丢弃理由 |
|-----------|---------|
| domain | 知识领域（如"数与代数"），DB 无此列 |
| grade | 年级（1-12），DB 无此列 |
| semester | 学期（上/下），DB 无此列 |
| chapter | 章节路径，DB 无此列（但用于推导 level/parent_id） |
| type | 知识点类型（conceptual/procedural），DB 无此列 |
| description | 知识点描述，DB 无此列 |
| external | 是否外部知识点，DB 无此列 |
| root_reason | 根因说明，DB 无此列（仅部分条目有） |

**level / parent_id 推导规则**：

topics.json 的 `chapter` 字段格式为 `"一年级上册 第1单元 5以内数的认识和加、减法"`，可按 grade 和 chapter 前缀分组推导层级：
- **level=1（章节）**：每个 grade+semester 的第一个 chapter 层级
- **level=2（知识点）**：chapter 内的独立知识点（大多数 topic 属于此层）
- **level=3（子知识点）**：如有嵌套关系

> **实现策略**：Demo 阶段，所有知识点统一设 `level=2`，`parent_id=null`。后续 change 可通过 chapter 字段解析建立层级关系。

#### 3.6.2 题目（questions.json → question_bank）

**DB 表结构**（V1__init_schema.sql）：
`question_id(PK, BIGINT AUTO_INCREMENT)`, `knowledge_point_id`, `content`(TEXT), `choices`(JSON), `answer`, `analysis`(TEXT), `difficulty`, `type`, `tenant_id`, `create_time`, `update_time`

**JSON 实际字段**（questions.json 样例核实）：
`id`(字符串如"cnm_p001_q1"), `topicId`, `difficulty`, `type`, `prompt`, `answer`, `explanation`, `verify`, `choices`(数组，仅 choice 类型)

| JSON 字段 | DB 列 | 映射说明 |
|-----------|-------|---------|
| — | question_id | **DB 为 BIGINT AUTO_INCREMENT**，JSON 的字符串 id（如 "cnm_p001_q1"）**丢弃**，由 DB 自动生成 |
| topicId | knowledge_point_id | 直接映射（外键，导入前必须已完成知识点导入） |
| prompt | content | 字段名不同，语义一致 |
| answer | answer | 直接映射 |
| explanation | analysis | 字段名不同，语义一致 |
| difficulty | difficulty | 直接映射 |
| type | type | 直接映射 |
| choices | choices | JSON 数组序列化为字符串存储（仅 choice 类型有值） |
| verify | — | **DB 无 verify_expr 列**，原样丢弃（verify 为 Python 表达式字符串，Java 不执行） |

**枚举定义（已核实真实数据）**：

| 字段 | 枚举值 | DB schema 定义 | 数据源归一化 |
|------|--------|---------------|-------------|
| difficulty | `basic` / `standard` / `challenge` | `'难度：basic / standard / challenge'` | 数据源含 `advanced`，运行时归一化为 `challenge` |
| type | `numeric` / `choice` / `short` / `self` | `'题型：numeric / choice'`（DB 为 VARCHAR(20) 无 CHECK 约束，可直接扩展；真实数据含全部四种类型） | — |

**各 type 字段结构差异**：

| type 值 | 说明 | JSON 必填字段 | JSON 可选字段 |
|---------|------|-------------|-------------|
| numeric | 数值题 | prompt, answer | verify, explanation |
| choice | 选择题 | prompt, answer, choices | verify, explanation |
| short | 简答题 | prompt, answer | explanation |
| self | 自评题 | prompt | answer（可选）, explanation |

**JSON 中存在但 DB 无对应列的字段（数据丢弃）**：

| JSON 字段 | 丢弃理由 |
|-----------|---------|
| id | DB 使用 BIGINT AUTO_INCREMENT，JSON 字符串 ID 不导入 |
| verify | Python 表达式，DB 无对应列，Java 不执行 |

> **注意**：真实数据中**不存在** figure、unordered、fixed_from_source、hideChoiceText 字段。

#### 3.6.3 先修关系（dependencies.json → knowledge_point_dependency）

**DB 表结构**（V1__init_schema.sql）：
`dependency_id(PK, BIGINT AUTO_INCREMENT)`, `knowledge_point_id`, `prerequisite_id`, `dependency_type`, `tenant_id`, `create_time`, `update_time`

**JSON 实际字段**（dependencies.json 样例核实）：
`topicId`, `prerequisiteId`, `strength`, `reason`

| JSON 字段 | DB 列 | 映射说明 |
|-----------|-------|---------|
| — | dependency_id | DB AUTO_INCREMENT，自动生成 |
| topicId | knowledge_point_id | 字段名不同，直接映射 |
| prerequisiteId | prerequisite_id | 字段名不同，直接映射 |
| strength | dependency_type | **字段名不同**，值映射：hard→hard, soft→soft |

**校验规则**：
- `strength` ∈ {hard, soft}，否则记录 error_log 跳过
- `topicId` 和 `prerequisiteId` 必须在 knowledge_point 表中存在（预查：批量 `selectBatchIds` 加载已导入的 knowledgePointId 集合）

**JSON 中存在但 DB 无对应列的字段（数据丢弃）**：

| JSON 字段 | 丢弃理由 |
|-----------|---------|
| reason | 先修原因描述，DB 无对应列 |

#### 3.6.4 课程标准（cn-curriculum-standards.json → curriculum_standard）

**DB 表结构**（V1__init_schema.sql）：
`standard_id(PK, BIGINT AUTO_INCREMENT)`, `subject`, `stage`, `code`, `description`(TEXT), `tenant_id`, `create_time`, `update_time`
唯一索引：`uk_subject_code(subject, code, tenant_id)`

**JSON 实际字段**（cn-curriculum-standards.json 样例核实）：
每个 item：`id`, `domain`, `section`, `clause`, `text`, `page_or_ref`, `verbatim`(布尔), `text_status`, `note`(可选), `elective`(可选布尔)
顶层：`source`(元数据对象), `items`(数组)

| JSON 字段 | DB 列 | 映射说明 |
|-----------|-------|---------|
| id | code | **字段名不同**，直接映射（如 "cms-2022:1.NA.01"） |
| — | subject | **从 id 前缀推导**：所有 "cms-" 前缀 → `"数学"` |
| — | stage | **从 id 推导**：见下方推导规则 |
| text | description | **字段名不同**，课标正文存入 description |

**stage 推导规则（模式匹配，支持 cms/ces/cps 全系列前缀）**：

| id 模式 | stage 值 | 说明 | 示例 |
|---------|---------|------|------|
| 含 `:H.` 或 `hs-` | `"高中"` | 普通高中 | `cms-2017:H.GA.04`、`cps-hs-2017:B1.1.1` |
| 冒号后数字 1~3 | `"小学"` | 义务教育第一至第三学段（1-6 年级） | `cms-2022:1.NA.01`、`ces-2022:1.PH.01`、`cps-2022:1.1.1` |
| 冒号后数字 4 | `"初中"` | 义务教育第四学段（7-9 年级） | `cms-2022:4.GG.50`、`ces-2022:4.*`、`cps-2022:4.1.1` |
| 冒号后数字 5 | `"高中"` | 普通高中 | `cps-2022:5.1.1` |

推导失败时：记录 error_log，跳过该条。

**顶层 source 元数据处理**：
- `source` 对象（含 title, issuer, url, revision 等 15+ 字段）序列化为 JSON 字符串，写入 `data_import_log.file_source`
- 学科补充块（如 "高中数学补充"）同样追加到 file_source

**JSON 中存在但 DB 无对应列的字段（数据丢弃）**：

| JSON 字段 | 丢弃理由 |
|-----------|---------|
| domain | 知识领域（如"数与代数"），DB 无此列 |
| section | 章节（如"数与运算"），DB 无此列 |
| clause | 条款编号（如"(1)"），DB 无此列 |
| page_or_ref | 页码引用，DB 无此列 |
| verbatim | 是否原文，DB 无此列 |
| text_status | 文本状态，DB 无此列 |
| note | 注释（仅部分条目有），DB 无此列 |
| elective | 是否选学（仅 3 条为 true），DB 无此列 |

> **数据丢失说明**：curriculum_standard 表设计较简略（仅 subject/stage/code/description），JSON 中 domain/section/clause 等结构化字段只能丢弃。如需保留完整课标结构，需后续 change 扩展 DB 列。

**批量策略**：
- 1200+ 条课程标准，按每 500 条一批处理
- **每条记录使用 TransactionTemplate 独立事务**（与其他三种类型保持一致），单条失败不影响同批次其他记录
- 每批写进度日志（log.info）

### 3.7 外键校验策略（预查模式）

为避免事务内异常回滚复杂化，**外键校验采用预查模式**：

```java
// 题目/先修关系导入前：批量加载已导入的 knowledgePointId 集合
Set<String> validKpIds = new HashSet<>();
try {
    List<KnowledgePoint> all = knowledgePointMapper.selectList(
            new LambdaQueryWrapper<KnowledgePoint>().select(KnowledgePoint::getKnowledgePointId));
    all.forEach(kp -> validKpIds.add(kp.getKnowledgePointId()));
} catch (Exception e) {
    log.error("预加载知识点 ID 集合失败", e);
    throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "外键预查失败，无法执行导入: " + e.getMessage());
}

// 逐条校验时直接查集合
if (!validKpIds.contains(question.getTopicId())) {
    // 记录 error_log，跳过
}
```

先修关系导入同理，预加载 knowledgePointId 集合后逐条校验。

### 3.8 DataImportController

```java
@RestController
@RequestMapping("/api/admin/import")
@Tag(name = "数据导入管理", description = "管理后端数据导入接口")
public class DataImportController {
    
    @PostMapping
    @Operation(summary = "执行数据导入")
    public Result<ImportResultDTO> importData(@RequestBody @Valid ImportRequest request) {
        ImportResultDTO result = dataImportService.importData(request.getDataType());
        return Result.success(result);
    }
    
    @GetMapping("/logs")
    @Operation(summary = "查询导入日志")
    public Result<PageResult<ImportLogVO>> getImportLogs(
            @RequestParam(required = false) String dataType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<ImportLogVO> result = dataImportService.getImportLogs(dataType, page, size);
        return Result.success(result);
    }
    
    @GetMapping("/errors")
    @Operation(summary = "查询导入错误记录")
    public Result<PageResult<DataImportErrorLog>> getImportErrors(
            @RequestParam Long importId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<DataImportErrorLog> result = dataImportService.getImportErrors(importId, page, size);
        return Result.success(result);
    }
}
```

---

## 4. 数据库变更

### 4.1 本 change 不创建新表

所有目标表已在 foundation change 中创建：
- `knowledge_point`、`question_bank`、`knowledge_point_dependency`、`curriculum_standard`
- `data_import_log`、`data_import_error_log`

### 4.2 本 change 不修改 DB Schema（DDL 不变）

所有字段映射均在现有 schema 范围内完成，部分 JSON 字段因 DB 无对应列而丢弃（见 §3.6 各表"数据丢弃"说明）。

### 4.3 字段映射依赖的现有列确认

| 目标表 | 依赖列 | 确认状态 |
|--------|--------|---------|
| knowledge_point | knowledge_point_id(VARCHAR 64) | ✅ 已存在 |
| knowledge_point | subject/stage/level/parent_id/status | ✅ 已存在 |
| knowledge_point | standards/mastery_criteria/diagnostic_question (JSON) | ✅ 已存在 |
| question_bank | question_id(BIGINT AUTO_INCREMENT) | ✅ 已存在 |
| question_bank | knowledge_point_id/content/choices/answer/analysis/difficulty/type | ✅ 已存在 |
| knowledge_point_dependency | dependency_id(BIGINT AUTO_INCREMENT) | ✅ 已存在 |
| knowledge_point_dependency | knowledge_point_id/prerequisite_id/dependency_type | ✅ 已存在 |
| curriculum_standard | standard_id(BIGINT AUTO_INCREMENT) | ✅ 已存在 |
| curriculum_standard | subject/stage/code/description | ✅ 已存在 |
| data_import_log | import_id/data_type/file_source/success_count/fail_count/skip_count/imported_at | ✅ 已存在 |
| data_import_error_log | error_id/import_id/record_id/error_reason | ✅ 已存在 |

---

## 5. 异常处理设计

| 异常场景 | 错误码 | HTTP 状态码 | 处理方式 |
|---------|--------|------------|----------|
| dataType 参数无效 | 400 | 200 | 返回 ImportResultDTO（failCount=0, errorSamples 含错误信息） |
| JSON 文件不存在 | 500 | 200 | 返回 ImportResultDTO（failCount=0, errorSamples 含错误信息） |
| JSON 解析失败 | 500 | 200 | 返回 ImportResultDTO（failCount=0, errorSamples 含错误信息） |
| 单条记录校验失败 | — | — | 记录到 error_log，不阻塞其他记录 |
| 单条记录插入冲突（DuplicateKeyException） | — | — | skipCount++，记录警告日志 |
| 单条记录外键预查失败（DataIntegrityViolationException） | — | — | failCount++，记录到 error_log |
| 文件级/系统级错误 | 500 | 200 | 由 ImportResultDTO 计数体现，非参数错误一律返回 200 |

**设计原则**：非参数/文件级错误一律返回 HTTP 200，由 ImportResultDTO 的 successCount/failCount/skipCount/errorSamples 体现具体结果。

---

## 6. 隐性约定对齐

| 约定 | 本 change 实现方式 |
|------|-------------------|
| 约定 #1：统一响应结构 | 所有接口返回 `Result<T>` |
| 约定 #2：异常处理规范 | 参数级错误由 ImportResultDTO 体现；系统级错误抛出 `BusinessException` |
| 约定 #4：时间格式规范 | 使用 `LocalDateTime`，由 MyBatis-Plus 自动填充 |
| 约定 #9：@Transactional rollbackFor | **不使用大事务包裹主流程**；每条记录用 `TransactionTemplate` 独立事务；错误日志和导入日志各自独立事务写入 |

---

## 7. 文件清单

| 文件路径 | 说明 |
|---------|------|
| `src/main/java/com/jiang/service/DataImportService.java` | 数据导入 Service 接口 |
| `src/main/java/com/jiang/service/impl/DataImportServiceImpl.java` | 数据导入 Service 实现 |
| `src/main/java/com/jiang/controller/DataImportController.java` | 数据导入 Controller |
| `src/main/java/com/jiang/dto/ImportRequest.java` | 导入请求 DTO |
| `src/main/java/com/jiang/dto/ImportResultDTO.java` | 导入结果 DTO |
| `src/main/java/com/jiang/vo/ImportLogVO.java` | 导入日志 VO |
| `src/test/java/com/jiang/service/DataImportServiceTest.java` | Service 单元测试 |
| `src/test/java/com/jiang/controller/DataImportControllerTest.java` | Controller 集成测试 |

---

## 8. 测试策略

### 8.1 单元测试

| 测试类 | 测试对象 | 测试场景 |
|--------|---------|----------|
| `DataImportServiceTest` | DataImportServiceImpl | 知识点导入成功（字段映射正确、status=pending_review、subject="数学"） |
| `DataImportServiceTest` | DataImportServiceImpl | 题目导入成功（prompt→content、explanation→analysis、difficulty ∈ {basic,standard,challenge}、type ∈ {numeric,choice,short,self}） |
| `DataImportServiceTest` | DataImportServiceImpl | 先修关系导入成功（topicId→knowledge_point_id、strength→dependency_type） |
| `DataImportServiceTest` | DataImportServiceImpl | 课程标准批量导入成功（id→code、text→description、subject/stage 模式匹配推导正确：含 :H./hs-→高中、冒号后1~3→小学、4→初中、5→高中，500 条/批） |
| `DataImportServiceTest` | DataImportServiceImpl | ID 冲突跳过（DuplicateKeyException → skipCount++） |
| `DataImportServiceTest` | DataImportServiceImpl | 校验失败记录错误日志（failCount++、error_log 写入、errorSamples 格式正确） |
| `DataImportServiceTest` | DataImportServiceImpl | 外键预查失败（topicId 不存在 → error_log） |
| `DataImportServiceTest` | DataImportServiceImpl | JSON 文件不存在异常 |
| `DataImportServiceTest` | DataImportServiceImpl | 导入顺序约束（question 依赖 topic 已导入） |
| `DataImportServiceTest` | DataImportServiceImpl | 每条记录独立事务（单条失败不影响其他记录） |

### 8.2 集成测试

| 测试类 | 测试对象 | 测试场景 |
|--------|---------|----------|
| `DataImportControllerTest` | DataImportController | 导入接口正常返回（ImportResultDTO 含 errorSamples）、参数校验失败、日志查询（分页）、错误记录查询（分页） |

### 8.3 Mock 策略

- **数据库**：使用 `@MybatisPlusTest` 或 H2 内存数据库
- **JSON 文件**：使用测试资源文件（`src/test/resources/json/`）
- **TransactionTemplate**：使用真实 Spring 事务管理器

---

## 9. 验收标准

- [ ] 四种数据类型均可从 JSON 文件成功导入数据库
- [ ] 知识点导入后 status=pending_review、subject="数学"、level/parent_id 合理
- [ ] 题目导入后 knowledge_point_id 外键校验通过（知识点必须先导入）
- [ ] 题目 difficulty ∈ {basic, standard, challenge, advanced}（advanced 运行时归一化为 challenge），type ∈ {numeric, choice, short, self}
- [ ] 先修关系导入后 dependency_type ∈ {hard, soft}（从 strength 映射）
- [ ] 课程标准导入采用批量策略（500 条/批），1200+ 条全部导入
- [ ] 课程标准 code 从 JSON id 映射，description 从 JSON text 映射，subject/stage 从 id 模式匹配推导（含 :H./hs-→高中、冒号后1~3→小学、4→初中、5→高中）
- [ ] ID 冲突时跳过并记录警告，不阻塞其他记录
- [ ] 校验失败的记录写入 `data_import_error_log`，不阻塞其他记录
- [ ] 导入操作记录写入 `data_import_log`（成功数、失败数、跳过数）
- [ ] 每条记录独立事务，单条失败不影响其他记录
- [ ] 导入接口返回 ImportResultDTO（含 errorSamples 前 5 条错误摘要，格式 "[recordId] 原因"）
- [ ] 导入日志查询接口可正常返回（分页）
- [ ] 导入错误记录查询接口可正常返回（分页）
- [ ] 单元测试覆盖 Service 核心逻辑（含事务策略、批量策略、外键预查、字段映射）
- [ ] 集成测试覆盖 Controller 接口
- [ ] 编译通过，测试通过
