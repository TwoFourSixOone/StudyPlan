# Verify Report：隐性约定校准与系统工作原理沉淀

> **Change ID**: `2026-11-10_docs_contracts_and_specs`
> **校验日期**: 2026-11-10
> **校验结论**: ✅ **对齐** — 可以进入归档

---

## 前置检查

| 检查项 | 结果 |
|--------|------|
| change 含 proposal / design / tasks | ✅ |
| 代码已实施（本次为纯文档，无代码变更） | ✅ |
| 未修改 `docs/product/index.md`、`docs/architecture/index.md` | ✅（`git status` 仅显示 `implicit-contracts.md` 被改） |

---

## Proposal 对齐（7 项）

| # | 功能点 | 状态 | 落点 |
|---|--------|------|------|
| 1 | 修订 #18 | ✅ | `implicit-contracts.md` §2 #18（保留编号，改为分情况说明 + 修订记录） |
| 2 | #5–#8 编号说明 | ✅ | `implicit-contracts.md` §2 顶部编号说明 |
| 3 | 新增 #21 枚举双形态 | ✅ | `implicit-contracts.md` §2 #21 |
| 4 | 重写系统总览 | ✅ | `openspec/specs/system-overview.md`（原 39 行模板 → 实际内容） |
| 5 | 新增学习闭环 | ✅ | `openspec/specs/learning-loop.md` |
| 6 | 新增 Agent 契约 | ✅ | `openspec/specs/agent-contracts.md` |
| 7 | 新增掌握度与记录 | ✅ | `openspec/specs/mastery-and-records.md` |

## Design 对齐

| 设计要点 | 状态 | 说明 |
|---------|------|------|
| §1 编号不重排、从 #21 起 | ✅ | #1–#4、#9–#20 原样保留，#21 追加 |
| §1 悬空引用对照 | ✅ | 明确「#7 → 产品规则 #7 + 约定 #15」 |
| §2 #18 保留编号改写 | ✅ | 三档分情况（`.ts` / `.js` / 请求工具）+ 修订原因 |
| §3 #21 内容与选型理由 | ✅ | 落库枚举双形态 + 两次踩坑记录 |
| §4 spec 四文件职责边界 | ✅ | 总览/闭环/Agent/掌握度，互不重复，均不复制架构分层 |
| §4 写作规则 1（每条断言有代码落点） | ✅ | 见下节「事实核对」 |
| §4 写作规则 3（标注未落地） | ✅ | OCR、规划 Agent、管理端托管、多租户均在 spec 中标注 |
| §5 维护触发声明 | ✅ | 4 份 spec 顶部均有「维护触发 / 冲突处理」 |
| §5 归档回写规则 | ✅ | 已写入 `implicit-contracts.md` §3 第 6 条 |
| §6 与既有文档边界 | ✅ | 仅 4 份 spec + 1 份约定文件变动 |

---

## Tasks 对齐（7 个节点）

全部 ✅ 完成，勾选 7 / 未勾选 0。

---

## 事实核对（本次 verify 的重点）

文档类 change 的风险不是"没写"，而是"写错"。逐项核对结果：

| 断言 | 核对方式 | 结果 |
|------|---------|------|
| 「数据表 26 张」 | 统计 `src/main/resources/db/*.sql` 的 `CREATE TABLE` | ✅ 26，无重复 |
| 「无 `PlanningAgent`」 | 遍历 `com/jiang/agent/` | ✅ 确认不存在 |
| 「`α` 为常量 0.3」 | 读 `MasteryFormulaUtil.ALPHA` | ✅ |
| 「难度映射 0.3 / 0.6 / 0.9」 | 读 `MasteryFormulaUtil` 常量 | ✅ |
| 「掌握度下限 0.05、初始 0.50、置信度 0.10 起每次 +0.05」 | 读常量 | ✅ |
| 「`daily_plan` 状态 5 值」 | 读 `PlanStatus` | ✅ pending / pending_generation / in_progress / completed / cancelled |
| 「艾宾浩斯 1/3/7/15/30」 | 读 `EbbinghausStage` | ✅ |
| 「OCR 直接抛 `OcrException`」 | 读 `TencentOcrServiceImpl.recognize()` | ✅ |
| 「归因 Agent 未写降级日志」 | 全库 grep `AgentDegradationLog` | ✅ 仅出题、相似题、调整三处 |
| 「学习记录过滤 `graded` / `completed`」 | 读 `LearningRecordMapper.xml` | ✅ |
| 「无 Flyway 依赖，脚本人工按序执行」 | 读 `pom.xml` | ✅ 无 flyway 依赖 |
| spec 中引用的 13 个类名 | 逐个查找 `*.java` | ✅ 全部存在（`TenantContext` 为核对清单自身笔误，未出现在 spec 中） |
| 残留占位（`待补充` / TODO / TBD） | grep `openspec/specs/` | ✅ 0 处 |

---

## 超出范围的改动

无。本次仅新增/修改 5 个文档文件，未触碰代码、配置、SQL、`docs/product/index.md`、`docs/architecture/index.md`。

---

## 测试结果

无代码变更，沿用当前基线：

- **编译**：BUILD SUCCESS
- **测试**：`521 tests, 0 failures, 0 errors, 5 skipped`

---

## 结论

✅ **对齐** — 3 条约定校准、4 份 spec 沉淀全部落地，13 项事实断言与代码逐一核对通过，无越界改动。

沉淀后 `openspec/specs/` 不再是空模板，且建立了「归档时回写」的触发规则，后续 change 归档需同步维护这 4 份文件。

**可进入 archive。**
