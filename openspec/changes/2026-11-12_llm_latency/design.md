# Design：LLM 调用延迟治理

> **Change 名称**：`2026-11-12_llm_latency`

---

## 0. 实测数据（决策依据）

工作机 → 网关 `ws-bvy3j3i349eo1sjs.cn-beijing.maas.aliyuncs.com`：

```
短文本  max_tokens=16    : TTFB 0.70s / total 0.70s，输出 13 tok
中文本  max_tokens=800   : 思考开 3.55s / 475 tok（含 reasoning）
                          思考关 1.79s / 174 tok
真实试卷图 max_tokens=2000: 思考开 13.08s / 2000 tok，finish_reason=length  ← 截断
                          思考关  6.55s / 1043 tok，finish_reason=stop    ← 完整
```

网络侧：DNS 93ms、TCP 57ms、TLS 112ms；吞吐约 130–150 tok/s。**结论：慢在输出 token 数与重试，不在网络。**

关键因果链：

```
默认思考开 → 思考 token 占满 max_tokens → JSON 截断(finish_reason=length)
           → AgentOutputInvalidException → 重试 2 次 → 单图 13s × 3 ≈ 39s（且可能全失败）
```

## 1. D1：关闭深度思考（`enable_thinking`）

AgentScope DashScope 扩展的 Builder 支持：

```java
DashScopeChatModel.builder()
    ...
    .enableThinking(agentScopeConfig.getEnableThinking())   // Boolean，null = 不传，沿用服务端默认
```

- `AgentScopeConfig.enableThinking` 默认 `Boolean.FALSE`；yml 显式写 `enable-thinking: false`
- 文本模型与视觉模型都要传（两个实例）
- 关闭后 `DashScopeParameters.enableThinking=false` → 请求体 `parameters.enable_thinking=false`；
  服务端返回 `finish_reason=stop`，输出预算全部给答案

> 为什么不直接改 `max_tokens`：思考长度不可控，单纯加大 max_tokens 只会让**每次都想更久**；
> 先关思考，再把 max_tokens 当作答案预算才成立。

## 2. D2：耗时可观测

`AgentScopeClient`：

```java
long start = System.currentTimeMillis();
String text = extractText(responseFlux.collectList().block());
log.info("LLM 文本调用完成: cost={}ms, promptChars={}, respChars={}", ...);
// 视觉：额外 imageCount / imageBytes（不打印 base64，遵守隐性约定 #27）
```

`LlmConcurrencyLimiter.call`：

```java
long start = System.currentTimeMillis();
acquire();
long waitMs = System.currentTimeMillis() - start;
if (waitMs >= 1000) log.warn("等待 LLM 并发许可 {}ms（闸门上限 {}，当前可用 {}）", ...);
```

启动探针也记录 `elapsed`，作为"网关是否退化"的快速信号。

**分段口径**（排障时按这三段看）：

| 日志 | 代表的耗时 |
|------|-----------|
| `等待 LLM 并发许可 Xms` | 排队（闸门被打满） |
| `LLM 文本/视觉调用完成: cost=Xms` | 纯模型生成（含网络） |
| 两次调用之间 | 业务编排 / 重试 / 序列化 |

## 3. D3：超时与重试（文档建议，不改默认）

当前 `timeout=300s`、`max-retries=2`（SDK `maxAttempts=3`）→ 单次调用最坏 15 分钟。

建议（按用途分级，需要运维确认）：

| 用途 | 建议 timeout | 建议重试 |
|------|--------------|----------|
| 文本 JSON（出题/检查/判分/归因） | 60s | 1 |
| 单图视觉切题 | 120s | 1（外层已有整卷重试 ≤2） |

## 4. D4：并发（文档建议，不改默认）

`exam.parse.grading-concurrency`（默认 3）是**全局**闸门（视觉解析 + 判分 + 出题共用）。

- 8 张图 = 3 波；若提到 4 → 2 波，墙钟约 −33%
- 实测网关可同时接受 8 个视觉请求且全部成功（批次墙钟 6.2s / 8 请求全部 200）；
  但并发批次耗时波动较大（另一次 4 并发用了 14.9s），说明网关有排队/抖动 → **提高并发前必须先压测**
- 但该闸门同时保护判分与出题，提高前建议先观察 `等待 LLM 并发许可` 日志与上游 429

## 5. D5：与非流式的关系

`stream(false)` 决定"必须等完整结果"，因此**总时长 = 生成时长**，无法像网页那样首 token 可见。
本项目链路（试卷解析 / 作业生成）都是后台任务 + 轮询，非流式可接受；真正要改的是 §1 的输出预算。

## 6. 验证方式

1. `mvn -q compile`；
2. 以 8081 启动，日志断言 `enableThinking=false` 与探针 `elapsed`；
3. 用 curl 对同一张图复测 `finish_reason`（思考开=length / 关=stop）；
4. 文档回写检查（agent-contracts §7、隐性约定 #31）。
