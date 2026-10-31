# Tasks：知识点与题库数据导入

> **Change ID**: `2026-10-31_data_import`  
> **关联 Design**: design.md

---

## Task 1：创建 DTO/VO 类

**任务描述**：
- 创建 `ImportRequest` 请求 DTO（包含 dataType 字段，@Valid 校验）
- 创建 `ImportResultDTO` 响应 DTO（包含 importId、dataType、fileSource、successCount、failCount、skipCount、errorSamples、importedAt）
- 创建 `ImportLogVO` 日志 VO（字段映射自 DataImportLog 实体：importId→id、dataType、fileSource、successCount、failCount、skipCount、importedAt）

**预估时间**：0.5h

**涉及文件**：
- `src/main/java/com/jiang/dto/ImportRequest.java`（新建）
- `src/main/java/com/jiang/dto/ImportResultDTO.java`（新建）
- `src/main/java/com/jiang/vo/ImportLogVO.java`（新建）

---

## Task 2：创建 DataImportService 接口

**任务描述**：
- 定义 `importData(String dataType)` 方法（返回 ImportResultDTO）
- 定义 `getImportLogs(String dataType, int page, int size)` 方法（返回 PageResult<ImportLogVO>）
- 定义 `getImportErrors(Long importId, int page, int size)` 方法（返回 PageResult<DataImportErrorLog>）

**预估时间**：0.5h

**涉及文件**：
- `src/main/java/com/jiang/service/DataImportService.java`（新建）

---

## Task 3：实现 DataImportService

**任务描述**：
- 实现 JSON 文件读取（使用 ClassPathResource 读取 classpath:json/ 下的文件）
- 实现导入流程：创建 import_log（初始计数=0，纳入独立事务）→ 预加载外键集合 → 逐条校验 → 独立事务插入 → 批量写 error_log → 更新 import_log（最终计数）
- 实现事务策略：主流程不用大事务，每条记录用 `TransactionTemplate` 独立事务；错误日志/导入日志各自独立事务写入
- 实现并发控制：Demo 阶段 `importData` 方法使用专用锁对象 `importLock` 加 `synchronized`（生产环境需改为分布式锁）
- 实现四种数据类型的校验与字段映射（**已核实真实 JSON 与 DB Schema**）：

  **知识点**（topics.json → knowledge_point）：
  - id → knowledge_point_id，name → name，subject 固定 "数学"，status 固定 "pending_review"
  - level 统一设 2，parent_id 设 null（Demo 阶段）
  - standards/mastery_criteria/diagnostic_question 序列化为 JSON 字符串存储
  - domain/grade/semester/chapter/type/description/external/root_reason 丢弃（DB 无对应列）
  - 校验：id 非空、name 非空

  **题目**（questions.json → question_bank）：
  - topicId → knowledge_point_id（外键预查），prompt → content，answer → answer，explanation → analysis
  - difficulty ∈ {basic, standard, challenge, advanced}（advanced 运行时归一化为 challenge），type ∈ {numeric, choice, short, self}
  - choices → JSON 字符串存储（仅 choice 类型有值）
  - verify 丢弃（Python 表达式，DB 无列），JSON 字符串 id 丢弃（DB 用 AUTO_INCREMENT）
  - 校验：topicId 存在于 knowledge_point、prompt 非空、answer 非空（self 类型可选）、difficulty/type 枚举

  **先修关系**（dependencies.json → knowledge_point_dependency）：
  - topicId → knowledge_point_id，prerequisiteId → prerequisite_id，strength → dependency_type
  - reason 丢弃（DB 无对应列）
  - 校验：topicId/prerequisiteId 存在（预查）、strength ∈ {hard, soft}

  **课程标准**（cn-curriculum-standards.json → curriculum_standard）：
  - id → code，text → description，subject 固定 "数学"
  - stage 从 id 模式匹配推导（支持 cms/ces/cps 全系列前缀）：
    - 含 `:H.` 或 `hs-` → "高中"（如 cms-2017:H.GA.04、cps-hs-2017:B1.1.1）
    - 冒号后数字 1~3 → "小学"（如 cms-2022:1.NA.01、ces-2022:1.PH.01、cps-2022:1.1.1）
    - 冒号后数字 4 → "初中"（如 cms-2022:4.GG.50、ces-2022:4.*、cps-2022:4.1.1）
    - 冒号后数字 5 → "高中"（如 cps-2022:5.1.1）
  - domain/section/clause/page_or_ref/verbatim/text_status/note/elective 丢弃（DB 无对应列）
  - 顶层 source 元数据序列化写入 import_log.file_source
  - 1200+ 条按 500 条/批处理，每条记录使用 TransactionTemplate 独立事务，单条失败不影响同批次其他记录
  - 每批写进度日志（log.info）
  - 校验：id 非空、text 非空、stage 可推导

