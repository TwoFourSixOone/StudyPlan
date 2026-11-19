# Design：试卷解析可靠性：上传页序、卷面题号保真、清晰度门槛

> **Change**：`2026-11-17_exam_parse_reliability`

## D1 上传页序（并行上传下保持选择顺序）

### D1.1 数据模型

`exam_paper` 新增一列，与 `image_urls` **等长、同下标**：

```sql
-- V16__exam_paper_image_page_nos.sql
ALTER TABLE `exam_paper`
    ADD COLUMN `image_page_nos` JSON DEFAULT NULL
    COMMENT '与 image_urls 等长的页序数组（1..8）；NULL=旧数据，按到达顺序'
    AFTER `image_urls`;
```

- 不改 `image_urls` 语义（仍是追加顺序），避免所有 `List<String>` 读取方改造。
- 旧数据整列为 NULL → 排序工具回退到 `image_urls` 原顺序，行为不变。

### D1.2 上传协议

`POST /api/student/exam/papers/{paperId}/images` 新增**可选** form 字段 `pageNo`（1..8，选择顺序的 1-based 下标）：

- 前端 `uploadPaperImage(paperId, filePath, pageNo)` → `uploadWithAuth(url, filePath, 'file', { pageNo })`
  （`uni.uploadFile` 的 `formData`，值转字符串）。
- `pageNo` 缺省（旧客户端）时，服务端取 **当前已上传张数 + 1**（`imageUrls.size() + 1`，在既有
  `synchronized(uploadLock(paperId))` 段内读取，不额外加锁）。
- 缺省/越界（<1 或 >8）一律按「当前张数+1」处理并 WARN，**不阻断上传**——pageNo 只决定排序，
  不能因为新参数把正常上传打死。

### D1.3 追加 SQL（一条语句追加两个数组）

```sql
UPDATE exam_paper
SET image_urls      = JSON_ARRAY_APPEND(image_urls, '$', #{imageUrl}),
    image_page_nos  = JSON_ARRAY_APPEND(COALESCE(image_page_nos, JSON_ARRAY()), '$', #{pageNo}),
    update_time     = NOW()
WHERE id = #{paperId} AND tenant_id = #{tenantId} AND status = 'uploading'
  AND JSON_LENGTH(image_urls) < #{maxImages}
```

- `pageNo` 在 Java 侧归一为**非空 int**（含旧客户端回退值），避免 SQL 里对 NULL 做 `JSON_ARRAY_APPEND`（结果为 NULL）。
- 两列同语句追加，长度天然一致；失败整体回滚（单语句原子）。
- 8 张自动触发解析的时序不变：第 8 张追加成功时，8 个 pageNo 都已落库，解析时按序重建。

### D1.4 排序与调用点

新增工具 `ExamPaperImages.ordered(List<String> imageUrls, List<Integer> pageNos)`：

| 输入 | 行为 |
|---|---|
| pageNos 为 null / 长度不一致 / 含 null | 返回原列表（到达顺序） |
| 正常 | 按下标稳定排序（pageNo 相同的保持到达顺序） |

必须使用**同一份有序列表**的四个调用点（漏一个就会错位）：

1. `processExamAnalysis`：解析入参 —— 页序影响模型阅读顺序、跨页题合并与答题卡匹配（题号已由 paperNo 决定，但页序仍影响上下文）；
2. `persistQuestions`：按 `sourceImageIndex` 取原图裁剪配图 —— 必须与解析时的图序一致，否则配图裁到别的页（改为入参传入有序列表，不再自行 `fromJson`）；
3. `getPaperDetail`：`imageUrls` 返回顺序（缩略图/回看）；
4. `buildAnalysisRecord`：`exam_analysis_result.image_urls` 快照顺序。

另外两处重置必须同时清空页序：`resetImageUrls`（30 分钟超期复用）、`resetFailedPaperForReupload`（失败当天重传）→
`image_page_nos = JSON_ARRAY()`。

### D1.5 边界

- `pageNo` = 该图**上传时在待上传列表中的 1-based 下标**：加入列表时赋值为「已有张数 + 本次已接受张数 + 1」，
  上传失败重试沿用该次的值；**删除待上传图片后必须对剩余项重排 1..n**（`ExamUpload.vue#renumberPageNos`）——
  否则补选的图会拿到重复 pageNo，服务端稳定排序后解析页序与缩略图顺序不一致（2026-11-17 评审发现）。
