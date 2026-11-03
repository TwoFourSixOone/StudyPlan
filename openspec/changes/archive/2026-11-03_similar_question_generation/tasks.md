# Tasks：举一反三新题生成与完整配额逻辑

> **Change 名称**：`2026-11-03_similar_question_generation`  
> **创建日期**：2026-11-03  
> **状态**：草案 v6（DETAIL-1~3 修正）

---

## Task 清单

### T-00：新增/修改实体、枚举、工具类

**任务描述**：
- 修改 `GeneratedQuestion` 实体：新增 `contentHash` 字段（VARCHAR(64)，题干 SimHash）
- 修改 `AgentType` 枚举：新增 `QUESTION_CHECK("question_check", "检查 Agent")`
- 修改 `ErrorCodes`：新增 1110-1113 错误码
  - `SIMILAR_QUESTION_GEN_FAILED(1110)`
  - `SIMILAR_QUESTION_CHECK_FAILED(1111)`
  - `SIMILAR_QUESTION_TOO_SIMILAR(1112)`
  - `AGENT_OUTPUT_INVALID(1113)`（NOTE-1 新增）
- **NOTE-1 新增**：新增异常类（`com.jiang.common.exception` 包）
  - `AgentTimeoutException extends RuntimeException` — Agent 调用超时异常
  - `AgentOutputInvalidException extends RuntimeException` — Agent 输出格式异常
  - 与现有 `BusinessException` 的关系：技术异常（Agent 层） vs 业务异常（Service 层），两者不互斥
- 新增 `SimilarityDetector` 工具类（`com.jiang.common.util`）
  - `simHash(String text)` — 64 位 SimHash 计算
  - `cosineSimilarity(String text1, String text2)` — 基于字符 bigram 的余弦相似度
  - `combinedSimilarity(String text1, String text2)` — 综合策略（SimHash 快速筛选 + 余弦精确计算）
  - `detectSimilarity(String newContent, List<String> existingContents)` — 返回最高相似度
  - `computeContentHash(String content)` — 返回 SimHash 字符串（用于 DB 存储）
  - 常量 `SIMILARITY_THRESHOLD = 0.6`（字符级粗筛，语义层由检查 Agent 兜底）
- 新增 `HomeworkQuotaCalculator` 工具类（`com.jiang.common.util`）
  - 常量：`DAILY_MAX=15`、`KP_MAX=4`、`SIMILAR_MIN_PER_KP=1`、`SIMILAR_MAX_PER_KP=3`
  - `calculate(dueErrorCount, errorKpIds, kpMasteryMap, kpErrorCountMap)` → `QuotaAllocation`
  - 内部类 `QuotaAllocation`：errorQuota / similarQuota / reviewQuota / similarQuotaPerKp / reviewQuotaPerKp / extremeMode / degradedMode
  - 配额优先级体系：P0（错题重做）→ P1（有错题知识点举一反三）→ P2（无错题知识点知识回顾）
  - 动态举一反三上限：KP_MAX - 该知识点错题数（而非固定 KP_MAX - 1）
  - 前置防御：校验 kpErrorCountMap 每个值 ≤ KP_MAX，若超过则抛 IllegalArgumentException
  - Map 语义统一：similarQuotaPerKp 和 reviewQuotaPerKp 只包含配额 > 0 的知识点（BLOCK-5 IMP-12）
  - degradedMode 标志：极端模式 OR 配额极度紧张（similarQuota < errorKpIds.size()）
- 更新测试 `schema.sql`：`generated_question` 表新增 `content_hash` 列 + `uk_kp_content_hash` 唯一索引

