# Verify：试卷双版本解析：印刷题目版 + 学生作答版

> **Change**：`2026-11-17_exam_dual_channel_parse`　**验证**：2026-11-17（本地单测；真实卷双调用待联调）

## 需求 → 实现

| 需求 | 实现 |
|---|---|
| 上传试卷变成两个版本 | 每页派生 **clean**（默认去红/蓝/绿，可 `remove-cool-ink=false` 只去红）与 **student**（只去红）两张图；派生图落盘 `exam-derived/{paperId}/`（可关，扩展名跟随实际媒体类型） |
| clean 版只出题目/答案/解析 | 新 Prompt `exam_parse_clean_v1.txt` + `ExamParseAgent#parseCleanPaper`；结果强制 `studentAnswer=""`、`answerStatus=blank` |
| student 版只出学生答案 | 新 Prompt `exam_parse_answers_v1.txt` + `exam_parse_answers_schema.json` + `parseStudentAnswers`；只输出 `paperNo → studentAnswer/status` |
| 两版对齐 | 按 paperNo 合并；缺题 blank + 告警；多出题号忽略；**clean 的 paperNo 必须唯一**（缺失/重复 → 回退单次整卷，避免同一份作答贴到两道题） |
| 降级 | clean 失败/缺 paperNo/paperNo 重复 → 回退单次整卷；**空 answers / 覆盖不足一半 → 也回退单次整卷**（避免整卷 blank 被判 0 分）；回退复用已构建的 OCR，不重复调用外部 OCR |
| 开关/成本 | `exam.parse.dual-channel`（默认开）、`remove-cool-ink`（默认开，关掉则 clean 只去红）、`save-images` 可关；视觉调用 ×2；OCR 每页仍 1 次 |
| 黑笔同色卷 | 两图相同（无彩色可分离），两次 Prompt 分别只读印刷/只读手写（`separation=prompt_only`） |

## 测试

```
mvn test → Tests run: 956, Failures: 0, Errors: 0, Skipped: 5 → BUILD SUCCESS（归档时 2026-11-17 复跑）
```

| 用例 | 覆盖 |
|---|---|
| `ExamInkFilterTest`（6） | clean 去红/蓝/绿、student 只去红、黑不动、无彩色原样返回、颜色阈值、**remove-cool-ink=false 只去红**、非图片回退 |
| `AgentOutputValidatorTest`（20） | 作答版：合法/缺字段/paperNo 越界/status 非法/answers 非数组/**空数组失败**/unclearFields 超限 |
| `ExamParseAgentImplTest`（19） | clean 强制 blank、作答版按 paperNo 解析、非法作答丢弃 + warning、作答版重试、空 answers 重试耗尽失败 |
| `ExamParseServiceDualChannelTest`（12） | 按 paperNo 合并、缺题 blank + 告警、多出题号忽略、unreadable/blank 归一、清单截断；**降级链路**：空 answers、覆盖不足、paperNo 重复、缺 paperNo、student 失败、开关关闭、回退复用 OCR |
| `ExamParsePromptContractTest`（8） | clean 继承整卷强制规则（多选/如图/figureRect/封面/不编造/分步）、answers 覆盖答题卡与铅笔、clean/answers 渲染无残留占位符 |

## 未验证（需真实模型）

| 项 | 说明 |
|---|---|
| 红笔卷双调用 | clean 图无红笔字母、student 图无红笔；clean 出题目/答案/解析，student 全 blank |
| 同色黑笔卷 | 两图相同、两次调用；clean 不混入手写、student 能读出勾选 |
| 派生图落盘 | `uploads/exam-derived/{paperId}/` 可核对 clean/student 两版 |
| 降级 | clean/student 失败时回退单次整卷，题号/判分不产生 0 分脏数据 |
| 开关关闭 | `exam.parse.dual-channel=false` 行为与旧版一致 |

## 已知取舍

- 黑笔手写与印刷同色，无法像素级分离；clean 纯度依赖 Prompt，可能仍混入手写（保留告警/人工核对）。
- 视觉调用翻倍（clean + student）；OCR 仍每页 1 次（在 clean 图上），回退时不重复调用。
- 蓝/绿像素无法区分学生彩色笔迹与彩色印刷内容：默认按笔迹处理，彩色印刷较多时用 `remove-cool-ink=false`。
- 派生图只增不减，需要运维清理。

## 评审后修订（2026-11-17，独立评审 → 修复）

| 级别 | 问题 | 修复 |
|---|---|---|
| high | `answers` 为空数组被当成功 → 全卷 `blank` → 判错/0 分（与"回退避免 0 分"直接矛盾） | 校验器把空数组判失败；`tryParseDualChannel` 增加覆盖率守卫（匹配不足一半即回退）；`dual_answer_coverage_low` 降级日志 |
| high | clean 模板丢失整卷 Prompt 的强制规则（多选判据/字母序、`figureRect`「如图必须给」、封面与纯答题卡页不产题、模糊题仍输出、阅读顺序、分步解析） | clean 模板补齐并作为 Prompt 契约断言 |
| high | answers 模板没写答题卡（product 规则 #4 明确支持答题卡页） | answers 模板补答题卡映射规则；对齐整卷 Prompt 第 5 条 |
| medium | clean 的 paperNo 重复未拦 → 同一份作答贴到两道题 | `isPaperNoAlignable`（非空/1..60/唯一），不满足回退单次整卷 |
| medium | 降级路径重复跑 OCR（与 D5「降级不额外调用」矛盾） | `DualChannelAttempt` 回带 OCR，整卷回退直接复用；clean 图路径跳过红笔抑制解码 |
| medium | clean 版把蓝/绿一律刷白 → 彩色印刷内容被删且无开关/无声明 | 新增 `exam.parse.dual-channel.remove-cool-ink` 开关 + 文档声明取舍 |
| low | 派生图扩展名恒 `.png`（未改写时内容是 JPEG）、`buildCleanPrompt` 死代码、`unclearFields` 未按 schema 校验、questionIndex 仍必填（残余静默丢题路径） | 按媒体类型选扩展名；删除死代码并把 `buildWholePrompt` 开放给契约测试；补 `unclearFields` 校验与截断；`questionIndex` 改为"与 paperNo 至少给一个" |

## 归档（2026-11-17）

- 按 openspec/changes/README.md 约定归档到 openspec/changes/archive/（目录名不变）。
- 归档时状态：全量 `mvn -o test` **956 用例通过**（跳过 5）、`npm run build:mp-weixin` 通过；代码/文档/单测无遗留 TODO。
- 未完成项仅为**环境/真实样本依赖**的手工验收（见上文「未验证」「归档说明」），已在 tasks.md 的「归档说明」逐条登记，不阻塞归档。
- 后续如要修存量试卷（重解析 + 结算回滚）或真表格富文本渲染，请另立 change。

