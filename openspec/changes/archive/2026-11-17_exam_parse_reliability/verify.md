# Verify：试卷解析可靠性：上传页序、卷面题号保真、清晰度门槛

> **Change**：`2026-11-17_exam_parse_reliability`　**验证**：2026-11-17（本地单测 + SQL 语法验证；真机联调待跑）

## 需求 → 实现

| 需求 | 实现 |
|---|---|
| 并行上传保页序 | 客户端每张图带 `pageNo` → `exam_paper.image_page_nos`（与 `image_urls` 等长）；`ExamPaperImages.ordered` 在解析入参 / 配图裁剪 / 详情 VO / 学习记录四处统一还原；旧数据 NULL 回退到达顺序；**删除待上传图后前端重排 pageNo**（`renumberPageNos`），避免重复序号导致页序错位 |
| 题号保真 | 模型输出 `paperNo` → `question_index = paperNo`（可能不连续）；`ExamParseNumbering` 负责合法性、稳定排序、回退 1..N 与缺口检测（回退原因细分：缺题号/重复/越界/超总数）；详情页显示「第 X 题」 |
| 缺题可见 | 模型输出 `paperQuestionTotal`；缺号/重复/回退 → `imageWarnings` → `exam_paper.remark`（`imageWarning`）→ 详情 banner + 降级日志 `parse_question_gap` |
| 低清图拦截 | 长边 <600 或短边 <500 → **1193**（消息带实际尺寸）；阈值 `exam.parse.min-long-side/min-short-side` 可配、置 0 关闭；小程序 `uni.getImageInfo` 预检 |
| 作答归属 | Prompt 三类笔迹规则 + **勾选=学生作答** + 老师勾只表示对错 + OCR 只作印刷内容真值；`studentAnswer` 仅 `√/×` → `blank` |
| 静默丢题 | `explanation` 上限 1000→1500（与 schema 对齐）；丢弃题数 → `warning` + `ImageTaskResult.droppedCount` → 降级日志 `parse_item_dropped` |

## 测试

```
mvn test → Tests run: 956, Failures: 0, Errors: 0, Skipped: 5 → BUILD SUCCESS（归档时 2026-11-17 复跑）
```

| 用例 | 覆盖 |
|---|---|
| `ExamParseNumberingTest`（8） | 题号原样保留/排序、缺第 16 题点名、缺 paperNo 回退、重复回退、越界回退（>60）、超过整卷题数回退、total=0 粗检、空列表 |
| `ExamPaperImagesTest`（4） | 乱序还原、null/长度不一致回退、重复 pageNo 稳定、空列表 |
| `ExamUploadValidatorTest`（+9） | 400×300→1193（含尺寸）、800×1131 通过、1242×1574 通过、900×400→1193、阈值 0 关闭、读头失败放行、**500×684 压线放行、600×500 放行、599×500 / 600×499→1193** |
| `AgentOutputValidatorTest`（+3） | explanation 1200 通过/1600 拒绝；schema `paperNo`/`paperQuestionTotal` 约束 + `anyOf(questionIndex\|paperNo)`；只给 paperNo 不丢题、两者都缺才非法 |
| `ExamParseAgentImplTest`（+4） | paperNo/paperQuestionTotal 解析、纯勾叉答案→blank、丢弃计数进 warning、只给 paperNo 时 questionIndex 取 paperNo |
| `ExamParsePromptContractTest`（+2） | 整卷 Prompt 含 paperNo/paperQuestionTotal/勾选/老师批改/红笔/铅笔/OCR 仅印刷；clean+answers 渲染无残留占位符 |
| `RedInkSuppressorTest`（4） | 红笔刷白、黑/灰不动、无红笔/非图片原样返回、红色阈值 |
| `ExamPaperServiceTest`（+3） | 带 pageNo 上传原样落库；pageNo 缺失/越界 → 按「当前张数+1」补齐 |
| `ExamPaperMapperSqlTest`（+1） | `image_page_nos` 与 URL 同语句追加 + 并发守卫；两处重置会话同时清空 `image_page_nos` |

> 2026-11-17 评审后补充：`pageNo` 删除重排（前端）、`resolvePageNo` 归一、重置 SQL、清晰度阈值压线用例。

