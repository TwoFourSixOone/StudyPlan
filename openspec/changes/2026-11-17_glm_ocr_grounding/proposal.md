# Proposal：GLM-OCR 文本 grounding（带降级）

> **Change**：`2026-11-17_glm_ocr_grounding`　**状态**：已实施，验证通过

## 背景

低分辨率/暗光照片下，整卷视觉解析会把 √7 读成 √3、编造卷面没有的题、漏掉跨页表格。
用户已开通智谱 GLM-OCR（`glm-ocr` 布局解析），希望用它提高文本准确度。

## 方案

```
每张图 → GLM-OCR layout_parsing
   ├─ md_results（Markdown，表格 HTML、公式文本）→ 注入整卷解析 Prompt 的【OCR 文本】
   └─ layout_details(label=image) 的 bbox → 吸附题目 figureRect（更准的配图裁剪）
视觉模型 → 题干/选项/表格以 OCR 为准；学生手写仍从图片读；图形用吸附后的 bbox
```

## 降级策略（本 change 的重点）

| 场景 | 行为 |
|---|---|
| 未配置 Key / 开关关闭 | OCR 通道完全不启用，纯视觉解析（不报错） |
| 单页超时 / 非 200 / 响应非法 | 跳过该页，其余页照常用 |
| OCR 总预算（默认 90s）到点 | 停止剩余页，用已拿到文本继续 |
| 全部页失败 | 写降级日志 `ocr_grounding_skip`，纯视觉解析 |
| 部分页失败 | 写 `ocr_grounding_partial`，用可用文本继续 |
| 单页文本过长 | 截断到 6000 字注入 |
| Key 配置 | 环境变量 `ZHIPU_API_KEY` 或 `exam.parse.ocr.api-key`（不入库） |

## 范围

- `ZhipuOcrClient`（JDK HttpClient，超时 30s、两次请求格式兼容 data URI/裸 base64）
- `OcrPageResult`、`OcrFigureSnapper`、`ExamParsePaperContext.ocrText`
- Prompt：`{ocrText}` 区块 + OCR 使用规则（以 OCR 为准/表格内联/手写只从图片读）
- Service：`buildOcrGrounding`（预算/截断/降级日志）+ 吸附调用
- 测试：`ZhipuOcrClientTest`、`OcrFigureSnapperTest`、Prompt 契约

## 不包含

- 用 OCR 完全替代视觉模型（手写/图形分离风险大）
- PDF 输入（当前一页一图）
- 存量试卷回填