**涉及文件**：
- `src/main/java/com/jiang/model/entity/GeneratedQuestion.java`（修改，新增 contentHash）
- `src/main/java/com/jiang/model/enums/AgentType.java`（修改，新增 QUESTION_CHECK）
- `src/main/java/com/jiang/common/exception/ErrorCodes.java`（修改，新增 1110-1113）
- `src/main/java/com/jiang/common/exception/AgentTimeoutException.java`（新增，NOTE-1）
- `src/main/java/com/jiang/common/exception/AgentOutputInvalidException.java`（新增，NOTE-1）
- `src/main/java/com/jiang/common/util/SimilarityDetector.java`（新增）
- `src/main/java/com/jiang/common/util/HomeworkQuotaCalculator.java`（新增）
- `src/test/resources/schema.sql`（修改，generated_question 表补充字段和索引）
- `src/test/java/com/jiang/common/util/SimilarityDetectorTest.java`（新增）
- `src/test/java/com/jiang/common/util/HomeworkQuotaCalculatorTest.java`（新增）

**测试覆盖**：
- `SimilarityDetectorTest`：
  - 相同文本 → 相似度 = 1.0
  - 完全不同文本 → 相似度 < 0.3
  - 部分相似文本 → 相似度在 0.5~0.8 之间
  - 空文本处理
  - detectSimilarity 返回最高值
- `HomeworkQuotaCalculatorTest`：
  - 极端场景：到期错题 ≥ 15 → extremeMode=true，similarQuota=0，reviewQuota=0（产品规则显式声明：牺牲举一反三保错题）
  - 正常场景：3 个错题 + 5 个知识点 → 配额分配正确（P0/P1/P2 优先级）
  - 配额不足场景：知识点过多 → 按掌握度从低到高优先，reviewQuotaPerKp 只包含配额 > 0 的知识点
  - 有错题知识点最低保障：每知识点至少 1 道举一反三
  - 动态上限场景：某知识点有 2 道错题 → 举一反三上限 = 4 - 2 = 2 道
  - KP_MAX 硬上限场景：某知识点错题数 = 4 → 举一反三上限 = 0，最低保障失效（BLOCK-5）
  - 配额极度紧张场景：N_error = 14 + 3 个有错题知识点 → 仅 1 个知识点分到举一反三，degradedMode=true
  - 两个 Map 只包含配额 > 0 的知识点（IMP-12 断言）
  - 无错题场景：全部配额给知识回顾
  - **BLOCK-7/IMP-17 新增**：极端模式下 degradedMode=true 且 similarQuotaPerKp 为空 Map（断言 Map.isEmpty()）
  - **BLOCK-7/IMP-17 新增**：配额紧张下 degradedMode=true 且 similarQuotaPerKp 非空（断言 Map 包含配额 > 0 的知识点）
  - **BLOCK-7/IMP-17 新增**：正常模式下 degradedMode=false（断言标志为 false）

**预估工作量**：1.5 天

---

### T-01：V4 DDL 变更脚本

**任务描述**：
- 编写 V4 DDL 变更脚本，修改 `generated_question` 表
- 新增 `content_hash` 列（VARCHAR(64)）
- 新增唯一索引 `uk_kp_content_hash (knowledge_point_id, content_hash, tenant_id)`
- 脚本命名：`V4__similar_question_enhancement.sql`
- 脚本位于受保护目录 `src/main/resources/db/`，需人工审批
- 回滚方案以注释形式保留

**涉及文件**：
- `src/main/resources/db/V4__similar_question_enhancement.sql`（新增，受保护需人工审批）

**依赖**：无（可与 T-00 并行；生产部署前须确保 V3_1 已 apply）

**预估工作量**：0.25 天

---

### T-02：新增 QuestionCheckAgent

