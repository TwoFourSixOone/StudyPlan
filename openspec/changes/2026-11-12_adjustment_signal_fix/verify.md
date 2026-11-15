# Verify：SIG-4 口径 / 12h 去重 / 举一反三质量门对齐

> **Change 名称**：`2026-11-12_adjustment_signal_fix`
> **验证时间**：2026-11-12 14:04–14:18（Asia/Shanghai）
> **结论**：**实现与工件对齐，可归档**（遗留项见 §7，均已明确另立 change）

---

## 1. 范围与任务对照

| 任务 | 状态 | 证据 |
|------|------|------|
| T1 新增 `KpCoverage` / `PausedKpIds` | ✅ | `src/main/java/com/jiang/common/util/KpCoverage.java`、`PausedKpIds.java` |
| T2 SIG-4 扣除暂缓 | ✅ | `SignalDetectorImpl.detectSIG4` 改用 `KpCoverage.countUncovered(..., PausedKpIds.parseSet(goal.getPausedKpIds()))`；`HomeworkServiceImpl` 私有解析方法删除，改调 `PausedKpIds.parse` |
| T3 调整上下文扣除暂缓 | ✅ | `PlanAdjustmentServiceImpl.buildAdjustmentContext`：先 `KpCoverage.uncoveredRows(...)` 再 `limit(30)` |
| T4 12h 去重（三入口） | ✅ | `PlanAdjustmentServiceImpl` 冷却拦截 + `PLAN_ADJUST_COOLDOWN(1192)` + `SignalConfig.dedupHours` + `application.yml`；`AutoAdjustmentTask` / `ExamSignalAdjustServiceImpl` 按「跳过」归类 |
| T5 目标创建容量预检 | ✅ | `GoalServiceImpl.buildCapacityWarning` + `GoalVO.capacityWarning`（非阻断） |
| T6 阈值/重试对齐 | ✅ | `SimilarityDetector.SIMILARITY_THRESHOLD = 0.8`；`SimilarQuestionServiceImpl.MAX_ATTEMPTS = 3`（首轮 + 2 次重试），降级文案用 `MAX_ATTEMPTS - 1` |
| T7 `question_check_v2.txt` | ✅ | 新文件 + `QuestionCheckAgent.loadPromptTemplate` 指向 v2；v1 保留 |
| T8 单测补齐与回归 | ✅ | 见 §2 |
| T9 文档回写 | ✅ | 见 §5 |
| T10 真实数据验证 | ✅ | 见 §4 |

---

## 2. 测试证据

```
mvn test
→ Tests run: 821, Failures: 0, Errors: 0, Skipped: 5
→ BUILD SUCCESS
```

- 基线（改动前）：801 tests / 0 failure / 5 skipped
- 本次新增：**+20 用例**（`KpCoverageTest` 9、`SignalDetectorTest` +2、`PlanAdjustmentServiceTest` +2、
  `GoalServiceTest` +2、`SimilarQuestionServiceTest` +1、`SimilarityDetectorTest` +1、`AutoAdjustmentTaskTest` +3）
- 定向运行（8 个类）：105 tests / 0 failure

关键断言：

| 用例 | 断言 |
|------|------|
| `SignalDetectorTest#should_notDetectSIG4_when_pausedKpsDeducted` | 10 个未覆盖 − 暂缓 8 = 2 ≤ 容量 7.2 → **不命中** |
| `SignalDetectorTest#should_detectSIG4_when_pausedDeductedButStillInsufficient` | 10 − 2 = 8 > 7.2 → 命中，描述含「已扣除暂缓 2 个」 |
| `PlanAdjustmentServiceTest#should_excludePausedKps_when_buildingContext` | `uncoveredKps` 仅 `kp_002`，暂缓的 `kp_001` 不出现 |
| `PlanAdjustmentServiceTest#should_throwCooldown_when_recentConfirmedAdjustment` | 抛 `1192`，不检测信号、不调 Agent |
| `AutoAdjustmentTaskTest#should_skip_when_dedupWindowHit` | 冷却 → 不 applyDiff、不抛异常 |
| `GoalServiceTest#should_returnCapacityWarning_when_capacityInsufficient` | 300 未覆盖 > 216 容量 → `capacityWarning` 非空且含「300」 |
| `GoalServiceTest#should_returnNoCapacityWarning_when_capacityIsEnough` | 10 未覆盖 → null |
| `SimilarQuestionServiceTest#should_logDegradation_when_maxRetriesExhausted` | 生成被调用 **3 次**；降级文案含「2 次重试」 |
| `SimilarQuestionServiceTest#should_pass_when_similarityEqualsThreshold` | 相似度 = 0.8 **放行**（`>` 语义） |
| `SimilarityDetectorTest#should_alignThresholdWithProductRule` | 常量 = 0.8（回归锁） |

---

## 3. 启动验证（Spring 装配）

