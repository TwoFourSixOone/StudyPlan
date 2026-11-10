# 系统工作原理：掌握度与学习记录

> **维护触发**：change 归档时，若改动了掌握度公式、参数、置信度策略、学习记录类型或错题周期，必须在同一轮更新本文件，并同步 `docs/product/index.md` 规则 #2/#9 与 `docs/changelog/mastery-formula.md`（后者尚未创建）。
> **冲突处理**：公式语义以 `docs/product/index.md` 规则 #2 为准；本文件记录**代码当前参数**，两者不一致时先改代码或先改文档，不允许长期并存。

---

## 1. 掌握度状态

`student_knowledge_state` 每（学生, 知识点）一行：

| 字段 | 含义 |
|------|------|
| `mastery_score` | 掌握度 0.0–1.0，`DECIMAL(5,4)` |
| `confidence` | 置信度 0.0–1.0，反映评分可信度 |
| `source` | 最近一次更新来源（homework / assessment / exam） |
| `last_assessed_at` | 最近评测时间 |
| `version` | 乐观锁版本号 |

## 2. 更新公式（`MasteryFormulaUtil`）

**答对**：`m_new = min(1.0, m + α · W · D · C · (1 − m))`
**答错**：`m_new = max(0.05, m − α · W · D · C · m)`
**置信度衰减**：`C = 1 − 0.5 · confidence`

| 参数 | 代码取值 | 说明 |
|------|---------|------|
| `α`（alpha） | **常量 0.3** | 产品要求"可配置、范围 0.1–0.5、越界拒绝保存"，**尚未配置化**（差异见 §6） |
| `W`（来源权重） | 考试 1.0 / 评测 0.7 / 作业 0.4 | 与产品一致 |
| `D`（难度归一化） | basic 0.3 / standard 0.6 / challenge 0.9 | 与产品一致 |
| 初始掌握度 | 0.50 | 首次建行时写入 |
| 下限 / 上限 | 0.05 / 1.0 | 与产品一致 |
| 结果精度 | 4 位小数，`HALF_UP` | 与 `DECIMAL(5,4)` 对齐 |

**置信度**：初始 0.10，每次有效评测 +0.05，上限 1.0。

**D 值校验**：Agent 生成的新题，若声明难度与同知识点历史均值偏离 > 0.2，降级为 0.5（`validateDNorm`）；原题直接用题库元数据难度。

## 3. 触发点

| 触发 | 入口 | 说明 |
|------|------|------|
| 每日作业批改 | `MasteryService.updateMasteryFromHomework` | 批量，按提交明细逐题计算，同题多次提交取最终判定 |
| 专项评测 | `AssessmentServiceImpl` | `W = 0.7` |
| 考试分析 | `ExamPaperServiceImpl` | `W = 1.0`；因 OCR 未接入，当前无法端到端触发 |

## 4. 追溯链

`mastery_change_log` 每次变更一行：

`student_id` / `knowledge_point_id` / `change_type`（homework/assessment/exam）/ `source_id`（关联作业或评测 ID）/ `delta` / `mastery_before` / `mastery_after` / `created_at`

追溯链：**学习记录（提交明细/评测/考试） → mastery_change_log.source_id → 掌握度前后值**。该表与学习记录一样**只追加**。

## 5. 学习记录

三类记录经 `LearningRecordMapper.selectUnionAllRecords` 用 `UNION ALL` 合并后分页：

| 类型 | 来源表 | 过滤条件 |
|------|--------|---------|
| HOMEWORK | `homework_submission_detail` LEFT JOIN `daily_plan` → `learning_goal` 取 `subject_id` | 按 `submitted_at` 过滤 |
| ASSESSMENT | `assessment_result` | `status = 'graded'`，按 `assessed_at` |
| EXAM | `exam_analysis_result` | `status = 'completed'`，按 `analyzed_at` |

- 返回的 `recordType` 在 SQL 中写**大写枚举名**，API 出参经 `RecordType.@JsonValue` 转**小写 code**（隐性约定 #21）
- 学习记录详情按 `(type, id)` 查询，`student_id + tenant_id` 作越权校验

## 6. 错题（艾宾浩斯）

| 项 | 实现 |
|----|------|
| 周期 | `EbbinghausStage`：1 → 3 → 7 → 15 → 30 天 |
| 入队 | 批改时答错即 `recordWrongAnswer`，stage = 1，`next_due_date = first_wrong_date + 1` |
| 答对 | `advanceStage`；stage > 5 → `completed` |
| 答错（redo） | `resetStage`，从 stage 1 重新计时 |
| 暂停 | 知识点掌握度 ≥ 0.8 时批量 `paused`；跌破后重新激活并从下一个未到期节点继续 |
| 超期 | > 30 天未答对进入 `selectCriticalErrors`，作为调整 Agent 的 `criticalErrorQuestionIds` 输入 |
| 唯一性 | `uk_student_question (student_id, question_id, tenant_id)` 防并发重复入队 |

## 7. 「只读不删」的落地方式

- MyBatis-Plus **未开启全局逻辑删除**（`application.yml` 显式不配置 logic-delete）
- 学习记录、`mastery_change_log`、`plan_adjustment_log`、`agent_degradation_log` 均无 DELETE 接口
- 唯一接近删除的操作是 `plan_adjustment_diff` 的过期标记，属状态变更而非删除

## 8. 与产品规则的已知差异

| # | 差异 | 影响 | 处理建议 |
|---|------|------|---------|
| 1 | `α` 硬编码 0.3，未实现"可配置 + 范围校验" | 无法按规则 #2 调参，也无法"越界拒绝保存" | 后续 change 引入配置项 + 启动时范围校验 |
| 2 | `docs/changelog/mastery-formula.md` 不存在 | 规则 #2 要求参数变更留痕，当前无落点 | 随 α 配置化一并创建 |
| 3 | 考试链路 `W = 1.0` 已实现但 OCR 未接入 | 该权重当前不可达 | 随 OCR 接入一起验证 |
