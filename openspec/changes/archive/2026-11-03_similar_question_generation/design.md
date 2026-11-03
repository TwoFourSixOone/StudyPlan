# Design：举一反三新题生成与完整配额逻辑

> **Change 名称**：`2026-11-03_similar_question_generation`  
> **创建日期**：2026-11-03  
> **状态**：草案 v3（第三轮审查修正）

---

## 1. 技术方案概述

本次 Change 实现举一反三新题生成的完整流程，包括：
1. 完整配额计算逻辑（规则 #3 的定量边界与裁剪规则）
2. 出题 Agent 针对错题知识点生成举一反三新题
3. 检查 Agent 校验新题质量
4. 本地相似度检测（SimHash + 余弦相似度）
5. 循环重试机制（最多 5 次）
6. 降级处理（记录 `agent_degradation_log`）

核心技术方案：
- **相似度检测**：SimHash 快速筛选 + 余弦相似度精确计算，阈值 0.6（字符级粗筛）
- **双 Agent 循环**：QuestionGenAgent 生成 → QuestionCheckAgent 校验 → 通过/重试
- **配额计算**：独立工具类 `HomeworkQuotaCalculator`，从 HomeworkServiceImpl 抽取
- **事务管理**：Agent 调用在事务外（TransactionTemplate），仅数据库写入在事务内

> **前置依赖**：本 Change V4 DDL 依赖前置 Change V3_1（`V3_1__add_performance_indexes.sql`）已 apply。

---

## 2. 分层设计

### 2.1 Agent 层

#### 2.1.1 QuestionGenAgent 扩展

在现有 `QuestionGenAgent` 中新增举一反三专项生成方法：

```java
/**
 * 针对指定错题知识点生成举一反三新题
 * 
 * @param kpId           知识点 ID
 * @param masteryScore   掌握度
 * @param sourceQuestion 原错题内容（从 question_bank 获取）
 * @param targetCount    目标生成数量（2~3 道），一次 Agent 调用生成全部
 * @param difficultyStrategy 难度递进策略（"progressive"），Agent 按 basic→standard→challenge 自行分配
 * @return 生成的新题列表
 */
public List<GeneratedQuestion> generateSimilar(
        String kpId, BigDecimal masteryScore,
        QuestionBank sourceQuestion, int targetCount, String difficultyStrategy);
```

Prompt 构造要点：
- 传入原错题内容（题干 + 答案 + 解析），要求"考查同一知识点但题目内容不同"
- 一次调用生成 targetCount 道，难度按"渐进"策略分配（第 1 道 basic/standard，第 2 道 standard，第 3 道 challenge）
- 要求输出格式与 `question_gen_schema.json` 一致

> **IMP-3 修正**：一次 Agent 调用生成 targetCount 道，难度由 Agent 按递进策略自行分配，而非调用方指定单值。difficulty 参数改为 difficultyStrategy="progressive"。

#### 2.1.2 QuestionCheckAgent（新增）

```java
@Component
@Slf4j
public class QuestionCheckAgent {

    @Autowired
    private AgentScopeClient agentScopeClient;

    /**
     * 校验新题质量
     * 检查项：
     * 1. 内容非空且格式完整（stem/answer/formatType 存在）
     * 2. 难度枚举合法性（difficulty ∈ {basic, standard, challenge}，IMP-14）
     * 3. 知识点关联正确（kpId 匹配）
     * 4. 答案合理性（非空、长度 > 0）
     * 5. 解题逻辑相似性（判断新题的解题思路是否与原题实质相同）— 纯规则无法覆盖，需 Agent 语义判断
     *
     * NOTE-2 修正：删除原 targetDifficulty 参数。渐进策略下每道题的目标难度由题序决定，
     * 但 check() 是单题校验，无法感知题序。难度校验简化为“枚举合法性”，
     * “相邻档位偏离”校验在当前架构下无法正确实现，已删除。
     *
     * @param question    待校验的新题
     * @param sourceQuestion 原错题（用于比较差异性）
     * @return 校验结果
     */
    public CheckResult check(GeneratedQuestion question, QuestionBank sourceQuestion);

    /**
     * BLOCK-7 修正：仅执行规则校验（内容完整、难度枚举合法、知识点匹配、答案合理），不调用 Agent
     * 用于检查 Agent 超时/不可用时的降级场景
     * DETAIL-2 修正：明确列出 4 项校验内容，避免“项 1-4”的抽象引用引起歧义
     *
     * @param question       待校验的新题
     * @param sourceQuestion 原错题
     * @return 规则校验是否全部通过
     */
    public boolean validateRulesOnly(GeneratedQuestion question, QuestionBank sourceQuestion);

    public static class CheckResult {
        private final boolean passed;
        private final String reason;  // 失败原因

        public static CheckResult pass() { return new CheckResult(true, null); }
        public static CheckResult fail(String reason) { return new CheckResult(false, reason); }
        // getters...
    }
}
```

