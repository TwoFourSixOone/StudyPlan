# Proposal：隐性约定校准与系统工作原理沉淀

> **Change 名称**：`2026-11-10_docs_contracts_and_specs`
> **创建日期**：2026-11-10
> **版本**：v1
> **状态**：待实施

---

## 1. 需求背景

18 个 change 归档后，知识层出现两处结构性缺口：

**（1）隐性约定失真**——`docs/architecture/implicit-contracts.md` 有 3 个问题：

| 问题 | 具体表现 | 后果 |
|------|---------|------|
| #18 已过时 | 约定称 `src/api/` 下的 JS 文件无法被 mp-weixin 注册，要求放 `utils/`；实际 `student-app/src/api/*.ts` 编译运行完全正常 | 后续开发会无谓绕路，甚至把正常的 `api/` 文件挪走 |
| #5–#8 编号断档 | 文件从 #4 直接跳到 #9，但多份历史 proposal 引用「隐性约定 #7 JWT 登录态」——该条并不存在 | 引用悬空，verify 阶段无法核对 |
| 枚举双形态未记录 | `RecordType` 需要「DB/XML 用大写枚举名、API 用 `@JsonValue` 小写 code」两处成对修改，只改一边就会导致类型显示英文 | 同类问题会重复踩 |

**（2）`openspec/specs/` 是空模板**——只有一个 39 行的脚手架文件，`模块列表` 还写着"（待补充）"。18 次归档的结论没有任何一条沉淀成"系统现在怎么工作"，新人（或 AI）只能靠读 2 万行代码反推。

## 2. 核心目标

1. 校准 3 条隐性约定：修订 #18、说明 #5–#8 空缺、新增 #21 枚举双形态
2. 把系统工作原理沉淀为 4 份 spec：总览 / 学习闭环 / Agent 契约 / 掌握度与记录
3. 建立「归档后回写 spec」的维护规则，避免下一次归档又只留下 change 目录

## 3. 功能范围

### 包含

| 类型 | 文件 | 动作 |
|------|------|------|
| 约定 | `docs/architecture/implicit-contracts.md` | 修订 #18、加编号说明、新增 #21 |
| Spec | `openspec/specs/system-overview.md` | 重写（替换模板） |
| Spec | `openspec/specs/learning-loop.md` | 新增 |
| Spec | `openspec/specs/agent-contracts.md` | 新增 |
| Spec | `openspec/specs/mastery-and-records.md` | 新增 |

### 不包含

- 不改 `docs/product/index.md`（业务规则唯一依据，本 change 无业务变更）
- 不改 `docs/architecture/index.md`（架构总览，内容仍准确）
- 不改任何代码、配置、SQL
- 不为 `2026-11-05_student_business_pages` / `grade_level_filtering` 补完工内容

## 4. 隐性约定检查

| 约定 / 规则 | 是否涉及 | 说明 |
|------|---------|------|
| 本文件自身的修改 | ✅ | `implicit-contracts.md` 属受保护区域，本 change 即为其变更载体（符合 AGENTS.md §5「可在 change 中同步更新」） |
| #2 异常处理规范 | — | 无代码 |
| #4 时间格式 | ✅ | spec 中时间字段统一写 `yyyy-MM-dd` / `yyyy-MM-dd HH:mm:ss` |
| 规则 #2 / #3 / #6 / #8 / #9 | ✅ | spec 内容必须与这些规则一致，不改写规则本身 |

## 5. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|---------|
| spec 写成"设计意图"而非"当前实现"，与代码二次偏离 | 高 | 每条 spec 断言必须能在代码中找到落点，标注类名/方法名 |
| 编号重排破坏已归档 proposal 的引用 | 高 | **不重排**，新增约定从 #21 起，历史编号保持原样 |
| spec 内容与 `docs/architecture/index.md` 重复 | 中 | spec 只写"运行时行为与契约"，架构分层、包结构仍以 architecture/index.md 为准，spec 中引用而非复制 |
| 沉淀后无人维护，再次腐烂 | 中 | 在 spec 顶部写明"归档时必须回写"的触发条件 |

## 6. 前置依赖

- 已归档的 18 个 change（结论是本 spec 的主要素材）
- `2026-11-09_plan_adjustment_context`、`2026-11-09_error_detail_and_record_pages`（已归档，提供降级与记录链路的最终形态）
