# Design：举一反三选择题选项缺失修复

> **Change**：`2026-11-11_similar_choice_options`

---

## 1. 数据形态

系统内**唯一标准形态**（落库 + 出参）：

```json
{
  "stem": "题干",
  "choices": [
    { "label": "A", "text": "选项内容" },
    { "label": "B", "text": "选项内容" }
  ],
  "answer": "A",
  "analysis": "解析",
  "formatType": "choice",
  "difficulty": "standard"
}
```

非选择题：`choices` 固定为 `[]`，`answer` 为文本答案。

### 兼容输入形态（归一化入口 `ChoiceOptions`）

| 输入 | 示例 | 归一化结果 |
|------|------|-----------|
| 标准对象 | `[{"label":"A","text":"4"}]` | 原样 |
| 题库形态 | `[{"key":"A","text":"4"}]` | `label` ← `key` |
| 带序号字符串 | `["A. 4", "B. 2"]` | 拆出 label=A，text=4 |
| 无序号字符串 | `["4", "2"]` | label 按序号补 A/B |
| 对象映射 | `{"A":"4","B":"2"}` | 展开为数组 |
| 缺失 / `null` / `[]` | — | 空列表（选择题判不可用） |

## 2. 组件改造

### 2.1 `ChoiceOptions`（新增，`common/util`）

```java
List<Map<String,String>> normalize(Object raw);      // 任意形态 → 标准形态
boolean isUsable(List<Map<String,String>> options);  // ≥2 个且 text 非空
void canonicalize(Map<String,Object> content);       // 就地归一化 choices + answer
```

- `canonicalize` 仅在 `formatType=choice` 时处理 `answer`：`B` / `B. xxx` / 命中某选项文本 → 统一写成 `B`
- 无法归一化时保留原值并 `log.warn`，不抛异常（避免整份输出作废）
- 上限 8 个选项（A–H），超出截断

### 2.2 `AgentOutputValidator.validateSimilar`

`formatType=choice` 时追加：

```java
List<Map<String,String>> options = ChoiceOptions.normalize(content.get("choices"));
if (!ChoiceOptions.isUsable(options)) {
    errors.add(prefix + ": 选择题 content.choices 至少需要 2 个有效选项");
}
```

校验是**整数组**粒度（现有语义不变），失败由 `QuestionGenAgent` 抛 `AGENT_OUTPUT_INVALID`。

### 2.3 `QuestionGenAgent`

两条解析路径（`validateAndParse`、`validateAndParseSimilar`）在 `setContent` 前构造 `Map` 并调用 `ChoiceOptions.canonicalize(content)`，确保落库形态统一。

### 2.4 `HomeworkServiceImpl.buildHomeworkVO`

- generated：用 `ChoiceOptions.normalize` 解析，不再强转 `List<Map<String,String>>`
- bank：`{key,text}` 走同一归一化，**出参 options 只保留 text**（label 由前端按序号渲染，避免 "A. A. 4" 重复）
- 选择题选项为空：`log.error` 明确记录 `questionId`，不构造占位选项

> 出参 `HomeworkVO.choices` 语义改为「纯选项文本列表」，label 由前端 `choiceLabel(index)` 生成。

### 2.5 `SimilarQuestionServiceImpl`

`doSingleAttempt` 抛 `BusinessException` 时：
- `AGENT_OUTPUT_INVALID`（输出结构非法，如选择题缺选项）→ `continue` 重试，最多 `MAX_ATTEMPTS=5` 轮
- 其余（`AGENT_CALL_FAILED` 超时/调用失败）→ `break` 立即放弃，避免 5×120s 长等待

> 设计初稿写的是「一律重试」，实现时按异常类型收敛，避免超时场景放大延迟。

### 2.6 前端 `HomeworkDetail.vue`

```html
<view class="choices" v-if="isChoice && current.choices && current.choices.length"> ... </view>
<view class="choices-missing" v-else-if="isChoice">本题选项数据异常，请跳过此题并反馈</view>
```

