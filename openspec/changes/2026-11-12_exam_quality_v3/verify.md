# Verify：试卷解析 v3

> **Change 名称**：`2026-11-12_exam_quality_v3`
> **验证时间**：2026-11-12 16:22–17:05（Asia/Shanghai）
> **结论**：代码/构建全部通过；**真实卷效果需重启后重传验证**（见 §4）

---

## 1. 用户反馈 → 实现对照

| 反馈 | 实现 |
|------|------|
| 配图空白 | `V14` 新增 `figure_base64`，裁剪件以 data URL 落库；详情页 `figureBase64 || questionImageUrl`（不再依赖 localhost URL） |
| 多选题被当单选 | 整卷单次 Prompt 明确多选判断规则；多标签答案保留并集合判分（v2 已有） |
| 所有图片一次性发大模型 | `parsePaper` 整卷单次调用（`exam.parse.single-call=true`），失败回退逐图 |
| 题目/图形跨页 | Prompt 要求跨页合并；`figureRect` 以图形所在图为准 |
| 答案写在答题卡 | Prompt 要求按题号从答题卡匹配；题目页空白不等于 blank |
| 请求体/截断风险 | >6MB 降采样 1600px/0.82；`maxTokens=8000` |

## 2. 后端验证

```
mvn test → Tests run: 843, Failures: 0, Errors: 0, Skipped: 5 → BUILD SUCCESS
mvn -q compile → exit 0
```

- 新增 `ImageDownscaler`、`ExamParsePaperContext`、`ExamParseAgent.parsePaper`
- `ExamParseServiceImpl`：整卷路径调用 `parsePaper`，结果非空走 `mergeAndAttribute`（题号重排 1..N）；异常/空自动回退逐图（原路径未改）
- Mapper：`batchInsert` / `selectByPaperOrderByIndex` 已含 `figure_base64`

## 3. 数据库

- 本地已执行 `V14__add_exam_question_figure_base64.sql`：`exam_question.figure_base64 MEDIUMTEXT NULL`
- 存量行为 NULL → 前端回退 `question_image_url`（旧数据仍可能显示空白，需重解析）

## 4. 前端

```
cd student-app && npm run build:mp-weixin → DONE Build complete.
```

- 详情页：`figureSrc = figureBase64 || questionImageUrl`；点击 `uni.previewImage`
- 轮询 90 → 150 次（5 分钟）

## 5. 网关能力实测（设计依据）

| 场景 | 结果 |
|------|------|
| 4 张图单次请求（body 0.89MB） | HTTP 200，21.85s，image_tokens=2351，max_tokens=4000 时 `finish_reason=length` → 故上限设 8000 |

## 5.1 追加：单选/多选显式区分（V15）

真实卷（2026-11-16）第 9 题卷面标注「全部选对的得 6 分，部分选对的得部分分，有选错的得 0 分」
（多选题），但页面显示为单选——因为当时仅靠"答案标签数 ≥2"推断，而模型只给了 1 个正确标签。

修复：

- `V15__add_exam_question_choice_mode.sql`：`exam_question.choice_mode VARCHAR(10)`（本地已执行）
- Prompt（`exam_parse_paper_v1.txt` / `exam_parse_v2.txt`）：choice 题必须输出
  `choiceMode: single|multi`，按卷面标注判断（多选/不定项/部分选对得部分分 → multi）
- Schema：`choiceMode` enum
- 解析：`ParsedQuestion.choiceMode`；缺省时按答案标签数回退
- 落库：`question_type` + `choice_mode`；判分仍按标签集合严格相等（部分分暂不做）
- 详情页：题型标签显示「单选 / 多选」（`multiChoice` 优先取 `choice_mode`）

验证：`mvn test` → **844 / 0 failure / 5 skipped**；`npm run build:mp-weixin` 通过。

## 6. 遗留（必须重启后人工验证）

| 项 | 说明 |
|----|------|
| 跨页合并 / 答题卡匹配 / 多选判断 | Prompt 行为无法单测保证，需用真实卷（含答题卡、多选、跨页图）验证 |
| 8 图单次调用的耗时/截断 | 实测 4 图 21.8s；8 图可能 60–120s，已放宽预算与轮询，仍可能出现截断 → 回退逐图 |
| 存量试卷 | 不回填；重解析需回滚已结算数据（另立 change） |
| 云端迁移 | V14 需在云数据库执行 |
