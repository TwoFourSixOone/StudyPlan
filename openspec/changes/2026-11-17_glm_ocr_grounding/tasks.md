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