## 数据库

- V16 的 `ALTER TABLE ... ADD COLUMN image_page_nos JSON ... AFTER image_urls` 与
  `JSON_ARRAY_APPEND(COALESCE(image_page_nos, JSON_ARRAY()), '$', #{pageNo})` 已在 MySQL **临时表**验证通过（未触碰真实表）。
- 待应用：应用重启后 Flyway 执行 V16；回滚语句在迁移文件注释中。

## 红笔样本实测（2026-11-17）

- 样本：800×1131 红笔批改卷（题号左侧红笔字母 = 正确答案，红笔解题过程/√×，整页无学生作答）。
- `RedInkSuppressor` 刷白 **11,915** 个红色像素（暗红笔芯，阈值按相对偏红判定），输出 PNG 555KB。
- 同一页送 GLM-OCR：**刷白前** OCR 文本含「C. 1. …」以及红笔推导（会被模型误当题干/学生答案）；
  **刷白后** OCR 文本只剩印刷题干与选项，红笔字母/解题过程消失（实测）。
- 视觉模型仍使用原图（保留颜色），用于区分学生黑/蓝笔与老师红笔。

## 未验证（需重启 + 真实卷）

| 项 | 说明 |
|---|---|
| OCR 生效 | 机器级 `ZHIPU_API_KEY` 不会注入已启动 JVM，必须重启；日志确认 `GLM-OCR grounding 完成: successPages=4` |
| 页序/题号联调 | 4 张 1242px 图乱序上传 → 第 17 题显示「第 17 题」、顺序 1..19、配图裁在正确页 |
| 低清拦截 | 400×300 页应被 1193 拒绝（联调期阈值 600/500，500×684 放行） |
| 作答归属 | 第 2 题 `studentAnswer=C`、第 3 题 `=D`；卷首 `-5/-34`、`116`、日期/时间/签名不进任何题；老师勾不改变 `correctAnswer` |
| 红笔批改卷 | 800×1131 样本：题号左侧红笔字母（正确答案）、红笔解题过程/√× 不得进入 `studentAnswer`；整页无学生作答时全部 `blank` |
| 缺口告警 | 缺第 16 题时 remark 含「应有 19 题 / 识别到 18 题 / 缺第 16 题」 |

## 已知取舍

- 低清阈值 600/500 为初始值，上线后按真实样本调（改配置即可；前端常量需同批改）。
- 模型 `paperNo` 个别误读不会被自动发现，详情页「第 X 题」便于人工核对。
- 存量试卷不回填；已污染的 `paperId=4` 数据清理/重解析另立 change。

## 评审后修订（2026-11-17，独立评审 → 修复）

