# Proposal：专项评测系统

> **Change 名称**：`2026-11-03_assessment_system`  
> **创建日期**：2026-11-03  
> **状态**：草案 v4（修复 BLOCK-7 + IMP-12~14）

---

## 1. 需求背景

产品规则 #1 定义了"知识点评测"为学生端核心功能之一：学生对当前知识点掌握情况进行专项评测，生成评测结果。当前系统已具备 `assessment_result` 表（V1 DDL）和 `AssessmentResult` 实体，但**无 Service、Controller、出题逻辑、批改逻辑、掌握度联动**——评测功能完全未实现。

本次 Change 目标：**跑通"学生发起评测 → 系统出题 → 学生作答 → 系统批改 → 掌握度更新 → 错题入队 → 历史记录查询"的完整闭环**。

---

## 2. 核心目标

1. 学生可针对指定科目发起专项评测，系统从该科目的 active 知识点中按掌握度 ASC 自动选 1~5 个出题（BLOCK-5 修复：优先选择掌握度最低的知识点）
2. 系统从 `question_bank` 中按知识点抽题（每知识点 3 道，难度均衡）
3. 学生逐题作答后一次性提交
4. 系统自动批改（客观题答案比对），计算得分与各知识点正确率
5. 按规则 #2 公式更新掌握度（`W_source = 0.7`，ChangeType.ASSESSMENT）
6. 答错题目自动进入错题队列（`ErrorQuestionSourceType.ASSESSMENT`）
7. 评测结果作为学习记录（规则 #9）永久保留，支持历史查询

---

## 3. 功能范围

### 包含（In Scope）

| 编号 | 功能点 | 对应规则 |
|------|--------|----------|
| F-1 | 评测创建（选科目 → 系统按掌握度优先选知识点 → 出题） | 规则 #1/#3 |
| F-2 | 学生答案提交与自动批改 | 规则 #2 |
| F-3 | 掌握度更新（逐知识点，W_source=0.7） | 规则 #2 |
| F-4 | 错题入队（答错题目 → error_question） | 规则 #3 |
| F-5 | 评测结果详情查看（含各知识点正确率；BLOCK-4 修复：详情接口不含答案，批改结果 VO 含答案） | 规则 #9 |
| F-6 | 评测历史列表（分页） | 规则 #9 |

### 不包含（Out of Scope）

| 编号 | 说明 | 后续 Change |
|------|------|------------|
| X-1 | 考试试卷上传与解析（规则 #4） | 试卷解析系统 |
| X-2 | Agent 智能选题（当前仅随机抽题） | 后续优化 |
| X-3 | 评测结果驱动的计划调整（规则 #5） | 计划调整系统 |
| X-4 | 评测中途中断与恢复（当前要求一次性提交） | 后续优化 |
| X-5 | 主观题批改（当前仅支持客观题：choice/numeric） | BLOCK-1 约束 |

---

## 4. 关键业务约束

| 约束 | 来源 | 说明 |
|------|------|------|
| 出题数量 | 产品规则 #3 | 每知识点 3 道，单次评测 1~5 个知识点（最多 15 道） |
| 批改范围 | BLOCK-1 修复 | **仅支持客观题**（choice/numeric），主观题不在本次范围（见 X-5） |
| 知识点选择 | BLOCK-5 修复 | 优先选择学生掌握度最低的知识点（mastery_score ASC），无记录视为 0 |
| 无薄弱点策略 | IMP-12 补充 | 所有知识点掌握度 ≥ 0.8 时仍出题（取相对最薄弱），返回 warning 提示 |
| numeric 题答案格式 | IMP-10 补充 | numeric 题的 `question_bank.answer` 必须为纯数字（整数/小数/科学计数法），不允许含单位或特殊字符 |
| choice 题选项格式 | IMP-13 补充 | 选项必须为单大写字母（A-Z），答案字符排序仅适用于多选；含非字母字符时退化为字符串比对 |
| choices 字段结构 | IMP-14 补充 | `question_bank.choices` 为纯文本数组，不含 isCorrect 等敏感标记；答案独立存于 answer 字段 |
| 掌握度权重 | 产品规则 #2 | `W_source = 0.7`（ASSESSMENT），高于作业（0.4），低于考试（1.0） |
| 知识点筛选 | 产品规则 #8 | 仅 `active` 状态的知识点可被选入评测 |
| 数据不可变 | 产品规则 #9 | 评测结果一旦批改完成，只读不删 |
| 错题来源 | 产品规则 #3 | `ErrorQuestionSourceType.ASSESSMENT`，sourceId = assessment_id |
| D_norm 取值 | 产品规则 #2 | 全部来自 `question_bank.difficulty`（题库原题，可信） |

---

## 5. 隐性约定检查

| 约定 | 影响 |
|------|------|
| #1 统一响应 | Controller 返回 `Result<T>` |
| #2 异常处理 | Service 抛 `BusinessException`，Controller 不 try-catch |
| #3 分页参数 | 历史列表 `page` 从 1 开始，`size` 默认 10 |
| #4 时间格式 | 使用 `LocalDateTime`/`LocalDate` |
| #9 rollbackFor | 批改+掌握度更新在同一事务内 |
| #10 事务边界 | 本次无外部 Agent 调用，可用 `@Transactional` |
| #11 并发保护 | assessment_result 状态机校验（pending → graded，IMP-7 修复） |
| #12 超时降级 | 本次不涉及 Agent 调用 |
| #13 UNIQUE 索引 | 本次无新增唯一索引 |
| #14 Agent 异常分层 | 本次不涉及 Agent 调用 |

---

## 6. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 题库题目不足 | 中 | 出题时校验数量，不足则返回明确错误提示（不降级） |
| 批改与掌握度更新一致性 | 中 | 同一 `@Transactional` 保证原子性 |
| 评测结果 JSON 过大 | 低 | 单次最多 15 道题，JSON 体积可控 |
| assessment_result DDL 变更 | 低 | 通过 V5 迁移脚本处理，含回滚方案；BLOCK-3/6/7 修复：Demo 阶段表为空，subject_id 和 status 均无 DEFAULT |

---

## 7. 前置依赖

- `error_question_management` Change 已完成（错题入队接口可用）
- `similar_question_generation` Change 已归档（不影响本次）
- V1~V4 DDL 脚本已 apply

---

## 8. 验收标准

1. 学生可成功创建评测（选科目 → 系统出题 → 返回题目列表）
2. 提交答案后系统正确批改，得分与各知识点正确率准确
3. 掌握度按公式更新，`mastery_change_log` 有对应记录（change_type = 'assessment'）
4. 答错题目自动进入 `error_question`（source_type = 'assessment'）
5. 评测历史列表可分页查询，详情可查看
6. 全部单元测试通过，Service 层覆盖率 ≥ 80%
