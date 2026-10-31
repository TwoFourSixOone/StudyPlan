# Proposal：知识点与题库数据导入

> **Change ID**: `2026-10-31_data_import`  
> **创建日期**: 2026-10-31  
> **状态**: 草案

---

## 1. 需求背景

智学伴系统已完成基础架构搭建（foundation change），数据库表结构已创建完毕，包括知识点表、题库表、先修关系表、课程标准表等。

当前系统中这些表均为空，需要导入初始数据才能支撑后续的学习计划生成、知识点评测、考试分析等核心功能。

数据来源为 JSON 文件（存放在 `src/main/resources/json/` 目录下），包括：
- `topics.json` — 知识点数据（约 75000+ 行）
- `questions.json` — 题库数据（约 265000+ 行）
- `dependencies.json` — 先修关系数据（约 25000+ 行）
- `cn-curriculum-standards.json` — 课程标准数据（约 7000+ 行，含 1200+ 条课标 + 顶层 source 元数据 + 学科补充块）

---

## 2. 核心目标

实现管理后端的数据导入功能，支持以下四种数据类型的导入：

1. **知识点导入**：从 `topics.json` 读取，写入 `knowledge_point` 表
2. **题目导入**：从 `questions.json` 读取，写入 `question_bank` 表
3. **先修关系导入**：从 `dependencies.json` 读取，写入 `knowledge_point_dependency` 表
4. **课程标准导入**：从 `cn-curriculum-standards.json` 读取，写入 `curriculum_standard` 表

**导入原则**：
- **追加模式**：只插入新记录，ID 冲突时跳过并记录警告
- **失败隔离**：单条记录失败不阻塞其他记录，失败记录单独存储到 `data_import_error_log` 表
- **独立事务**：每条记录用独立事务（TransactionTemplate），不使用大事务包裹整个导入流程
- **导入日志**：每次导入操作记录到 `data_import_log` 表（含成功数、失败数、跳过数）
- **顺序约束**：必须按 topic → standard → dependency → question 顺序导入（外键依赖）
- **批量策略**：课程标准 1200+ 条按 500 条/批 batchInsert + 分批提交 + 每批进度日志
- **字段映射**：JSON 字段名与 DB 列名不完全一致，需在映射层转换（详见 design §3.6）

---

## 3. 功能范围

### 包含

- 数据导入接口（管理后端调用）
- 导入进度记录（导入日志表）
- 导入错误记录（错误日志表）
- 导入日志查询接口（分页）
- 导入错误记录查询接口（分页）
- 导入结果返回统计摘要（含 errorSamples 前 5 条错误，格式 "[recordId] 原因"）
- 四种数据类型的完整性校验
- 外键预查（批量加载已导入 ID 集合，逐条校验）
- 并发控制（Demo 阶段 synchronized，生产需改为分布式锁）
- JSON → DB 字段名映射转换（如 topicId → knowledge_point_id、strength → dependency_type、prompt → content 等）

### 不包含

- 数据导出功能
- 数据清理/重置功能
- 导入任务的异步执行（当前为同步接口）
- 前端导入界面（仅提供 API）
- `knowledge_point_standard` 关联表导入（JSON 数据中无关联字段，不在本 change 范围）
- 题目 verify 字段执行（原为 Python 表达式，Java 不执行，且 DB 无对应列，直接丢弃）
- DB Schema 变更（DDL 不变，JSON 中部分字段因 DB 无对应列而丢弃，详见 design §3.6）

---

## 4. 隐性约定检查

根据 `docs/architecture/implicit-contracts.md`，本 change 涉及以下隐性约定：

| 约定 | 说明 | 本 change 实现方式 |
|------|------|-------------------|
| 约定 #1：统一响应结构 | 所有接口返回 `Result<T>` | Controller 层包装返回值 |
| 约定 #2：异常处理规范 | 业务异常使用 `BusinessException` | 参数校验失败抛出 BusinessException |
| 约定 #4：时间格式规范 | 数据库时间字段使用 `LocalDateTime` | 实体类字段类型 |
| 约定 #9：@Transactional rollbackFor | 事务注解必须指定 rollbackFor | **修正**：主流程不用大事务；每条记录用 TransactionTemplate 独立事务；错误日志/导入日志各自独立事务写入 |

---

## 5. 风险评估

| 风险项 | 可能性 | 影响 | 缓解措施 |
|--------|--------|------|----------|
| JSON 文件过大导致 OOM | 中 | 高 | 使用流式解析（Jackson Streaming API）或分批读取 |
| 导入过程中系统崩溃导致数据不一致 | 低 | 高 | 每条记录独立事务，崩溃后重试即可；导入日志记录进度 |
| 外键依赖顺序问题 | 高 | 中 | 强制导入顺序：topic → standard → dependency → question；外键预查失败记 error_log 跳过 |
| 并发导入导致数据冲突 | 中 | 中 | Demo 阶段 synchronized；生产环境需改为分布式锁 |
| 课程标准 1200+ 条逐条插入性能差 | 高 | 中 | 500 条/批 batchInsert + 分批提交 + 每批进度日志 |
| JSON 字段名与 DB 列名不一致 | 高 | 中 | design §3.6 已核实全部字段映射表，映射层负责转换 |
| JSON 字段在 DB 无对应列导致数据丢失 | 中 | 低 | design §3.6 已标注所有丢弃字段及理由；当前 DB schema 为 foundation 设计，后续 change 可扩展 |
| 知识点 level/parent_id 无法从 JSON 直接获取 | 中 | 中 | Demo 阶段统一 level=2、parent_id=null；后续 change 可通过 chapter 字段解析建立层级 |

---

## 6. 验收标准

- [ ] 四种数据类型均可从 JSON 文件成功导入数据库
- [ ] 知识点导入后 status=pending_review、subject="数学"
- [ ] 题目导入后 knowledge_point_id 外键校验通过（知识点必须先导入）
- [ ] 题目 difficulty ∈ {basic, standard, challenge, advanced}（advanced 运行时归一化为 challenge），type ∈ {numeric, choice, short, self}
- [ ] 先修关系导入后 dependency_type ∈ {hard, soft}（从 JSON strength 字段映射）
- [ ] 课程标准导入采用批量策略（500 条/批），1200+ 条全部导入
- [ ] 课程标准 code 从 JSON id 映射，description 从 JSON text 映射，stage 从 id 模式匹配推导（含 :H./hs-→高中、冒号后1~3→小学、4→初中、5→高中）
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