**任务描述**：
- 新增 `QuestionCheckAgent`（`com.jiang.agent`）
  - `check(GeneratedQuestion, QuestionBank sourceQuestion)` → `CheckResult`（NOTE-2 修正：删除 targetDifficulty 参数）
  - 校验项：
    1. 内容完整性（stem/answer/formatType 非空）
    2. 难度枚举合法性（difficulty ∈ {basic, standard, challenge}，NOTE-2 修正：删除原“相邻档位”校验）
    3. 知识点关联检查（kpId 匹配）
    4. 答案合理性（非空、长度 > 0）
    5. 解题逻辑相似性检查（Agent 语义判断，保守策略：宁可漏放不可误杀，BLOCK-6）
  - 调用检查 Agent（AgentScopeClient）进行内容质量判断
  - 检查 Agent Prompt 输入必须包含原题和新题的**题干 + 答案 + 解析**
  - 检查 Agent Prompt 模板：`prompts/versions/question_check_v1.txt`
  - 检查 Agent JSON Schema：`prompts/schemas/question_check_schema.json`
- `AgentOutputValidator` 扩展：新增 `validateCheckResult(String)` 方法 + 难度枚举校验（IMP-14：difficulty 不在 {basic, standard, challenge} 内直接判失败）
- **BLOCK-7 新增**：`validateRulesOnly(GeneratedQuestion, QuestionBank)` 方法，仅执行规则校验（内容完整、难度枚举合法、知识点匹配、答案合理），不调用 Agent，用于超时降级场景（DETAIL-2 修正：明确列出 4 项校验内容）

**涉及文件**：
- `src/main/java/com/jiang/agent/QuestionCheckAgent.java`（新增）
- `src/main/resources/prompts/versions/question_check_v1.txt`（新增，受保护）
- `src/main/resources/prompts/schemas/question_check_schema.json`（新增，受保护）
- `src/main/java/com/jiang/common/util/AgentOutputValidator.java`（修改，新增 validateCheckResult）
- `src/main/java/com/jiang/common/exception/AgentTimeoutException.java`（引用，NOTE-1：已在 T-00 新增）
- `src/main/java/com/jiang/common/exception/AgentOutputInvalidException.java`（引用，NOTE-1：已在 T-00 新增）
- `src/test/java/com/jiang/agent/QuestionCheckAgentTest.java`（新增）

**测试覆盖**：
- 内容完整性校验：空 stem → fail
- 难度枚举合法性：difficulty='hard'（不在枚举内）→ fail（IMP-14）
- 难度枚举合法性：difficulty='basic'/'standard'/'challenge' → pass（NOTE-2 修正：删除原“basic → challenge 跨两档 → fail”用例，档位校验已删除）
- Agent 独占校验：解题逻辑实质相同的新题 → fail（规则+字符相似度无法覆盖）
- 保守策略：Agent 返回“解题逻辑相同”但实际解法不同 → 按保守策略应判 pass（BLOCK-6）
- **BLOCK-7 修正**：Agent 超时 → 降级为仅规则校验（项 1-4 通过则 pass，不因 Agent 不可用而 fail）
- **BLOCK-7 修正**：validateRulesOnly 正常执行 → 项 1-4 全部通过 → return true
- **BLOCK-7 修正**：validateRulesOnly 内容不完整 → return false
- **IMP-16 新增**：Agent 返回非 JSON（如纯文本“通过”）→ 抛出 AgentOutputInvalidException → 计入失败，进入重试
- **IMP-16 新增**：Agent 返回 JSON 但 passed 字段缺失 → 视为 passed=false（保守策略）
- **IMP-16 新增**：Agent 返回 passed=false 但 reason 为空 → 接受该结果，判失败
- **DETAIL-3 新增**：Agent 返回 passed=true + reason='xxx' → pass（忽略 reason 字段）
- 知识点关联检查：kpId 不匹配 → fail
- 正常题目 → pass

**预估工作量**：1 天

---

### T-03：QuestionGenAgent 扩展举一反三生成

**任务描述**：
- 在 `QuestionGenAgent` 中新增 `generateSimilar` 方法
  - 输入：知识点 ID、掌握度、原错题内容（QuestionBank）、目标数量、难度递进策略（difficultyStrategy="progressive"）
  - 一次 Agent 调用生成 targetCount 道，难度按 basic→standard→challenge 递进分配
  - 构造专项 Prompt（传入原错题内容，要求生成不同但同知识点的新题）
  - 调用 AgentScopeClient
  - 解析输出为 `List<GeneratedQuestion>`（不创建 ref，仅返回实体）
