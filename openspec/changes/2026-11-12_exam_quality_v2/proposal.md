# Proposal：试卷解析质量 v2（归因候选 / 多选 / 详细解析 / 题目配图）

> **Change 名称**：`2026-11-12_exam_quality_v2`
> **状态**：已实施，验证通过（见 `verify.md`），待归档

---

## 1. 背景

2026-11-12 真实试卷（paper 4，高三数学）暴露四类质量问题，且都来自产品侧的真实诉求：

| # | 现象（实测） | 根因 |
|---|--------------|------|
| 1 | Q6（复数）、Q7（集合）知识点为空、待人工归因 | 候选知识点只取「学生年级」，高考跨年级考点不在清单；另外归因 Agent 输出被 ```` ```json ```` 围栏包裹，JSON 解析失败整批降级 |
| 2 | 多选题判分/解析未支持 | 解析层把答案限定为单字母 `[A-H]`，多标签被置空；判分是字符串等值 |
| 3 | 解析只写「利用导数求最值…」这类考查总结，学生看不懂 | `exam_parse_v1.txt` 仅要求"简要解析" |
| 4 | 第 17 题几何图在详情页缺失 | 解析只产出文字，没有图形区域信息；`question_image_url` 一直为 null |

用户明确的口径：
- 归因候选 = **≤学生年级但同段**（高三含高一/高二，不含初中）
- 多选题 **少选/多选都算错**（严格集合相等）
- 题目解析必须**详细、学生看得懂**
- 配图由**模型给区域 + 服务端裁剪**

## 2. 目标

1. 归因候选口径改为「同学段 + ≤年级」，并把归因 JSON 的代码围栏清洗做成通用能力（所有 Agent 生效）。
2. 支持多选题：解析保留多标签答案、判分按标签集合严格相等、详情页显示「多选」。
3. 解析 Prompt 升级为分步详细解析，禁止"考查…/略"。
4. 含图题目：模型输出归一化 `figureRect` → 服务端裁剪 → 落 `exam_question.question_image_url` → 详情页展示、可放大；裁剪失败回退整页原图。

## 3. 范围

### 包含

- `GradeMatcher.matchesRangeAtOrBelow`（同段 ≤ 年级）+ `ExamKpCandidateServiceImpl` 改用；parse 切片 150 → 300
- `JsonPayloadExtractor`（剥代码围栏/截 JSON）+ `AgentOutputValidator`（7 处）/ `AttributionAgentImpl` / `ExamParseAgentImpl` 接入
- `ChoiceAnswer`（标签抽取/集合比对）+ `ChoiceOptions` 多选归一 + `AnswerGradingServiceImpl` 判分 + `ExamParseAgentImpl` 答案校验
- `exam_parse_v2.txt`（详细解析 + 多选 + figureRect）与 `exam_parse_schema.json`（figureRect / explanation 1500）
- `ImageCropper`、`FileStorageService.uploadBytes`（local/COS）、`ExamPaperServiceImpl.cropQuestionFigures`（事务外）
- `ExamQuestionItemVO.multiChoice / questionImageUrl`、`ExamDetail.vue` 多选标签 + 配图展示/放大
- 文档：产品规则 #4、隐性约定 #32/#33/#34、`agent-contracts.md` §3
- 单测：`GradeMatcherAtOrBelowTest`、`ChoiceAnswerTest`、`JsonPayloadExtractorTest`、`ImageCropperTest`，
  以及候选/判分/多选/契约测试更新

### 不包含

- **存量试卷不回填**（需重解析 + 回滚已结算数据，风险高，另立 change）
- 不引入部分分（多选少选算错，1 题 1 分制不变）
- 不做"多图跨页图形拼接"（`figureRect` 只在本图内）
- 不改出题/复习的精确年级过滤（产品规则 #11 不变）

## 4. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 模型给的配图坐标不准，裁到空白/题号 | 中 | 尺寸 <5% 直接丢弃 → 回退整页原图；裁剪四周 3% 留白；真实卷验证后再调 Prompt |
| 候选变多（128 → 271）导致 Prompt 变大、解析变慢 | 中 | parse 切片 300；实测单图 6.5–8s，prompt 4.3KB → 约 9KB 仍在可接受区间 |
| 多选答案被误判（模型写 "A、B"） | 低 | `ChoiceAnswer.labels` 统一抽标签；落库归一为 `AB` |
| 详细解析让输出变长，可能触顶 max_tokens 被截断 | 中 | explanation 上限 1000→1500；解析失败仍按单题丢弃（既有策略），不阻塞整卷 |
| 裁剪/上传放事务外，失败不影响解析 | 低 | 全部 try/catch + 回退整页原图；事务内只做批量 INSERT |
