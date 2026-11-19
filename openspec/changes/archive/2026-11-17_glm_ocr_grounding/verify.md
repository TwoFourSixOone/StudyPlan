# Verify：GLM-OCR 文本 grounding

> **Change**：`2026-11-17_glm_ocr_grounding`　**验证**：2026-11-17（本地，未联网调用）

## 需求 → 实现

| 需求 | 实现 |
|---|---|
| OCR 文本提高题干/选项准确度 | `layout_parsing` → `md_results` 注入整卷 Prompt（`{ocrText}`），Prompt 要求以 OCR 为准 |
| 跨页表格 | Prompt 要求表格必须内联进题干（HTML/markdown），跨页也要并入 |
| 配图裁剪更准 | `layout_details` 的 image bbox → `OcrFigureSnapper` 吸附 `figureRect` |
| **必须可降级** | 无 Key/关闭 → 停用；单页失败/超时/非法响应 → 跳过；总预算 90s；全部失败 → `ocr_grounding_skip` 纯视觉；部分失败 → `ocr_grounding_partial`；单页 6000 字截断；异常不外抛 |
| 密钥安全 | 环境变量 `ZHIPU_API_KEY` 或 `exam.parse.ocr.api-key`，不入库 |

## 测试

```
mvn test → Tests run: 956, Failures: 0, Errors: 0, Skipped: 5 → BUILD SUCCESS（归档时 2026-11-17 复跑）
```

| 用例 | 覆盖 |
|---|---|
| `ZhipuOcrClientTest`（3） | md+bbox 解析、无图页、空/非法/错误体 → null |
| `OcrFigureSnapperTest`（4） | 吸附、过远不动、缺失坐标但唯一图元素、空输入 |
| `ExamParsePromptContractTest` | 整卷 Prompt 含 `{ocrText}` / OCR 规则 / 表格 |

## 未验证（需真实 Key）

| 项 | 说明 |
|---|---|
| 真实调用 | 本机无 `ZHIPU_API_KEY`，未做联网验证；需配置后重传真实卷 |
| 计费/耗时 | 预期 4 页约 6–10s、1–2 分钱；需实测记录 |
| OCR 对 √7/表格的准确率 | 需对比“OCR grounding 前后”题干与表格是否一致 |

## 使用说明

1. 配置环境变量（用户级）：`setx ZHIPU_API_KEY "你的key"`，然后**重启后端进程**；
2. 关闭 OCR 通道：`exam.parse.ocr.grounding=false`（或删掉 Key）；
3. 观察日志：`GLM-OCR grounding 完成: successPages=...` / `ocr_grounding_skip` / `ocr_grounding_partial`。

## 归档（2026-11-17）

- 按 `openspec/changes/README.md` 约定移入 `openspec/changes/archive/`（目录名不变）。
- 未完成项仅为真实样本/计费依赖的手工验证，已在 tasks.md「归档说明」登记。
- 同批修复（配图双候选、二次确认收紧、OCR 复用、模板 UTF-8、表格 rich-text）见 tasks.md「归档说明」。