- 新增 Prompt 模板：`prompts/versions/similar_question_gen_v1.txt`
- 更新 `question_gen_schema.json` 或新增 `similar_question_gen_schema.json`

**涉及文件**：
- `src/main/java/com/jiang/agent/QuestionGenAgent.java`（修改，新增 generateSimilar）
- `src/main/resources/prompts/versions/similar_question_gen_v1.txt`（新增，受保护）
- `src/test/java/com/jiang/agent/QuestionGenAgentTest.java`（修改，补充 generateSimilar 测试）

**测试覆盖**：
- generateSimilar 正常生成（一次调用生成 3 道，难度按 basic→standard→challenge 递进，IMP-13）
- generateSimilar Agent 调用失败 → 抛异常
- generateSimilar 输出解析失败 → 抛 AGENT_OUTPUT_INVALID

**预估工作量**：0.75 天

---

### T-04：GeneratedQuestionMapper 扩展

**任务描述**：
- 新增 `selectCheckedContentsByKp` — 查询已校验通过的新题内容（用于相似度比较）
  - **IMP-15 修正**：增加 `ORDER BY created_at DESC LIMIT 100`，仅取最近 100 条，避免历史新题过多时性能下降
- 新增 `selectSourceQuestionForSimilar` — 获取到期错题对应的原题内容（JOIN question_bank，ORDER BY + error_question_id ASC 稳定排序）
- BLOCK-5 IMP-11：删除 `countByContentHash`（死代码），防重完全依赖唯一索引 + DuplicateKeyException

**涉及文件**：
- `src/main/java/com/jiang/mapper/GeneratedQuestionMapper.java`（修改，新增 1 个方法，删除 countByContentHash）
- `src/main/java/com/jiang/mapper/ErrorQuestionMapper.java`（修改，新增 selectSourceQuestionForSimilar）

**预估工作量**：0.25 天

---

### T-05：新增 SimilarQuestionService 接口与实现

**任务描述**：
- 新增 `SimilarQuestionService` 接口
- 新增 `SimilarQuestionServiceImpl` 实现
  - `generateWithValidation` 方法：循环 ≤ 5 次
    0. 防御性复制：`List<String> mutableContents = new ArrayList<>(existingContents)`，加入 sourceContent（IMP-9）
    1. 调用 `questionGenAgent.generateSimilar` 生成候选
    2. 对每个候选调用 `similarityDetector.detectSimilarity` 检查相似度
    3. 对每个候选调用 `questionCheckAgent.check` 校验质量
    4. 通过的保留，失败的记录原因
    5. 全部通过 → 返回
    6. 部分失败 → 仅补充失败数量，进入下一轮
    7. 5 次后仍有失败 → 统一记录一次 `agent_degradation_log`（在事务内与 new question 同步写入）
  - 注入：`QuestionGenAgent`、`QuestionCheckAgent`、`SimilarityDetector`、`HomeworkDegradationHandler`
  - 常量：`MAX_ATTEMPTS = 5`

**涉及文件**：
- `src/main/java/com/jiang/service/SimilarQuestionService.java`（新增）
- `src/main/java/com/jiang/service/impl/SimilarQuestionServiceImpl.java`（新增）
- `src/test/java/com/jiang/service/SimilarQuestionServiceTest.java`（新增）