> **BLOCK-CHECK-1**：检查 Agent 的职责边界：
> - **规则校验（项 1-4）**：内容非空、难度枚举合法、知识点匹配、答案合理——这些由确定性规则判断，Agent 仅做最终确认。
> - **Agent 独占校验（项 5）**：解题逻辑相似性判断——字符相似度检测（§2.2.1）无法识别“字面不同但解法相同”的情况，必须由 Agent 做语义层判断。这是检查 Agent 的不可替代价值。
> - **NOTE-2 修正**：原“难度映射一致 + 相邻档位”校验已删除，简化为“难度枚举合法性”（IMP-14 已覆盖）。渐进策略下每道题的目标难度由题序决定，但 check() 是单题校验，无法感知题序，因此不做档位偏离比较。
>
> **BLOCK-6 修正 — 检查 Agent 误判权衡策略**：
> - **保守策略（宁可漏放不可误杀）**：项 5 判断采用保守策略——仅当两题的核心解法步骤高度一致时才判为"实质相同"（失败）。表述不同但解法不同的题应判为"不同"（通过）。
> - **理由**：误杀代价（学生少题 + 30s Agent 超时浪费）> 漏放代价（学生做一道稍简单的题，仍有练习价值）。
> - **Prompt 输入要求**：检查 Agent 的输入必须包含原题和新题的**题干 + 答案 + 解析**，使 Agent 能基于解法判断（§2.6.2 已修正）。
> - **IMP-14 修正 — 非标准难度值处理**：若新题 difficulty 字段不在 {basic, standard, challenge} 枚举内（如 "hard"、"中等"），校验项 2 直接判失败（reason="invalid difficulty value"），不进入映射比较。在 `AgentOutputValidator` 中增加难度枚举校验。
> - **BLOCK-7 修正 — Agent 超时/不可用的降级语义**：
>   - 检查 Agent **超时或不可用**时，降级为"仅规则校验"（项 1-4 仍执行，项 5 跳过），规则通过则放行该题，记录 WARN 日志。
>   - 检查 Agent **正常返回"实质相同"**（passed=false）时，判该题失败，进入重试；5 次后跳过该题。
>   - 两者区分：超时/不可用 = 系统故障 → 降级放行；Agent 正常判断 = 业务结果 → 按结果处理。

#### 2.1.3 AgentType 枚举扩展

```java
// 新增
QUESTION_CHECK("question_check", "检查 Agent");
```

### 2.2 工具类层

#### 2.2.1 SimilarityDetector（新增）

```java
@Component
public class SimilarityDetector {

    /** 相似度阈值（字符级粗筛，语义层由检查 Agent 兜底） */
    private static final double SIMILARITY_THRESHOLD = 0.6;

    /**
     * 检测新题与已有题目的相似度
     * 
     * @param newQuestionContent 新题题干
     * @param existingContents   已有题目题干列表（题库原题 + 已生成新题）
     * @return 最高相似度值（0.0~1.0）
     */
    public double detectSimilarity(String newQuestionContent, List<String> existingContents);

    /**
     * 计算 SimHash
     */
    long simHash(String text);

    /**
     * 计算余弦相似度（基于词频向量）
     */
    double cosineSimilarity(String text1, String text2);

    /**
     * 综合相似度得分：SimHash 海明距离快速筛选 + 余弦相似度精确计算
     */
    double combinedSimilarity(String text1, String text2);
}
```

算法说明：
1. **SimHash**：将文本转为 64 位 hash，海明距离 ≤ 3 视为高度相似（快速筛选）
2. **余弦相似度**：基于中文分词（按字符 bigram 简化实现，不引入额外分词库）的词频向量计算
3. **综合策略**：先 SimHash 快速过滤（海明距离 > 10 直接返回低相似度），再对可疑对计算余弦相似度
4. **检测对象区分**：
   - **与原题比较**：sourceContent（原错题题干）必须包含在 existingContents 中，确保新题不与原题实质相同
   - **与已生成新题比较**：selectCheckedContentsByKp 的结果，确保不重复生成
   - 两者合并为 existingContents 统一检测

> **BLOCK-2 修正**：字符级相似度检测（SimHash + bigram 余弦）对中文数学题的语义识别能力有限，仅作为"明显重复"的**粗筛**。阈值从 0.6 而非 0.8（字符 bigram 在中文短文本上的取值范围通常 0.2~0.7，0.8 几乎不可达）。语义层面的"举一反三"质量（解题逻辑是否实质相同）由检查 Agent（§2.1.2 校验项 5）兜底。

> **TD-SIMILARITY-THRESHOLD**：阈值 0.6 为经验值，未经数据验证，后续可根据实际数据调整。建议以配置常量方式存放。

#### 2.2.2 HomeworkQuotaCalculator（新增）

