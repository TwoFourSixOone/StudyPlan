# Verify Report：举一反三新题生成与完整配额逻辑

> **Change 名称**：`2026-11-03_similar_question_generation`  
> **校验日期**：2026-11-03  
> **校验结论**：⚠️ **部分偏离（轻微）** — 可归档

---

## 一、Task 节点对齐

| Task | 状态 | 说明 |
|------|------|------|
| T-00 | ✅ | 实体/枚举/工具类/异常类全部就位 |
| T-01 | ✅ | V4 DDL 脚本（含 NULL 唯一索引修复） |
| T-02 | ✅ | QuestionCheckAgent（5 项校验 + BLOCK-7 + IMP-14/16） |
| T-03 | ✅ | QuestionGenAgent.generateSimilar（progressive 策略） |
| T-04 | ✅ | Mapper 扩展（IMP-15 LIMIT 100 + IMP-5/10 稳定排序） |
| T-05 | ✅ | SimilarQuestionServiceImpl（循环重试 + 降级 + 脱敏） |
| T-06 | ✅ | HomeworkServiceImpl 重构（配额集成 + 事务边界） |
| T-07 | ✅ | 知识点停用联动 |
| T-08 | ✅ | 387 tests, 0 failures |

## 二、Proposal 功能点（8/8 完成）

完整配额计算 ✅ | 出题 Agent ✅ | 检查 Agent ✅ | 相似度检测 ✅ | 循环重试 ✅ | 降级处理 ✅ | 新题存储 ✅ | 知识点联动 ✅

## 三、Design 方案对齐

30+ 技术方案要点全部对齐，含 BLOCK-1~7、IMP-1~17、NOTE-1~2、DETAIL-1~3。

## 四、隐性约定（6/6 遵循）

#1 统一响应 ✅ | #2 异常处理 ✅ | #9 rollbackFor ✅ | #10 事务边界 ✅ | #11 并发保护 ✅ | #12 超时降级 ✅

## 五、偏离项

| 偏离 | 严重度 | 说明 |
|------|--------|------|
| BLOCK-3 降级日志事务边界 | 低 | 降级日志在 SimilarQuestionServiceImpl 中写入（无事务），新题在 HomeworkServiceImpl TransactionTemplate 中写入。风险：事务失败时"日志有、新题无"短暂不一致，但最终一致 |

## 六、代码审查修复记录

| 编号 | 问题 | 修复 |
|------|------|------|
| H-1 | 降级日志未脱敏 | 添加 sanitize() 截断 500 字符 + 移除敏感信息 |
| H-3 | DuplicateKeyException 后 ID 回填错位 | 使用引用相等（==）对比原始列表与持久化结果 |
| H-4 | 错误码语义不匹配 | HOMEWORK_ALREADY_EXISTS → BAD_REQUEST |
| H-6 | selectCheckedContentsByKp 返回完整 JSON | 提取 stem 再比较 |
| M-3 | degradedMode 判断不精确 | 添加 eligibleKpCount 统计 |
| SQL-1 | content_hash DEFAULT NULL 唯一索引失效 | 改为 NOT NULL DEFAULT '' |

## 七、技术债务登记

| 编号 | 描述 |
|------|------|
| TD-SIMILARITY-THRESHOLD | 相似度阈值 0.6 为经验值 |
| TD-CHECK-AGENT-CAPABILITY | 检查 Agent 校验准确率未评估 |
| TD-CHECK-AGENT-CONSERVATIVE | 保守策略误杀率可能偏高 |
| TD-EXTREME-MODE-STARVATION | 极端模式下错题顺延 |
| TD-SIMILAR-NO-PREREQUISITE | Demo 阶段不强制先修知识点 |
| TD-BLOCK3-TX-BOUNDARY | 降级日志与新题写入不在同一事务（BLOCK-3 偏离） |
