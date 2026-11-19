# Tasks：试卷双版本解析：印刷题目版 + 学生作答版

> **Change**：`2026-11-17_exam_dual_channel_parse`　**状态**：已实施（联调待跑）

## T1 派生图

- [x] `ExamInkFilter`：clean（去红/蓝/绿）、student（只去红）；无彩色像素原样返回；PNG 输出
- [x] 颜色判定单测：红/蓝/绿命中，黑/白/灰/暖纸不命中
- [x] Service 落盘派生图（`exam-derived/{paperId}/`，可关）

## T2 clean 调用

- [x] `exam_parse_clean_v1.txt`：只读印刷、忽略一切手写、studentAnswer 空 + blank
- [x] `ExamParseAgent#parseCleanPaper`（复用整卷重试/校验链路，仅 Prompt 不同；结果强制 blank）
- [x] Prompt 契约测试：clean 只读印刷

## T3 student 调用

- [x] `exam_parse_answers_v1.txt` + `exam_parse_answers_schema.json`
- [x] DTO：`ExamAnswerParseContext` / `ParsedAnswer` / `ExamAnswerParseResult`
- [x] `ExamParseAgent#parseStudentAnswers`（重试 2 次、单条丢弃计数）
- [x] `AgentOutputValidator#validateExamAnswers`
- [x] 单测：合法/缺字段/paperNo 越界/status 非法；解析/重试/丢弃

## T4 编排与合并

- [x] `ExamParseServiceImpl` 双版本流程：派生图 → OCR(clean) → clean 调用 → 题目清单 → student 调用 → 按 paperNo 合并
- [x] 告警：未匹配题 blank、student 多出题忽略、student 失败回退单次
- [x] 降级：clean 失败回退单次整卷；缺 paperNo / **paperNo 重复**回退单次；student 失败回退单次（避免整卷 0 分）；
      **作答覆盖率不足（空 answers 或匹配不足一半）也回退单次整卷**
- [x] 回退时复用已构建的 OCR（design D5「降级不额外调用」）
- [x] 开关 `exam.parse.dual-channel`（默认开）、`remove-cool-ink`（默认开）
- [x] 测试：`ExamParseServiceDualChannelTest` 覆盖合并、缺 paperNo 回退、paperNo 重复回退、空 answers 回退、
      覆盖不足回退、student 失败回退、开关关闭、回退复用 OCR

## T5 文档

- [x] 产品规则 #4：双版本（clean/student）、红笔规则、成本/开关、蓝绿取舍、覆盖守卫
- [x] 隐性约定 #41：双版本解析（派生图 + 两次调用 + paperNo 合并 + 对齐/覆盖前提 + 回退复用 OCR）
- [x] 隐性约定 #40：作答归属补铅笔（灰）判据；OCR 冲突优先级（先剔手写再以 OCR 为准）
- [x] 当前编号更新（#41 起 -> #42）

## T6 回归与联调

- [x] `mvn test` 全绿（901）
- [ ] 红笔卷：clean 图无红笔、student 图无红笔；clean 出题、student 全 blank
- [ ] 同色卷：两图相同、两次调用；按 paperNo 合并
- [ ] 派生图落盘可核对；关闭开关时行为与旧版一致

**验收**：红笔卷不把老师答案当学生答案；同色卷题目与作答分路抽取；clean 失败自动回退；`mvn test` 全绿。

## 归档说明（2026-11-17）

- **归档依据**：代码、Prompt/Schema、文档与单测均已完成并通过（归档时全量 `mvn test` = **956 用例通过**，小程序 `build:mp-weixin` 通过）；T4「合并/降级测试」已补齐（`ExamParseServiceDualChannelTest` 12 例）。
- **归档时仍未做（需真实模型样本，未阻塞归档）**：上面 T6 的 3 条未勾选项（红笔卷 clean/student 双调用、同色卷按 paperNo 合并、派生图落盘与开关关闭等价性）。
- **归档后追加的修复**（见 verify.md「评审后修订」）：空 answers 判失败 + 作答覆盖率守卫、paperNo 唯一性前提、clean Prompt 继承整卷强制规则、answers 覆盖答题卡与铅笔、`remove-cool-ink` 开关、回退复用 OCR（不重复调用）、派生图扩展名、clean/answers 渲染无残留占位符测试。
- **关系说明**：本 change 依赖并部分修订了 `2026-11-17_exam_parse_reliability`（题号/页序/作答归属），两者同批归档。