```java
/**
 * 每日作业配额计算器
 * 实现规则 #3 的完整配额逻辑
 */
public class HomeworkQuotaCalculator {

    /** 单日总量上限 */
    public static final int DAILY_MAX = 15;
    /** 单知识点每日出题上限 */
    public static final int KP_MAX = 4;
    /** 每个有错题知识点至少分配的举一反三新题数 */
    public static final int SIMILAR_MIN_PER_KP = 1;
    /** 每个有错题知识点最多分配的举一反三新题数 */
    public static final int SIMILAR_MAX_PER_KP = 3;

    /**
     * 计算配额分配方案
     *
     * @param dueErrorCount  当日到期错题总数
     * @param errorKpIds     有错题的知识点 ID 列表
     * @param kpMasteryMap   知识点掌握度 Map（kpId → masteryScore）
     * @param kpErrorCountMap 每个知识点的错题数 Map（kpId → errorCount），用于动态计算举一反三上限
     * @return 配额方案
     * @throws IllegalArgumentException 若 kpErrorCountMap 中某值 > KP_MAX（防御性校验）
     */
    public static QuotaAllocation calculate(
            int dueErrorCount,
            Set<String> errorKpIds,
            Map<String, BigDecimal> kpMasteryMap,
            Map<String, Integer> kpErrorCountMap);

    /**
     * 配额分配方案
     */
    public static class QuotaAllocation {
        private final int errorQuota;          // 错题重做配额
        private final int similarQuota;        // 举一反三总配额
        private final int reviewQuota;         // 知识回顾总配额
        /** 只包含配额 > 0 的知识点（BLOCK-5 修正：统一语义，调用方无需额外判断 > 0） */
        private final Map<String, Integer> similarQuotaPerKp;  // 每个知识点的举一反三配额
        /** 只包含配额 > 0 的知识点 */
        private final Map<String, Integer> reviewQuotaPerKp;   // 每个知识点的知识回顾配额
        private final boolean extremeMode;     // 是否极端场景（错题 ≥ 15）
        /** IMP-17 修正：仅用于日志/监控，调用方遍历 Map 即可，无需分支判断 */
        private final boolean degradedMode;    // 是否降级模式（极端模式 OR 配额极度紧张）
    }
}
```

配额计算流程（规则 #3，BLOCK-1/BLOCK-5 修正）：
```
优先级体系：
  P0: 错题重做（每知识点 ≤ 3 道，总数 ≤ 15）
  P1: 有错题知识点的举一反三（每知识点至少 1 道，配额充足时扩展）
  P2: 无错题知识点的知识回顾（按掌握度从低到高分配）

前置防御（BLOCK-5 修正）：
  - 校验 kpErrorCountMap 每个值 ≤ KP_MAX(4)，若超过则抛 IllegalArgumentException
  - 调用方（HomeworkServiceImpl）负责确保入参合法（前置 Change 已截断每知识点 ≤ 3 道）

流程：
1. 统计到期错题数 N_error
2. 若 N_error ≥ 15 → 极端模式（degradedMode=true）：
   - 错题重做独占当日全部配额（取前 15 题），举一反三全部暂停（含最低保障），知识回顾暂停
   - 这是产品规则的显式声明：极端场景下牺牲举一反三保错题重做（proposal §3 已声明）
   - 超出 15 题的错题顺延至次日（不视为未完成，不触发规则 #5 的 SIG-3 信号）
   - BLOCK-5 饥饿风险声明：若长期有 >15 道到期错题，说明学生积压严重，
     需由 Change 10（计划调整系统）处理，本 Change 不做轮转公平性保证。
     登记 TD-EXTREME-MODE-STARVATION。
3. 否则（正常模式）：
   a. P0 — 错题重做配额 = N_error
   b. P1 — 有错题知识点的举一反三：
      - 每个有错题知识点至少分配 1 道举一反三（最低保障）
      - 特殊情况：若某知识点错题数 = KP_MAX(4)，举一反三上限 = 0，
        最低保障对该知识点失效（优先级：KP_MAX 硬上限 > 最低保障）
      - 若剩余配额不足以覆盖所有有错题知识点的最低保障：
        按掌握度从低到高依次分配，配额用完即止
        此时若 similarQuota < errorKpIds.size()，标记 degradedMode=true（配额极度紧张）
      - 配额充足时，举一反三可扩展，动态上限 = KP_MAX - 该知识点错题数
        （例：某知识点有 2 道错题 → 举一反三上限 = 4 - 2 = 2 道）
        单知识点总量（错题 + 举一反三）不超过 KP_MAX = 4
   c. P2 — 无错题知识点的知识回顾：
      - 按掌握度从低到高，每个分配 2~3 道常规新题，直到配额用完
      - 当知识点数过多导致配额不足：优先保障低掌握度知识点，
        高掌握度知识点的新题可压缩至 0 道
   d. Map 语义统一（IMP-12 修正）：
      - similarQuotaPerKp 只包含配额 > 0 的知识点
      - reviewQuotaPerKp 只包含配额 > 0 的知识点
      - 调用方无需额外判断 > 0，遍历 Map 即可
```

> **BLOCK-IMP-17 修正 — degradedMode 用途说明**：
> - `degradedMode` 仅用于**日志标记和监控指标**，调用方（HomeworkServiceImpl）遍历 `similarQuotaPerKp` Map 即可，无需根据 `degradedMode` 做分支判断。
> - 两种场景区分：极端模式下 Map 为空（全暂停，无需遍历）；配额紧张下 Map 非空（部分知识点配额 > 0，正常遍历生成）。
> - 若未来需要根据降级类型做不同处理，可拆分为 `extremeMode`（极端）+ `quotaTight`（配额紧张）两个独立标志。

#### 2.3 Service 层

#### 2.3.1 SimilarQuestionService（新增）

