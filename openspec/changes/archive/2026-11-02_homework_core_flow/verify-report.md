# Verify Report：每日作业核心流程

> **Change 名称**：`2026-11-02_homework_core_flow`  
> **校验日期**：2026-11-02  
> **校验结论**：✅ **对齐**

---

## 一、Proposal 对齐

| 功能点 | 状态 | 实现位置 |
|--------|------|----------|
| 出题 Agent 调用（含 subjectId 定位 goal） | ✅ | `QuestionGenAgent.generate()` → `HomeworkServiceImpl.generateDailyHomework()` |
| Agent 输出结构化校验（失败重试 ≤2 次） | ✅ | `AgentOutputValidator.validate()` + Service 层重试循环 |
| Agent 降级处理 | ✅ | `HomeworkDegradationHandler.generateDefaultPlan()` |
| 每日作业存储（daily_plan） | ✅ | `HomeworkServiceImpl` 写入 `daily_plan` 表 |
| 作业下发接口（按科目+日期查询） | ✅ | `HomeworkController.getHomeworkByDate()` |
| 作答提交接口 | ✅ | `HomeworkController.submitAnswer()` → `HomeworkServiceImpl.submitAnswer()` |
| 自动批改逻辑 | ✅ | `HomeworkServiceImpl.gradeHomework()` 逐题对比标准答案 |
| 掌握度更新调用 | ✅ | `gradeHomework()` 内调用 `MasteryService.updateMasteryFromHomework()` |
| 作业状态管理（pending_generation→pending→in_progress→completed） | ✅ | 状态机校验通过 `updateStatusWithCheck` / `updateContentAndStatus` |

---

## 二、Design 对齐

| 设计要点 | 状态 | 说明 |
|----------|------|------|
| §2.1 Controller 层（BLOCK-2: StudentContext 取身份） | ✅ | 4 个接口均从 `StudentContext` 提取 studentId/tenantId |
| §2.2 generateDailyHomework（V1 Schema 适配） | ⚠️ 小幅偏离 | 数据查询路径优化：直接用 Mapper 替代 Service 调用，避免 VO 转换 |
| §2.2 submitAnswer（OBS-2: planId 直查 + 归属校验） | ✅ | `validatePlanAndGet()` 通过 goal_id 反查 learning_goal 校验归属 |
| §2.2 gradeHomework（BLOCK-3: 收集 submissionIds） | ✅ | 收集所有 submissionId 调用 MasteryService |
| §2.3 Agent 层（IMP-8: validate→parse 原子流程） | ✅ | `QuestionGenAgent.validateAndParse()` |
| §2.5 DAO 层（BLOCK-5: 状态机替代乐观锁） | ✅ | `updateStatusWithCheck` / `updateContentAndStatus` 含 `WHERE status = #{expectedStatus}` |
| §2.6 Model 层（IMP-B: DailyPlanContentParser） | ✅ | 含异常包装的 JSON 解析/序列化工具 |
| §2.6 IMP-7 脱敏规则 | ✅ | `HomeworkDegradationHandler.sanitize()` 截断 500 字符 + 移除敏感信息 |
| §2.6 IMP-15 AgentType 枚举 | ✅ | `AgentType.QUESTION_GEN` 替代魔法字符串 |
| §3 异常处理（错误码 1070-1079） | ✅ | `ErrorCodes` 已扩展 |
| §3.3 降级策略（IMP-F: cancelled） | ⚠️ 小幅偏离 | 新计划降级失败不写 cancelled（合理优化：计划未落库） |
| §7 安全（DDL 变更需人工审批） | ✅ | V2 脚本标注"受保护，需人工审批" |

**事务管理优化**：Design §1.1 声明使用 `@Transactional(rollbackFor = Exception.class)`，实际 `generateDailyHomework` 改用 `TransactionTemplate` 仅包裹写操作，Agent LLM 调用在事务外执行。原因：SQL 风险审查发现事务内包含 30s+ 外部 API 调用，优化事务边界。**影响：正向改进**。

---

## 三、Tasks 对齐

| Task | 状态 | 说明 |
|------|------|------|
| T-00 AgentScope 配置 | ✅ | AgentType + AgentScopeConfig + AgentScopeClient + .gitignore |
| T-01a 实体类 + Mapper | ✅ | 4 实体 + 4 Mapper + 自定义方法 |
| T-01b V2 DDL | ✅ | `V2__homework_uk_index.sql`（含 uk_plan_question + idx_student + uk_goal_date） |
| T-02 VO/DTO/工具类 | ✅ | HomeworkVO + GradeResultVO + SubmitAnswerRequest + DailyPlanContentParser + DailyPlanQuestionRef |
| T-03 Agent 输出校验器 | ✅ | AgentOutputValidator |
| T-04 出题 Agent | ✅ | QuestionGenAgent |
| T-05 降级策略 | ✅ | HomeworkDegradationHandler |
| T-06 HomeworkService 接口 | ✅ | 4 方法签名 |
| T-07 generateDailyHomework | ✅ | 重试 + 降级 + 落题拆分 + 状态机 + TransactionTemplate |
| T-08 getHomeworkByDate | ✅ | 双来源批量查询（selectBatchIds） |
| T-09 submitAnswer | ✅ | planId 直查 + 归属校验 + insertOrUpdate + 跨层字段映射 |
| T-10 gradeHomework | ✅ | 幂等 + 逐题批改 + MasteryService + 状态机 |
| T-11 HomeworkController | ✅ | 4 个学生端接口 |
| T-12 集成测试 | ✅ | HomeworkControllerTest(15) + HomeworkServiceTest(15) |
| T-13 错误码 | ✅ | 1052-1059 + 1082 |
| T-14 Prompt + Schema | ✅ | question_gen_v1.txt + question_gen_schema.json |

---

## 四、隐性约定对齐

| 约定 | 状态 | 说明 |
|------|------|------|
| #1 统一响应结构 `Result<T>` | ✅ | HomeworkController 所有接口返回 `Result<T>` |
| #2 异常处理规范 | ✅ | Service 抛 `BusinessException(ErrorCodes)`，Controller 不 try-catch |
| #3 分页参数规范 | ✅ | 作业按日期单日查询，题量 ≤15，无需分页 |
| #4 时间格式规范 | ✅ | 实体用 `LocalDateTime`/`LocalDate`，Controller 用 `@DateTimeFormat(iso=DATE)` |
| #9 @Transactional rollbackFor | ✅ | `submitAnswer` / `gradeHomework` 显式指定；`generateDailyHomework` 改用 TransactionTemplate |

---

## 五、评审阶段记录

| 评审阶段 | 结论 | 修复项 |
|----------|------|--------|
| `/prepare-review` 摘要评审 | ✅ 完成 | — |
| `/java-architecture-review` 分层架构 | ✅ 完成 | HomeworkServiceImpl 方法过长（技术债务已登记） |
| `/sql-risk-review` SQL 风险 | ✅ 完成 | 补充 uk_goal_date 唯一索引 + Agent 调用移出事务 |
| `@reviewer` 独立代码审查 | ✅ 完成 | 修复 persistHomeworkResult 状态机逻辑不一致 |
| `/opsx:verify` 对齐校验 | ✅ 对齐 | — |

---

## 六、归档信息

- **归档位置**：`openspec/changes/archive/2026-11-02_homework_core_flow/`
- **归档日期**：2026-11-02
- **新增隐性约定**：
  - 约定 #10：事务边界精确控制
  - 约定 #11：并发写入保护模式
  - 约定 #12：Agent 调用超时降级

---

**校验人**：AI Agent  
**校验结论**：✅ 对齐，可归档
