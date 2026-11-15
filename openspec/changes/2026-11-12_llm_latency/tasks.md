# Tasks：LLM 调用延迟治理

> **Change 名称**：`2026-11-12_llm_latency`
> **实施顺序**：T1 → T2 → T3 → T4 → T5 → T6

---

## T1 关闭深度思考（可配置）

- [x] `AgentScopeConfig` 新增 `enableThinking`（`Boolean`，默认 `FALSE`，含实测说明）
- [x] `AgentScopeClient.init`：文本模型与视觉模型都 `.enableThinking(...)`
- [x] 启动日志补 `enableThinking={}`
- [x] `application.yml` 增加 `agent.agentscope.enable-thinking: false`
- **验收**：启动日志 `enableThinking=false`；探针调用正常返回 ✅

## T2 每次调用记录耗时

- [x] `AgentScopeClient.doCall`：`cost / promptChars / respChars`
- [x] `AgentScopeClient.doVisionCall`：额外 `imageCount / imageBytes`（不含 base64，遵守 #27）
- [x] 启动探针记录 `elapsed`
- **验收**：日志出现 `LLM 文本调用完成` / `LLM 视觉调用完成`（启动日志已见 `elapsed=862ms`） ✅

## T3 排队可观测

- [x] `LlmConcurrencyLimiter.call`：获取许可等待 ≥1s 记 WARN（含闸门上限与可用数）
- **验收**：`mvn -q compile` 通过；行为与原来一致（仅新增日志） ✅

## T4 文档：性能与调用参数

- [x] `openspec/specs/agent-contracts.md` 新增 §7「性能与调用参数」（基线、分段口径、超时/并发建议）
- [x] `docs/architecture/implicit-contracts.md` 新增 #31（结构化输出必须关闭思考 + 每次调用必须留耗时），维护规则编号 → #32
- **验收**：文档描述与代码一致 ✅

## T5 构建与启动验证

- [x] `mvn -q compile`（exit 0）
- [x] 8081 启动实例，日志断言 `enableThinking=false` + 探针 `elapsed`（已见 862ms）
- [x] 关闭后停止实例，不影响 80 端口实例
- **验收**：启动成功、探针 1s 内 ✅

## T6 复测思考开/关的差异（记录证据）

- [x] 同一张试卷图、同 prompt、max_tokens=2000：
      思考开 13.08s / `finish_reason=length`；思考关 6.55s / `finish_reason=stop`
- [x] 短文本 0.70s、中文本 3.55s→1.79s（关思考）
- **验收**：结果写入 verify.md §2 ✅
