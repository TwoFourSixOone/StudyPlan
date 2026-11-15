# Tasks：SIG-4 口径 / 12h 去重 / 举一反三质量门对齐

> **Change 名称**：`2026-11-12_adjustment_signal_fix`
> **实施顺序**：T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10

---

## T1 新增口径工具（`KpCoverage` / `PausedKpIds`）

- [x] `src/main/java/com/jiang/common/util/KpCoverage.java`
    - `isUncovered(Map)`：`masteryScore == null || == 0`
    - `uncoveredRows(List<Map>, Integer studentGrade, Set<String> pausedKpIds)`：年级过滤（`GradeMatcher.matchesRange`，null 年级 = 不过滤）→ 未学 → 非暂缓
    - `countUncovered(...)`：`uncoveredRows(...).size()`
    - `capacity(LocalDate from, LocalDate endDate, double capDay, double eff)`：`max(0, DAYS.between) × capDay × eff`
- [x] `src/main/java/com/jiang/common/util/PausedKpIds.java`
    - `parse(String json)` / `parseSet(String json)`：null / 空白 / 解析异常 → 空集合，异常 `log.warn`（不抛）
- **验收**：两个类无 Spring 依赖，可被单测直接调用 ✅

## T2 SIG-4 扣除暂缓（检测器）

- [x] `SignalDetectorImpl.detectSIG4` 改用 `KpCoverage.countUncovered(rows, studentGrade, PausedKpIds.parseSet(goal.getPausedKpIds()))`
- [x] signal description 追加「（已扣除暂缓 N 个）」（N>0 时）
- [x] `HomeworkServiceImpl` 删除私有 `parsePausedKpIds`，改调 `PausedKpIds.parse`（行为不变）
- **验收**：`SignalDetectorTest` 新增两条用例（暂缓 8 个 → 不命中；扣除后仍不足 → 命中且描述含扣除数）通过 ✅

## T3 调整上下文扣除暂缓

- [x] `PlanAdjustmentServiceImpl.buildAdjustmentContext`：先 `KpCoverage.uncoveredRows(...)` 再 `limit(UNCOVERED_KP_LIMIT)`
- **验收**：`PlanAdjustmentServiceTest` 新增「pausedKpIds 中的 kp 不出现在 uncoveredKps」 ✅

## T4 12h 去重（全部入口）

- [x] `SignalConfig` 增加 `dedupHours`（默认 12，`@Min(1)`）
- [x] `application.yml` 的 `plan.adjustment` 下增加 `dedup-hours: 12`（含注释）
- [x] `ErrorCodes` 增加 `PLAN_ADJUST_COOLDOWN(1192, "近期已调整过计划，请稍后再试")`
- [x] `PlanAdjustmentServiceImpl.triggerAdjustment`：pending 检查后增加 `countConfirmedSince(goalId, tenantId, now.minusHours(dedupHours)) > 0 → 抛 PLAN_ADJUST_COOLDOWN`
- [x] `AutoAdjustmentTask`：`BusinessException` 且 code ∈ {PLAN_ADJUST_CONFLICT, PLAN_ADJUST_COOLDOWN} → `skipped++` + info 日志
- [x] `ExamSignalAdjustServiceImpl`：catch 分支同样把 `PLAN_ADJUST_COOLDOWN` 归为跳过
- **验收**：`PlanAdjustmentServiceTest` 冷却用例 + `AutoAdjustmentTaskTest` 两条跳过用例通过 ✅

## T5 目标创建容量预检

- [x] `GoalServiceImpl` 注入 `StudentKnowledgeStateMapper` + `SignalConfig`
- [x] `createGoal` 计算 `capacityWarning`（年级从已加载的 `Student.grade` 归一化；创建时暂缓视为空）
- [x] `GoalVO` 增加 `capacityWarning` 字段，仅创建接口赋值
- [x] `log.warn` 记录 goalId / subjectId / grade / kUncovered / capacity
- **验收**：`GoalServiceTest` 新增「容量不足 → warning 非空」「容量充足 → null」；现有 28 条创建/查询用例不受影响 ✅

## T6 举一反三阈值与重试

- [x] `SimilarityDetector.SIMILARITY_THRESHOLD` 0.6 → 0.8（Javadoc 注明依据规则 #3）
- [x] `SimilarQuestionServiceImpl.MAX_ATTEMPTS` 5 → 3（首轮 + 最多 2 次重试；Javadoc「≤5 次」改「≤3 轮」）
- [x] 降级日志文案改为 `MAX_ATTEMPTS - 1`（即「2 次重试」）
- **验收**：`SimilarityDetectorTest` 阈值用例改 0.8 并新增常量回归锁；`SimilarQuestionServiceTest` 第 2 轮用例相似度 0.8 → 0.9（`>` 语义）、降级用例断言生成被调用 3 次 ✅

## T7 `question_check_v2.txt`

- [x] 新增 `src/main/resources/prompts/versions/question_check_v2.txt`（判据改为「情境/数据/设问是否几乎一致」+ 正反例）
- [x] `QuestionCheckAgent.loadPromptTemplate` 指向 v2；保留 v1 文件
- **验收**：`QuestionCheckAgentTest` 全绿（加载失败会抛 `AGENT_CALL_FAILED`） ✅

## T8 单测补齐与回归

- [x] 更新 `AutoAdjustmentTaskTest`（保留原有 4 条，新增 3 条去重跳过用例）
- [x] 更新：`SignalDetectorTest`、`PlanAdjustmentServiceTest`、`GoalServiceTest`、`SimilarityDetectorTest`、`SimilarQuestionServiceTest`
- [x] 新增 `KpCoverageTest`（9 条）
- [x] `mvn test` 全绿：**821 tests / 0 failure / 5 skipped**（基线 801）
- **验收**：0 failure / 0 error；新增用例 20 条 ✅

## T9 文档回写

- [x] `docs/product/index.md`：规则 #5（SIG-4 口径、12h 去重覆盖三入口 + `1192`、创建容量提示）
- [x] `docs/architecture/implicit-contracts.md`：新增 #28 / #29，维护规则编号起点改 #30
- [x] `openspec/specs/agent-contracts.md`：新题校验 Prompt → `question_check_v2.txt`；举一反三降级行为 5 轮 → 3 轮
- [x] `docs/standards/testing.md`：规则 #3 补「首轮 + 最多 2 次重试，共 3 轮」
- **验收**：文档与代码数值/口径逐项对齐，无残留「阈值 0.6 / 举一反三 5 轮」的现行描述 ✅

## T10 真实数据验证（verify 阶段）

- [x] goal 7 / student 9：年级内未覆盖 **174**，其中不含 20 个暂缓点（均为 `grade_range=10`），扣除前后均为 174；容量 9.6 → SIG-4 仍命中（口径生效且未误关）
- [x] 12h 去重查询（Java 本地时间参数）：goal 7 最近一次 `confirmed` 在 36h 前 → 窗口内 0 条，不误挡
- [x] 记录到 `verify.md` ✅