```java
public interface SimilarQuestionService {

    /**
     * 为指定知识点生成举一反三新题（含检查 Agent 校验 + 相似度检测 + 循环重试）
     *
     * @param studentId       学生 ID
     * @param tenantId        租户 ID
     * @param kpId            知识点 ID
     * @param masteryScore    掌握度
     * @param sourceQuestion  原错题内容
     * @param targetCount     目标生成数量
     * @param sourceContent   原错题题干（用于相似度比较，确保新题不与原题实质相同）
     * @param existingContents 已有新题内容列表（selectCheckedContentsByKp 结果）
     * @return 校验通过的新题列表
     */
    List<GeneratedQuestion> generateWithValidation(
            Long studentId, String tenantId, String kpId,
            BigDecimal masteryScore, QuestionBank sourceQuestion,
            int targetCount, String sourceContent, List<String> existingContents);
}
```

核心流程（循环 ≤ 5 次）：
```
// IMP-9 修正：防御性复制，避免修改调用方的不可变 List
List<String> mutableContents = new ArrayList<>(existingContents);
mutableContents.add(sourceContent);  // 加入原错题题干

for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    // 1. 出题 Agent 生成新题
    List<GeneratedQuestion> candidates = questionGenAgent.generateSimilar(...);
    
    List<GeneratedQuestion> passed = new ArrayList<>();
    List<GeneratedQuestion> failed = new ArrayList<>();
    
    for (GeneratedQuestion candidate : candidates) {
        // 2. 相似度检测（mutableContents 包含原错题题干 + 已生成新题）
        double similarity = similarityDetector.detectSimilarity(
                extractStem(candidate), mutableContents);
        if (similarity > 0.6) {
            failed.add(candidate);
            log.warn("新题相似度过高: {} (similarity={})", candidate.getKnowledgePointId(), similarity);
            continue;
        }
        
        // 3. 检查 Agent 校验（保守策略：宁可漏放不可误杀，BLOCK-6 + BLOCK-7）
        try {
            QuestionCheckAgent.CheckResult checkResult = questionCheckAgent.check(
                    candidate, sourceQuestion);
            if (!checkResult.isPassed()) {
                failed.add(candidate);
                log.warn("新题校验失败: {} (reason={})", candidate.getKnowledgePointId(), checkResult.getReason());
                continue;
            }
        } catch (AgentTimeoutException e) {
            // BLOCK-7 修正：Agent 超时/不可用 → 降级为仅规则校验（项 1-4）
            // 规则校验是确定性的，不依赖 Agent，仍能挡住明显不合格的题
            boolean rulesPassed = questionCheckAgent.validateRulesOnly(candidate, sourceQuestion);
            if (rulesPassed) {
                log.warn("检查 Agent 超时，降级为仅规则校验通过: {}", candidate.getKnowledgePointId());
                // 规则通过 → 放行该题（宁可漏放不可误杀）
            } else {
                failed.add(candidate);
                log.warn("检查 Agent 超时，降级规则校验失败: {}", candidate.getKnowledgePointId());
                continue;
            }
        } catch (AgentOutputInvalidException e) {
            // IMP-16 修正：Agent 输出格式异常 → 视为校验失败，进入重试
            failed.add(candidate);
            log.warn("检查 Agent 输出格式异常: {} (error={})", candidate.getKnowledgePointId(), e.getMessage());
            continue;
        }
        
        // 4. 通过 → 标记已校验
        candidate.setSimilarityChecked(1);
        candidate.setContentHash(computeContentHash(candidate));
        passed.add(candidate);
    }
    
    // 5. 全部通过 → 返回
    if (failed.isEmpty()) {
        return passed;
    }
    
    // 6. 部分失败 → 仅对失败数量补充生成，进入下一轮
    int stillNeeded = targetCount - passed.size();
    if (stillNeeded <= 0) {
        return passed;
    }
    // 更新 mutableContents（加入已通过的题目）
    mutableContents.addAll(passed.stream().map(this::extractStem).collect(toList()));
    targetCount = stillNeeded;
}

// 7. 循环 5 次仍有不足 → 记录降级日志（统一一次）
// BLOCK-3 修正：降级日志在循环结束后统一记录一次，包含失败数量、原因汇总、重试轮次
// 降级日志与通过的新题在同一事务内写入，避免"日志有、新题无"的不一致
logFailedGenerations(failed, retryAttempts, ...);  // 在 TransactionTemplate 回调内
return passed;  // 返回可能不足 targetCount
```

> **BLOCK-RETRY-1**：每轮循环中，已通过的题目保留，仅对失败数量重新生成。避免重复生成已通过的题目。

> **BLOCK-RETRY-2**：循环 5 次后仍有失败，记录 `agent_degradation_log`（agentType=QUESTION_CHECK，degradationAction="skip_similar_question"），失败题目不纳入作业，但不阻塞已通过的题目和后续流程。

> **BLOCK-3 修正**：降级日志在循环结束后**统一记录一次**（而非每次失败记录），记录内容包括：失败题目数量、失败原因汇总、实际重试轮次。降级日志与通过的新题在**同一事务内写入**（TransactionTemplate 回调内），避免“日志有、新题无”的不一致。复用 `HomeworkDegradationHandler` 记录日志，agentType=QUESTION_CHECK。

