# Design：试卷双版本解析：印刷题目版 + 学生作答版

> **Change**：`2026-11-17_exam_dual_channel_parse`

## D1 派生图（ExamInkFilter）

每页原图（已是上传原图；>总 6MB 时先按现有逻辑降采样 1600px）生成两版：

| 版本 | 规则 | 用途 |
|---|---|---|
| clean | 刷白红、蓝、绿像素（彩色笔迹） | 题目/答案/解析；OCR 也在此图跑 |
| student | 只刷白红像素（老师批改） | 学生作答（黑/蓝保留） |

- 颜色判定：
  - 红：`red≥60 && red-green≥45 && red-blue≥45`（复用 `RedInkSuppressor` 阈值，深红命中）；
  - 蓝：`blue-red≥45 && blue-green≥25`；
  - 绿：`green-red≥45 && green-blue≥25`。
- **蓝/绿的取舍（2026-11-17 评审补充）**：蓝/绿像素**无法区分学生彩色笔迹与彩色印刷内容**（蓝表格线、
  绿图表、彩色题头）。默认按笔迹处理；彩色印刷较多的卷可置 `exam.parse.dual-channel.remove-cool-ink=false`，
  clean 改为只去红 + Prompt 隔离（`ExamInkFilter.clean(bytes, mediaType, removeCoolInk)`）。
- 一个彩色像素都没刷到 → 原样返回（不重编码）；刷到 → PNG。
- **黑笔手写与印刷同色，像素级无法分离**：此时 clean/student 两图都等于原图，
  靠两次调用的 Prompt 分别"只读印刷"/"只读手写"隔离；日志标记 `separation=prompt_only`。
- 派生图落盘（默认开）：`exam-derived/{paperId}/clean-{uuid}.{ext}`、`student-{uuid}.{ext}`
  （扩展名跟随实际媒体类型：未改写时可能是 `.jpg`）；仅用于排查，不落库、不参与详情页。

## D2 clean 调用（印刷题目版）

- 新 Prompt `exam_parse_clean_v1.txt`：以整卷 Prompt 为**基线**（逐条继承其强制规则），只把"作答"相关要求替换掉：
  - 只识别**印刷内容**；所有手写（学生作答/老师批改/铅笔草稿）一律忽略；
  - `studentAnswer` 必须输出空字符串、`answerStatus` 固定 `blank`；
  - 其余字段（paperNo/paperQuestionTotal/choices/correctAnswer/explanation/kp/difficulty/figure）与整卷 Prompt 一致；
  - **必须继承**：多选判据与 `correctAnswer` 字母序连写、`figureRect`「题干出现如图必须给」、
    封面/考试说明/纯答题卡页不产生题目、模糊题仍要输出（不丢题）、阅读顺序、解析分步格式（"第 1 步："）。
    （2026-11-17 评审发现首版 clean 模板漏了这批规则，而双版本是默认路径 → 相当于让既有产品规则 #4 退化。）
  - OCR 文本只作印刷内容真值；与"以 OCR 为准"冲突时先剔除手写/批改片段。
- 新方法 `ExamParseAgent#parseCleanPaper`，复用整卷解析的重试/校验/清洗链路，仅 Prompt 路径不同。
- 输出直接复用 `ExamParseImageResult`（questions 的 studentAnswer 为空）。

## D3 student 调用（学生作答版）