| 级别 | 问题 | 修复 |
|---|---|---|
| medium | 小程序 pageNo 只在加入列表时赋值、删除不重排 → 删除 2 张再补选会拿到重复 pageNo，服务端稳定排序后解析页序与缩略图顺序不一致 | `ExamUpload.vue#renumberPageNos`（删除后重排 1..n）；#38/design D1.5 措辞同步为"上传时列表下标" |
| medium | 整卷 Prompt 第 18 条自相矛盾（"逐字一致/以 OCR 为准" vs "只作印刷内容真值"） | 改为"印刷部分与 OCR 一致 + 手写/批改片段先剔除"；clean 模板同步 |
| medium | 作答归属只认"黑/蓝笔"，铅笔（灰）作答无归属 → 可能整题 blank 判错 | 整卷/作答版 Prompt 与 #40 增加铅笔判据（作答位置 + 与同卷笔迹一致，否则 unreadable） |
| low | 回退原因"题号超出范围"被用于"超过整卷题数"，且 #39 承诺的统一话术只覆盖一种情况 | 原因细分（缺/重复/越界/超总数）+ 统一"已按图片顺序编号"；#39 同步 |
| low | `questionIndex` 仍必填 → 模型漏给时整题被静默丢弃 | `checkExamParseItem` 改为"questionIndex 或 paperNo 至少一个"，schema 用 `anyOf` 表达；`toQuestion` 回退 paperNo |
| low | Prompt 模板用平台默认字符集解码（Windows 中文 JVM 为 GBK）→ 整卷/clean/answers 模板会整体乱码，而单图模板已按 UTF-8 读取 | `loadTemplate` 显式 `StandardCharsets.UTF_8`（并加 Prompt 渲染无残留占位符/内容断言） |
| medium | **标准答案与解析结论不一致**（真实卷：我的答案 ABD / 标准答案 AB / 0 分 / 解析写"故选 ABD"）→ 学生按解析作答被判错，掌握度/错题被污染 | 新增 `AnswerConclusionExtractor`（两级保守抽取：结论区允许"选 A"、全文只认强引导词、取最后一处、排除"若选 A 则…/选 A、B 都错"）+ `ExamParseAgentImpl#toQuestion`：选择题从解析结论抽取标签集合（需落在本卷选项内），与 `correctAnswer` 不一致即**以解析结论为准**并写告警「已按解析结论修正 N 道标准答案」；两份解析 Prompt 增加"最后一行固定写 `答案：X` 且与 correctAnswer 一致"要求；新增隐性约定 **#42**、产品规则 #4 同步；单测 `AnswerConclusionExtractorTest`(8) + `ExamParseAgentImplTest`(+2) |
| medium | `配图二次确认不通过（疑似文字/题号/手写），回退整页原图` 反复出现：① OCR 吸附**破坏性覆盖**模型框（模型框对了也会被装饰图 bbox 顶掉，且没有第二次机会）；② `FigureCheckDecision` 先宽匹配否定词，`printed（图中带坐标轴与标注文字）` 命中"文字"被判不合格；③ 无可观测信息（不知道框到了什么） | ① `ParsedQuestion#ocrFigureRect` + `FigureRects`：候选=模型框→OCR框，逐个裁剪+确认，任一通过即采用（`ExamPaperServiceImpl#cropQuestionFigures`）；② 判定改"先明确结论词（跳过'不是印刷图形'）→ 再宽匹配"，`figure_check_v1.txt` 明确"带标注文字的图形仍判合格"；③ 失败日志补 candidate/rect/cropBytes/answer 全文，被拒裁剪件落盘 `exam-figure-rejected/{paperId}/`（`save-rejected` 可关）；④ `FigureRects.isPlausible` 过滤整页/细条/碎片，避免必然被拒的模型调用 |
| medium | **题干里的 HTML 表格原样显示给学生**（真实卷概率统计题：列联表以 `<table><tr><td>…` 显示），详情页 `<text>` 纯文本渲染 | **demo 阶段按"表格真的要像表格"实现**：① 存储层 `HtmlRichText.sanitizeForStorage` 只留白名单标签（删 script/style、属性全丢）；② 详情接口出 `stemBlocks`（`HtmlRichText.toBlocks`：text/table 分块）+ `stemHtml`（整段），表格块带 `minWidthPx`（列数×84，≥280），小程序 text 块用 `<rich-text>`、table 块套 `<scroll-view scroll-x>` 并设 `min-width` → **宽表格可横向滚动**；③ `stem`/判分入参继续用 `HtmlTableTextifier.textifyTablesOnly` 派生纯文本；④ 未知标签按文本转义（防吞掉 `x<y` 这类数学写法）；两句 Prompt 改为"表格用最简 HTML（禁属性/脚本）或纯文本表格"；新增隐性约定 **#43**、产品规则 #4 同步；单测 `HtmlRichTextTest`(5) + `HtmlTableTextifierTest`(7) + `ExamPaperReadServiceTest`(+1 stemHtml) + `ExamParseAgentImplTest`(+1) |
## 归档（2026-11-17）

- 按 openspec/changes/README.md 约定归档到 openspec/changes/archive/（目录名不变）。
- 归档时状态：全量 `mvn -o test` **956 用例通过**（跳过 5）、`npm run build:mp-weixin` 通过；代码/文档/单测无遗留 TODO。
- 未完成项仅为**环境/真实样本依赖**的手工验收（见上文「未验证」「归档说明」），已在 tasks.md 的「归档说明」逐条登记，不阻塞归档。
- 后续如要修存量试卷（重解析 + 结算回滚）或真表格富文本渲染，请另立 change。

