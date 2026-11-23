# Verify：计划调整 diff 按学生隔离

> **Change**：`2026-11-19_plan_adjustment_isolation`　**验证**：2026-11-19（本地定向 + 全量单测；真机双账号与 R1-b 复评见「未验证」）

## 缺陷复盘（现象 → 根因 → 修复）

| 现象 | 根因 | 修复 |
|---|---|---|
| 学生 A 拿到 B 的 `diffId` 可读 B 的调整方案（信号/日期/日题量/暂缓知识点） | `plan_adjustment_diff` 只有 `goal_id`，`PlanAdjustmentServiceImpl#getDiffDetail` 只按 `selectById` + `tenant_id` 过滤 | `requireOwnedDiff` → `PlanAdjustmentDiffMapper#selectByIdWithStudent`（JOIN `learning_goal` 带 `lg.student_id`） |
| A 可把 B 的 diff 置为 `rejected`，或 confirm 后由 `applyDiff` 改写 B 的目标策略与未来 `daily_plan` | `confirmDiff` / `rejectDiff` 同样只按 `tenant_id`；归属校验缺失 | 三入口统一 `requireOwnedDiff`；`confirmDiff` 在 `applyDiff` 之前校验 |
| 调整历史返回全租户记录 | `plan_adjustment_log` 只有 `goal_id`，`getAdjustmentHistory` 只按 `tenant_id` 分页 | 先 `selectGoalIdsByStudent` → 空则空分页；非空 `in(goalId, goalIds)` + MP Page |
| A 可对 B 的 `goalId` 触发调整，生成 pending diff 并阻塞 B 7 天 | `triggerAdjustment(Long)` 只透传客户端 `goalId` | `requireOwnedGoal` → `LearningGoalMapper#selectOwnedGoalId`，统一 1140 |

## 需求 → 实现

| 需求（任务契约） | 实现 |
|---|---|
| 读/写入口都按当前登录学生归属过滤 | `PlanAdjustmentServiceImpl#requireOwnedDiff` / `#requireOwnedGoal`；`selectByIdWithStudent`（JOIN + 两侧租户 + `lg.student_id`）；`selectOwnedGoalId` |
| 不区分存在性、不泄漏他人数据 | 统一 `PLAN_ADJUST_NOT_FOUND(1140)`；WARN 只记 `diffId`/`studentId`，不打印调整内容 |
| 历史不退化全租户 | `getAdjustmentHistory` 空 goalIds → `PageResult.of(0, page, size, List.of())`；非空 `in` + MP Page（无 `last()`） |
| 系统路径不回归 | `PlanDiffServiceImpl#applyDiff` 行为零改动（仅 Javadoc）；`triggerAdjustment(goalId, studentId, tenantId)` 保持系统重载；`AutoAdjustmentTask` / `ExamSignalAdjustServiceImpl` 未改 |
| 回归护栏 | `PlanAdjustmentIsolationSqlTest`（4 例反射）、`PlanAdjustmentServiceTest`（+14 例）、`PlanDiffServiceTest`（+2 例） |

## 复核记录

| 环节 | 结论 |
|---|---|
| R1 复核（t4） | verdict=pass（三入口归属校验、写路径顺序、历史过滤、系统路径均核对通过） |
| t9 收尾 | 删除 `confirmDiff` 无用局部变量；`PlanAdjustmentIsolationSqlTest` 第 4 例改为「弱化形态反证」 |
| R1-b 复评（t14） | 落笔时进行中：t9 改了 `PlanAdjustmentServiceImpl` / `PlanAdjustmentIsolationSqlTest`，按流程需按当前字节复评，**以 t14 最终结论为准** |

## 测试

```
定向（T1/T9）：mvn -o -Dtest=PlanAdjustmentServiceTest,PlanDiffServiceTest,PlanAdjustmentIsolationSqlTest test
  → 43 例全绿（T1 output；t9 收尾后仍 43 例全绿）
定向（T1）：mvn -o -q compile → exit 0
全量：实施侧最后一轮 mvn -o test → Tests run: 1023, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
  （T1 当时为 978/0/0/5；t2/t3 补测后 999、1015，最终 1023）
V1：verifier 将执行 mvn -o clean test 复跑，以 V1 结果为准
```

