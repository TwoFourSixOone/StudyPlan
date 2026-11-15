# Proposal：LLM 调用延迟治理（关闭深度思考 + 耗时可观测）

> **Change 名称**：`2026-11-12_llm_latency`
> **状态**：已实施，验证通过（见 `verify.md`），待归档

---

## 1. 背景

现象：后端调用百炼（DashScope 网关）的 qwen/deepseek 模型**很慢**，但在网页上直接对话很快。

### 1.1 实测（同一台机器、同一 endpoint `ws-bvy3j3i349eo1sjs.cn-beijing.maas.aliyuncs.com`、同一 key）

| 场景 | 结果 |
|------|------|
| TCP/TLS 建连 | DNS 93ms、connect 57ms、TLS 112ms — **网络不是瓶颈** |
| 短文本（"只回复 OK"，max_tokens=16） | **0.70s**，输出 13 token |
| 中等文本（约 300 字说明，max_tokens=800） | 3.55s / 475 token（含思考）；关思考 1.79s / 174 token |
| **真实试卷图切题**（max_tokens=2000，思考开） | **13.1s，`finish_reason=length`（被截断！）** |
| 同上，**关闭思考** | **6.5s，`finish_reason=stop`（完整 JSON）** |

### 1.2 根因（不是网络，是调用参数与输出预算）

1. **模型默认开启深度思考**（响应里 `reasoning_content` + `usage.output_tokens_details.reasoning_tokens`）。
   项目的全部调用都是**结构化输出**（切题 JSON / 出题 JSON / 判分 / 归因映射），思考内容**不会进入结果**，
   纯增加 token 与延迟。
2. **思考 token 吃掉 max_tokens 预算**：同一张图 max_tokens=2000 时，思考占掉约一半输出预算，
   `finish_reason=length` → JSON 被截断 → `AgentOutputInvalidException` → **重试 2 次**（SDK `maxAttempts=3`）
   → 单图从 13s 变 **39s+**，且可能全部失败降级。
3. **非流式**（`stream(false)`）：必须等完整生成才返回，而网页对话是流式，首 token 就可见 → 体感差距被放大。
4. **全局并发闸门 3**：8 张试卷图要排 3 波，墙钟 ≈ 波数 × 单图耗时。
5. **超时/重试放大**：`timeout=300s` + `max-retries=2`，一次卡死最坏 15 分钟。

## 2. 目标

1. 结构化输出场景**关闭深度思考**（可配置），把输出预算还给 JSON。
2. **耗时可观测**：每次 LLM 调用记录 `cost / promptChars / respChars`；等待并发许可超过 1s 记录排队耗时。
3. 固化性能基线（见 §1.1），后续"慢"的投诉能立刻区分：模型慢 / 排队慢 / 网络慢 / 重试慢。
4. 文档化并发与超时的调优建议（不在本 change 改默认值）。

## 3. 范围

### 包含

- `AgentScopeConfig` 新增 `enableThinking`（默认 `false`），`AgentScopeClient` 两个模型都 `enableThinking(...)`
- `application.yml` 增加 `agent.agentscope.enable-thinking: false`（含实测说明）
- `AgentScopeClient`：`doCall` / `doVisionCall` 增加耗时日志；启动探针记录 `elapsed`
- `LlmConcurrencyLimiter`：获取许可等待 ≥1s 记 WARN（排队可观测）
- `openspec/specs/agent-contracts.md` 新增 §7「性能与调用参数」
- `docs/architecture/implicit-contracts.md` 新增 #31

### 不包含

- **不改** `exam.parse.grading-concurrency`（默认 3）：并发提升需要压测与限流评估，先留文档建议
- **不改** `timeout`（当前 300s）与 `max-retries`（2）：属运维调参，按 §7 建议由使用方决定
- 不改流式（后端非流式是可接受的：试卷解析本身是后台任务 + 轮询）
- 不改模型/网关：实测该网关吞吐正常（~130–150 tok/s）

## 4. 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 关闭思考后复杂推理任务质量下降 | 中 | 配置项可随时置 `true`；本项目四类 Agent 全是结构化抽取/生成，思考不进入输出 |
| 部分模型不支持 `enable_thinking` 参数 | 低 | 实测当前模型（deepseek-v4.1-flash-1017）支持；不支持的模型服务端会忽略或报错，回滚只需改一行 yml |
| 日志量增加（每次调用 1 条 INFO） | 低 | 单请求调用数 ≤ 数十条，可接受；需要时调 `com.jiang.client` 日志级别 |

## 5. 验收口径

- 启动日志出现 `enableThinking=false`，探针 `elapsed` 在 1s 量级
- 同一张试卷图：思考关时 `finish_reason=stop`、单图 ≤7s（实测 6.5s）
- 日志里能看到 `LLM 文本调用完成 / LLM 视觉调用完成` 的 cost/字符数
