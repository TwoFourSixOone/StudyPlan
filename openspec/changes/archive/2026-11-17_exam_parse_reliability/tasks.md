# Tasks：试卷解析可靠性：上传页序、卷面题号保真、清晰度门槛

> **Change**：`2026-11-17_exam_parse_reliability`　**状态**：已实施（T7 联调待跑）

## T1 上传页序

- [x] **V16 迁移**：`src/main/resources/db/V16__exam_paper_image_page_nos.sql`（`image_page_nos JSON NULL`，含回滚注释）
- [x] `ExamPaper` 增加 `imagePageNos`（`@TableField(typeHandler = JsonStringTypeHandler.class)`）
- [x] `ExamPaperMapper`：`appendImageUrl` 同语句追加两个数组；`resetImageUrls` / `resetFailedPaperForReupload` 清空 `image_page_nos`
- [x] `pageNo` 归一（1..8，非法/缺失 → 当前张数+1，WARN 不阻断）
- [x] `ExamPaperController.uploadImage` 接收可选 `pageNo`；`ExamPaperServiceImpl.uploadImage` 传入
- [x] 新增 `ExamPaperImages.ordered(urls, pageNos)`（null/长度不一致回退；稳定排序）
- [x] 四个调用点全部改用有序列表：解析入参、`persistQuestions` 配图裁剪、详情 VO、学习记录快照
- [x] 小程序：`uploadPaperImage(paperId, filePath, pageNo)` 走 `formData`（值转字符串）；`ExamUpload.vue` 在图片加入待上传列表时确定 pageNo（列表下标），删除后重排（`renumberPageNos`），重试沿用

**验收**：4 张 1242px 图乱序并发上传后，`image_urls`/`image_page_nos` 同下标，解析入参与配图裁剪都按页序（题号由 T3 的 paperNo 保证）；存量 `page_nos=NULL` 行为不变。

## T2 清晰度门槛

- [x] `ErrorCodes` 新增 `EXAM_IMAGE_RESOLUTION_TOO_LOW(1193)`，文案由校验器覆盖为带尺寸的动态消息
- [x] `ExamUploadValidator.validateResolution`：`ImageIO` 只读图片头取宽高；`exam.parse.min-long-side=600`、`min-short-side=500`，≤0 关闭；读头失败放行 + WARN
- [x] 小程序：`api/exam.ts` 新增分辨率常量；`ExamUpload.vue` 选图后 `uni.getImageInfo` 预检，不达标 `showError` 且不加入列表
- [x] 测试：400×300 拒绝、800×1131 通过、1242×1574 通过、900×400 拒绝、阈值关闭放行、读图头失败放行

**验收**：400×300 页被 1193 拒绝，提示含实际尺寸与最低要求；800×1131 / 1242px 页正常上传（联调阈值 600/500）。

## T3 题号保真与完整性

- [x] Prompt `exam_parse_paper_v1.txt`：顶层 `paperQuestionTotal` + 每题 `paperNo`（说明跨页取题干题号、不确定省略）
- [x] Schema `exam_parse_schema.json`：两个可选属性（不加入 required）
- [x] `ExamParseImageResult.paperQuestionTotal/droppedCount`、`ParsedQuestion.paperNo`；Agent 容错解析（非法值只忽略，不丢题）
- [x] 编号：`mergeAndAttribute` **保留卷面题号**（全部题都有合法且不重复的 paperNo → `questionIndex = paperNo`；否则回退 1..N + 告警），按题号稳定排序
- [x] `ExamParseServiceImpl`：整卷路径把 total 传入 `mergeAndAttribute`；新增 `ExamParseNumbering` 工具（保留题号/回退、识别数≠总数、缺号、max 校验 → 告警文案）
- [x] 告警并入 `imageWarnings` → `remark` → 详情页 banner；降级日志 `parse_question_gap`
- [x] 小程序 `ExamDetail.vue`：题目标签显示「第 {{ questionIndex }} 题」，进度仍为识别数 `n/N`
- [x] 测试：全量 paperNo 原样落库、缺一个回退、重复回退、缺口保留（1..15,17..19）、排序稳定、缺号文案、total=0 粗检

**验收**：卷面第 17 题落库 `question_index=17`，列表按 1..19 展示并显示「第 17 题」；缺第 16 题时备注含缺口、列表保留缺口；模型不给题号时回退 1..N 并在详情页提示。

## T4 作答归属（学生答案 vs 老师批改）

