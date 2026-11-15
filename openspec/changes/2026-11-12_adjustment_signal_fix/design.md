# Design：SIG-4 口径 / 12h 去重 / 举一反三质量门对齐

> **Change 名称**：`2026-11-12_adjustment_signal_fix`

---

## 0. 总览

```
                    ┌──────────────────────────────┐
KpCoverage（新）───>│ 未覆盖口径：年级 + 未学 + 扣暂缓 │
PausedKpIds（新）──>└───────────┬──────────────────┘
                               │
      ┌────────────────────────┼────────────────────────┐
      ▼                        ▼                        ▼
SignalDetectorImpl     buildAdjustmentContext     GoalServiceImpl
  (SIG-4 判定)            (Agent 上下文清单)        (创建容量预检)

PlanAdjustmentServiceImpl.triggerAdjustment
  ① pending diff 存在 → PLAN_ADJUST_CONFLICT（原有）
  ② 12h 内已 confirmed → PLAN_ADJUST_COOLDOWN（新增）

SimilarQuestionServiceImpl：阈值 0.8 / 重试 2 轮（对齐规则 #3）
QuestionCheckAgent：question_check_v2.txt（判据改为「题目是否换了皮」）
```

---

## 1. D1：未覆盖知识点的唯一口径

### 1.1 现状问题

三处各自实现「未覆盖」，且没有一处扣除暂缓：

| 位置 | 现状 |
|------|------|
| `SignalDetectorImpl.detectSIG4` | 年级过滤 + `masteryScore==null \\|\\| ==0` |
| `PlanAdjustmentServiceImpl.buildAdjustmentContext` | 同上，再 `limit(30)` |
| `GoalServiceImpl.createGoal` | 无（本次新增） |

### 1.2 新工具

`com.jiang.common.util.KpCoverage`（纯静态，无 Spring 依赖，易单测）：

```java
boolean isUncovered(Map row)                     // masteryScore null || 0
List<Map<String,Object>> uncoveredRows(List<Map<String,Object>> rows,
                                       Integer studentGrade,
                                       Set<String> pausedKpIds)   // 年级 + 未覆盖 + 非暂缓
long countUncovered(...)                          // 同上，计数
double capacity(LocalDate today, LocalDate endDate, double capDay, double eff)
                                                  // max(0, days) * capDay * eff
```

行字段键沿用 `selectActiveKpsWithMastery` 的别名：`kpId` / `gradeRange` / `masteryScore`。

`com.jiang.common.util.PausedKpIds`（暂缓 JSON 解析，替换 `HomeworkServiceImpl` 私有方法）：

```java
List<String> parse(String json)     // null/空/解析失败 → List.of()，失败 log.warn
Set<String>  parseSet(String json)
```

### 1.3 改动点

- `SignalDetectorImpl.detectSIG4`：`paused = PausedKpIds.parseSet(goal.getPausedKpIds())`，
  `KpCoverage.countUncovered(rows, studentGrade, paused)`。
  signal description 追加「（已扣除暂缓 N 个）」，便于线上排障。
- `PlanAdjustmentServiceImpl.buildAdjustmentContext`：`KpCoverage.uncoveredRows(...)` 后
  `limit(UNCOVERED_KP_LIMIT=30)`——顺序不能反（先截断会把暂缓的 30 个全取走）。
- `HomeworkServiceImpl`：删私有 `parsePausedKpIds`，改调 `PausedKpIds.parse`（行为不变）。

### 1.4 不变量

- 扣除暂缓**只影响「哪些点算作未覆盖」**，不影响容量公式 `D_remain × capDay × eff`。
- 暂缓集合为空 / 解析失败 → 行为与改动前完全一致（空集合）。
- 暂缓导致的 `kUncovered` 下降是**真实的**：这些点本轮不出题（`HomeworkServiceImpl` 已排除），
  不能既不出题又计入压力。
- **实测边界（goal 7）**：该目标的 20 个暂缓点全部 `grade_range=10`，不在高三口径内，
  因此扣除前后都是 174；扣除逻辑对这类「跨年级暂缓」不产生数值变化（也不该产生）。
  跨年级暂缓本身来自旧版本未按年级过滤的调整，属于历史数据，清理另立 change。

