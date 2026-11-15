# Proposal：修复 SIG-4 恒真与举一反三过度拒绝

> **Change 名称**：`2026-11-12_adjustment_signal_fix`
> **状态**：已实施，验证通过（见 `verify.md`），待归档

---

## 1. 背景

2026-11-12 13:25–13:54 的运行日志与线上库数据暴露三组问题：

### 1.1 SIG-4 对部分目标是「结构性恒真」

| 事实（实测） | 值 |
|--------------|-----|
| goal 7 | student 9（高三）、subject 14（英语）、`2026-11-11 → 2026-11-16`（6 天） |
| 年级过滤后未覆盖知识点 | **174**（`grade_range=12` 36−4 + `10-12` 142） |
| 6 天最大容量 | 6 × 3.0 × 0.8 = **14.4** |
| 覆盖 174 个所需天数 | **约 72 天** |

结论：该目标从创建第一天起 SIG-4 必然命中（同目标 11-11 的 `diff id=2` 已是 SIG-4），
而 `AutoAdjustmentTask`（每日 01:30）命中即生成 diff 并**直接应用**，且不设冷却期。

更关键的是**调整手段无法降低分子**：`diff id=4` 实测 `dailyQuestionLimit=null`、
`modifiedDates=[]`、`pausedKpIds` 20 个——只全量替换暂缓列表。而 SIG-4 的
`kUncovered` 统计**不扣除 `pausedKpIds`**，于是暂缓永远不减少分子，次日 01:30 再次命中、
再次生成一个「只换 20 个暂缓」的 diff，形成每日空转。

> **实测补充（2026-11-12 核库）**：该目标的 20 个暂缓点全部是 `grade_range=10`（高一年级），
> 不在高三学生的 174 个年级内未覆盖集合里——扣除前后都是 174。这进一步说明该 diff 是纯空转
> （它们是被旧版本「未按年级过滤」时暂缓的）。对「暂缓点确实在年级内」的目标，缺少扣除会让
> SIG-4 永远命中（暂缓点不出题却仍计压力），这正是本 change 要修的口径缺陷。

### 1.2 同一目标的 12h 去重只在考试结算入口实现

`docs/product/index.md` 规则 #5 已写明「同一目标 12 小时内已有
`plan_adjustment_log.action='confirmed'` 记录时跳过」，但代码只在
`ExamSignalAdjustServiceImpl`（考试结算触发）里实现；
手动入口 `PlanAdjustmentServiceImpl.triggerAdjustment` 与
`AutoAdjustmentTask`（01:30）**都没有该去重**，只挡 `pending` diff。

### 1.3 举一反三质量门与产品规则不一致，导致必然降级

| 维度 | 产品规则 #3（文档） | 代码实现（实测） |
|------|--------------------|------------------|
| 题干相似度阈值 | **0.8** | `SimilarityDetector.SIMILARITY_THRESHOLD = 0.6` |
| 重试次数 | **最多 2 次** | `MAX_ATTEMPTS = 5` |

日志中 `新题相似度过高 (similarity=0.625)` 恰好卡在被低估的 0.6 阈值上；
`question_check_v1.txt` 第 5 项又把「核心解法步骤高度一致」判为实质相同，
而举一反三要求的就是「同知识点同类题」——同一知识点的简单题不存在第二种解法，
语义检查必然判失败。两者叠加：`cne_h084` 2 题 × 5 轮全部失败、
`cnm_h145` 5 轮失败，最终 `agent_degradation_log.skip_similar_question`。
单知识点最坏 5 轮 ×（1 次生成 + N 次检查）≈ 15 次 LLM 调用，
在全局 3 并发闸门下长时间占用许可，直接放大 DashScope 的
`Model request timeout after PT2M` 与 `Connection reset`。

## 2. 目标

1. **SIG-4 口径修正**：`kUncovered` 扣除目标上的 `pausedKpIds`，
   检测（`SignalDetectorImpl`）与调整上下文（`buildAdjustmentContext`）保持同一口径。
2. **12h 去重覆盖全部入口**：手动触发与 01:30 自动任务同样跳过近期已确认的调整。
3. **目标创建时容量预检**：周期内可覆盖量 < 未覆盖量时返回**非阻断**提示（不拒绝创建）。
4. **举一反三质量门对齐产品规则**：阈值 0.6 → 0.8、生成轮数 5 → 3（首轮 + 最多 2 次重试）；新增
   `question_check_v2.txt`，把判据从「解法是否一致」改为「题干情境/数值/设问是否几乎一致」。

