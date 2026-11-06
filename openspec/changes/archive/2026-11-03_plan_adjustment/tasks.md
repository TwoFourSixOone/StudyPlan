# Tasks：计划调整系统（v3 修复版）

> **Change 名称**：`2026-11-03_plan_adjustment`  
> **版本**：v3（修复 BLOCK-B + IMP-28）

---

## DDL 版本声明

- 本 Change 使用 **V7__plan_adjustment.sql**
- V7 依赖 V6（exam_paper_analysis）已 apply
- 后续 Change 依赖：V8（learning_record_index）→ V9（admin_list_index）
- **合并顺序**：V6 → V7 → V8 → V9

---

## 任务清单

### T-01：数据库 DDL 脚本与实体类创建 ✅

**任务描述**：
1. 创建 `V7__plan_adjustment.sql`（MySQL 5.7 兼容，不用 IF NOT EXISTS）
2. 实体类：`PlanAdjustmentDiff`、`PlanAdjustmentLog`
3. Mapper：`PlanAdjustmentDiffMapper`（含 `batchExpire`、`selectByExpireAtBefore`）、`PlanAdjustmentLogMapper`（含 `batchInsert`）
4. diff 表去掉唯一索引（BLOCK-B 修复：id 主键已唯一，含主键的唯一索引无意义）；log 表保留 `uk_goal_diff (goal_id, diff_id, tenant_id)`

**涉及文件**：
- `src/main/resources/db/migration/V7__plan_adjustment.sql`（新增）
- `src/main/java/com/jiang/model/entity/PlanAdjustmentDiff.java`（新增）
- `src/main/java/com/jiang/model/entity/PlanAdjustmentLog.java`（新增）
- `src/main/java/com/jiang/mapper/PlanAdjustmentDiffMapper.java`（新增）
- `src/main/java/com/jiang/mapper/PlanAdjustmentLogMapper.java`（新增）

---

### T-02：DTO/VO 类与错误码定义 ✅

**任务描述**：
1. DTO：`TriggerSignalDTO`、`PlanDiffDTO`（合并 IMP-22）、`PlanDateModification`、`AdjustRequestDTO`（IMP-23 新增）、`AdjustmentAgentInput`、`AdjustmentAgentOutput`（内部使用）
2. VO：`AdjustTriggerVO`、`PlanDiffDetailVO`、`AdjustmentHistoryVO`
3. 错误码（**1140-1149 编号段**）：
   - `PLAN_ADJUST_NOT_FOUND(1140)`
   - `PLAN_DIFF_NOT_FOUND_OR_INVALID(1141)`
   - `PLAN_DIFF_ALREADY_CONFIRMED(1142)`
   - `PLAN_DIFF_EXPIRED(1143)`
   - `SIGNAL_DETECT_FAILED(1144)`
   - `ADJUST_AGENT_FAILED(1145)`
   - `PLAN_DIFF_ALREADY_REJECTED(1146)`
   - `NO_SIGNAL_DETECTED(1147)`
   - `PLAN_ADJUST_CONFLICT(1148)`
   - `PLAN_DATE_IN_PAST(1149)`

**涉及文件**：
- `src/main/java/com/jiang/dto/` 下多个 DTO（新增）
- `src/main/java/com/jiang/vo/` 下多个 VO（新增）
- `src/main/java/com/jiang/common/exception/ErrorCodes.java`（修改）

---

### T-03：SIG-1~4 配置类与触发信号检测 ✅

**任务描述**：
1. 创建 `SignalConfig` 配置类（`@Component` + `@Validated` + `@ConfigurationProperties(prefix="plan.adjustment")`）（IMP-28 + NOTE-3 修复：含 @DecimalMin/@DecimalMax 校验，配置值非法时启动失败）
2. 创建 `SignalDetector` 接口 + `SignalDetectorImpl`
3. SIG-1：窗口 7 天，连续 3 次正确率 < 60%（从配置读取）
4. SIG-2：掌握度单次考试下降 > 0.15（从配置读取）
5. SIG-3：连续 2 天未完成（从配置读取）
6. SIG-4：D_remain × cap_day × eff < |K_uncovered|（BLOCK-14 修复：配置化 + 语义修正）
7. application.yml 添加 `plan.adjustment.sig1~sig4` 配置项

**涉及文件**：
- `src/main/java/com/jiang/config/SignalConfig.java`（新增）
- `src/main/java/com/jiang/service/SignalDetector.java`（新增）
- `src/main/java/com/jiang/service/impl/SignalDetectorImpl.java`（新增）
- `src/main/resources/application.yml`（修改）

---

### T-04：调整 Agent 实现 ✅

**任务描述**：
1. 创建 `AdjustmentAgent` 接口 + `AdjustmentAgentImpl`
2. 创建 `AdjustmentException` 异常类
3. Agent 输出直接映射为 `PlanDiffDTO`（IMP-22 修复：合并 DTO）
4. 重试 2 次（共 3 次），失败后降级

**涉及文件**：
- `src/main/java/com/jiang/agent/AdjustmentAgent.java`（新增）
- `src/main/java/com/jiang/agent/impl/AdjustmentAgentImpl.java`（新增）
- `src/main/java/com/jiang/common/exception/AdjustmentException.java`（新增）

