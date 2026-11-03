# Proposal：错题管理与艾宾浩斯周期

> **Change 名称**：`2026-11-02_error_question_management`  
> **创建日期**：2026-11-02  
> **状态**：草案 v8（修复 BLOCK-I + IMP-AG~AI）

---

## 1. 需求背景

当前系统已完成：
- 后端基础设施（JWT 鉴权、统一响应结构、全局异常处理）
- 学生登录与认证
- 数据导入功能（题库、知识点、先修关系、课程标准）
- 学习目标管理
- 掌握度状态管理（公式引擎、状态更新、查询接口）
- 每日作业核心流程（Agent 出题 → 生成 → 提交 → 批改 → 掌握度更新）

下一步需要实现**错题管理系统**，基于艾宾浩斯遗忘曲线（1/3/7/15/30 天）自动安排错题重做，并与掌握度状态联动（暂停/重新入队），为每日作业提供错题重做题源。

本次 Change 聚焦于**错题队列管理与艾宾浩斯周期调度**，实现错题入队、周期推进、掌握度联动暂停/重新入队、每日作业集成错题查询。

---

## 2. 核心目标

1. **错题入队**：学生答错题目后，自动将原题加入错题队列，记录首次答错日期
2. **艾宾浩斯周期调度**：按 1/3/7/15/30 天周期计算下次到期日期，到期错题纳入每日作业
3. **周期推进与重置**：重做答对 → 推进到下一阶段；重做答错 → 重置周期（回到 stage 1）
4. **掌握度联动暂停**：知识点掌握度 ≥ 0.8 时，该知识点下所有错题暂停重做
5. **掌握度联动重新入队**：知识点掌握度跌破 0.8 时，暂停的错题自动重新入队
6. **每日作业集成**：生成每日作业时，查询到期错题并纳入作业（taskType=redo）
7. **错题列表查询**：学生端查看自己的错题队列（支持按知识点/状态筛选）

---

## 3. 功能范围

### 包含（In Scope）

| 功能点 | 说明 |
|--------|------|
| 错题队列表设计 | 新增 `error_question` 表，记录错题队列状态与艾宾浩斯周期 |
| 错题入队逻辑 | 答错时自动入队（首次答错/重做答错重置周期） |
| 周期推进逻辑 | 重做答对后推进到下一阶段，stage > 5 时移除出队列 |
| 掌握度联动暂停 | 掌握度 ≥ 0.8 时暂停该知识点下所有错题 |
| 掌握度联动重新入队 | 掌握度 < 0.8 时自动重新激活暂停的错题 |
| 批改触发错题入队 | gradeHomework 批改时：非 redo 题答错 → recordWrongAnswer 入队；redo 题答对 → advanceStage；redo 题答错 → resetStage |
| 每日作业集成错题 | 生成作业时查询到期错题，按知识点截断至 ≤ 3 道（BLOCK-A），以 taskType=redo 纳入 daily_plan.content；剩余配额给 Agent 新题（避开已占用 questionId，非避开知识点） |
| 错题列表查询接口 | 学生端查看错题队列（按知识点/状态筛选） |
| 错题统计查询接口 | 学生端查看错题统计（总数/到期数/暂停数） |

### 不包含（Out of Scope）

| 功能点 | 说明 | 归属 Change |
|--------|------|------------|
| 举一反三新题生成 | Agent 生成相似新题、相似度校验 | Change 3 |
| 专项评测 | 集中评测任务 | 独立 Change |
| 试卷上传与解析 | OCR 切题、归因 Agent | 独立 Change |
| 计划调整 | 触发信号检测、调整 Agent | 独立 Change |
| 错题超过 30 天标记"需重点关注" | 由调整 Agent 处理 | Change 5 |

---

## 4. 隐性约定检查

