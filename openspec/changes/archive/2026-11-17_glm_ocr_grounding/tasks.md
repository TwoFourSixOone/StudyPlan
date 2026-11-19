# Tasks：GLM-OCR 文本 grounding

> **Change**：`2026-11-17_glm_ocr_grounding`

## T1 客户端
- [x] `ZhipuOcrClient`：layout_parsing（data URI → 裸 base64 双格式兼容、超时 30s、单页失败返回 null）
- [x] `OcrPageResult`（markdown + image bbox 归一化）
- [x] Key 读取：`exam.parse.ocr.api-key` 优先，其次环境变量 `ZHIPU_API_KEY`；缺 Key 自动关闭并只告警一次
- **验收**：`ZhipuOcrClientTest` 3 用例 ✅

## T2 Prompt 注入
- [x] `exam_parse_paper_v1.txt`：`{ocrText}` 区块 + 第 18 条规则（以 OCR 为准/表格内联/手写只从图片读）
- [x] `buildPaperPrompt` 替换 `{ocrText}`；空时固定文案
- **验收**：`ExamParsePromptContractTest` 断言通过 ✅

## T3 配图吸附
- [x] `OcrFigureSnapper`：中心距 ≤0.35 吸附；无坐标 + 唯一图元素直接采用
- [x] 服务端在整卷解析返回后调用
- **验收**：`OcrFigureSnapperTest` 4 用例 ✅

## T4 降级（重点）
- [x] 无 Key / 开关关闭 → 完全不启用
- [x] 单页失败/超时 → 跳过；总预算 90s → 停止剩余页
- [x] 全部失败 → `ocr_grounding_skip`；部分失败 → `ocr_grounding_partial`
- [x] 单页文本截断 6000 字
- [x] 整个 OCR 阶段异常不外抛（不影响解析主流程）

## T5 回归与文档
- [x] `mvn test` → **861 / 0 failure / 5 skipped**
- [x] 文档：产品规则 #4 + 隐性约定 #37

## T6 待验证（需 Key）
- [ ] 配置 `ZHIPU_API_KEY` 后重传真实卷：验证 √7、跨页表格、配图裁剪是否改善
- [ ] 计费/耗时实测（预期 4 页约 6–10s、约 1–2 分钱）

## 归档说明（2026-11-17）

- **归档依据**：客户端 / Prompt 注入 / bbox 吸附 / 降级链路均已完成并通过单测；归档时全量 `mvn test` = **956 用例通过**（跳过 5）。
- **归档时仍未做（需真实样本，未阻塞归档）**：上面 2 条未勾选项——配 `ZHIPU_API_KEY` 重传真实卷对比 OCR 前后效果；计费/耗时实测。
- **归档后追加的修复（同批 change 内）**：
  - bbox 语义与配图：OCR 框改为**候选**（不覆盖模型框，`ParsedFigureRects`/`FigureRects` + 双候选裁剪），二次确认判定收紧（`FigureCheckDecision`）、被拒裁剪件落盘；
  - OCR 输入：双版本路径在 clean 图上跑 OCR（跳过红笔抑制重复解码）；回退整卷时**复用已构建 OCR**，不重复调用；
  - Prompt 模板加载改为显式 UTF-8（此前平台默认字符集下整卷/clean/answers 模板会整体乱码）；
  - 表格渲染：题干内联 HTML 表格改由 `HtmlRichText` 出受控 HTML + 小程序 `rich-text` 渲染，宽表格 `scroll-view` 横向滚动（判分仍用 `HtmlTableTextifier` 纯文本派生）。
- **回归方式**：`mvn -o test`。