**测试覆盖**：
- 首次生成全部通过 → 返回所有新题
- 第 1 轮部分失败 → 第 2 轮补充 → 全部通过
- 5 轮仍有失败 → 统一记录一次降级日志（断言日志仅 1 条）+ 返回通过的部分 + 日志在事务内写入
- 相似度检测 > 0.6 → 重试
- 检查 Agent 校验失败 → 重试
- Agent 调用异常 → 降级处理
- 传入不可变 List（如 List.of(...)）不抛异常（IMP-9 防御性复制）
- **BLOCK-7 新增**：检查 Agent 超时 → 降级为仅规则校验，规则通过则放行该题（断言 passed=true）
- **BLOCK-7 新增**：检查 Agent 超时 + 规则校验失败 → 该题判失败，进入重试（断言 failed 列表包含该题）
- **IMP-16 新增**：检查 Agent 返回非 JSON → 抛出 AgentOutputInvalidException → 计入失败，进入重试
- **IMP-16 新增**：检查 Agent 返回 passed 字段缺失 → 视为 passed=false，计入失败

**预估工作量**：1.5 天

---

### T-06：HomeworkServiceImpl.generateDailyHomework 重构

**任务描述**：
- 重构 `generateDailyHomework` 方法，集成完整配额逻辑与举一反三流程
- 新步骤：
  ```
  1. 获取 active goal（不变）
  2. 查询 daily_plan（幂等检查）
     2a. 若 daily_plan 已存在 → 直接返回已有作业，跳过步骤 3-10，不重新生成举一反三新题
  3. 查询掌握度状态 + 知识点列表（不变）
  4. 查询到期错题（不变）
  5. 【新】配额计算（HomeworkQuotaCalculator.calculate）
  6. 组装错题引用（taskType=redo）（不变）
  7. 【新】举一反三新题生成（similarQuestionService.generateWithValidation）
     - 按配额为每个有错题的知识点生成新题
     - 查询原错题内容（selectSourceQuestionForSimilar）
     - 查询已有新题内容（selectCheckedContentsByKp）
  8. 【改】知识回顾新题生成（questionGenAgent.generate）
     - 使用配额计算结果中的 reviewQuota
  9. 合并所有引用（不变）
  10. 事务内写操作（不变）
  ```
- 注入新依赖：`SimilarQuestionService`、`HomeworkQuotaCalculator`（静态调用无需注入）
- 极端场景处理：到期错题 ≥ 15 → 举一反三/知识回顾暂停

**涉及文件**：
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`（修改）
- `src/test/java/com/jiang/service/HomeworkServiceTest.java`（修改，补充配额与举一反三测试）

**测试覆盖**：
- 极端场景：到期错题 ≥ 15 → 仅错题重做，无举一反三/知识回顾（产品规则显式降级场景）
- 正常场景：3 道错题 + 5 个知识点 → 配额分配正确
- 举一反三生成失败 → 降级处理，不阻塞作业生成
- 配额不足场景：知识点过多 → 低掌握度优先

**预估工作量**：1.5 天

---

### T-07：知识点状态联动

**任务描述**：
- 在 `SimilarQuestionServiceImpl` 中查询源题时，检查知识点状态
- 知识点状态为 `disabled` 时，跳过该知识点的举一反三生成
- 在 `HomeworkServiceImpl.generateDailyHomework` 中，过滤掉已停用知识点的错题
- 存量新题联动：`generated_question` 关联知识点被停用后，不再进入新作业（查询时过滤）

**涉及文件**：
- `src/main/java/com/jiang/service/impl/SimilarQuestionServiceImpl.java`（修改）
- `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java`（修改）
- `src/test/java/com/jiang/service/SimilarQuestionServiceTest.java`（修改）
- `src/test/java/com/jiang/service/HomeworkServiceTest.java`（修改）

**预估工作量**：0.5 天

---

### T-08：集成测试

**职责**：聚焦跨类、端到端的集成场景，不重复各 Task 中已覆盖的单元测试。

**集成测试用例**：
- 完整 `generateDailyHomework` 流程：配额计算 → 举一反三生成 → 检查 Agent 校验 → 相似度检测 → 写入 `generated_question` → 写入 `daily_plan`
- 降级路径：Agent 超时 → 降级日志写入（与 new question 同事务）→ 作业仍生成（仅缺少举一反三部分）
- 幂等性检查：重复调用 `generateDailyHomework` → 第二次直接返回已有 `daily_plan`，不重新生成新题
- 配额优先级场景：P0/P1/P2 分配正确，极端模式触发时举一反三全部暂停
- degradedMode 场景：N_error=14 + 多知识点 → 配额极度紧张，跳过配额为 0 的知识点的 Agent 调用
- **IMP-17 新增**：区分极端模式（similarQuotaPerKp 为空 Map）与配额紧张（similarQuotaPerKp 非空）的降级行为
- **BLOCK-7 新增**：检查 Agent 超时降级路径 → 仅规则校验通过 → 新题放行（断言 generated_question 表有记录）
- **BLOCK-7 新增**：检查 Agent 超时降级路径 → 规则校验失败 → 新题跳过（断言 generated_question 表无该题记录）
- **IMP-16 新增**：检查 Agent 输出格式异常 → 重试 5 次 → 记录降级日志 → 跳过该题
- 知识点停用联动：停用知识点不进入举一反三生成，存量新题不进入新作业
- DuplicateKeyException 场景：`uk_kp_content_hash` 冲突时跳过该题，不阻塞其他题目

**全量测试**：确保既有 320 测试 + 新增测试全部通过

**依赖**：T-05, T-06, T-07

**预估工作量**：1.5 天

---

## 依赖关系图

```
T-00（实体/枚举/工具类 + 测试 schema）
T-01（V4 DDL 脚本）
  ⇅ （并行，无依赖关系）
  ↓