- [x] Prompt `exam_parse_paper_v1.txt`：新增「三类笔迹」规则——印刷内容 / 学生手写 / 老师批改；**先看颜色（红笔默认老师批改）**；黑/蓝笔的**选择题勾选 = 学生作答**；老师勾/红笔字母只表示对错（落在正确答案上的勾视为批改）；老师分数、扣分、√/×、圈画、划线、订正、日期、时间、签名、评语一律忽略，不作 `correctAnswer` 依据
- [x] Prompt：学生原始答案被老师订正时保留学生答案；无法分辨 → `unreadable` + `unclearFields=["studentAnswer"]`；只有批改没有作答 → `blank`
- [x] Prompt OCR 第 18 条补充：OCR 文本只作**印刷内容**真值，混入的手写/批改片段不得并入题干/选项
- [x] `RedInkSuppressor`：OCR 前把红色像素刷白（无红笔原样返回、不重编码；纯视觉仍用原图），避免红笔写的正确答案/解题过程混入 OCR 文本
- [x] `ExamParseAgentImpl.toQuestion` 兜底：`answered` 但 `studentAnswer` 仅为 `√ ✓ × ✗ ✔ ✘` → `blank` + WARN
- [x] `ExamParsePromptContractTest` 增加三类笔迹 / OCR 例外断言；`ExamParseAgentImplTest` 增加纯符号兜底用例
- [ ] 真实卷核对（T7 联调）：卷首批改不进题干/学生答案；第 2 题 `studentAnswer=C`、第 3 题 `=D`（学生勾选，不是 B / 印刷公式）；无法分辨归属 → `unreadable`，学生未勾 → `blank`

**验收**：老师批改不进入题干 / 学生答案 / 正确答案依据；纯勾叉不落成学生答案；无法分辨的作答标 unreadable 而不是臆造。

## T5 静默丢题修复

- [x] `AgentOutputValidator` explanation 上限 1000 → 1500（与 schema 对齐）
- [x] `ExamParseAgentImpl.extractQuestions` 统计丢弃数并入 `warning`；`ImageTaskResult.droppedCount` → 降级日志 `parse_item_dropped`
- [x] 测试：1200 字解析保留、1600 字仍拒绝；丢弃 N 题时 warning 文案

**验收**：长解析不再静默丢题；被丢弃题数在详情页/降级日志可见。

## T6 文档

- [x] `docs/product/index.md` 规则 #4：**题号保留卷面题号并按题号排序**、页序（pageNo）、清晰度门槛（1193）、完整性告警、**作答归属（学生/老师/印刷）**、静默丢题修复
- [x] `docs/architecture/implicit-contracts.md`：新增 **#38 上传页序以客户端 pageNo 为准**、**#39 题号保真与解析缺题/丢题必须告警**、**#40 作答归属：老师批改不得进入学生答案**；当前最大编号更新为 #40、后续从 #41 起
- [x] 与本 change 的 proposal/design/tasks 交叉引用

**验收**：文档与实现一致，无"文档说做了、代码没做"。

## T7 回归与联调

- [x] `mvn test` 全绿（882+）
- [ ] V16 在干净库与存量库均 apply 成功（DDL/JSON 追加 SQL 已在临时表验证，待 Flyway 实跑）
- [ ] **重启应用**（机器级 `ZHIPU_API_KEY` 不会注入已启动的 JVM），日志确认 `GLM-OCR grounding 完成`
- [ ] 手工联调：1242px 卷乱序上传 → 第 17 题显示「第 17 题」且顺序 1..19、配图正确；500px 页被拦；缺题 banner
- [ ] 真实卷核对：卷首 `-5/-34`、`116`、日期/时间/签名不进任何题；第 2 题 `studentAnswer=C`、第 3 题 `=D`（勾选，不是 B / 印刷公式）；老师勾不改变 `correctAnswer`
- [ ] 待验证（OCR 接通后）：重传本卷，验证编题是否消失、缺口告警是否与 OCR 一致

## 归档说明（2026-11-17）

- **归档依据**：本 change 的代码、Prompt/Schema、文档与单测均已完成并通过（归档时全量 `mvn test` = **956 用例通过**，小程序 `build:mp-weixin` 通过）。
- **归档时仍未做（需真机/真实模型，未阻塞归档）**：上面 T7 的 6 条未勾选项（V16 Flyway 实跑、重启后 GLM-OCR 生效、乱序上传/低清拦截/缺题 banner 手工联调、真实卷作答归属核对、OCR 接通后的编题复验）。这些是**环境/样本依赖**的验收动作，不属于代码缺陷。
- **归档后追加的修复**（见 verify.md「评审后修订」）：pageNo 删除重排、题号回退原因细分、铅笔归属、`questionIndex`/`paperNo` 二选一、Prompt 模板 UTF-8、配图双候选框 + 二次确认收紧、答案-解析一致性兜底、题干表格 rich-text 渲染与宽表格横向滚动。
- **回归方式**：`mvn -o test`（或 `mvn -o -Dtest=ExamParse*Test,AgentOutputValidatorTest,ExamPaper*Test test`）+ `cd student-app && npm run build:mp-weixin`。