- 实现异常处理：
  - DuplicateKeyException → skipCount++，记录警告日志
  - DataIntegrityViolationException（外键预查失败）→ failCount++，记录 error_log
  - 其他异常 → failCount++，记录 error_log
  - 所有异常不阻塞主流程，由 ImportResultDTO 计数体现
- 实现 errorSamples：前 5 条错误摘要，格式 `"[recordId] 原因"`

**预估时间**：4h（含字段映射、事务策略、批量策略、外键预查、并发控制）

**涉及文件**：
- `src/main/java/com/jiang/service/impl/DataImportServiceImpl.java`（新建）

---

## Task 4：创建 DataImportController

**任务描述**：
- 实现 POST `/api/admin/import` 接口（执行数据导入，参数校验）
- 实现 GET `/api/admin/import/logs` 接口（查询导入日志列表，分页）
- 实现 GET `/api/admin/import/errors` 接口（查询导入错误记录，分页）
- 所有接口添加 Knife4j 注解（@Tag、@Operation）
- 所有接口返回 `Result<T>` 统一响应结构

**预估时间**：1h

**涉及文件**：
- `src/main/java/com/jiang/controller/DataImportController.java`（新建）

---

## Task 5：编写 DataImportService 单元测试

**任务描述**：
- 测试知识点导入成功场景（字段映射正确：id→knowledge_point_id、subject="数学"、status="pending_review"、JSON 字段序列化）
- 测试题目导入成功场景（prompt→content、explanation→analysis、difficulty ∈ {basic,standard,challenge}、type ∈ {numeric,choice,short,self}、choices JSON 序列化）
- 测试先修关系导入成功场景（topicId→knowledge_point_id、strength→dependency_type）
- 测试课程标准批量导入成功场景（id→code、text→description、stage 模式匹配推导：含 :H./hs-→高中、冒号后1~3→小学、4→初中、5→高中，500 条/批）
- 测试 ID 冲突跳过场景（DuplicateKeyException → skipCount++）
- 测试校验失败记录错误日志场景（failCount++、error_log 写入、errorSamples 格式 "[recordId] 原因"）
- 测试外键预查失败场景（topicId 不存在 → error_log）
- 测试 JSON 文件不存在异常场景
- 测试导入顺序约束场景（question 依赖 topic 已导入）
- 测试每条记录独立事务场景（单条失败不影响其他记录）
- Mock 策略：使用 H2 内存数据库 + 测试资源 JSON 文件

**预估时间**：1.5h

**涉及文件**：
- `src/test/java/com/jiang/service/DataImportServiceTest.java`（新建）

---

## Task 6：编写 DataImportController 集成测试

**任务描述**：
- 测试导入接口正常返回场景（ImportResultDTO 含 successCount/failCount/skipCount/errorSamples）
- 测试参数校验失败场景（dataType 无效 → 返回错误摘要）
- 测试日志查询接口场景（分页返回 PageResult<ImportLogVO>）
- 测试错误记录查询接口场景（分页返回 PageResult<DataImportErrorLog>）

**预估时间**：1h

**涉及文件**：
- `src/test/java/com/jiang/controller/DataImportControllerTest.java`（新建）

---

## 汇总

| Task | 预估时间 | 依赖 |
|------|---------|------|
| Task 1：DTO/VO 类 | 0.5h | 无 |
| Task 2：Service 接口 | 0.5h | Task 1 |
| Task 3：Service 实现 | 4h | Task 1, Task 2 |
| Task 4：Controller | 1h | Task 1, Task 2, Task 3 |
| Task 5：Service 单元测试 | 1.5h | Task 3 |
| Task 6：Controller 集成测试 | 1h | Task 4 |
| **总计** | **8.5h** | — |

**备注**：
- Task 3 预估 4h（含字段映射、事务策略、批量策略、外键预查、并发控制），视 JSON 字段复杂度可能上浮
- 导入顺序约束：必须按 topic → standard → dependency → question 顺序导入
- 本 change 不导入 knowledge_point_standard 关联表（JSON 数据中无关联字段）
- 本 change 不修改 DB Schema（DDL 不变），部分 JSON 字段因 DB 无对应列而丢弃
- 所有字段映射已核实真实 JSON 数据与 V1__init_schema.sql 表结构
