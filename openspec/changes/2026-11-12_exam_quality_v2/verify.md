# Verify：试卷解析质量 v2

> **Change 名称**：`2026-11-12_exam_quality_v2`
> **验证时间**：2026-11-12 15:55–16:30（Asia/Shanghai）
> **结论**：**实现与工件对齐**；单测/构建通过，真实卷验证待重启后执行（见 §5）

---

## 1. 需求 → 实现对照

| 用户要求 | 实现 | 位置 |
|----------|------|------|
| 归因候选 ≤ 学生年级、但不跨学段（高三不含初中） | `matchesRangeAtOrBelow` + 候选服务接入；parse 切片 150→300 | `GradeMatcher`、`ExamKpCandidateServiceImpl` |
| 归因 JSON 被 ```` ```json ```` 包裹导致整批降级 | 通用 `JsonPayloadExtractor`，7 处校验器 + 归因/解析接入 | `JsonPayloadExtractor`、`AgentOutputValidator` 等 |
| 多选题 | 多标签答案归一 + 严格集合判分 + 详情页「多选」标签 | `ChoiceAnswer`、`ChoiceOptions`、`AnswerGradingServiceImpl`、`ExamDetail.vue` |
| 解析太简略、学生看不懂 | `exam_parse_v2.txt` 强制分步详细解析（≥3 步、关键公式、结论一致、禁"略"） | `exam_parse_v2.txt`、schema 1500 |
| 带图题目没有图 | `figureRect` → 服务端裁剪 → `question_image_url` → 详情页展示可放大；失败回退整页原图 | `ImageCropper`、`uploadBytes`、`persistQuestions`、`ExamDetail.vue` |

## 2. 测试

```
mvn test
→ Tests run: 843, Failures: 0, Errors: 0, Skipped: 5
→ BUILD SUCCESS
```

新增/更新用例：

| 测试 | 覆盖 |
|------|------|
| `GradeMatcherAtOrBelowTest` | 高三含 10/11/12/10-12、排除 7-9/9/3/7-12；高一不提前；空值不过滤 |
| `ChoiceAnswerTest` | 标签抽取（`A、B`/`ba`/`选ABD`）、isMulti、集合相等、withinOptions |
| `JsonPayloadExtractorTest` | ```` ```json ````、``` 无标记、前后说明、对象/数组、无 JSON |
| `ImageCropperTest` | 正常裁剪出 JPEG、过小/越界/字段缺失/非图片 → null |
| `ExamKpCandidateServiceTest` | 新口径过滤 + parse 300 / attribution 500 + 截断可达性 |
| `AnswerGradingServiceTest` | 多选集合相等判对；少选/多选判错 |
| `ChoiceOptionsTest` | 多选答案归一；"选项内容当答案"兼容分支仍工作 |
| `ExamParsePromptContractTest` | v2 契约（详细解析/多选/figureRect/归一化坐标） |

## 3. 构建

```
cd student-app && npm run build:mp-weixin
→ DONE  Build complete.（exit 0）
```

产物核对：

- `pages/exam-detail/ExamDetail.wxml` 含「多选」「点击查看大图」与 `figure-wrap` 结构
- `pages/exam-detail/ExamDetail.wxss` 含 `.figure-img` / `.multi-tag`
- dev watcher（`npm run dev:mp-weixin`）同步更新 `dist/dev`

## 4. 行为说明（对已有数据的影响）

| 场景 | 变化 |
|------|------|
| 新解析高三试卷 | 候选含高一/高二知识（集合、复数等可正常归因）；parse 切片 300 |
| 新解析多选题 | 答案保留 `ABD` 形式；学生写 `BA` 也判对；少选 `AB` 判错 |
| 新解析带图题 | 详情页显示裁剪后的配图（失败时整页原图），可点击放大 |
| 已完成的旧试卷 | **不变**（无 figureRect 归因数据、解析文本仍为旧版）；需重解析才更新，本次不回填 |

## 5. 遗留

| 项 | 说明 |
|----|------|
| 真实卷验证裁剪坐标 | 需重启后端后重新上传一张含多图/多选的卷子，重点看 `figureRect` 是否框住图形（模型坐标准确率无法单测保证，失败会回退整页图） |
| 存量试卷回填 | 另立 change（重解析 + 回滚掌握度/错题/学习记录） |
| 候选增大后的耗时 | parse prompt 约 4.3KB → 9KB；若单图耗时明显上升，可下调 parse 切片或按掌握度排序保留 |
| 归因 Prompt 的 `{subjectId}` 空串 | 未在本 change 处理（无害）；可顺手改成科目名 |