- 已上传（`status=done`）的图片不允许删除，因此重排只发生在尚未上传的阶段，不会与"重试沿用原值"冲突。
- 同一 pageNo 重复（仅可能在旧客户端/多实例并发补齐时出现）：稳定排序保留到达顺序，不报错。
- 部分图上传失败后重试：重试沿用该图 `pageNo`，追加到数组尾部，排序仍正确。
- 多实例并发：现有 `appendImageUrl` 的 `JSON_LENGTH` 守卫仍在；页序是展示/解析顺序，不是安全边界。

## D2 清晰度门槛（入口拦截低清图）

### D2.1 校验实现

`ExamUploadValidator.validateSingleFile` 末尾新增 `validateResolution(file)`：

- 用 `ImageIO.getImageReaders(ImageIO.createImageInputStream(file.getInputStream()))` 只读**图片头**取宽高，
  不解码整图（8 张 × 9.5MB 不能全量 decode）。
- 阈值（配置项，代码内默认；不改 application.yml）：

| 配置 | 默认 | 含义 |
|---|---|---|
| `exam.parse.min-long-side` | 600（联调期；正式建议 1000） | 长边下限（px） |
| `exam.parse.min-short-side` | 500（联调期；正式建议 700） | 短边下限（px） |

- 两者都 ≤ 0 → 关闭校验（回滚开关）。
- 不合格 → 新增错误码 **1193**，消息带实际尺寸：
  `图片分辨率过低（400×300），请上传原图或重新拍摄（长边≥600px、短边≥500px）`。
- 读不出尺寸（非标准 JPEG/构造图）→ **放行 + WARN**，不新增阻断点：魔数已挡住明显伪造，
  读头失败只说明格式不标准，交给解析阶段暴露，避免把存量测试夹具/第三方图误杀。

依据：本次 500×684 / 500×657 的页全部读错/编题；1242px 的页识别正常。另实测 GLM-OCR 对同一张
500×684 页也会把 `√7` 读成 `√2`、`(1+5i)i` 读成 `(1+50)`（只能保住题目结构）——**低清图两条通道都救不了**，
必须在入口拦。新样本 800×1131（长边 1131、短边 800）为清晰扫描页，阈值取 **600/500** 可放行；
上线后用真实样本再调（阈值改动只需改配置，不新增 change）。

### D2.2 小程序预检

- `api/exam.ts` 新增 `EXAM_MIN_LONG_SIDE = 600`、`EXAM_MIN_SHORT_SIDE = 500`（与后端默认值对齐）。
- `ExamUpload.vue` 选图/压缩后，用 `uni.getImageInfo` 取宽高；低于阈值 → `showError` 并**不加入待上传列表**
  （省流量、早提示）。后端 1193 仍是权威兜底（旧客户端）。
- 压缩策略不变（`compressImage` 只压 quality，不 resize），尺寸即原图尺寸。

## D3 卷面题号保真与排序

### D3.1 模型输出扩展

`exam_parse_paper_v1.txt` 输出要求新增：

- 顶层 `paperQuestionTotal`：整卷题目总数（整数；不确定给 0 或省略）；
- 每题 `paperNo`：**卷面印刷题号**（跨页合并取题干所在题号；无法辨认时省略）。

`exam_parse_schema.json` 增加两个可选属性（`paperQuestionTotal: integer 0..60`、`paperNo: integer 1..60`）。

> 新字段**不参与** `checkExamParseItem` 的必填/丢弃判定：值非法只忽略该字段，绝不因此丢整题。

### D3.2 编号算法（保留卷面题号，不再按图序重排）

`mergeAndAttribute` 把「按图序重排 1..N」改为：

```
usable = 每题都有 paperNo ∈ [1,60] 且互不重复，且（total<=0 或 max(paperNo) <= total）
if (usable):
    questionIndex = paperNo                  // 保留卷面题号（可能不连续，如缺 16）
else:
    questionIndex = 按（图序 → 图内顺序）1..N    // 回退旧行为
    warnings.add("未能识别卷面题号，已按图片顺序编号")
按 questionIndex 稳定排序后落库              // persistQuestions 已按题号升序
```