## 3. 范围

### 包含

- 新增 `KpCoverage`（未覆盖口径：年级 + 未学 + 扣除暂缓）与 `PausedKpIds`（暂缓 JSON 解析）工具
- `SignalDetectorImpl.detectSIG4`、`PlanAdjustmentServiceImpl.buildAdjustmentContext` 改用统一口径
- `PlanAdjustmentServiceImpl.triggerAdjustment` 增加 12h 去重；新增错误码 `PLAN_ADJUST_COOLDOWN`
- `AutoAdjustmentTask` / `ExamSignalAdjustServiceImpl` 对冷却跳过按「跳过」统计，不再计入失败
- `SignalConfig` 增加 `dedup-hours`（默认 12）+ `application.yml` 配置项
- `GoalServiceImpl.createGoal` 容量预检 + `GoalVO.capacityWarning`
- `SimilarityDetector.SIMILARITY_THRESHOLD` 0.6 → 0.8
- `SimilarQuestionServiceImpl.MAX_ATTEMPTS` 5 → 3（首轮 + 最多 2 次重试），降级日志文案改为「重试次数 = MAX_ATTEMPTS − 1」
- 新增 `prompts/versions/question_check_v2.txt`，`QuestionCheckAgent` 切换到 v2
- 单测：新增/更新 `SignalDetectorTest`、`PlanAdjustmentServiceTest`、`GoalServiceTest`、
  `SimilarityDetectorTest`、`SimilarQuestionServiceTest`
- 文档：规则 #5（SIG-4 口径 / 12h 去重范围 / 创建容量提示）、隐性约定 #28/#29、
  `openspec/specs/agent-contracts.md`（Prompt 版本）、`docs/standards/testing.md` 同步

### 不包含（另立 change）

- **P2 超时治理**：`agent.agentscope.timeout` 120s→90s、transport 错误退避、举一反三异步化
  —— 属受保护配置 + 调用链结构调整，需要单独评估
- **P3 时区口径**：连接串 `serverTimezone` 或统一 DB 时间（已存在 3 行 `update_time < create_time`）
- **知识点数据治理**：英语 791 个 kp、`grade_range=10-12` 独占 142 个，是否拆分过细
- 不延长/缩短目标周期（SIG-4 仍以暂缓知识点缓解，规则 #5 原设计不变）

## 4. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 扣除暂缓后 SIG-4 长期不再命中，学生「进度风险」不再被提示 | 中 | 暂缓是学生/Agent 主动选择的结果，且目标创建预检会在源头提示周期不足；`pausedKpIds` 可由下一次调整全量清空 |
| 12h 去重挡住学生主动的手动调整 | 低 | 返回明确错误码 `PLAN_ADJUST_COOLDOWN` 与文案；窗口可通过 `plan.adjustment.dedup-hours` 调整 |
| 阈值 0.8 + 2 轮导致举一反三题量不足 | 中 | 规则 #3 本就允许部分降级（跳过该题、减少 1 道），且 0.8 仍能拦截「只改数字」的换皮题（相似度通常 >0.95） |
| v2 Prompt 放宽后放过实质相同的新题 | 中 | 判据给出反例与正例，并保留相似度硬门 0.8；v1 文件保留可回滚 |
| 容量预检在创建接口增加一次知识点全表扫描（单科目 ≤791 行） | 低 | 复用 `selectActiveKpsWithMastery`（一次 JOIN 查询，无 N+1）；仅创建时触发 |

## 5. 验收口径

- `mvn test` 全绿（当前基线 801 tests / 0 failure / 5 skipped）
- 新增单测覆盖：暂缓扣除、12h 去重、容量预检、阈值/重试常量、Prompt v2 加载
- 用真实库数据验证：goal 7 的 174 个未覆盖中不含暂缓点（20 个暂缓点均为 `grade_range=10`），
  扣除前后均为 174、容量 9.6 → SIG-4 仍命中（口径生效且未误关）；
  「暂缓点确实在年级内时扣除生效」由单测覆盖
