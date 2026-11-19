# Proposal：试卷双版本解析：印刷题目版 + 学生作答版

> **Change**：`2026-11-17_exam_dual_channel_parse`　**状态**：已实施（2026-11-17，真实卷双调用联调待跑）

## 背景

单次整卷调用同时做「题目/正确答案/解析」和「学生作答」，两类内容互相污染：

- 老师红笔写的正确答案、解题过程、√/× 会被读成学生答案或题干（实测手改卷 OCR 文本出现 `C. 1. …`）；
- 同色黑笔卷面，老师勾与学生勾只能靠位置/叠压猜；
- 上一版 `2026-11-17_exam_parse_reliability` 用 Prompt 分流 + OCR 红笔抑制，仍是"同一份输入做两件事"。

产品要求：把上传的试卷变成**两个版本**——

1. **clean（印刷题目版）**：只有印刷题目，用于提取题干/选项/正确答案/解析；
2. **student（学生作答版）**：去掉老师批改、保留学生作答，用于提取学生答案。

## 方案

```
每页原图
 ├─ clean 图：去红/蓝/绿彩色笔迹（黑笔手写无法像素分离 → 与 student 同图，靠 Prompt 隔离）
 │    └─ OCR 在 clean 图上跑 → 视觉调用 A（parseCleanPaper）
 │         只出 题目/正确答案/解析，studentAnswer 固定空、answerStatus=blank
 └─ student 图：只去红（老师批改），保留学生黑/蓝笔迹
      └─ 视觉调用 B（parseStudentAnswers）
           输入题目清单（paperNo + 题干 + 选项），只出 paperNo → studentAnswer / status
合并：按 paperNo 对齐；对不上/缺题 → blank/unreadable + 告警
降级：clean 失败/缺 paperNo/paperNo 重复 → 现有单次整卷解析；
      student 失败/answers 为空/覆盖不足一半 → 也回退单次整卷（避免整卷判 0 分）
      回退复用已构建的 OCR（降级不额外调用）
```

> **评审后修订（2026-11-17）**：新增"作答覆盖率守卫"（空 answers 直接判失败）、paperNo 唯一性前提、
> clean 模板继承整卷 Prompt 强制规则、answers 覆盖答题卡与铅笔、`remove-cool-ink` 开关与派生图扩展名修正。

## 范围

- **图像**：`ExamInkFilter`（红/蓝/绿过滤，clean/student 两版）；派生图落盘 `uploads/exam-derived/{paperId}/`（可关）
- **Prompt**：`exam_parse_clean_v1.txt`、`exam_parse_answers_v1.txt`、`exam_parse_answers_schema.json`
- **Agent**：`parseCleanPaper`、`parseStudentAnswers`（各最多重试 2 次）；`AgentOutputValidator` 增加 answers 校验
- **Service**：`ExamParseServiceImpl` 双版本编排、按 paperNo 合并、告警、开关 `exam.parse.dual-channel`（默认开）
- **文档**：产品规则 #4、隐性约定 #41
- **测试**：`ExamInkFilterTest`、answers 解析/合并用例、Prompt 契约、失败降级

## 不包含

- 黑笔手写的像素级擦除（没有图像编辑模型；黑笔卷两图相同，靠 Prompt 隔离）
- 存量试卷回填
- 判分口径、掌握度公式、归因规则
- PDF 输入

## 验收

- 红笔批改卷：clean 图无红笔字母/解题过程；student 图无红笔；clean 出题目/答案/解析，student 全 blank（整页无学生作答）
- 同色黑笔卷：两图相同，但 clean 只读印刷、student 只读手写；按 paperNo 合并
- `student` 调用失败 → 回退单次整卷（题目仍能落库，避免整卷被判 0 分）
- `clean` 调用失败 → 回退现有单次整卷解析
- `mvn test` 全绿
