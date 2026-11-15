# Design：试卷解析 v3

> **Change 名称**：`2026-11-12_exam_quality_v3`

---

## 1. D1 整卷单次调用

`ExamParseServiceImpl.parsePaper`：

```
if (singleCallEnabled):
    images = readBytes(所有图片)；totalBytes > 6MB → ImageDownscaler(1600px, 0.82)
    result = examParseAgent.parsePaper(images, context)
    if result.questions 非空 → 放入同一个 ImageTaskResult(imageSeq=1) → mergeAndAttribute（题号重排 1..N）
    else → 回退逐图（原流程）
```

- 开关：`exam.parse.single-call`（默认 true）
- Prompt：`exam_parse_paper_v1.txt`；输出题号**全卷连续**、每题带 `imageIndex`
- `ParsedQuestion.sourceImageIndex`：整卷模式取模型 `imageIndex`（缺失回退 1）；逐图模式仍取图序
- 输出上限：`agent.agentscope.max-tokens: 8000`（否则全卷 JSON 必被截断）
- 预算：`exam.parse.task-timeout-seconds` 默认 180 → **300**；前端轮询 90 → **150** 次（5 分钟）

## 2. D2 答题卡与跨页

Prompt 规则（`exam_parse_paper_v1.txt`）：

- 跨页题必须合并成一道，题干拼接，`imageIndex` 取题干起始图
- 学生答案优先从**答题卡**按题号匹配；题目页没写不等于 blank，先去答题卡找
- 多选：按卷面标注（多选/不定项/有多项符合）输出全部正确标签；**不要因为学生只选一个就少写正确项**
- 图形在另一张图时，`figureRect` 坐标以**图形所在图**为准

## 3. D3 配图 base64 落库

- 迁移：`V14__add_exam_question_figure_base64.sql`（`figure_base64 MEDIUMTEXT NULL`）
- 裁剪成功后同时写：
  - `question_image_url` = 文件 URL（保留兼容/整页回退）
  - `figure_base64` = `data:image/jpeg;base64,...`
- 裁剪失败：`question_image_url` = 整页原图，`figure_base64` = NULL
- VO：`figureBase64` 新增；前端 `src = figureBase64 || questionImageUrl`
- 文件 IO 仍在事务外（TX-A 前完成）

## 4. D4 为什么之前配图空白

`file.storage.local.base-url` 默认 `http://localhost:8080/uploads`，而后端实际跑在 80 端口；
即使同机也 404，真机更不可能访问 localhost。base64 落库后页面不再依赖文件服务地址。

## 5. 验证

- `mvn test`：843 / 0 failure / 5 skipped
- `npm run build:mp-weixin` 通过
- 真实卷待重启后重传验证：跨页题合并、答题卡答案、多选、配图位置