> **IMP-15 修正 — mutableContents 规模控制**：
> - `existingContents` 来自 `selectCheckedContentsByKp`，已限制为最近 100 条（§2.4.1）。
> - 循环中每轮追加通过题的题干，最多 5 轮 × 3 道 = 15 条，总规模 ≤ 116 条。
> - 相似度检测复杂度 O(候选数 × existingContents 大小)，候选数 ≤ 3，总比较次数 ≤ 3 × 116 = 348，性能可接受。

#### 2.3.2 HomeworkServiceImpl 重构

`generateDailyHomework` 方法重构为以下步骤：

```
1. 获取 active goal
2. 查询 daily_plan（幂等检查）
   2a. 【IMP-7 修正】若 daily_plan 已存在 → 直接返回已有作业，跳过步骤 3-10，
       不重新生成举一反三新题，确保幂等性
3. 查询掌握度状态 + 知识点列表
4. 查询到期错题（errorQuestionService.getDueErrorQuestions）
5. 【新】配额计算（HomeworkQuotaCalculator.calculate）
   - 极端场景：错题 ≥ 15 → 独占，举一反三/知识回顾暂停
   - 正常场景：分配错题/举一反三/知识回顾配额
6. 组装错题引用（taskType=redo）
7. 【新】举一反三新题生成（similarQuestionService.generateWithValidation）
   - 按配额为每个有错题的知识点生成 1~3 道新题
   - 含检查 Agent 校验 + 相似度检测 + 循环重试
8. 【新】知识回顾新题生成（questionGenAgent.generate）
   - 按配额为无错题知识点生成常规新题
   - 复用现有 Agent 调用逻辑
9. 合并所有引用（错题 + 举一反三 + 知识回顾）
10. 事务内写操作（TransactionTemplate）
```

> **IMP-TX-1**：Agent 调用（步骤 7/8）在事务外执行，仅数据库写入（步骤 10）在事务内。遵循隐性约定 #10。

### 2.4 DAO 层

#### 2.4.1 GeneratedQuestionMapper 扩展

```java
/**
 * 查询指定知识点下已生成且通过校验的新题内容（用于相似度比较）
 * IMP-15 修正：仅取最近 100 条，按生成时间倒序，更早的题由唯一索引防重
 */
@Select("SELECT content FROM generated_question " +
        "WHERE knowledge_point_id = #{kpId} AND tenant_id = #{tenantId} " +
        "AND similarity_checked = 1 " +
        "ORDER BY created_at DESC " +
        "LIMIT 100")
List<String> selectCheckedContentsByKp(@Param("kpId") String kpId,
                                       @Param("tenantId") String tenantId);
```

> **IMP-11 修正**：删除 `countByContentHash` 方法（原声明但流程中未调用）。防重复写入完全依赖 `uk_kp_content_hash` 唯一索引 + DuplicateKeyException 捕获（§2.4.3），避免多一次查询。

#### 2.4.2 ErrorQuestionMapper 扩展

```java
/**
 * 获取到期错题对应的原题内容（JOIN question_bank）
 * 用于构造出题 Agent 的输入
 *
 * IMP-5 修正：举一反三的源题选择策略——取最早到期的 1 道错题对应的原题，
 * 作为该知识点的"代表错题"。不要求覆盖所有错题，
 * 因为举一反三的目标是"巩固知识点"而非"覆盖所有错题"。
 */
@Select("SELECT qb.question_id, qb.content, qb.answer, qb.analysis, " +
        "qb.difficulty, qb.type, qb.knowledge_point_id " +
        "FROM error_question eq " +
        "JOIN question_bank qb ON eq.question_id = qb.question_id " +
        "  AND qb.tenant_id = eq.tenant_id " +
        "WHERE eq.student_id = #{studentId} AND eq.tenant_id = #{tenantId} " +
        "AND eq.knowledge_point_id = #{kpId} " +
        "AND eq.status = 'active' AND eq.next_due_date <= #{targetDate} " +
        "ORDER BY eq.next_due_date ASC, eq.error_question_id ASC " +
        "LIMIT 1")
QuestionBank selectSourceQuestionForSimilar(@Param("studentId") Long studentId,
                                             @Param("tenantId") String tenantId,
                                             @Param("kpId") String kpId,
                                             @Param("targetDate") LocalDate targetDate);
```

#### 2.4.3 DuplicateKeyException 处理

写入新题到 `generated_question` 时，捕获 `DuplicateKeyException`（触发 `uk_kp_content_hash` 唯一索引）：
- 视为"内容重复"，跳过该题
- 记录 WARN 日志
- 不阻塞其他题目的写入
- 与相似度检测的"重复"处理一致（跳过，不报错）

> **IMP-4 修正**：SimHash 碰撞概率低但非零，唯一索引可能误杀（题干不同但 hash 相同）。当前接受此风险，因为 64 位 SimHash 碰撞概率极低（~2^-64），且应用层已有相似度检测作为前置防线。

### 2.5 Model 层

#### 2.5.1 GeneratedQuestion 实体扩展

新增字段：
```java
/** 题干内容哈希（SimHash，用于快速相似度比较） */
private String contentHash;
```

#### 2.5.2 新增枚举值

`AgentType` 新增：
```java
QUESTION_CHECK("question_check", "检查 Agent");
```

