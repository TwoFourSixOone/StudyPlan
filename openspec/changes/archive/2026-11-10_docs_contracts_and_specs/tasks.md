# Tasks：隐性约定校准与系统工作原理沉淀

> **Change 名称**：`2026-11-10_docs_contracts_and_specs`
> **版本**：v1
> **状态**：Task 1-7 已完成，待 verify

---

## 任务清单

### Task 1：约定编号说明

- [x] 在 `implicit-contracts.md` §2 顶部加入编号说明：#5–#8 空号不复用、新增从 #21 起
- [x] 明确「隐性约定 #7」为悬空引用，指向产品规则 #7 与约定 #15

**涉及文件**：
- `docs/architecture/implicit-contracts.md`

**验证**：说明段位于 §2 标题之后、#1 之前，不改变任何既有编号

---

### Task 2：修订 #18

- [x] 保留编号与位置，把「禁止使用 `api/`」改为按文件类型分情况：`.ts` 放 `src/api/`、`.js` 放 `utils/`、请求工具固定 `utils/`
- [x] 补「修订时间 / 修订原因」字段，说明原前提（uni-app 早期 + 纯 JS）已失效

**涉及文件**：
- `docs/architecture/implicit-contracts.md`

**验证**：与 `student-app/src/api/*.ts` 的实际结构一致

---

### Task 3：新增 #21 枚举双形态

- [x] 新增约定 #21：DB/MyBatis 用枚举名、API 用 `@JsonValue` code，两处必须成对修改
- [x] §3 维护规则补 2 条：编号规则、归档回写规则

**涉及文件**：
- `docs/architecture/implicit-contracts.md`

**验证**：#21 位于 #20 之后、§3 之前；§3 编号连续

---

### Task 4：重写系统总览

- [x] 用真实内容替换脚手架模板（模块列表原为"（待补充）"）
- [x] 覆盖：四个逻辑单元、请求链路、鉴权与租户、26 张表分组、4 个状态机、外部依赖、已知未落地

**涉及文件**：
- `openspec/specs/system-overview.md`

**验证**：表数量与 `src/main/resources/db/*.sql` 的 `CREATE TABLE` 一致（26）

---

### Task 5：沉淀学习闭环

- [x] 七步闭环表（接口 + 落库表 + 对应规则）
- [x] 每日作业生成的配额算法与极端模式
- [x] 提交/批改流程（选择题精确比较、非选择题 AI 批改及字符串比较降级、并发冲突检测）
- [x] 试卷解析链路与「当前不可用」的如实标注
- [x] 计划调整的信号、上下文、diff 确认与 `pending_generation` 规则

**涉及文件**：
- `openspec/specs/learning-loop.md`

**验证**：每条断言可对应到 `HomeworkServiceImpl` / `PlanAdjustmentServiceImpl` / `PlanDiffServiceImpl` 的方法

---

### Task 6：沉淀 Agent 契约

- [x] 四类 Agent 落地现状（明确标注规划 Agent 未实现）
- [x] 共同契约（只调 LLM、结构化校验、重试 2 次、超时、只引用启用知识点）
- [x] 每类 Agent 的降级动作与「是否写降级日志」现状（标注归因 Agent 未写日志）
- [x] 降级日志字段与脱敏规则（约定 #12）
- [x] 修改 Agent 时的 5 项检查清单

**涉及文件**：
- `openspec/specs/agent-contracts.md`

**验证**：Agent 清单与 `src/main/java/com/jiang/agent/` 实际文件一致（无 `PlanningAgent`）

---

### Task 7：沉淀掌握度与学习记录

- [x] 公式与全部参数取值（含 `α` 为常量 0.3 的现状）
- [x] 三个触发点与 `W` 权重
- [x] 追溯链字段与关联方式
- [x] 学习记录三类 UNION 查询及状态过滤
- [x] 错题艾宾浩斯周期、暂停/重新入队、超期处理
- [x] 「只读不删」的落地方式
- [x] 与产品规则的 3 项已知差异

**涉及文件**：
- `openspec/specs/mastery-and-records.md`

**验证**：参数值与 `MasteryFormulaUtil` 常量逐个比对一致

---

## 验收标准

1. `docs/architecture/implicit-contracts.md` 修订 3 处，既有编号未变
2. `openspec/specs/` 下 4 份文件均无"待补充"占位
3. 每份 spec 顶部有「维护触发 / 冲突处理」声明
4. 未修改 `docs/product/index.md`、`docs/architecture/index.md`、任何代码/配置/SQL
5. spec 中标注的「未落地」项与代码现状一致