- 新 Prompt `exam_parse_answers_v1.txt`：
  - 输入：student 图（1..N）+ **题目清单**（paperNo + 题干摘要 + 选项 label/text 摘要）；
  - 只提取学生作答，不识别题目、不输出题干；
  - 红笔是老师批改（正确答案/√×/过程）不是学生答案；黑/蓝/**铅笔（灰）**才是学生；
  - **答题卡**：答案可能写在与题目页不同的答题卡上，按题号/方框/填涂映射到 paperNo；
    不得因为"题目页没写"就判 blank（对齐整卷 Prompt 第 5 条）；
  - 选择题勾选/圈选/字母 → 选项标签；整页无学生作答 → 全部 `blank`；无法辨认 → `unreadable`；
  - 必须按题目清单逐题输出（含 blank），按 paperNo 对齐，禁止编造；**answers 不得为空数组**。
- 新 Schema `exam_parse_answers_schema.json`：
  `{ "answers": [ { "paperNo": 1, "studentAnswer": "C", "answerStatus": "answered|blank|unreadable", "unclearFields": [] } ], "imageWarning": "" }`
- 新 DTO：`ExamAnswerParseContext`（图片数/学科/年级/题目清单/重试提示）、`ParsedAnswer`、`ExamAnswerParseResult`。
- 新方法 `ExamParseAgent#parseStudentAnswers`，重试 2 次；单条非法丢弃并计数。

## D4 合并与降级

```
clean 成功：
  if clean 题 paperNo 缺失/越界/重复 → 对齐不可靠 → 返回 null（回退单次整卷）
  student 调用成功：
      if answers 为空 或 匹配到的 paperNo < clean 题数一半 → 覆盖不足 → 返回 null（回退单次整卷）
      按 paperNo 合并 studentAnswer/answerStatus
      未匹配的 clean 题 → blank + warning「第 X 题未在作答版中找到」
      student 多出的 paperNo → 忽略 + warning
  student 调用失败 → 返回 null → 回退单次整卷（避免整卷被判 0 分）
clean 失败/为空 → 返回 null → 现有整卷单次解析（再失败才逐图）
回退时 `DualChannelAttempt.ocr()` 把已构建的 OCR 传给整卷路径复用 → 不重复调用外部 OCR
```

- 合并发生在 `mergeAndAttribute` 之前；题号/归因/完整性逻辑复用现有实现。
- **覆盖守卫的原因**：缺失的题会落 `blank`，而 `AnswerGradingServiceImpl` 对「学生空 + 标准答案有」直接判错
  → 空 answers / 大比例缺失等价于整卷（或大面积）0 分并污染掌握度，必须回退而不是"尽力合并"。
- 双版本开关 `exam.parse.dual-channel`（默认 `true`）；关闭即完全回到现有单次整卷。

## D5 成本与配置

| 配置 | 默认 | 说明 |
|---|---|---|
| `exam.parse.dual-channel` | true | 双版本开关 |
| `exam.parse.dual-channel.save-images` | true | 是否落盘派生图 |
| `exam.parse.dual-channel.remove-cool-ink` | true | clean 版是否连蓝/绿一起去（彩色印刷较多的卷可关） |
| `exam.parse.dual-channel.max-digest-chars` | 6000 | 题目清单上限，防 prompt 爆炸 |

- 视觉调用：1 → 2 次；OCR 仍每页 1 次（在 clean 图上，文本更干净）；耗时约 +1 次视觉调用。
- 降级不额外调用：回退整卷时复用 clean 图上已跑过的 OCR（`buildOcrGrounding(..., redAlreadyRemoved=true)`
  也省掉一次红笔抑制解码）。

## D6 测试

| 层 | 用例 |
|---|---|
| `ExamInkFilterTest` | 红/蓝/绿去除、黑不动、无彩色原样返回、clean 去蓝绿而 student 保留、`remove-cool-ink=false` 只去红、非图片回退 |
| `AgentOutputValidatorTest` | answers：合法/缺字段/paperNo 越界/status 非法/**空数组失败**/unclearFields 超限 |
| `ExamParseAgentImplTest` | clean 调用 studentAnswer 强制 blank；student 调用解析 answers、重试、单条丢弃、空 answers 重试耗尽失败 |
| `ExamParseServiceDualChannelTest` | 合并（按 paperNo）/缺题告警/多出题号忽略/unreadable 归一/清单截断；**降级**：空 answers、覆盖不足、paperNo 重复、缺 paperNo、student 失败、开关关闭、回退复用 OCR |
| Prompt 契约 | clean 继承整卷规则（多选/如图/封面/不编造/分步）、answers 覆盖答题卡与铅笔、两套模板渲染无残留占位符 |
| 真实卷 | 红笔卷 clean 无红笔、student 全 blank；同色卷两图相同但结果按 paperNo 合并 |

## D7 风险

- **黑笔同色卷**：无法真正生成"干净图"，两图相同；clean 调用的纯度依赖 Prompt，可能仍混入手写 → 保留现有告警与人工核对。
- **蓝色/绿色印刷**：clean 默认把蓝/绿一起刷白，彩色印刷内容可能被删；用 `remove-cool-ink=false` 退回"只去红 + Prompt 隔离"。
- **成本翻倍**：视觉调用 ×2；开关可关。
- **题号对齐**：student 调用必须回填 paperNo；缺失/重复/覆盖不足 → 回退单次整卷。
- **派生图落盘**：只增不减，运维需清理；默认开，可关。