#### 2.5.3 新增错误码

`ErrorCodes` 新增（1110-1119 段）：
```java
// 举一反三相关（1110-1119）
SIMILAR_QUESTION_GEN_FAILED(1110, "举一反三新题生成失败"),
SIMILAR_QUESTION_CHECK_FAILED(1111, "举一反三新题校验失败"),
SIMILAR_QUESTION_TOO_SIMILAR(1112, "举一反三新题与原题相似度过高"),
AGENT_OUTPUT_INVALID(1113, "Agent 输出格式异常");
```

#### 2.5.4 新增异常类（NOTE-1 修正）

`AgentTimeoutException` 和 `AgentOutputInvalidException` 均为**新增异常类**，位于 `com.jiang.common.exception` 包：

```java
/**
 * Agent 调用超时异常
 * 当 AgentScopeClient 调用超过 30s 时抛出
 */
public class AgentTimeoutException extends RuntimeException {
    public AgentTimeoutException(String message) { super(message); }
    public AgentTimeoutException(String message, Throwable cause) { super(message, cause); }
}

/**
 * Agent 输出格式异常
 * 当 Agent 返回的内容无法解析为合法 JSON 或必要字段缺失时抛出
 */
public class AgentOutputInvalidException extends RuntimeException {
    public AgentOutputInvalidException(String message) { super(message); }
    public AgentOutputInvalidException(String message, Throwable cause) { super(message, cause); }
}
```

> **与现有异常体系的关系**：
> - 现有 `BusinessException` + `ErrorCodes` 用于业务层异常（如 AGENT_CALL_FAILED）。
> - `AgentTimeoutException` / `AgentOutputInvalidException` 是 Agent 层的**技术异常**，用于 SimilarQuestionService 循环内的细粒度异常处理（BLOCK-7 降级 / IMP-16 重试）。
> - 两者不互斥：Agent 层抛出技术异常 → Service 层捕获处理 → 若 5 次重试均失败，最终可通过 `BusinessException(ErrorCodes.SIMILAR_QUESTION_CHECK_FAILED)` 向上层报告。

### 2.6 Prompt 与 Schema

#### 2.6.1 新增 Prompt 模板

`src/main/resources/prompts/versions/similar_question_gen_v1.txt`

Prompt 要点：
- 输入：原错题内容（题干 + 答案 + 解析）、知识点名称、目标难度
- 要求：生成 1 道与原题考查同一知识点但内容不同的新题
- 输出格式：与 `question_gen_schema.json` 一致的单题 JSON

#### 2.6.2 新增检查 Agent Prompt

`src/main/resources/prompts/versions/question_check_v1.txt`

Prompt 要点：
- 输入：新题内容（**题干 + 答案 + 解析**）+ 原错题内容（**题干 + 答案 + 解析**）
- NOTE-2 修正：删除原“目标难度”输入，难度校验已简化为枚举合法性（项 2），无需传入目标难度
- 要求：判断新题是否与原题实质相同（解题逻辑相似性）、难度枚举是否合法、答案是否合理
- BLOCK-6 修正：Prompt 中明确指示“仅当两题的核心解法步骤高度一致时才判为相同（passed=false）；表述不同但解法不同的题应判为不同（passed=true）”——保守策略，宁可漏放不可误杀
- 输出格式：`{"passed": true/false, "reason": "..."}`
- **IMP-16 修正 — Agent 输出格式异常处理**：
  - 返回非 JSON（如纯文本“通过”）→ 抛出 `AgentOutputInvalidException`，由 SimilarQuestionService 捕获并计入失败，进入重试
  - `passed` 字段缺失 → 视为 `passed=false`（保守策略，不因 Agent 输出问题而漏放）
  - `reason` 为空但 `passed=false` → 接受该结果（Agent 可能无法给出详细原因）
  - **DETAIL-3 补充**：`passed=true` 时忽略 `reason` 字段（不校验其是否为空）——Agent 可能异常输出“passed=true + reason='xxx'”，以 passed 为准
  - 5 次输出格式异常后，记录降级日志，跳过该题

---

## 3. 数据库变更

### 3.1 V4 DDL 脚本

```sql
-- V4__similar_question_enhancement.sql
-- Change: 2026-11-03_similar_question_generation
-- 依赖：V3_1__add_performance_indexes.sql 已 apply

-- 1. generated_question 表新增 content_hash 字段
ALTER TABLE `generated_question`
    ADD COLUMN `content_hash` VARCHAR(64) DEFAULT NULL COMMENT '题干内容 SimHash（相似度快速比较）'
    AFTER `similarity_checked`;

-- 2. 唯一索引：防止同一知识点下重复写入相同内容的新题
ALTER TABLE `generated_question`
    ADD UNIQUE INDEX `uk_kp_content_hash` (`knowledge_point_id`, `content_hash`, `tenant_id`);

-- 回滚方案（仅开发环境可执行，生产需数据迁移）
-- ALTER TABLE `generated_question` DROP INDEX `uk_kp_content_hash`;
-- ALTER TABLE `generated_question` DROP COLUMN `content_hash`;
```

### 3.2 测试 schema.sql 同步