---

## 2. D2：12h 去重覆盖全部入口

### 2.1 现状

`plan_adjustment_log.action='confirmed' + action_at >= since` 的计数方法
`PlanAdjustmentLogMapper.countConfirmedSince` 已存在，只有 `ExamSignalAdjustServiceImpl` 调用。

### 2.2 改动

- `SignalConfig` 增加顶层字段 `dedupHours`（`plan.adjustment.dedup-hours`，默认 12，`@Min(1)`）。
- `PlanAdjustmentServiceImpl.triggerAdjustment`：在 pending 检查之后、信号检测之前：
  ```java
  int confirmed = logMapper.countConfirmedSince(goalId, tenantId, now.minusHours(dedupHours));
  if (confirmed > 0) throw new BusinessException(ErrorCodes.PLAN_ADJUST_COOLDOWN);
  ```
- 新增错误码 `PLAN_ADJUST_COOLDOWN(1192, "近期已调整过计划，请稍后再试")`。
- `AutoAdjustmentTask`：捕获 `BusinessException`，`PLAN_ADJUST_CONFLICT` /
  `PLAN_ADJUST_COOLDOWN` → `skipped++`（不再记 `failed`），日志 info。
- `ExamSignalAdjustServiceImpl`：保留自身 12h 预检（避免无谓的信号检测），
  catch 分支把 `PLAN_ADJUST_COOLDOWN` 也按「跳过」处理。

### 2.3 为什么放在 `triggerAdjustment` 而不是任务里

三个入口（手动 / 01:30 / 考试结算）都会经过 `triggerAdjustment`，放这里只维护一处；
任务侧只负责把「业务拒绝」正确归类为 skip，不影响埋点含义。

### 2.4 边界

- 手动入口被冷却挡住时，前端收到 `1149` 明确提示，不是静默成功。
- `saveAdjustmentLog` 写 `action='confirmed'`、`action_at=now()`（已有实现），无需改动。
- 冷却窗口判据用 `action_at`（应用本地时间写入），不混用 DB `CURRENT_TIMESTAMP`。

---

## 3. D3：目标创建容量预检（非阻断）

### 3.1 规则

```
effectiveStart = max(today, request.startDate)
capacity       = max(0, days(effectiveStart → endDate)) × capDay × efficiency
kUncovered     = KpCoverage.countUncovered(rows, studentGrade, 空集合)   // 创建时尚无暂缓
if (capacity < kUncovered) → capacityWarning（不抛异常）
```

### 3.2 落地

- `GoalVO` 增加 `capacityWarning`（String，可空）。只在 `createGoal` 返回时赋值，
  查询/更新接口为 null（不改变现有语义）。
- 文案：「按当前进度，目标周期内最多覆盖 14.4 个知识点，但高三年级还有 174 个未覆盖知识点；
  建议延长周期或缩小学习范围」。
- 同时 `log.warn` 记录 goalId / grade / kUncovered / capacity，便于线上统计「创建即注定失败的目标」占比。

### 3.3 为什么不做硬拒绝

创建目标是学生主流程（规则 #1），硬拒绝会阻断「短周期冲刺」这类合理诉求；
且 SIG-4 已能在运行期兜底。预检的价值是**把不可达信息提前暴露**并留痕。

---

## 4. D4：举一反三质量门对齐产品规则 #3

| 常量 | 改前 | 改后 | 依据 |
|------|------|------|------|
| `SimilarityDetector.SIMILARITY_THRESHOLD` | 0.6 | **0.8** | 规则 #3、`docs/standards/testing.md` 均写 0.8 |
| `SimilarQuestionServiceImpl.MAX_ATTEMPTS` | 5 | **3**（首轮 + 最多 2 次重试） | 规则 #3「最多 2 次」 |

说明：

- 0.625 这类「同知识点同类题」在 0.8 阈值下放行；「只改数字」的换皮题相似度通常在 0.95 以上，
  仍会被拦（不会引入换皮题）。