```
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
→ Tomcat started on port 8081 (http)
→ Started StudyPlanApplication in 4.197 seconds
→ 智学伴（StudyMate）启动成功
```

- 验证 `SignalConfig.dedupHours` 绑定、`GoalServiceImpl` / `PlanAdjustmentServiceImpl` 新依赖注入、
  `question_check_v2.txt` 可加载（Prompt 在首次调用时读取，启动不触发；单测已覆盖加载路径）
- 验证后已停止该实例；用户原有 80 端口实例未被触碰

---

## 4. 真实数据核对（studyplan 库，student 9 / goal 7）

| 检查项 | SQL 口径 | 结果 |
|--------|----------|------|
| 年级内未覆盖总量 | `grade_range IN ('12','10-12')` 且 mastery NULL/0 | **174**（与日志 `4.0 天 … < 174` 一致） |
| 扣除暂缓后 | `NOT IN (20 个 paused_kp_ids)` | **174**（见下方说明） |
| 20 个暂缓点的年级 | `knowledge_point.grade_range` | 全部 **10**（高一年级），不在高三口径内 |
| 容量 | `4 天 × 3.0 × 0.8` | **9.6** → SIG-4 仍命中（174 > 9.6），口径未误关 |
| 12h 去重（Java 本地时间参数） | `action='confirmed' AND action_at >= now-12h` | **0 条**（最近一次 confirmed 为 11-11 02:37，36h 前）→ 不会误挡手动调整 |
| 待处理 diff | `plan_adjustment_diff id=4` | `pending`、`pausedKpIds=20`、`modifiedDates=[]` → 应用后仅替换暂缓列表 |

> **重要事实（修正 proposal 初稿的估算）**：goal 7 的 20 个暂缓点全部是 `grade_range=10`，
> 属于旧版本「未按年级过滤」时暂缓的跨年级点，**不在** 174 个未覆盖集合里。
> 因此扣除暂缓对 goal 7 的数值无影响（174 → 174），本 change 的扣除逻辑价值在于
> 「暂缓点确实在年级内」的目标——那类目标若缺少扣除，SIG-4 会因暂缓点不出题却仍计压力而永远命中。
> 该行为由 `KpCoverageTest` / `SignalDetectorTest` 的构造用例覆盖。

---

## 5. 文档一致性

| 文档 | 改动 | 与代码对齐 |
|------|------|------------|
| `docs/product/index.md` 规则 #5 | SIG-4 未覆盖口径（年级 + 未学 + 扣暂缓）；去重 12h 覆盖三入口 + `1192`；边界补创建容量提示 | ✅ |
| `docs/product/index.md` 规则 #3 | 原文已是「阈值 0.8、最多 2 次」→ 本次让**代码**对齐文档，文档无需改数值 | ✅ |
| `docs/standards/testing.md` | 规则 #3 行补「首轮 + 最多 2 次重试，共 3 轮」 | ✅ |
| `docs/architecture/implicit-contracts.md` | 新增 #28（未覆盖口径唯一且扣暂缓）、#29（质量门常量以规则 #3 为准）；维护规则编号起点 #27 → #30 | ✅ |
| `openspec/specs/agent-contracts.md` | 新题校验 Prompt → `question_check_v2.txt`；举一反三降级行为 5 轮 → 3 轮 | ✅ |

残留检查：`grep` 全仓未发现还有「阈值 0.6」「举一反三 5 轮」的现行描述
（`implicit-contracts #29` 中的 0.6 / 5 轮属**历史说明**，用于记录发现背景，保留）。

---

## 6. 与 proposal 的偏离（全部为有意决策）

| 项 | proposal 初稿 | 实际实现 | 原因 |
|----|--------------|----------|------|
| 相似度阈值 | 「源题 0.6 / 新生成题 0.85」双档 | **单档 0.8** | 实测日志的 0.625 是与**原题**比较的相似度，保留 0.6 根本修复不了该现象；且规则 #3 与测试规范早已写 0.8，代码 0.6 是 drift。0.8 仍能拦「只改数字」的换皮题（相似度通常 >0.95） |
| 重试轮数 | 5 → 2 | 5 → **3**（首轮 + 2 次重试） | 规则 #3 原文是「最多 2 次**重试**」，字面语义 = 首轮 + 2 次；单 kp 最坏调用从 15 次降到 9 次 |
| 12h 冷却窗口 | 我建议 3 天 | **12 小时**（文档已有值，配置化） | 规则 #5 早已写「12 小时内已 confirmed 跳过」，本次只是把三个入口补齐 |
| 容量预检 | 「拒绝或提示」 | **仅提示**（`GoalVO.capacityWarning`） | 硬拒绝会阻断「短周期冲刺」这类合理诉求（规则 #1 主流程），运行期 SIG-4 兜底 |
| SIG-4 扣除暂缓的实际效果 | 预期 174 → 154 | 174 → 174（暂缓点跨年级） | 见 §4：数据事实与估算不同，已回写 proposal / design |