| 用例 | 覆盖 |
|---|---|
| `PlanAdjustmentIsolationSqlTest`（4） | `selectByIdWithStudent` 必须 JOIN + 两侧租户 + `lg.student_id`；`selectOwnedGoalId` 带学生条件；`selectGoalIdsByStudent` 不过滤 status；弱化形态（删任一条件）必须判红 |
| `PlanAdjustmentServiceTest`（+14 例；他人 goalId / 他人 diffId / 空 goalIds / 无学生上下文各有用例） | 他人 goalId/diffId → 1140 且不写库；自己的 diff 正常读/确认/拒绝；状态错误在归属之后；历史只查当前学生 goal；空 goalIds/无上下文 → 空分页 |
| `PlanDiffServiceTest`（+2） | 系统路径无学生上下文仍可 `applyDiff`（租户取 diff 自身）；`applyDiff` 不做学生归属校验 |
| `AutoAdjustmentTaskTest`（6） | 01:30 自动调整链路不回归 |

## 未验证

| 项 | 说明 |
|---|---|
| 真机双账号复核 | 需造两个学生账号（A/B 同租户），确认 A 刷 B 的 diffId/goalId 一律 1140、历史只含自己记录 |
| R1-b 复评（t14） | 落笔时进行中，结论以 t14 为准 |
| 多目标同科目历史 | 历史按 goalIds 全量查，逻辑上覆盖；未单独构造多目标数据验证分页合并口径 |

## 残留风险清单

1. **旧 admin token 部署后 403 需重登**：升级后旧会话（无 `role` claim）访问管理端一律 403，admin-web 已改为 403 清 token 并回登录页（`admin-web/src/api/request.js`），需重新登录。
2. **首次部署仍需 bootstrap 初始化**：`ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` 未设置时不创建管理员（仅 WARN）；`admin_user` 为空时管理端无法登录；**上线前必须修改初始密码**。
3. **迁移未在真实 MySQL 实跑**：当前工程未接入 Flyway，迁移脚本需人工按版本顺序执行；V16/V17 仅在 H2 schema 与单测中验证，未对真实 MySQL 5.7 执行。
4. **01:30 自动调整 + 考试结算组合路径无装配级测试**：两者分别有单测（`AutoAdjustmentTaskTest` / `ExamSignalAdjustService` 相关用例），但「同一目标同一天先结算再 01:30 调整」的组合未做容器级联调。
5. **`src/main/resources/application-dev.yml` 明文 DB 口令待人工审批**：属 AGENTS.md 受保护文件，本轮未改；需 owner 审批后改环境变量占位并**轮换口令**（历史 commit 已含明文）。
6. **归档历史文档含旧口令引用**：`openspec/changes/archive/2026-11-11_exam_paper_upload_llm/verify.md` 含旧口令命令示例；归档只读，按约定不修改。

## 已知取舍

- 不改表结构（零 DDL）：归属继续经 `learning_goal` 表达，`plan_adjustment_*` 不加 `student_id` 列；
- 学生入口新增一次主键命中的轻量归属查询，可接受；
- `applyDiff` 不做学生归属校验是**有意保留的系统语义**（定时任务/考试结算无 HTTP 上下文），由调用方 `requireOwnedDiff` 负责；
- 单测里为 `LambdaQueryWrapper` 初始化了 `PlanAdjustmentLog` 的 `TableInfo`（仅测试侧，无容器环境所需）。

## 回滚

本次为纯代码 + 测试改动：无 DDL、无数据回填、无配置变更。回滚 = 还原
`PlanAdjustmentServiceImpl` / `PlanAdjustmentDiffMapper` / `LearningGoalMapper` / `PlanDiffServiceImpl`（仅 Javadoc）与对应测试；
回滚会重新打开跨学生读/写通道，**不建议**。

## 归档（2026-11-19）

- 按 `openspec/changes/README.md` 约定归档到 `openspec/changes/archive/`（目录名不变）。
- 归档时状态：定向 43 例、全量 `mvn -o clean test` **1023 通过 / 0 跳过**；R1-b 复评 pass（最终字节）。
- 未验证项：01:30 自动调整与考试结算的**组合路径**无装配级测试（当前靠代码未改动 + 静态核对 + 分层单测保证），已记入 `2026-11-19_prod_hardening` 残留风险清单。