在测试 `schema.sql` 的 `generated_question` 表中补充：
```sql
`content_hash` VARCHAR(64) DEFAULT NULL,
-- 索引
UNIQUE INDEX `uk_kp_content_hash` (`knowledge_point_id`, `content_hash`, `tenant_id`)
```

---

## 4. 异常处理

### 4.1 业务异常

| 异常场景 | 错误码 | 编号 | 处理方式 |
|----------|--------|------|----------|
| 举一反三新题生成失败 | `SIMILAR_QUESTION_GEN_FAILED` | 1110 | 记录降级日志，跳过该知识点 |
| 新题校验失败 | `SIMILAR_QUESTION_CHECK_FAILED` | 1111 | 重试，5 次后记录降级日志 |
| 新题相似度过高 | `SIMILAR_QUESTION_TOO_SIMILAR` | 1112 | 重试，5 次后记录降级日志 |
| Agent 输出格式异常 | `AGENT_OUTPUT_INVALID` | 1113 | 重试，5 次后记录降级日志 |

### 4.2 降级策略

| 场景 | 降级行为 | 用户影响 |
|------|----------|----------|
| 出题 Agent 生成失败 | 跳过该知识点的举一反三，保留错题重做 + 知识回顾 | 少 1~3 道新题 |
| 检查 Agent 超时/不可用 | **BLOCK-7 修正**：降级为仅规则校验（项 1-4），规则通过则放行，记录 WARN 日志 | 可能放行一道语义重复题（保守策略） |
| 检查 Agent 输出格式异常 | **IMP-16 修正**：视为校验失败，进入重试；5 次后记录降级日志，跳过该题 | 少 1 道新题 |
| 检查 Agent 校验 5 次不通过（业务结果） | 记录 `agent_degradation_log`，跳过该题 | 少 1 道新题 |
| 相似度检测 5 次不通过 | 记录 `agent_degradation_log`，跳过该题 | 少 1 道新题 |
| 配额计算异常 | 回退到简化配额（15 - 错题数） | 配额可能不精确 |
| 配额极度紧张（degradedMode） | 跳过配额为 0 的知识点的 Agent 调用，避免无谓 30s 超时 | 少 1~3 道新题 |

---

## 5. 隐性约定对齐

| 约定编号 | 约定内容 | 本次实现 |
|----------|----------|----------|
| #1 | 统一响应结构 | 不涉及新接口 |
| #2 | 异常处理规范 | 新增 4 个错误码（1110-1113）+ 2 个技术异常类，Service 捕获后按降级策略处理 |
| #9 | @Transactional rollbackFor | 新题写入使用显式 rollbackFor |
| #10 | 事务边界精确控制 | Agent 调用在事务外（TransactionTemplate），仅 DB 写入在事务内 |
| #11 | 并发写入保护 | `uk_kp_content_hash` 唯一索引 + DuplicateKeyException 捕获（跳过重复，记录 WARN 日志） |
| #12 | Agent 调用超时降级 | 出题 Agent + 检查 Agent 均 30s 超时；**BLOCK-7 修正**：检查 Agent 超时降级为仅规则校验（项 1-4），规则通过则放行 |

---

## 6. 技术债务登记

| 编号 | 描述 | 登记原因 |
|------|------|----------|
| **TD-SIMILARITY-THRESHOLD** | 相似度阈值 0.6 为经验值，未经数据验证 | 字符级粗筛，语义层由检查 Agent 兜底；后续可根据实际数据调整 |
| **TD-CHECK-AGENT-CAPABILITY** | 检查 Agent 的校验准确率（特别是解题逻辑相似性判断）未评估 | 当前仅校验明显问题 + 语义判断，后续需建立评估基准 |
| **TD-CHECK-AGENT-CONSERVATIVE** | 检查 Agent 采用保守判断策略（宁可漏放不可误杀），误杀率可能偏高 | BLOCK-6 明确权衡策略，待能力评估后调整 |
| **TD-EXTREME-MODE-STARVATION** | 极端模式下错题全局截断可能导致某些知识点长期顺延 | 由 Change 10（计划调整系统）处理，本 Change 不做轮转公平性保证 |
| **TD-SIMILAR-NO-PREREQUISITE** | Demo 阶段举一反三不强制涉及先修知识点 | 规则 #3 明确“Demo 阶段不强制涉及先修知识点” |

---

## 7. 审查修正记录

### 第一轮审查

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-CHECK-1 | 阻断 | 检查 Agent 校验能力边界未定义 | §2.1.2 明确仅校验明显问题 |
| BLOCK-RETRY-1 | 阻断 | 循环重试时已通过的题目是否保留未说明 | §2.3.1 明确保留已通过题目 |
| BLOCK-RETRY-2 | 阻断 | 5 次失败后的处理策略未定义 | §2.3.1 明确记录降级日志 + 跳过 |
| IMP-TX-1 | 重要 | Agent 调用的事务边界未明确 | §2.3.2 明确 Agent 在事务外 |
| IMP-HASH-1 | 重要 | content_hash 的计算方式未定义 | §2.2.1 明确使用 SimHash |
| IMP-QUOTA-1 | 改进 | 配额计算逻辑位置未明确 | §2.2.2 独立工具类 |

