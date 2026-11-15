# Verify：LLM 调用延迟治理

> **Change 名称**：`2026-11-12_llm_latency`
> **验证时间**：2026-11-12 15:26–15:40（Asia/Shanghai）
> **结论**：**实现与工件对齐**；根因是「默认深度思考 + 输出预算被思考吃掉 + 重试放大」，非网络问题

---

## 1. 范围与任务对照

| 任务 | 状态 | 证据 |
|------|------|------|
| T1 关闭深度思考 | ✅ | `AgentScopeConfig.enableThinking=false`；两个模型 `.enableThinking(...)`；yml `enable-thinking: false` |
| T2 调用耗时日志 | ✅ | `doCall` / `doVisionCall` 输出 `cost/promptChars/respChars`（视觉加 imageCount/imageBytes） |
| T3 排队可观测 | ✅ | `LlmConcurrencyLimiter.call` 等待 ≥1s 记 WARN |
| T4 文档 | ✅ | `agent-contracts.md` §7；`implicit-contracts.md` #31（编号起点 → #32） |
| T5 构建与启动 | ✅ | `mvn -q compile` exit 0；8081 启动成功并停止 |
| T6 开/关思考复测 | ✅ | 见 §2 |

---

## 2. 实测证据（同机、同 endpoint、同 key、同图）

### 2.1 网络与吞吐（排除网络因素）

```
短文本 max_tokens=16 : dns=93ms connect=57ms tls=112ms  ttfb=705ms total=705ms  http=200
```

### 2.2 思考开关对比（同一张试卷图，`max_tokens=2000`）

| 配置 | 总耗时 | 输出 token | finish_reason | 响应大小 |
|------|--------|-----------|---------------|----------|
| 默认（思考开） | **13.08s** | 2000（顶满） | **`length`（截断）** | 3.7KB |
| `enable_thinking=false` | **6.55s** | 1043 | **`stop`（完整）** | 2.3KB |

文本侧（约 300 字说明，max_tokens=800）：思考开 3.55s / 475 tok → 关 1.79s / 174 tok。

> 因果链：思考 token 占用 `max_tokens` → `finish_reason=length` → JSON 截断 →
> `AgentOutputInvalidException` → 重试 2 次（`maxAttempts=3`）→ 单图 13s 放大到 39s+。

### 2.3 并发观察

- 8 个并发视觉请求全部成功（批次墙钟 6.21s，8/8 HTTP 200）
- 但另一次 4 并发批次用了 14.93s → **网关有排队/抖动**，提高并发前需压测（本 change 未改并发默认值）

### 2.4 启动日志（应用层确认配置生效）

```
AgentScope SDK 初始化完成: model=deepseek-v4.1-flash-1017, baseUrl=https://ws-...maas.aliyuncs.com,
                          timeout=300s, maxRetries=2, enableThinking=false
AgentScope SDK 测试调用成功: elapsed=862ms, response=调用成功
Started StudyPlanApplication in 3.701 seconds
```

---

## 3. 回归

```
mvn test
→ Tests run: 821, Failures: 0, Errors: 0, Skipped: 5
→ BUILD SUCCESS
```

- 8081 验证实例已停止，用户 80 端口实例未受影响（但该实例是重启前的类，需重启才能带上本 change）

---

## 4. 文档一致性

| 文档 | 改动 |
|------|------|
| `openspec/specs/agent-contracts.md` | 新增 §7「性能与调用参数」：基线、分段口径、超时/并发建议、变更要求；检查清单加「是否关闭深度思考」 |
| `docs/architecture/implicit-contracts.md` | 新增 #31（结构化输出必须关思考 + 必须留耗时）；维护规则编号起点 → #32 |

---

## 5. 遗留（需人工决策 / 另立 change）

| 项 | 说明 |
|----|------|
| `timeout=300s` / `max-retries=2` | 未改。建议按用途分级（文本 60s、单图视觉 120s、重试 1），需运维确认 |
| `exam.parse.grading-concurrency=3` | 未改。8 图 = 3 波；提到 4 可省约 1/3 墙钟，但网关有抖动，需压测 |
| 重启 80 端口实例 | 当前实例仍是旧类，需重启才能生效 |
| 其他模型 | 若切回开启思考的模型/场景，把 `enable-thinking` 置 `true` 即可 |