| 约定编号 | 约定内容 | 本次如何遵循 |
|----------|----------|-------------|
| #1 | 统一响应结构 `Result<T>` | ErrorQuestionController 所有接口返回 `Result<T>` |
| #2 | 异常处理规范 | 业务异常抛出 `BusinessException`，由全局异常处理器捕获 |
| #3 | 分页参数规范 | 错题列表支持分页，`page` 从 1 开始，`size` 默认 10 |
| #4 | 时间格式规范 | 实体使用 `LocalDateTime`/`LocalDate`，前端展示 `yyyy-MM-dd HH:mm:ss` |
| #9 | @Transactional 指定 rollbackFor | Service 层事务方法显式指定 `rollbackFor = Exception.class` |
| #10 | 事务边界精确控制 | 本次无外部 API 调用，使用 `@Transactional` 即可 |
| #11 | 并发写入保护模式 | `error_question` 表按 `(student_id, question_id, tenant_id)` 唯一索引保护 + DuplicateKeyException 捕获 |
| #12 | Agent 调用超时降级 | 本次不涉及 Agent 调用 |

---

## 5. 数据表依赖

| 表名 | 用途 | 状态 |
|------|------|------|
| `error_question` | 错题队列（艾宾浩斯周期调度） | **需新增** |
| `homework_submission_detail` | 查询答错记录（错题入队来源） | 已实现 |
| `student_knowledge_state` | 查询掌握度（暂停/重新入队判定） | 已实现 |
| `question_bank` | 获取原题内容（错题重做展示） | 已实现 |
| `daily_plan` | 集成错题到每日作业 | 已实现 |
| `knowledge_point` | 按知识点筛选错题 | 已实现 |

**DDL 变更**：新增 `error_question` 表（V3 DDL 脚本）。依赖前置 Change V2（`V2__homework_uk_index.sql`）已 apply。含 2 个普通索引：idx_student_status_due / idx_student_kp_status。分页排序 first_wrong_date 无索引覆盖（TD-PAGINATION-FILESORT：预估单学生错题上限 2000 条，filesort 耗时 < 20ms；T-01 EXPLAIN 验证后若 filesort 行数 > 5000 则补索引，补索引后需验证 5 种查询组合的索引命中）。selectDueKnowledgePointIds 含 LIMIT 51 知识点上限保护（IMP-AH，Service 层 size > 50 时 WARN + subList(0, 50)）。

---

## 6. 风险评估

