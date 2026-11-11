# Proposal：修复举一反三选择题缺少选项

> **Change 名称**：`2026-11-11_similar_choice_options`
> **创建日期**：2026-11-11
> **状态**：待实施
> **关联 change**：`2026-11-10_homework_bank_first`（举一反三题库优先）、`d85b584`（修正误用 question_gen schema）

---

## 1. 背景

学生在小程序做作业时，遇到一道「举一反三」生成的选择题：题型标签显示「选择」，题干正常，但**下方没有任何选项**，导致「提交答案」永远不可点，题目卡死。

排查结论（代码级证据）：

| 环节 | 现状 | 问题 |
|------|------|------|
| Prompt `similar_question_gen_v1.txt` | 只写「choices: 选项数组」 | **没规定元素结构**，模型可返回字符串数组、对象映射或直接省略 |
| Schema `similar_question_schema.json` | `choices` 元素为 `string` | 与系统内标准形态 `{label,text}` **不一致**（`question_gen_schema.json` 是对象） |
| 校验 `AgentOutputValidator.validateSimilar` | 不校验 `formatType=choice` 时选项是否存在 | 无选项的选择题**照样通过校验** |
| 落库 `QuestionGenAgent.validateAndParseSimilar` | 原样存储模型输出的 `content` | 非标准形态进入 `generated_question.content` |
| 出参 `HomeworkServiceImpl.buildHomeworkVO` | `content.choices` 强转 `List<Map<String,String>>` 后取 `label`/`text` | 字符串数组会抛 `ClassCastException`，被 catch 后**静默变成"无选项"** |
| 前端 `HomeworkDetail.vue` | `v-if="... && currentQuestion.choices"` | `[]` 在 JS 中为真值，渲染出一个**空白选项区**，没有兜底提示 |

一句话：**模型输出的选项形态没人管，解析端按另一种形态读，失败后静默降级成空**。

## 2. 目标

1. 选择题（`formatType=choice`）**必须有 ≥2 个可用选项**，否则该题不进入作业
2. 选项在系统内**只有一个标准形态**：`[{"label":"A","text":"..."}]`
3. 解析端**容错**：字符串数组、`{key,text}`、`{"A":"..."}` 等历史/变体形态统一归一化
4. 选择题答案统一为**选项标签**（A/B/C/D），保证 `studentAnswer` 与 `content.answer` 可直接比较
5. 前端在选项缺失时**明确提示**，不再出现空白选项区

## 3. 范围

### 包含

- 新增 `com.jiang.common.util.ChoiceOptions`：选项归一化 + 可用性判断 + 答案标签归一化
- `similar_question_schema.json`：`choices` 元素改为 `{label,text}` 对象；`formatType=choice` 时必填且 `minItems=2`
- `question_gen_v1.txt` / `similar_question_gen_v1.txt`：明确选项结构与答案取值规则
- `AgentOutputValidator.validateSimilar`：选择题选项缺失/不足直接判失败
- `QuestionGenAgent`（两条解析路径）：落库前统一 `canonicalize`
- `HomeworkServiceImpl.buildHomeworkVO`：选项解析容错（bank / generated 都走归一化）
- `SimilarQuestionServiceImpl`：单轮生成异常改为**重试**而非直接放弃
- `HomeworkDetail.vue`：选项为空时显示提示文案，不再渲染空白区
- 新增 `ChoiceOptionsTest`、补充 `AgentOutputValidator` 用例

### 不包含

- 不改题库导入格式（`question_bank.choices` 仍是 `{key,text}`，出参时归一化）
- 不改配额算法、状态机、组卷流程
- 不回溯修复历史 `generated_question`（已在数据重置范围内）

## 4. 关键决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 标准形态 | `{label, text}` | 与 `question_gen_schema.json`、前端渲染一致，`key` 只作为兼容输入 |
| 选项不足时的处置 | 校验失败 → 该轮重试；重试耗尽则该知识点少出题 | 宁可少题，不可给无法作答的题 |
| 为什么不在出参层伪造选项 | 不伪造 A/B/C/D 占位 | 假选项会让学生背错误答案，题库路径已有占位兜底属历史问题，本次不扩散 |
| 答案归一化 | `B` / `B. xxx` / 选项文本 → 统一为 `B` | 选择题批改是字符串比较，答案形态必须与前端提交的 label 一致 |
| 前端空选项 | 显示「本题选项数据异常，请跳过并反馈」 | 让用户可继续做题，不阻塞整份作业 |

## 5. 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| 模型仍返回非标准形态 | 生成失败率上升 | 解析端容错 + 重试；Prompt 明确结构 |
| 校验变严导致举一反三题量下降 | 每日题量略少 | 题库优先已覆盖大部分题量；降级日志可观测 |
| 答案无法归一化 | 选择题判分错误 | 记录 warn 日志，保留原答案；后续以日志为线索补规则 |
