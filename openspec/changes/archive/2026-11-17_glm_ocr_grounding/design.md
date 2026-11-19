# Design：GLM-OCR 文本 grounding

> **Change**：`2026-11-17_glm_ocr_grounding`

## D1 接口

`POST https://open.bigmodel.cn/api/paas/v4/layout_parsing`
```json
{"model":"glm-ocr","file":"<url 或 base64>"}
```
响应：
- `md_results`：Markdown（表格 HTML、公式文本）
- `layout_details`：按页的数组，元素含 `label`(text/formula/table/image)、`bbox_2d:[x1,y1,x2,y2]`（归一化）、`content`
- 价格 0.2 元/百万 token；单图 ≤10MB

`ZhipuOcrClient.parse(byte[], mediaType)`：
1. 先发 `data:image/jpeg;base64,...`；失败再发裸 base64（兼容网关差异）
2. 只接受 200 + `parseResponse` 非空；其它一律返回 null（降级）
3. 只取 `label=image` 的 bbox → `{x,y,width,height}`

## D2 注入 Prompt

`exam_parse_paper_v1.txt` 新增：
```
【OCR 文本（专业 OCR 识别结果，按图片页序；表格为 HTML、公式为文本；可能为空）】
{ocrText}
```
规则（第 18 条）：
- 题干/选项/表格**以 OCR 为准**；OCR 与图片冲突以 OCR 为准（明显乱码才按图片修正并标 unclearFields）
- 表格必须内联进题干（HTML/markdown 均可），跨页表格也要并入
- 学生手写作答只从图片读取
- 某页无 OCR 文本 → 按图片识别

`buildPaperPrompt` 用 `context.getOcrText()` 替换，空时填「（本卷无 OCR 文本，按图片识别）」。

## D3 配图吸附（OcrFigureSnapper）

- 题目有 `figureRect`：找同页 OCR image bbox 中心距最近者，≤0.35 则替换（模型坐标 → OCR 精确坐标）
- 题目无 `figureRect` 但 `hasFigure=true` 且该页只有 1 个 image bbox → 直接采用
- 其它情况不动

## D4 降级实现

`ExamParseServiceImpl.buildOcrGrounding`：
```
if 开关关闭 || 无 Key → null
for 每页:
   预算超时 → break（记录跳过页数）
   client.parse → null 则跳过
   成功：追加 "### 第 N 张图片 OCR\n" + truncate(md, 6000)；收集 image rects
全部为空 → degradationLogger(ocr_grounding_skip) + 返回 null（纯视觉）
部分失败/超时 → degradationLogger(ocr_grounding_partial)
```
- `OcrGrounding(text, rectsByPage)` 传给 `ExamParsePaperContext.ocrText` 与吸附
- 整个 OCR 阶段被 try/catch 包住，任何异常都只记日志，不影响 `parsePaper` 主流程

## D5 验证

- `ZhipuOcrClientTest`（3）：md+bbox 解析、无图页、空/非法/错误体 → null
- `OcrFigureSnapperTest`（4）：吸附、距离过远不动、缺失坐标但唯一图元素、空输入
- `ExamParsePromptContractTest`：整卷 Prompt 含 `{ocrText}`/OCR 规则/表格
- 全量 `mvn test`（861）
- 真实调用需 `ZHIPU_API_KEY`（本环境未配置，未做联网验证）
