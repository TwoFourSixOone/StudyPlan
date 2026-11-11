# Tasks：举一反三选择题选项缺失修复

> **Change**：`2026-11-11_similar_choice_options`
> **实现提交**：`31265eb`（选项结构）、`e59067c`（题干/选项纯文本清洗）

---

## 1. 工具与校验

- [x] 1.1 新增 `com.jiang.common.util.ChoiceOptions`（`normalize` / `isUsable` / `canonicalize`）
- [x] 1.2 `similar_question_schema.json`：`choices` 元素改为 `{label,text}`；choice 时必填、`minItems: 2`
- [x] 1.3 `AgentOutputValidator.validateSimilar`：choice 选项不足判失败
- [x] 1.4 `AgentOutputValidator`：兼容判定（`{key,text}` / 字符串数组仍可归一化通过）
- [x] 1.5 `AgentOutputValidator.validate`：生成题（`questionSource=generated`）choice 选项不足同样判失败
- [x] 1.6 `question_gen_schema.json`：`label` 增加 `^[A-H]$` 约束，description 说明生成题必填

## 2. 落库与出参

- [x] 2.1 `QuestionGenAgent.validateAndParse`：落库前 `canonicalize`
- [x] 2.2 `QuestionGenAgent.validateAndParseSimilar`：落库前 `canonicalize`
- [x] 2.3 `HomeworkServiceImpl.buildHomeworkVO`：generated / bank 选项统一走 `ChoiceOptions.normalize`
- [x] 2.4 `HomeworkServiceImpl.buildHomeworkVO`：选择题选项为空时 `log.error`，不伪造选项
- [x] 2.5 出参 `choices` 统一为纯文本列表（label 由前端序号生成）
- [x] 2.6 `buildHomeworkVO`：题干与选项统一过 `LlmTextSanitizer`（约定 #24 补齐）

## 3. 重试与降级

- [x] 3.1 `SimilarQuestionServiceImpl`：`AGENT_OUTPUT_INVALID` 重试，调用失败/超时立即放弃

## 4. 前端

- [x] 4.1 `HomeworkDetail.vue`：选项为空时显示提示，不再渲染空白选项区
- [x] 4.2 `HomeworkDetail.vue`：选项文本不再重复显示 label

## 5. Prompt

- [x] 5.1 `similar_question_gen_v1.txt`：明确 `choices` 结构与 `answer` 取值
- [x] 5.2 `question_gen_v1.txt`：同上
- [x] 5.3 两个出题 Prompt 增加「纯文本要求」：禁止 LaTeX/Markdown，根号写 `√10`
- [x] 5.4 Prompt 说明 `stem` / `choices[].text` / `analysis` 三个字段都适用纯文本约束

## 6. 文档

- [x] 6.1 `docs/architecture/implicit-contracts.md` 新增约定 #25
- [x] 6.2 约定 #24 补充覆盖范围（题干 + 选项）

## 7. 运维

- [x] 7.1 `target/sql/clean_invalid_choice_questions.sql`：诊断 / 备份 / 清理 / 校验（MySQL 5.7）

## 8. 验证

- [x] 8.1 `ChoiceOptionsTest`：10 个用例
- [x] 8.2 `AgentOutputValidatorTest`：8 个用例（含主路径 generated/bank 区分）
- [x] 8.3 `HomeworkServiceTest`：新增 1 个题干/选项清洗用例
- [x] 8.4 `mvn test` 全绿：613 tests, 0 failures, 5 skipped
- [x] 8.5 `npm run build:mp-weixin` 通过
- [x] 8.6 联调：答题页选择题显示 4 个可点选项，答案 D 正确（2026-11-11 截图确认）