---

## 7. 遗留与后续 change

| 项 | 优先级 | 说明 |
|----|--------|------|
| P2 超时治理 | 高 | `agent.agentscope.timeout` 120s → 60–90s、transport 错误退避、举一反三移出同步链路（受保护配置 + 调用链调整） |
| P3 时间口径 | 中 | 连接串 `serverTimezone=Asia/Shanghai` 或统一 DB 时间；已存在 3 行 `daily_plan.update_time < create_time` |
| 跨年级暂缓历史数据 | 低 | goal 7 的 20 个 `grade_range=10` 暂缓点来自旧版本，可选择性清理（不影响正确性，只影响「暂缓」语义展示） |
| 0.8 阈值上线观察 | 中 | 需观察真实通过率；若仍大量降级，再评估「新题之间」单独阈值（0.85） |
| 知识点数据粒度 | 低 | 英语 791 个 kp、`grade_range=10-12` 独占 142 个，与「3 个/天」容量假设冲突 |

---

## 8. 变更清单（main / prompt / 配置）

| 文件 | sha256 |
|------|--------|
| `src/main/java/com/jiang/common/util/KpCoverage.java` | 68aef08554c29df4bff5d6306850d4cbd8bd3e711877ca913fe55b270355a4fd |
| `src/main/java/com/jiang/common/util/PausedKpIds.java` | 3e9f12a19620a8131d5b907d6419ce7a6935853cc45234fe2c42429bcd6927e8 |
| `src/main/java/com/jiang/service/impl/SignalDetectorImpl.java` | 2beb329c184254310d83d6496e646982f5237ffc7dbaac41322f69181da74b9e |
| `src/main/java/com/jiang/service/impl/PlanAdjustmentServiceImpl.java` | b90befcfe01e5ff1e07cc26e3eb96eb19a2b34513f13f1bb5a5db8311a958b9c |
| `src/main/java/com/jiang/service/impl/GoalServiceImpl.java` | 9289ffeb13408b8a43efc506767faff99f2adaa9b94d0037c477bbc7ed8a2cd3 |
| `src/main/java/com/jiang/service/impl/SimilarQuestionServiceImpl.java` | 2bf9e689a17f8239e14cc6ea197068072d8cb0370754392e30cc56bf88a4a56b |
| `src/main/java/com/jiang/service/impl/HomeworkServiceImpl.java` | 070f0ccfaaf193fd4e2c8d223e299876d5bb0c6ad231ca2295dc98ac66c21c85 |
| `src/main/java/com/jiang/service/impl/ExamSignalAdjustServiceImpl.java` | 1db5e985c01f88153ba0b9e98564faeba1e38a2d8e96e551f933aace89d104ec |
| `src/main/java/com/jiang/task/AutoAdjustmentTask.java` | 217c7444c3e51010128b621e8ff0d0ab385c37586e82e039f47846d8d2398634 |
| `src/main/java/com/jiang/common/util/SimilarityDetector.java` | 8d27adf2466a6b780dce8e96a9ef75455cf2214a7269d57201f0f312b8a4fae9 |
| `src/main/java/com/jiang/agent/QuestionCheckAgent.java` | b47ba40d8be8fba672b9f238c895bd42d6b8d2b05e3344c6844a88842820c46c |
| `src/main/java/com/jiang/config/SignalConfig.java` | 058f0dd0a9e516607b926c78231fa6ea75537dd638085ace85ed2a95893dd2af |
| `src/main/java/com/jiang/common/exception/ErrorCodes.java` | 04c639beea5549ffcf1bb232bbfc3074222fb706050ea9c67c096c1ca3870aaf |
| `src/main/java/com/jiang/vo/GoalVO.java` | 61d7d3eef7fbf8d91532def16b4828247a2d00e1d51b523249324ffc0469cd71 |
| `src/main/resources/prompts/versions/question_check_v2.txt`（新增） | dcad5d6c173d751f0058cba8be1cec84bffcbc03b9f972f3bedb4d33c89dac7f |
| `src/main/resources/application.yml` | fd3769141b1ab477aaa9a447e05e743469beb9b30e51f3d82e3189fbfa9c739f |

测试文件（新增/修改）：`KpCoverageTest`、`SignalDetectorTest`、`PlanAdjustmentServiceTest`、
`GoalServiceTest`、`SimilarityDetectorTest`、`SimilarQuestionServiceTest`、`AutoAdjustmentTaskTest`。

---

## 9. 结论

- 三组问题均已落地：SIG-4 未覆盖口径统一且扣除暂缓、12h 去重覆盖三入口、举一反三质量门与规则 #3 对齐。
- 回归 821 用例全绿，Spring 装配启动验证通过，真实库数据核对一致。
- 与初稿的 5 处偏离均已在 §6 说明原因，其中「阈值取 0.8」「重试 3 轮」以产品文档为准则。
- **建议归档**；P2（超时治理）/ P3（时区）另立 change。