### 2.7 题干与选项的纯文本清洗（隐性约定 #24，二次修复）

选择题选项虽然补上了，但**题干与选项此前完全没过清洗**（`LlmTextSanitizer` 只在生成解析时调用），模型若输出 `\sqrt{10}`、`\frac{1}{2}`，学生会看到字面量。

- `HomeworkServiceImpl.buildHomeworkVO`：`questionContent` 与 `choices[].text` 统一过 `LlmTextSanitizer.sanitize(...)`
- 出题 Prompt（`question_gen_v1.txt`、`similar_question_gen_v1.txt`）增加「纯文本要求」段：禁止 LaTeX/Markdown，根号写 `√10`、分数写 `1/2`、向量写 `向量AB`、幂写 `x²`
- 清洗映射：`\sqrt{10}` → `√(10)`、`\frac{1}{2}` → `(1)/(2)`；模型按新 Prompt 直接写 `√10` 时不触发兜底

> 该修复属于约定 #24 的实现补齐，不新增业务流程；为避免与选项结构修复分叉，合并记录在本 change。

## 3. Prompt 约定

两个出题 Prompt 的 `choices` 说明改为：

```
- choices: 选项数组，仅 formatType=choice 时必填，固定格式：
  [{"label":"A","text":"..."},{"label":"B","text":"..."},{"label":"C","text":"..."},{"label":"D","text":"..."}]
  其他题型填空数组 []
- 选择题的 answer 必须是选项 label（A/B/C/D），不是选项内容
```

Prompt 版本号：`similar_question_gen_v1.txt`、`question_gen_v1.txt` 内容变更，**不新增版本文件**。

- 理由：本次只补充「输出格式约束」（选项结构、纯文本要求），不改变 Agent 的职责与规则 #6 语义
- 与仓库既有做法一致：`analysis_gen_v1.txt` 在 2026-11-10 的纯文本修复中同样原地修订
- 约束：若后续再改动**语义**（如难度策略、生成数量规则），必须新建 `v2` 并保留 `v1`，代码常量同步指向新版本

## 4. 测试策略

| 用例 | 断言 |
|------|------|
| 字符串数组 `["A. 4","B. 2"]` | 归一化为 2 个 `{label,text}` |
| `{key,text}` 数组 | `label` ← `key` |
| `{"A":"4"}` 映射 | 展开为数组 |
| 空 / null / 缺失 | `isUsable=false` |
| 选择题 answer=`B. 2` | 归一化为 `B` |
| 选择题 answer=选项文本 | 归一化为对应 label |
| `validateSimilar`：choice 无选项 | 返回失败且含「至少需要 2 个有效选项」 |
| `validateSimilar`：choice 4 个对象选项 | 返回成功 |
| `validate`（主路径）：generated + choice 无选项 | 返回失败 |
| `validate`（主路径）：bank + choice 无选项 | 通过（选项来自 `question_bank`） |
| `getHomeworkByDate`：生成题 stem/choices 含 `\sqrt`、`\frac` | 出参为 `√(10)`、`(1)/(2)` |

实际用例数：`ChoiceOptionsTest` 10 个、`AgentOutputValidatorTest` 8 个、`HomeworkServiceTest` 新增 1 个。

## 5. 隐性约定

新增 **#25：选择题选项必须在落库前归一化为 `[{label,text}]`，解析端必须容错**（写入 `docs/architecture/implicit-contracts.md`）。

同时修订 **#24**：清洗范围从「解析/批改」扩展到「题干 + 选项」。

## 6. 运维脚本（不在 `db/` 迁移内）

`target/sql/clean_invalid_choice_questions.sql`：清理已落库的历史坏题（choice 且有效选项 < 2），并把受影响计划重置为 `pending_generation`（今天及以后）或 `cancelled`（历史）。

- 不含 DDL，不进 Flyway 迁移目录
- MySQL 5.7 兼容（不使用 CTE / JSON_TABLE）
- 计划级重置原因：`daily_plan.content` 是题目引用数组且无外键，单删题会留下悬空引用
