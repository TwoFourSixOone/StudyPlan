# Design：试卷解析质量 v2

> **Change 名称**：`2026-11-12_exam_quality_v2`

---

## 1. D1 归因候选：同段 + ≤ 学生年级

新增 `GradeMatcher.matchesRangeAtOrBelow(studentGrade, gradeRange)`：

```
studentStage = stageOf(studentGrade)      // 小学/初中/高中
for token in gradeRange:
    if stageOfRange(token) != studentStage: continue   // 跨段/异段直接跳过
    if hi <= studentGrade && hi >= stageFloor(studentStage): return true
return false
```

- 高三（12）：`10` / `11` / `12` / `10-12` 命中；`7-9` / `9` / `3` 不命中
- 高一（10）：`10` 命中，`11` / `12` / `10-12` 不命中（不提前学）
- 空年级/空范围/非法范围 → 不过滤（与全库降级一致）

`ExamKpCandidateServiceImpl` 用它替换 `matchesRange`；parse 切片默认 150 → **300**（高三数学 271 个候选）。

## 2. D2 JSON 围栏清洗（通用）

`JsonPayloadExtractor.extract(raw)`：剥 ```` ```json ```` / ```` ``` ```` 围栏 → 截首个 `{`/`[` 到末个 `}`/`]`。
接入点：

- `AgentOutputValidator` 的 7 处 `readTree(agentOutput)`（规划/出题/检查/归因/试卷解析/调整等全部校验器）
- `AttributionAgentImpl.parseAttributionResult`
- `ExamParseAgentImpl.extractQuestions`（原来自带的 `stripCodeFence` 保留，提取器兜底更多情况）

只定位 JSON 载荷，不修语法：语法错仍由校验器判失败并走既有降级。

## 3. D3 多选题

- 标准形态：`correctAnswer` = 字母序标签串（`ABD`）；`studentAnswer` 保留学生写法
- `ChoiceAnswer.labels(raw)`：抽 A–H、大写、去重、升序（`"A、B"` / `"ba"` / `"选ABD"` 均可）
- `ChoiceAnswer.sameAnswer(a, b)`：**集合严格相等**；少选/多选 false（产品规则 #4 口头确认）
- `ChoiceOptions.canonicalize`：按旧标签映射到重排后的新标签；保留"答案是选项内容→按文本匹配"的兼容分支
- `ExamParseAgentImpl`：答案标签必须 ⊂ 本卷选项标签，否则置 null（替代原 `matches("[A-H]")`）
- `AnswerGradingServiceImpl`：choice 两个分支统一 `sameAnswer`（含批量）
- `ExamQuestionItemVO.multiChoice` = choice 且（正确答案或学生答案标签数 ≥2）

## 4. D4 详细解析（Prompt v2）

`exam_parse_v2.txt` 新增硬性要求：

- 分步写：`第 1 步：` / `第 2 步：` …，每步一行
- 关键公式/代入数值/定理必须写出；末行结论与 `correctAnswer` 一致
- 解答题 ≥3 步；选择/填空说明为什么选它（能判断时）
- 禁止"解析略/证明略/求解略"
- Schema `explanation.maxLength` 1000 → 1500

## 5. D5 题目配图（模型给区域 + 服务端裁剪）

**Prompt 侧**：含图题目输出

```json
"figureRect": {"x":0.12,"y":0.35,"width":0.30,"height":0.22}
```

相对**本张图片**归一化；框图形本身（含坐标轴/标注），不框题干；`<5%` 或无法定位则省略。

**解析侧**（`ExamParseAgentImpl.parseFigureRect`）：非数字/尺寸 <0.05/越界丢弃；`x+w>1` 时收敛宽度。

**裁剪侧**（`ImageCropper.cropJpeg`）：四周 3% 留白、最小边 ≥40px、白底转 RGB 编码 JPEG；任何异常 → null。

**落库侧**（`ExamPaperServiceImpl.cropQuestionFigures`，**事务外**）：

```
sourceUrl = paper.imageUrls[sourceImageIndex - 1]
cropped   = ImageCropper.cropJpeg(readBytes(sourceUrl), figureRect)
url       = cropped != null ? uploadBytes(cropped, "exam-figures/{studentId}", ".jpg") : sourceUrl
entity.questionImageUrl = url      // 裁剪失败回退整页原图
```

`FileStorageService` 新增 `uploadBytes(byte[], path, extension)`：local 直接写盘；COS 仍抛"未实现"。

**展示侧**：`ExamQuestionItemVO.questionImageUrl` → `ExamDetail.vue` 在题干下方渲染图片（widthFix），
点击 `uni.previewImage` 放大；同时按 `multiChoice` 显示「多选」标签。

## 6. 验证方式

- `mvn test`：843 / 0 failure / 5 skipped（新增 20 例）
- `npm run build:mp-weixin` 通过；产物含「多选」「点击查看大图」「figure-wrap」
- 真实卷验证（遗留）：需重启后端后重新上传一张带图/多选的卷子，确认裁剪框位置与多选判分