### 第二轮审查（BLOCK/IMP 修正）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-1 | 阻断 | 配额逻辑优先级体系缺失，极端模式语义不清，KP_MAX-1 静态，参数未使用 | §2.2.2 重写配额流程（P0/P1/P2 优先级，动态上限，kpErrorCountMap 替代 totalActiveKpCount） |
| BLOCK-2 | 阻断 | 字符 bigram 对中文数学题语义识别不足，阈值 0.8 虚高 | §2.2.1 阈值降至 0.6，明确"字符级粗筛 + Agent 语义兜底"定位 |
| BLOCK-3 | 阻断 | 降级日志写入时机与事务边界矛盾 | §2.3.1 循环后统一记录一次，与 new question 同事务写入 |
| BLOCK-4 | 阻断 | 难度枚举与 D_norm 映射不一致 | §2.1.2 明确 basic=0.3/standard=0.6/challenge=0.9，允许相邻档位 |
| IMP-1 | 重要 | existingContents 未包含原错题题干 | §2.3.1 接口新增 sourceContent 参数，合并到 existingContents |
| IMP-2 | 重要 | reviewQuotaPerKp 截断规则未定义 | §2.2.2 明确只包含配额 > 0 的知识点 |
| IMP-3 | 重要 | 多题生成逻辑（一次 vs N 次调用）未说明 | §2.1.1 明确一次调用 + difficultyStrategy="progressive" |
| IMP-4 | 重要 | DuplicateKeyException 处理未说明 | §2.4.3 新增专门小节 |
| IMP-5 | 重要 | 源题选择策略未说明 | §2.4.2 注释中说明 |
| IMP-6 | 重要 | 检查 Agent 的不可替代价值未说明 | §2.1.2 BLOCK-CHECK-1 明确项 5（解题逻辑相似性） |
| IMP-7 | 重要 | generateDailyHomework 幂等性未讨论 | §2.3.2 步骤 2a 补充幂等检查 |
| IMP-8 | 重要 | T-08 集成测试与单元测试混淆 | tasks.md T-08 重写 |

### 第三轮审查（BLOCK-5/BLOCK-6/IMP-9~14 修正）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-5 | 阻断 | P1 最低保障与 KP_MAX 冲突、N_error=14 临界、极端模式饥饿风险 | §2.2.2 前置防御、KP_MAX 硬上限优先、degradedMode 标志、TD-EXTREME-MODE-STARVATION |
| BLOCK-6 | 阻断 | 检查 Agent 误判权衡未定义、Prompt 输入不完整 | §2.1.2 保守策略、§2.6.2 输入含答案+解析、TD-CHECK-AGENT-CONSERVATIVE |
| IMP-9 | 重要 | existingContents 原地修改不可变 List 抛异常 | §2.3.1 防御性复制 mutableContents |
| IMP-10 | 重要 | SQL 换行符污染 + LIMIT 1 无稳定二级排序 | §2.4.2 清理 \n、ORDER BY + error_question_id ASC |
| IMP-11 | 重要 | countByContentHash 声明但未调用（死代码） | §2.4.1 删除，防重完全依赖唯一索引 |
| IMP-12 | 重要 | similarQuotaPerKp Map 语义不统一 | §2.2.2 统一为只包含配额 > 0 的知识点 |
| IMP-13 | 重要 | T-03 签名与 design 不一致 | tasks.md T-03 同步为 difficultyStrategy |
| IMP-14 | 重要 | 非标准难度值处理未定义 | §2.1.2 直接判校验失败 |

### 第四轮审查（BLOCK-7/IMP-15~17 修正）

| 编号 | 级别 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| BLOCK-7 | 阻断 | Agent 超时降级语义与 design §4.2 冲突（tasks 写“放行”，design 写“跳过”） | §2.1.2 BLOCK-7 修正、§2.3.1 循环逻辑、§4.2 降级策略表：统一为“降级为仅规则校验（项 1-4），规则通过则放行” |
| IMP-15 | 重要 | selectCheckedContentsByKp 未限制返回条数，历史新题过多时性能风险 | §2.4.1 增加 LIMIT 100 + ORDER BY created_at DESC |
| IMP-16 | 重要 | 检查 Agent 输出格式异常（非 JSON、passed 缺失）处理未定义 | §2.1.2 BLOCK-7、§2.3.1 循环逻辑、§2.6.2 Prompt 输出格式、§4.2 降级策略表 |
| IMP-17 | 重要 | degradedMode 标志在两种场景下均为 true，调用方处理不同 | §2.2.2 明确 degradedMode 仅用于日志/监控，调用方遍历 Map 即可 |
| NOTE-1 | 备注 | AgentTimeoutException / AgentOutputInvalidException 未声明为新增异常类 | §2.5.4 新增异常类定义 + ErrorCodes 新增 AGENT_OUTPUT_INVALID(1113) |
| NOTE-2 | 备注 | check() 的 targetDifficulty 参数在渐进策略下语义不清，校验项 2 形同虚设 | §2.1.2 删除 targetDifficulty 参数，校验项 2 简化为枚举合法性；§2.3.1/§2.6.2 同步 |

---

**设计人**：AI Agent  
**审核状态**：草案 v6（DETAIL-1~3 修正，待人工复审）
