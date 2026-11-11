# Verify：举一反三选择题选项缺失修复

> **Change**：`2026-11-11_similar_choice_options`
> **核对日期**：2026-11-11
> **实现提交**：`31265eb`（选项结构）、`e59067c`（纯文本清洗）
> **核对方式**：工件（proposal / design / tasks）逐条对照代码与测试

---

## 1. 结论

**对齐通过**：本次核对发现 5 处「实现已做、工件未记」的偏差，已在同一次核对中回写工件（详见 §3）。回写后，`tasks.md` 全部勾选，`design.md` 覆盖实现细节，无未落地的设计项。

---

## 2. 需求 → 实现对照

| 工件要求 | 实现位置 | 结论 |
|----------|----------|------|
| 选项归一化工具 | `ChoiceOptions.normalize`（`ChoiceOptions.java:60`）、`canonicalize`（`:109`）、`isUsable`（`:92`） | ✅ |
| 选择题至少 2 个有效选项 | `AgentOutputValidator.java:126`（主路径）、`:257`（举一反三） | ✅ |
| 落库前统一形态 | `QuestionGenAgent.java:153` `buildCanonicalContent`，两条解析路径 `:117`、`:232` 调用 | ✅ |
| 出参容错（bank `{key,text}` / 字符串数组 / 映射） | `HomeworkServiceImpl.resolveChoiceTexts`（`:1132`），bank `:1076`、generated `:1095` | ✅ |
| 不伪造占位选项 | 空选项仅 `log.error`（`:1080`、`:1097`），无默认选项构造 | ✅ |
| 出参 choices 只放文本 | `resolveChoiceTexts` 返回 `List<String>`；前端 `choiceLabel(index)` 生成 label | ✅ |
| 前端空选项兜底 | `HomeworkDetail.vue:30`（`choices.length`）、`:49-50`（提示文案） | ✅ |
| 输出非法时重试 | `SimilarQuestionServiceImpl.java:89-92`：`AGENT_OUTPUT_INVALID` continue，其余 break | ✅ |
| Prompt 明确选项结构 | `similar_question_gen_v1.txt:39`、`question_gen_v1.txt:36` | ✅ |
| Prompt 禁止 LaTeX | 两个 Prompt 的「纯文本要求」段（`similar:21`、`question_gen:16`） | ✅ |
| 题干/选项清洗 | `HomeworkServiceImpl.java:1072`、`:1092`、`:1152`（`resolveChoiceTexts` 内） | ✅ |
| Schema 选项为对象 | `similar_question_schema.json:27`、`:57`；`question_gen_schema.json:45` | ✅ |
| 隐性约定 | `implicit-contracts.md:190`（#24 修订）、`:202`（#25 新增） | ✅ |
| 历史坏数据可清理 | `target/sql/clean_invalid_choice_questions.sql` | ✅ |

---

## 3. 核对发现的偏差与处置

| # | 偏差 | 处置 |
|---|------|------|
| 1 | 题干/选项纯文本清洗（`e59067c`）未写入 proposal/design/tasks | proposal「包含」补充；design 新增 §2.7；tasks 新增 1.6 / 2.6 / 5.3 / 5.4 |
| 2 | design §2.5 原文写「一律重试」，实现按异常类型区分 | design §2.5 改为「输出非法重试 / 调用失败放弃」，并注明初稿与实现的差异原因 |
| 3 | `question_gen_schema.json` 的 `label` 约束变更未记录 | proposal 补充；tasks 1.6 |
| 4 | 清洗用例（`HomeworkServiceTest`）未在测试策略中体现 | design §4 表格补充 3 行用例 + 实际用例数 |
| 5 | 运维脚本未在工件中登记 | design 新增 §6「运维脚本」；tasks 7.1 |

---

## 4. 验证证据

| 项 | 结果 |
|----|------|
| `mvn test` | 613 tests, 0 failures, 5 skipped |
| `npm run build:mp-weixin` | Build complete |
| 联调（真实数据） | 选择题 4 个选项可点，`√10` 正常显示，答案 D（0）正确 |
| 数据库清理 | 脚本已交付，未执行（由人工在云库执行） |

---

## 5. 不在本 change 范围（已识别，未处理）

| 项 | 说明 | 建议 |
|----|------|------|
| 难度标签失真 | `progressive` 策略按序号硬分配，第 3 道必为 `challenge`，出现"送分题标挑战" | 另起 change：Prompt 改为实际难度自评 + 区间约束 |
| `√(10)` 括号 | `\sqrt{10}` 兜底为 `√(10)`，不如 `√10` 干净 | 可选优化：单 token 不加括号，表达式加括号 |
| 历史坏数据 | 已落库的坏题不会自愈 | 执行 `clean_invalid_choice_questions.sql` |
| Prompt 原地修订未升版 | 两个出题 Prompt 直接改 `v1`，未生成 `v2`；严格的「Prompt 版本化管理」会希望升版 | 本次属输出格式约束补充，沿用 `analysis_gen_v1` 的既有做法；若后续改语义必须升 `v2` 并保留 `v1` |

---

## 6. 归档前置条件

- [x] 工件与实现对齐
- [x] 单测全绿
- [x] 小程序构建通过
- [x] 联调确认选项可作答
- [ ] 人工在云库执行清理脚本（可选，不阻塞归档）
- [x] 归档到 `openspec/changes/archive/`（2026-11-11）

---

## 7. `openspec/specs` 回写（归档时完成）

| 文件 | 回写内容 | 状态 |
|------|----------|------|
| `openspec/specs/agent-contracts.md` §3 | 新增「出题（举一反三）」一行（`similar_question_gen_v1.txt` / `similar_question_schema.json`，输出 `{knowledgePointId, content}`）；补充输出结构约束（选项 ≥2、answer 为 label、纯文本） | ✅ 已回写 |
| `openspec/specs/agent-contracts.md` §4 | 新增「出题（举一反三）」降级行：输出非法重试 5 轮、调用失败立即放弃 | ✅ 已回写 |
| `openspec/specs/agent-contracts.md` §1 | 规划 Agent 仍写「❌ 未实现」 | ⚠️ 不属于本 change，留给 `2026-11-10_goal_planning_agent` 归档时更新 |