---

### T-05：PlanDiffService + 定时任务（修复 BLOCK-13）✅

**任务描述**：
1. 创建 `PlanDiffService` 接口 + `PlanDiffServiceImpl`
2. `applyDiff()`：只更新 `plan_date >= today` 的计划（IMP-21 修复）
3. `cleanExpiredDiffs()`：批量 UPDATE + 批量 INSERT 日志（BLOCK-13 修复）
4. 创建 `PlanAdjustmentScheduledTask`：`@Scheduled(cron="0 0 2 * * ?")` 每天凌晨 2 点执行（IMP-24 修复）

**涉及文件**：
- `src/main/java/com/jiang/service/PlanDiffService.java`（新增）
- `src/main/java/com/jiang/service/impl/PlanDiffServiceImpl.java`（新增）
- `src/main/java/com/jiang/task/PlanAdjustmentScheduledTask.java`（新增）

---

### T-06：PlanAdjustmentService 核心实现 ✅

**任务描述**：
1. 创建 `PlanAdjustmentService` 接口 + `PlanAdjustmentServiceImpl`
2. `triggerAdjustment()`：应用层防重检查（BLOCK-B 修复：查是否有 pending diff，有则抛 PLAN_ADJUST_CONFLICT）→ 检测信号 → 调用 Agent → 保存 diff（IMP-20：Agent 成功但 saveDiff 失败时记录 WARN 日志）
3. `confirmDiff()` / `rejectDiff()` / `getDiffDetail()` / `getAdjustmentHistory()`

**涉及文件**：
- `src/main/java/com/jiang/service/PlanAdjustmentService.java`（新增）
- `src/main/java/com/jiang/service/impl/PlanAdjustmentServiceImpl.java`（新增）

---

### T-07：PlanAdjustmentController 实现（修复 IMP-23）✅

**任务描述**：
1. `POST /api/student/plan/adjust`：使用 `@RequestBody AdjustRequestDTO`（IMP-23 修复）
2. `GET /api/student/plan/diff/{diffId}`
3. `POST /api/student/plan/diff/{diffId}/confirm`
4. `POST /api/student/plan/diff/{diffId}/reject`
5. `GET /api/student/plan/adjustments`

**涉及文件**：
- `src/main/java/com/jiang/controller/PlanAdjustmentController.java`（新增）

---

### T-08：错题超 30 天标记 ✅（已在 T-04 中完成）

**任务描述**：
1. `ErrorQuestionMapper.selectCriticalErrors()`：查询 stage=5 且 first_wrong_date < 30 天前的错题
2. `AdjustmentAgentImpl` 将超 30 天错题作为高优先级输入传给 Agent
3. `PlanDiffDTO.criticalErrorQuestionIds` 字段记录标记

**涉及文件**：
- `src/main/java/com/jiang/mapper/ErrorQuestionMapper.java`（修改）
- `src/main/java/com/jiang/agent/impl/AdjustmentAgentImpl.java`（修改）

---

### T-09：单元测试

**任务描述**：
1. `SignalDetectorTest`：
   - `should_detectSIG1_when_accuracyBelow60In7Days`
   - `should_detectSIG4_when_remainingDaysInsufficient`（BLOCK-14 验证）
   - `should_notDetectSIG4_when_kUncoveredUsesNullNotThreshold`（BLOCK-14 语义验证）
2. `PlanDiffServiceTest`：
   - `should_onlyUpdateFutureDates_when_applyDiff`（IMP-21 验证）
   - `should_batchExpire_when_cleanExpiredDiffs`（BLOCK-13 验证）
3. `PlanAdjustmentServiceTest`：
   - `should_returnNoAdjustment_when_noSignalDetected`
   - `should_logWarning_when_saveDiffFailsAfterAgentSuccess`（IMP-20 验证）
   - `should_throwConflict_when_pendingDiffExists`（BLOCK-B 验证）

**涉及文件**：
- `src/test/java/com/jiang/service/SignalDetectorTest.java`（新增）
- `src/test/java/com/jiang/service/PlanDiffServiceTest.java`（新增）
- `src/test/java/com/jiang/service/PlanAdjustmentServiceTest.java`（新增）

---

### T-10：集成测试

**涉及文件**：
- `src/test/java/com/jiang/controller/PlanAdjustmentControllerTest.java`（新增）

---

### T-11：全量编译与测试验证 ✅

**验收标准**：
- [x] `mvn clean compile` 通过
- [x] `mvn test` 全部通过（444 tests, 0 failures, 0 errors, 5 skipped）
- [x] `mvn package -DskipTests` 成功

---

## 预估工时

| 任务 | 预估工时 |
|------|----------|
| T-01 | 1.5h |
| T-02 | 1h |
| T-03 | 3h |
| T-04 | 2h |
| T-05 | 2.5h |
| T-06 | 3h |
| T-07 | 1.5h |
| T-08 | 1h |
| T-09 | 3h |
| T-10 | 2h |
| T-11 | 1h |
| **总计** | **21.5h** |

---

**版本**：v3  
**创建日期**：2026-11-03  
**最后更新**：2026-11-04