| 风险项 | 风险等级 | 缓解措施 |
|--------|----------|----------|
| 掌握度更新与错题状态联动一致性 | 中 | 在同一事务内完成掌握度更新 + 错题暂停/重新入队 |
| 错题队列数据量增长 | 低 | stage > 5 自动移除；completed 状态记录保留但可定期归档 |
| 并发入队冲突 | 低 | 唯一索引 `(student_id, question_id, tenant_id)` + DuplicateKeyException 捕获 → 幂等 UPDATE |
| **IMP-A: 并发推进冲突** | 低 | advanceStage UPDATE WHERE current_stage = expectedStage → affected rows 校验，0 行抛 ERROR_QUESTION_CONCURRENT_MODIFIED(1103) |
| 掌握度 0.8 附近震荡 | 低 | 暂停/激活反复切换代价低（仅 status 字段更新，stage 保留）；登记技术债务 TD-PAUSE-OSCILLATION |
| 每日作业集成时错题配额计算复杂 | 中 | **BLOCK-A 修复**：错题按知识点截断至 3 道，剩余配额给 Agent 新题；完整配额逻辑在 Change 3（举一反三）中统一实现 |
| **BLOCK-B: 入队即暂停** | 低 | 掌握度 ≥ 0.8 时答错入队后立即被 syncPauseStatus 暂停——预期行为，错题记录保留用于统计 |
| **BLOCK-D: completed 重入队** | 低 | recordWrongAnswer 回退 UPDATE 显式清空 completed_date，允许 completed→active |
| **BLOCK-E: advanceStage 参数语义** | 低 | WHERE current_stage = #{currentStage}（数据库旧值），参数命名已修正避免 off-by-one |
| **BLOCK-F: first_wrong_date 不可变性** | 低 | first_wrong_date 只在首次 INSERT 时写入，resetStage/回退 UPDATE 不修改，保证统计/排序稳定性 |
| **IMP-J: SQL 层 LIMIT 保护** | 低 | BLOCK-H 修复后每个知识点 LIMIT 3，不再有全局 LIMIT；单知识点 > 3 道时截断行为稳定 |
| **IMP-N: 分页排序稳定性** | 低 | 分页排序改为 first_wrong_date DESC（BLOCK-F 保证其稳定性） |
| **IMP-Q: 批次去重 redo 优先** | 低 | 同一 questionId 同时存在 redo 和 non-redo 时，优先处理 redo（涉及周期推进） |
| **IMP-R: batchPause 不更新 update_time** | 低 | 暂停/激活是派生状态变更，不视为内容修改，审计意义更清晰 |
| **BLOCK-G: 多知识点均衡截断** | 低 | BLOCK-H 根治：按知识点分别查询，每个知识点 LIMIT 3，彻底消除全局截断导致知识点覆盖不均 |
| **IMP-V: 分组排序稳定性** | 低 | 每个知识点独立查询 LIMIT 3，SQL 层已保证排序，无需 Java 层重新排序 |
| **IMP-W: 多条 redo 去重** | 低 | submissions 按 create_time ASC + submission_id ASC 排序，同一 questionId 多条 redo 时取最后一条 |
| **IMP-AB: Map 顺序稳定性** | 低 | getDueErrorQuestions 返回 LinkedHashMap，保证多次调用顺序一致 |
| **IMP-AD: 排序策略** | 低 | 优先按 create_time 排序，避免雪花算法 submissionId 非严格递增导致语义错乱（已确认 HomeworkSubmissionDetail 继承 BaseEntity，createTime 由 FieldFill.INSERT 自动填充） |
| **BLOCK-I: N+1 数据漂移** | 低 | N+1 循环不纳入事务（只读查询），接受 READ COMMITTED 下的数据漂移；空结果知识点不放入 Map |
| **IMP-AH: 知识点上限保护** | 低 | selectDueKnowledgePointIds LIMIT 51，Service 层 size > 50 时 WARN + subList(0, 50) 截断 |

---

## 7. 验收标准

1. **错题入队正确**：答错后自动入队，首次答错日期（first_wrong_date）在首次入队时准确记录，后续 redo 答错不改变该字段
2. **艾宾浩斯周期计算正确**：1/3/7/15/30 天到期日期计算无误
3. **周期推进正确**：重做答对后 stage 递增，stage > 5 时移除
4. **周期重置正确**：重做答错后 stage 重置为 1，next_due_date 重新计算
5. **掌握度联动暂停**：掌握度 ≥ 0.8 时错题状态变为 paused
6. **掌握度联动重新入队**：掌握度 < 0.8 时暂停的错题自动重新入队
7. **每日作业集成**：生成作业时能查询并纳入到期错题（taskType=redo）
8. **错题列表查询**：学生端能按知识点/状态筛选查看错题队列
9. **单元测试覆盖**：核心逻辑测试覆盖率 ≥ 80%

---

## 8. 后续规划

本次 Change 完成后，后续 Change 规划：

- **Change 3**：举一反三新题生成（Agent 生成 + 相似度校验 + 每日作业配额完整逻辑）
- **Change 4**：专项评测系统（**IMP-C: 需定义 ASSESSMENT/EXAM 的 sourceId 映射规则，解决 TD-SOURCE-ID-SEMANTICS**）
- **Change 5**：计划调整系统（含错题超过 30 天标记"需重点关注"）

---

**提案人**：AI Agent  
**审核状态**：待人工审核