- **不补号**：缺第 16 题就保留缺口（列表 1..15,17..19），不把 17 改成 16。
- 查询方本身就是 `ORDER BY question_index`（[ExamPaperServiceImpl.java:1093](src/main/java/com/jiang/service/impl/ExamPaperServiceImpl.java#L1093)），排序不需要改 SQL。
- 详情页按题号顺序展示；题目标签新增「第 {{ questionIndex }} 题」，顶部进度仍是识别数 `n/N`
  （缺号时两者会不一致，正是缺题的可视化信号）。
- 重复题号（模型把一题拆成两个相同号、或 18 误读成 16）→ 视为 unusable，回退 1..N + 告警。
- 题号同时被用作配图 map 与归因批次的 key（`cropQuestionFigures`、`attributeQuestions`），
  `usable` 的唯一性校验保证不会互相覆盖。

### D3.3 完整性校验（缺题必须可见）

- `paperQuestionTotal > 0` 且 `识别数 != total` → 告警；usable 时列出缺号（缺第 16 题）。
- `paperQuestionTotal` 缺失时：`max(paperNo) != 识别数` → 告警「题号不连续（最大 19，共 18 题），请核对」。
- 回退场景（题号缺失/重复）同样告警，写明回退原因。
- 告警沿既有链路展示：`ExamParseOutcome.imageWarnings` → `exam_paper.remark`（≤500 字）→
  `ExamPaperDetailVO.imageWarning` → 详情页已有黄色 banner（[ExamDetail.vue:33](student-app/src/pages/exam-detail/ExamDetail.vue#L33)）。
- 降级日志 `parse_question_gap`（只记 paperId/total/missing/duplicates，不记题面）。
- 仍落库已识别题目；缺题不阻断解析（与「全部题目落库、可回看」规则一致）。

### D3.4 为什么不做"编题检测"

题干与图片/OCR 的一致性校验需要真实 OCR 文本调阈值；本机无 `ZHIPU_API_KEY`，无样本可调。
本 change 先用「清晰度门槛 + OCR（已有能力）+ 缺题告警」封住已知路径；编题检测等 OCR 接通后另立 change。

## D4 作答归属（学生答案 vs 老师批改）

### D4.1 卷面事实

- 本卷选择题的作答方式是**在选项上打勾**（不是写字母）；老师批改也**只打勾/打叉**，不写正确答案。
  第 2 题：学生勾 C、老师也在 C 上打勾（答对，两个勾叠在同一选项；`C. 5` 是印刷选项文本，不是手写订正）；
  第 3 题同理（`D. 2√2` 是印刷选项，学生/老师各一个勾）。
- 老师批改**不是红笔**（4 页均为灰度图，红色像素占比 0），与学生笔迹同色；只能靠位置/内容/笔迹/叠压判断。
- 当前解析没有"勾选 = 作答"的规则：第 2 题学生勾了 C，库里记成 B；第 3 题学生勾了 D，库里记成印刷公式 `a²+b²=c²`。
- OCR 文本同样会混入手写：实测第 1 页 OCR 把学生过程 `= -5+i` 拼进第 1 题题干、把学生计算混进第 5 题选项。
  所以「以 OCR 为准」必须限定为**印刷内容**。
- **红笔批改卷样本（2026-11-17 追加）**：800×1131 的试卷扫描件上，题号左侧红笔字母（C/A/C/A…）是**正确答案**，
  红笔解题过程、选项上的红 √/× 是老师批改，整页没有学生黑/蓝笔作答 → 所有题应为 `blank`；
  红笔字母绝不能写进 `studentAnswer`，也不能直接当 `correctAnswer`。

### D4.2 Prompt 规则（`exam_parse_paper_v1.txt` 新增）

卷面有三类笔迹 + 一种特殊作答（勾选），归属必须分开，**先看颜色**：

1. **印刷内容**（题干/选项/表格/图形）→ 只进题干；
2. **学生作答**（**黑/蓝色笔**的勾选/圈选/划线，或作答位置上的答案、解题过程、草稿）→ 只进 `studentAnswer`；
3. **老师批改**（**红笔**或与题面/主体笔迹明显不同的批改色；卷首/得分栏/题号旁/页边：分数、扣分、√/×、圈画、划线、
   日期、时间、签名、评语）→ **一律忽略**：不进题干、不进 `studentAnswer`、**不作为 `correctAnswer` 的依据**。

**红笔规则（新增样本关键）**：

- 红笔写的**选项字母**（常见于题号左侧/页边）、正确答案、解题过程、分数、√/× 都是老师批改，不是学生答案。
- 整页只有红笔批改、没有学生黑/蓝笔作答 → 所有题 `answerStatus=blank`，**禁止用红笔答案填充**。
- 选项上的红 √/× 只表示对错，不代表学生选了该选项。

**勾选规则（同色卷面关键）**：

- 选择题以"在选项上打勾/圈选/划线"作答：学生勾 → 映射为选项标签写入 `studentAnswer`（第 2 题→C、第 3 题→D）。
- 学生答对时，老师会在**同一选项**再打一个勾（两个勾叠加/重叠）——`studentAnswer` 仍是该选项，**不能因为"老师也勾了"就把整个勾选当批改而丢掉学生答案**。
- 学生答错时会出现两个勾在**不同选项**：先按题目算出 `correctAnswer`，把落在 `correctAnswer` 上的勾视为老师批改，另一个视为学生作答；算不出正确答案或两勾无法对应 → `answerStatus=unreadable`。
- 只有一个勾且无法确认归属：勾在作答区且与其他题作答笔迹一致 → 学生；否则 `unreadable`，不猜。
- 学生未勾选（空白）而老师勾了正确选项 → `blank`，**不能**把老师勾记成学生答案。
- 老师勾若在题号旁/与分数同笔/压在选项外 → 明确是批改。

判定依据（无颜色线索时）：

| 线索 | 批改 | 学生作答 |
|---|---|---|
| 位置 | 卷首/页边/得分栏/题号旁 | 选项字母/括号/填空横线/解答空白处 |
| 内容 | 分数/扣分算式/日期/时间/签名/"阅"/评语/纯对错符号 | 勾选/圈选/划线、具体选项、数值、解题过程 |
| 笔迹 | 常更粗/更草、与主体不同 | 与同卷其他作答笔迹一致 |

冲突处理：

- 学生原始答案被老师划掉/订正：能辨认 → 保留**学生原始答案**；不能 → `answerStatus=unreadable`，
  `unclearFields=["studentAnswer"]`；
- 整题只有老师批改、学生没写/没勾 → `blank`；
- 老师打 √/× 只表示对错，**不代表学生选了该选项**；老师圈出的答案**不等于**学生答案；
- 禁止用老师订正"补"学生答案。

OCR 例外（第 18 条补充）：OCR 文本只作**印刷内容**的真值；OCR 里混入的手写/批改片段
不得并入题干或选项，学生作答仍只从图片读。

**OCR 前红笔抑制（2026-11-17）**：`RedInkSuppressor` 在送 GLM-OCR 前把红色像素刷白
（无红笔原样返回、不重编码），避免红笔写的正确答案/解题过程混入 OCR 文本；纯视觉解析仍用原图。

### D4.3 服务端兜底（轻量）

`ExamParseAgentImpl.toQuestion`：`answerStatus=answered` 但 `studentAnswer` 仅为对错符号
（`√ ✓ × ✗ ✔ ✘`）→ 视为老师批改，置 `blank` + 空串并记 WARN。
只处理**纯符号**，不猜数字/长文本，避免误伤合法答案。

### D4.4 验证

- Prompt 契约测试：断言三类笔迹 + 勾选规则、老师批改忽略、OCR 仅印刷、`unreadable` 兜底。
- 真实卷人工核对：卷首批改不进任何题；第 2 题 `studentAnswer=C`、第 3 题 `studentAnswer=D`（勾选，
  不是 B / 印刷公式）；老师勾只表示对错，学生未勾选的题不能被记成已作答；老师 √/× 不改变 `correctAnswer`；
  解答题保留学生过程但不得夹带老师批改。
- 模型行为无法单测，最终在 T7 联调用真实卷回归（OCR 接通后）。

### D4.5 不包含

- 不把老师批改当作判分真值（可能批错、也可能只是勾选）；
- 不做"老师批改 vs 模型判分"差异告警（另立 change）。

## D5 静默丢题修复

1. `explanation` 上限不一致：schema 允许 1500 字，`checkExamParseItem` 卡 1000 字 → 超限整题被静默丢弃
   （[AgentOutputValidator.java:313](src/main/java/com/jiang/common/util/AgentOutputValidator.java#L313)）。
   统一为 **1500**。
2. `ExamParseAgentImpl.extractQuestions` 统计被丢弃题数，拼进 `warning`：
   `已丢弃 N 道输出不合法的题目`；`ExamParseServiceImpl` 会把它并入 imageWarnings → remark → 详情页。
   同时写降级日志 `parse_item_dropped`（只记数量与原因，不记题面）。

## D6 兼容与降级

| 场景 | 行为 |
|---|---|
| 旧数据 `image_page_nos=NULL` | 按 `image_urls` 到达顺序（与现在完全一致） |
| 旧客户端不传 pageNo | 服务端按「当前张数+1」补，等价于到达顺序 |
| 存量 `question_index=1..N` | 保持原编号与顺序，不回填卷面题号 |
| 模型不返回 `paperNo` | 回退按图片顺序 1..N + 告警（不丢题、不误改题号） |
| 模型返回的 paperNo 重复/越界 | 视为不可用 → 回退 1..N + 告警 |
| 模型返回的 paperNo 个别误读（6→9） | 按题号排序，顺序可能错位；无自动检测，详情页逐题核对 |
| 分辨率校验误伤 | `exam.parse.min-long-side/min-short-side=0` 关闭；阈值可调 |
| 上传 SQL 失败 | 单语句原子，返回 1186/1189，行为同现状 |

## D7 测试计划

| 层 | 用例 |
|---|---|
| `ExamUploadValidatorTest` | 400×300 → 1193（消息含尺寸）；800×1131 清晰扫描页通过；1242×1574 通过；900×400 → 1193；阈值置 0 → 关闭；读头失败 → 放行 + WARN |
| `ExamPaperImagesTest` | 乱序 pageNo 排序；null/长度不一致回退；重复 pageNo 稳定；空列表 |
| `ExamParseNumberingTest` | 全量 paperNo → 原样落库；缺一个 → 回退 1..N + 告警；重复 → 回退；缺口保留（1..15,17..19）；排序稳定 |
| `ExamParseNumberingTest` | 保留题号/缺口保留/回退/重复/越界/总数未知 max 校验；缺号文案 |
| `AgentOutputValidatorTest` | explanation 1200 字通过、1600 字拒绝；非法 paperNo 不丢题 |
| `ExamParseAgentImplTest` | 解析 paperNo/paperQuestionTotal；丢弃计数进 warning；纯 √/× 的 studentAnswer → blank；正常选项标签不受影响 |
| `ExamParsePromptContractTest` | 三类笔迹规则、老师批改忽略、OCR 仅印刷内容、`unreadable` 兜底文案存在 |
| 手工联调 | 4 张 1242px 乱序并发上传 → 第 17 题显示「第 17 题」、顺序 1..19；配图裁在正确页；500px 页被 1193 拦；缺题时详情页 banner |
| 真实卷核对 | 卷首批改（-5/-34、116、日期时间签名）不进任何题干/学生答案；第 2 题 `studentAnswer=C`；老师 √/× 不改变 `correctAnswer` |
| 回归 | `mvn test` 全绿；V16 迁移在干净库 + 存量库均可 apply |

## D8 风险与取舍

- **低清拦截可能挡住合法上传**：阈值可配置；文案明确（尺寸 + 最低要求），引导重拍/传原图；上线后按真实样本调。
- **pageNo 由客户端提供**：只影响顺序，不影响权限/数据；非法值按缺省处理。
- **模型 paperNo 不稳定**：缺失/重复/越界 → 回退 1..N + 告警；个别误读（6→9）不会被自动发现，按题号排序可能错位 → 详情页显示「第 X 题」便于人工核对。
- **题号不连续**：缺题时列表保留缺口，顶部进度 `n/N` 与「第 X 题」会不一致 —— 这是有意的（提示缺题），不是 bug。
- **老师批改同色同笔迹**：无颜色线索，模型可能把批改当答案（或反之）；只能靠位置/内容/笔迹 + 人工核对兜底，无法保证 100%。
- **勾选归属依赖正确答案**：学生答错时用"老师勾落在正确答案上"区分两个勾，若模型算错 `correctAnswer` 会误判学生答案；因此两勾无法对应时必须落 `unreadable`，不可猜。
- **OCR 已配置但需重启**：`ZHIPU_API_KEY` 是机器级变量，重启前运行的进程读不到；联调前必须重启并确认日志出现 `GLM-OCR grounding 完成`。
- **DDL 触及受保护区**（`src/main/resources/db/`）：本 change 明确列出，需人工审批后 apply。
