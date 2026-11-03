# Proposal：掌握度状态管理

> **Change ID**: `2026-10-31_mastery_state_management`  
> **创建日期**: 2026-10-31  
> **状态**: 草案（待人工审查）

---

## 1. 需求背景

智学伴（StudyMate）学习闭环中，"知识点掌握度"是核心状态指标。掌握度由后端根据题目级判定结果按公式更新（产品规则 #2），不由 LLM 直接给出。本 change 实现掌握度的计算引擎、状态管理与查询接口，为后续每日作业、专项评测、试卷分析等模块提供数据支撑。

---

## 2. 核心目标

1. **实现掌握度公式计算引擎**：答对/答错两套公式，含 D_norm 难度归一化校验、来源权重、置信度衰减因子
2. **实现置信度更新机制**：每次有效评测后递增，初始值 0.10
3. **实现变更追溯日志**：每次掌握度变更记录变更类型、来源、变动量、变更前后值（只读不删）
4. **提供学生端掌握度查询接口**：掌握度看板（按科目筛选，仅展示 active 知识点）、变更历史
5. **提供管理端掌握度看板查询接口**：按学生/知识点/科目筛选，支持分页

---

## 3. 功能范围

### 包含（In Scope）

| 模块 | 说明 |
|------|------|
| `ChangeType` 枚举 | 变更类型枚举：homework / assessment / exam |
| `MasteryFormulaUtil` | 掌握度公式计算工具类，封装答对/答错公式、D_norm 校验、置信度衰减 |
| `MasteryService` | 掌握度核心业务：从作业/评测/考试更新掌握度、查询看板、查询历史 |
| 学生端 Controller | 掌握度看板 + 变更历史查询 |
| 管理端 Controller | 掌握度看板查询（按学生/知识点/科目筛选） |
| VO / DTO | 看板视图、详情视图、变更日志视图、查询请求 |
| 自定义 Mapper 查询 | 掌握度状态与知识点表的关联查询 |

### 不包含（Out of Scope）

| 模块 | 说明 |
|------|------|
| 每日作业提交流程 | 由后续 Homework change 实现，本 change 只提供方法供调用 |
| 专项评测流程 | 由后续 Assessment change 实现 |
| 试卷分析流程 | 由后续 Exam change 实现 |
| alpha 配置表 | 当前 alpha=0.3 硬编码为常量，后续可迁移至数据库配置表 |
| 前端看板页面 | 本 change 仅实现后端 API |

---

## 4. 隐性约定检查

| 约定 | 实现方式 |
|------|----------|
| 约定 #1：统一响应结构 | 所有接口返回 `Result<T>`，分页接口返回 `Result<PageResult<T>>` |
| 约定 #2：异常处理规范 | 业务异常统一抛出 `BusinessException`，携带 `ErrorCodes` 枚举 |
| 约定 #3：分页参数规范 | 分页参数 page 从 1 开始，size 默认 10 |
| 约定 #4：时间格式规范 | 实体使用 `LocalDateTime`，MyBatis-Plus 自动填充 |
| 约定 #9：@Transactional rollbackFor | 所有写方法添加 `@Transactional(rollbackFor = Exception.class)` |

---

## 5. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 掌握度公式精度 | 中 | 公式参数（alpha、W_source 等）均为常量，便于后续调整；单元测试覆盖公式正确性 |
| BigDecimal 精度丢失 | 低 | 全程使用 `BigDecimal` 进行计算，避免浮点误差；RoundingMode 统一使用 HALF_UP |
| D_norm 校验依赖历史数据 | 中 | 历史均值无数据时（新知识点）使用默认值 0.5，不阻塞流程 |
| 并发更新同一知识点状态 | 低 | `updateMasteryFromHomework` 批量提交中同一知识点可能出现多次，采用内存 Map 缓存策略确保每个知识点只执行一次 DB 更新，避免后写覆盖前写导致增量丢失；同时引入 `@Version` 乐观锁防御跨线程/实例并发冲突 |
| 学生看板需 JOIN 知识点表 | 低 | 通过自定义 Mapper 方法实现，索引已在 V1 schema 中定义 |

---

## 6. 验收标准

- [ ] `MasteryFormulaUtil` 正确实现答对/答错两套公式，BigDecimal 精度无损
- [ ] `updateMasteryFromHomework` 方法能根据 submission_id 列表批量更新掌握度，且同一知识点多次出现时采用内存缓存保证增量不丢失
- [ ] `updateMasteryFromHomework` 方法对 submissionIds 执行归属校验，防止越权更新
- [ ] `updateMasteryFromResult` 方法能处理单次评测/考试的掌握度更新
- [ ] D_norm 校验逻辑：原题使用题库元数据难度，新题偏离 > 0.2 时降级为 0.5
- [ ] 每次更新均写入 `mastery_change_log`，包含 delta、mastery_before、mastery_after
- [ ] 置信度每次有效评测后递增 0.05，上限 1.00
- [ ] 学生端看板接口仅返回 active 知识点，按科目筛选
- [ ] 管理端看板接口支持按学生/知识点/科目筛选，分页正确
- [ ] 所有写方法添加 `@Transactional(rollbackFor = Exception.class)`
- [ ] `StudentKnowledgeState` 实体新增 `@Version version` 字段，写回时通过乐观锁防御并发冲突
- [ ] `ErrorCodes` 新增 `MASTERY_CONCURRENT_CONFLICT(1061)` 错误码
- [ ] 单元测试覆盖：答对/答错公式、D_norm 校验、置信度更新、边界场景（首次评测、满分/零分）
- [ ] 集成测试覆盖：学生端看板、管理端看板、变更历史查询

---

## 7. 参考文档

- `docs/product/index.md` — 产品规则 #2（掌握度公式）、#8（知识点状态）、#9（学习记录）
- `docs/architecture/index.md` — 项目架构 §4（Service 层）、§5.4（规则 #2 实现细节）
- `docs/architecture/implicit-contracts.md` — 隐性约定 #1/#2/#3/#4/#9
- `docs/standards/database.md` — 数据库规范、多租户约定
- `docs/standards/testing.md` — 测试规范