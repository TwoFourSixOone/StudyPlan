# Tasks：试卷解析质量 v2

> **Change 名称**：`2026-11-12_exam_quality_v2`
> **实施顺序**：T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8

---

## T1 归因候选口径（同段 ≤ 年级）

- [x] `GradeMatcher.matchesRangeAtOrBelow`（跨段/异段跳过；hi ≤ 学生年级；空值不过滤）
- [x] `ExamKpCandidateServiceImpl` 改用新口径；parse 切片 150 → 300
- **验收**：`GradeMatcherAtOrBelowTest`（高三含 10/11/12/10-12、不含 7-9/9/3）通过 ✅

## T2 JSON 围栏清洗（通用）

- [x] 新增 `JsonPayloadExtractor`（剥围栏 + 截取 JSON 载荷）
- [x] `AgentOutputValidator` 7 处 / `AttributionAgentImpl` / `ExamParseAgentImpl` 接入
- **验收**：`JsonPayloadExtractorTest`（围栏/说明文字/嵌套数组/无 JSON）通过 ✅

## T3 多选题

- [x] 新增 `ChoiceAnswer`（labels / isMulti / sameAnswer / withinOptions）
- [x] `ChoiceOptions.canonicalize` 多标签归一（保留文本答案兼容）
- [x] `ExamParseAgentImpl` 答案校验改 `withinOptions`（替代单字母正则）
- [x] `AnswerGradingServiceImpl` 单题/批量 choice 统一 `sameAnswer`
- [x] `ExamQuestionItemVO.multiChoice` 出参
- **验收**：`ChoiceAnswerTest`、`AnswerGradingServiceTest`（集合相等/少选多选算错）、`ChoiceOptionsTest` 通过 ✅

## T4 详细解析 Prompt v2

- [x] 新增 `exam_parse_v2.txt`（分步解析 + 多选 + figureRect + 纯文本约束）
- [x] `ExamParseAgentImpl.PROMPT_PATH` → v2（v1 保留）
- [x] `exam_parse_schema.json`：`figureRect` + explanation 1500
- **验收**：`ExamParsePromptContractTest`（v2 契约）通过 ✅

## T5 题目配图（区域 + 裁剪）

- [x] `ParsedQuestion.figureRect`（含 `hasFigure()`）
- [x] `ExamParseAgentImpl.parseFigureRect`（归一化 + 过小/越界丢弃）
- [x] `ImageCropper.cropJpeg`（3% 留白、40px 下限、白底 JPEG、异常返回 null）
- [x] `FileStorageService.uploadBytes` + local 实现 + COS 明确不支持
- [x] `ExamPaperServiceImpl.cropQuestionFigures`（事务外；失败回退整页原图）
- **验收**：`ImageCropperTest`（正常/过小/越界/非图片）通过 ✅

## T6 出参与前端展示

- [x] `ExamQuestionItemVO.questionImageUrl / multiChoice`
- [x] `ExamDetail.vue`：题干下方渲染配图（widthFix）+ 点击 `uni.previewImage`；「多选」标签
- [x] `api/exam.ts` 类型补两个字段
- **验收**：`npm run build:mp-weixin` 通过；产物含「多选」「点击查看大图」 ✅

## T7 回归

- [x] `mvn test`：**843 tests / 0 failure / 5 skipped**
- [x] 前端构建通过（dist/build 与 dev watcher 同步）
- **验收**：全绿 ✅

## T8 文档回写

- [x] 产品规则 #4：归因候选口径、多选判定、详细解析、配图、存量不回填
- [x] 隐性约定 #32（候选）/ #33（多选）/ #34（配图）；编号起点 → #35
- [x] `agent-contracts.md` §3：试卷视觉解析 v2（字段与切片）
- **验收**：文档与代码一致 ✅