T-02（QuestionCheckAgent）
T-03（QuestionGenAgent 扩展）
T-04（Mapper 扩展）
  ↓
T-05（SimilarQuestionService）← T-02, T-03, T-04
  ↓
T-06（HomeworkServiceImpl 重构）← T-00（HomeworkQuotaCalculator）, T-05
  ↓
T-07（知识点状态联动）← T-05, T-06
  ↓
T-08（集成测试）← T-05, T-06, T-07
```

---

## 工作量汇总

| Task | 预估工作量 |
|------|----------|
| T-00 | 1.5 天 |
| T-01 | 0.25 天 |
| T-02 | 1 天 |
| T-03 | 0.75 天 |
| T-04 | 0.25 天 |
| T-05 | 1.5 天 |
| T-06 | 1.5 天 |
| T-07 | 0.5 天 |
| T-08 | 1.5 天 |
| **总计** | **8.75 天** |

---

## 执行顺序建议

**第一阶段（基础设施）**：
- T-00 → T-01（并行：T-01 与 T-00 无依赖）

**第二阶段（Agent 层）**：
- T-02 + T-03 + T-04（可并行）

**第三阶段（核心 Service）**：
- T-05（依赖 T-02/T-03/T-04）

**第四阶段（集成重构）**：
- T-06（依赖 T-00/T-05）

**第五阶段（联动 + 测试）**：
- T-07 → T-08

---

## 技术债务登记（与 design.md §6 对齐）

| 编号 | 描述 | 登记位置 |
|------|------|----------|
| TD-SIMILARITY-THRESHOLD | 相似度阈值 0.6 为字符级粗筛经验值，语义层由检查 Agent 兜底 | design.md §6 |
| TD-CHECK-AGENT-CAPABILITY | 检查 Agent 校验准确率未评估 | design.md §6 |
| TD-CHECK-AGENT-CONSERVATIVE | 检查 Agent 采用保守判断策略（宁可漏放不可误杀） | design.md §6 BLOCK-6 |
| TD-EXTREME-MODE-STARVATION | 极端模式下错题全局截断可能导致某些知识点长期顺延 | design.md §6 BLOCK-5 |
| TD-SIMILAR-NO-PREREQUISITE | Demo 阶段不强制涉及先修知识点 | design.md §6 |

---

**任务制定人**：AI Agent  
**审核状态**：草案 v6（DETAIL-1~3 修正，待人工复审）
