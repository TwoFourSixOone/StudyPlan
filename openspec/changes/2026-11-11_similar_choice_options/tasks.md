# Tasks：举一反三选择题选项缺失修复

> **Change**：`2026-11-11_similar_choice_options`

---

## 1. 工具与校验

- [x] 1.1 新增 `com.jiang.common.util.ChoiceOptions`（`normalize` / `isUsable` / `canonicalize`）
- [x] 1.2 `similar_question_schema.json`：`choices` 元素改为 `{label,text}`；choice 时必填、`minItems: 2`
- [x] 1.3 `AgentOutputValidator.validateSimilar`：choice 选项不足判失败
- [x] 1.4 `AgentOutputValidator`：兼容判定（`{key,text}` / 字符串数组仍可归一化通过）
- [x] 1.5 `AgentOutputValidator.validate`：生成题（`questionSource=generated`）choice 选项不足同样判失败

## 2. 落库与出参

- [x] 2.1 `QuestionGenAgent.validateAndParse`：落库前 `canonicalize`
- [x] 2.2 `QuestionGenAgent.validateAndParseSimilar`：落库前 `canonicalize`
- [x] 2.3 `HomeworkServiceImpl.buildHomeworkVO`：generated / bank 选项统一走 `ChoiceOptions.normalize`
- [x] 2.4 `HomeworkServiceImpl.buildHomeworkVO`：选择题选项为空时 `log.error`，不伪造选项
- [x] 2.5 出参 `choices` 统一为纯文本列表（label 由前端序号生成）

## 3. 重试与降级

- [x] 3.1 `SimilarQuestionServiceImpl`：输出格式非法时重试，调用失败/超时仍立即放弃

## 4. 前端

- [x] 4.1 `HomeworkDetail.vue`：选项为空时显示提示，不再渲染空白选项区
- [x] 4.2 `HomeworkDetail.vue`：选项文本不再重复显示 label

## 5. Prompt

- [x] 5.1 `similar_question_gen_v1.txt`：明确 `choices` 结构与 `answer` 取值
- [x] 5.2 `question_gen_v1.txt`：同上

## 6. 文档

- [x] 6.1 `docs/architecture/implicit-contracts.md` 新增约定 #25

## 7. 验证

- [x] 7.1 新增 `ChoiceOptionsTest`（10 个用例）
- [x] 7.2 `AgentOutputValidator` 补 choice 选项用例（8 个用例）
- [x] 7.3 `mvn test` 全绿：612 tests, 0 failures, 5 skipped
- [x] 7.5 `npm run build:mp-weixin` 通过（小程序模板/样式可编译）
- [ ] 7.4 手动：生成今日作业 → 举一反三选择题有 4 个可点选项（待联调验证）