- 跨图去重用独立常量 `ExamParseServiceImpl.CROSS_PAGE_DUPLICATE_THRESHOLD = 0.9`，不受影响。
- `MAX_ATTEMPTS` 与 `SimilarityDetector` 无耦合；降级日志文案里的「重试 N 次」自动跟随常量。
- 保留 `similarityChecked=1` 语义：只有通过两道门（相似度 + 语义检查）的题才标记。

---

## 5. D5：`question_check_v2.txt`

**v1 的问题**：第 19–22 行的判据是「核心解法步骤高度一致 → 实质相同」，
与「举一反三＝同知识点同类题」的产品定义冲突；对简单知识点（如直接求根）必然判「实质相同」。

**v2 的判据**（改判「是否换了皮」）：

1. `passed=false` **仅当**新题与原题的**情境、数据、设问三者几乎一致**（换数字/换字母/换措辞不算新题）；
2. 同知识点、同解法、不同情境/数据 → `passed=true`；
3. 给出正例/反例各 2 个；
4. 保留原有 1–4 项（完整性、难度枚举、知识点一致、答案非空），输出 JSON 结构不变（`passed` / `reason`）。

Prompt 版本化（规则 #6）：新增 `question_check_v2.txt`，**保留 v1**，`QuestionCheckAgent` 指向 v2；
`openspec/specs/agent-contracts.md` 的 Prompt 表同步为 v2。

---

## 6. 测试策略

| 用例 | 类型 | 断言 |
|------|------|------|
| SIG-4 扣除暂缓 | 单测 `SignalDetectorTest` | 10 个未覆盖 − 暂缓 8 = 2，容量 7.2 充足 → 不命中 |
| SIG-4 暂缓为空 | 单测 `SignalDetectorTest` | 与改动前一致（10 > 7.2 → 命中） |
| 调整上下文不含暂缓 | 单测 `PlanAdjustmentServiceTest` | `uncoveredKps` 不含 `pausedKpIds` 中的 kp |
| 12h 冷却 | 单测 `PlanAdjustmentServiceTest` | `countConfirmedSince>0` → 抛 `PLAN_ADJUST_COOLDOWN`，不调 Agent |
| 冷却后自动任务跳过 | 单测 `AutoAdjustmentTaskTest`（新增） | 计数 `skipped`，不调 `applyDiff` |
| 容量预检 | 单测 `GoalServiceTest` | 174 > 14.4 → `capacityWarning` 非空；容量充足 → null |
| 阈值/重试常量 | 单测 `SimilarityDetectorTest` / `SimilarQuestionServiceTest` | 0.8 / 2，且 0.625 不触发 |
| Prompt v2 加载 | 单测 `QuestionCheckAgentTest` | 语义检查走 v2（断言 mock 输入不变即可，加载失败会抛 `AGENT_CALL_FAILED`） |

## 7. 文档更新

| 文件 | 改动 |
|------|------|
| `docs/product/index.md` 规则 #5 | SIG-4 定义补「未覆盖量已扣除 `pausedKpIds`」；去重补「手动 / 01:30 同样适用」；边界补「创建目标时容量不足给出提示（不阻断）」 |
| `docs/product/index.md` 规则 #3 | 新题重复校验段补「触发重试的相似度阈值 0.8，最多 2 轮」，与代码一致（原已写，无需改数值） |
| `docs/architecture/implicit-contracts.md` | 新增 #28（未覆盖口径统一扣暂缓）、#29（质量门常量以规则 #3 为准，不得各自定义）；维护规则「从 #27 起」→「从 #30 起」 |
| `openspec/specs/agent-contracts.md` | 校验 Agent Prompt 版本 v1 → v2 |
| `docs/standards/testing.md` | 与规则 #3 一致，已写 0.8；补「最多 2 轮」如缺 |

## 8. 与隐性约定的一致性检查

- #12（降级日志脱敏截断）：新增日志仅记录 grade/kp 数/目标 ID，不含 API Key / studentId 原始值 → 一致
- #16（无 StudentContext 的异步线程显式传参）：`AutoAdjustmentTask` 现有实现不变 → 一致
- #23（年级中文名进 Prompt）：本 change 不动 Prompt 年级展示 → 一致
- #26（试卷负 ID）：本 change 不涉及 → 一致
