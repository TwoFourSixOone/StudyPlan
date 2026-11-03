# Tasks：掌握度状态管理

> **Change ID**: `2026-10-31_mastery_state_management`
> **关联 Design**: `design.md`

---

## Task 清单

### Task 0：扩展 ErrorCodes 错误码（对应审查 G 项）
- [x] 在 `ErrorCodes.java` 中新增以下错误码：
  - `MASTERY_CONCURRENT_CONFLICT(1061, "掌握度更新冲突，请重试")` — 乐观锁失败时使用
- [x] 确认已有错误码满足需求：`KNOWLEDGE_POINT_NOT_FOUND(1010)`、`KNOWLEDGE_POINT_DISABLED(1011)`、`MASTERY_STATE_NOT_FOUND(1060)`、`HOMEWORK_NOT_FOUND(1050)`、`BAD_REQUEST(400)`
- [x] 文件：`src/main/java/com/jiang/common/exception/ErrorCodes.java`

### Task 1：创建 ChangeType 枚举
- [x] 新建 `com.jiang.model.enums.ChangeType`，含 HOMEWORK/ASSESSMENT/EXAM，各携带 sourceWeight
- [x] 文件：`src/main/java/com/jiang/model/enums/ChangeType.java`

### Task 2：创建 MasteryFormulaUtil 工具类
- [x] 纯静态工具类，所有计算使用 BigDecimal（**全局精度约定：scale=4, RoundingMode.HALF_UP**，与 DB DECIMAL(5,4) 对齐）
- [x] 新增常量 `MASTERY_INITIAL = new BigDecimal("0.50")`（首次创建记录时的初始掌握度）
- [x] 方法：calculateMasteryCorrect / calculateMasteryIncorrect / calculateCNorm / validateDNorm / mapDifficulty / updateConfidence
- [x] 文件：`src/main/java/com/jiang/common/util/MasteryFormulaUtil.java`

### Task 3：创建 VO 和 DTO
- [x] `MasteryDashboardVO`（学生看板）、`MasteryDetailVO`（管理端看板）、`MasteryChangeLogVO`（变更历史，含 knowledgePointName + subject 上下文字段）
- [x] `AdminMasteryQueryRequest`（管理端查询入参）
- [x] 所有 VO 添加 @Data @Builder @NoArgsConstructor @AllArgsConstructor
- [x] 文件：`src/main/java/com/jiang/vo/MasteryDashboardVO.java`、`MasteryDetailVO.java`、`MasteryChangeLogVO.java`、`src/main/java/com/jiang/dto/AdminMasteryQueryRequest.java`

### Task 4：创建 MasteryService 接口
- [x] 定义写方法：updateMasteryFromHomework / updateMasteryFromResult
- [x] 定义学生端查询：getStudentDashboard / getStudentChangeHistory
- [x] 定义管理端查询：getAdminDashboard / getAdminChangeHistory
- [x] 写方法添加 @Transactional(rollbackFor = Exception.class)
- [x] 文件：`src/main/java/com/jiang/service/MasteryService.java`

### Task 5：实现 MasteryServiceImpl
- [x] 注入 StudentKnowledgeStateMapper / MasteryChangeLogMapper / KnowledgePointMapper / QuestionBankMapper / HomeworkSubmissionDetailMapper / GeneratedQuestionMapper
- [x] 实现 updateMasteryFromHomework：
  - **步骤 1：归属校验** — 根据 submissionIds 批量查询 submission，校验每条的 student_id/tenant_id 与入参一致，不一致抛 HOMEWORK_NOT_FOUND
  - **步骤 2-3：获取题目难度 + 初始化内存缓存** — `Map<String, StudentKnowledgeState> stateCache`
  - **步骤 4：循环处理** — 从 stateCache 读取 mCurrent（而非每次查 DB），计算 delta，更新缓存；mastery_change_log 逐条插入
  - **步骤 5：批量写回** — 循环结束后对 stateCache 中每个 state 执行一次 DB updateById
  - **步骤 6：乐观锁冲突处理（对应审查 F 项）** — `StudentKnowledgeState` 实体新增 `@Version private Integer version;` 字段；若 updateById 返回受影响行数为 0，抛出 `BusinessException(MASTERY_CONCURRENT_CONFLICT)`，事务回滚
- [x] 实现 updateMasteryFromResult：直接计算 → 更新状态+写日志
- [x] 实现 getStudentDashboard：查 active 知识点 → 关联状态 → 组装 VO
- [x] 实现 getStudentChangeHistory：分页查询 mastery_change_log
- [x] 实现 getAdminDashboard：按筛选条件分页查询，JOIN knowledge_point 获取名称和状态
- [x] 实现 getAdminChangeHistory：按学生+知识点分页查询
- [x] 私有方法：findOrCreateState / calculateAndApplyDelta
- [x] 文件：`src/main/java/com/jiang/service/impl/MasteryServiceImpl.java`

### Task 6：添加自定义 Mapper 方法
- [x] StudentKnowledgeStateMapper：selectActiveStatesWithKpInfo（JOIN knowledge_point）
- [x] MasteryChangeLogMapper：selectHistoryByStudentAndKp（分页查询）
- [x] MasteryChangeLogMapper：selectLatestByKpIds（批量查询各知识点最新一条日志，用于看板 lastChangeDirection 组装，对应审查 I6 项）
- [x] QuestionBankMapper：selectAvgDifficultyByKp（D_norm 校验用）
- [x] 文件：对应 3 个 Mapper 接口

### Task 7：创建 MasteryController（学生端）
- [x] GET `/api/student/mastery/dashboard` — 掌握度看板（可选 subject 参数）
- [x] GET `/api/student/mastery/history/{knowledgePointId}` — 变更历史（分页）
- [x] 从 StudentContext 获取 studentId/tenantId，Controller 不含业务逻辑
- [x] 文件：`src/main/java/com/jiang/controller/MasteryController.java`

### Task 8：创建 AdminMasteryController（管理端）
- [x] GET `/api/admin/mastery/dashboard` — 掌握度看板（筛选+分页）
- [x] GET `/api/admin/mastery/student/{studentId}/history/{knowledgePointId}` — 学生变更历史
- [x] 文件：`src/main/java/com/jiang/controller/AdminMasteryController.java`

### Task 9：补充测试 Schema（对应审查 F/G 项）
- [x] 在 `src/test/resources/schema.sql` 中追加：student_knowledge_state（**含 `version INT NOT NULL DEFAULT 0` 字段**）、mastery_change_log、knowledge_point 表定义
- [x] 文件：`src/test/resources/schema.sql`

### Task 10：编写 MasteryFormulaUtil 单元测试
- [x] 测试答对公式：m=0.5, homework, standard → 预期结果
- [x] 测试答错公式：m=0.5, exam, challenge → 预期结果
- [x] 测试 C_norm 计算：confidence=0.1 → 0.95, confidence=1.0 → 0.5
- [x] 测试 D_norm 校验：偏离>0.2 降级为 0.5，偏离<=0.2 使用声明值
- [x] 测试难度映射：basic→0.3, standard→0.6, challenge→0.9, 无效值→异常
- [x] 测试置信度更新：+0.05 递增，上限 1.0
- [x] 测试边界：m_current=0 答错 → 保持 0.05（备注：验证首次零分答题后的下限截断，m_current=0 对应新建记录的 MASTERY_INITIAL=0.50 被首次答错拉低的极端场景），m_current=1.0 答对 → 保持 1.0
- [x] 文件：`src/test/java/com/jiang/common/util/MasteryFormulaUtilTest.java`

### Task 11：编写 MasteryService 单元测试
- [x] Mock 所有 Mapper 依赖
- [x] 测试 updateMasteryFromResult：答对场景、答错场景、首次创建状态（验证初始化字段：mastery_score=0.50, confidence=0.10, source=当前ChangeType, lastAssessedAt=now）
- [x] 测试 updateMasteryFromHomework：正常批量更新、**同一知识点多次出现时增量不丢失（内存缓存验证）**、**同批次内同一知识点多条日志的 mastery_before/mastery_after 依次行接（如第一条 0.50→0.55，第二条 0.55→0.65，最终 state 值为 0.65）**、submission 归属校验失败抛异常、submission 不存在异常
- [x] 测试乐观锁冲突：模拟 updateById 返回 0（version 不匹配），验证抛出 MASTERY_CONCURRENT_CONFLICT
- [x] 测试 getStudentDashboard：按科目筛选、无 active 知识点
- [x] 测试 getStudentChangeHistory：正常分页、空结果
- [x] 测试 getAdminDashboard：多条件筛选、分页正确
- [x] 测试变更日志写入：验证 delta/masteryBefore/masteryAfter 正确
- [x] 文件：`src/test/java/com/jiang/service/MasteryServiceTest.java`

### Task 12：编写 Controller 集成测试
- [x] MasteryControllerTest：看板查询 200、history 查询分页参数、未登录 401
- [x] AdminMasteryControllerTest：看板查询筛选条件、history 查询、无效参数 400
- [x] 文件：`src/test/java/com/jiang/controller/MasteryControllerTest.java`、`AdminMasteryControllerTest.java`

---

## 执行顺序

Task 0 → Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 9 → Task 7 → Task 8 → Task 10 → Task 11 → Task 12

> Task 0 为错误码准备，Task 1-6 为核心实现，Task 9 为测试基础设施，Task 7-8 为 Controller，Task 10-12 为测试。
> 每个 Task 完成后执行 `mvn compile` 验证编译通过。
> 全部完成后执行 `mvn test` 验证测试通过。

---

## 涉及文件汇总

| 类型 | 文件 |
|------|------|
| 新增 | `src/main/java/com/jiang/model/enums/ChangeType.java` |
| 新增 | `src/main/java/com/jiang/common/util/MasteryFormulaUtil.java` |
| 新增 | `src/main/java/com/jiang/service/MasteryService.java` |
| 新增 | `src/main/java/com/jiang/service/impl/MasteryServiceImpl.java` |
| 新增 | `src/main/java/com/jiang/controller/MasteryController.java` |
| 新增 | `src/main/java/com/jiang/controller/AdminMasteryController.java` |
| 新增 | `src/main/java/com/jiang/vo/MasteryDashboardVO.java` |
| 新增 | `src/main/java/com/jiang/vo/MasteryDetailVO.java` |
| 新增 | `src/main/java/com/jiang/vo/MasteryChangeLogVO.java` |
| 新增 | `src/main/java/com/jiang/dto/AdminMasteryQueryRequest.java` |
| 新增 | `src/test/java/com/jiang/common/util/MasteryFormulaUtilTest.java` |
| 新增 | `src/test/java/com/jiang/service/MasteryServiceTest.java` |
| 新增 | `src/test/java/com/jiang/controller/MasteryControllerTest.java` |
| 新增 | `src/test/java/com/jiang/controller/AdminMasteryControllerTest.java` |
| 修改 | `src/main/java/com/jiang/mapper/StudentKnowledgeStateMapper.java` |
| 修改 | `src/main/java/com/jiang/mapper/MasteryChangeLogMapper.java` |
| 修改 | `src/main/java/com/jiang/mapper/QuestionBankMapper.java` |
| 修改 | `src/main/java/com/jiang/model/entity/StudentKnowledgeState.java`（新增 `@Version version` 字段） |
| 修改 | `src/main/java/com/jiang/model/entity/HomeworkSubmissionDetail.java`（新增 `studentId` 字段） |
| 修改 | `src/main/java/com/jiang/common/exception/ErrorCodes.java`（新增 MASTERY_CONCURRENT_CONFLICT） |
| 修改 | `src/test/resources/schema.sql` |

---

## 实施报告

**实施日期**: 2026-11-02

### 完成情况

| Task | 描述 | 状态 | 编译 | 测试 |
|------|------|------|------|------|
| Task 0 | 扩展 ErrorCodes 错误码 | ✅ 完成 | ✅ | ✅ |
| Task 1 | 创建 ChangeType 枚举 | ✅ 完成 | ✅ | ✅ |
| Task 2 | 创建 MasteryFormulaUtil 工具类 | ✅ 完成 | ✅ | ✅ |
| Task 3 | 创建 VO 和 DTO | ✅ 完成 | ✅ | ✅ |
| Task 4 | 创建 MasteryService 接口 | ✅ 完成 | ✅ | ✅ |
| Task 5 | 实现 MasteryServiceImpl | ✅ 完成 | ✅ | ✅ |
| Task 6 | 添加自定义 Mapper 方法 | ✅ 完成 | ✅ | ✅ |
| Task 7 | 创建 MasteryController | ✅ 完成 | ✅ | ✅ |
| Task 8 | 创建 AdminMasteryController | ✅ 完成 | ✅ | ✅ |
| Task 9 | 补充测试 Schema | ✅ 完成 | ✅ | ✅ |
| Task 10 | MasteryFormulaUtil 单元测试 | ✅ 完成 | ✅ | ✅ 19 tests |
| Task 11 | MasteryService 单元测试 | ✅ 完成 | ✅ | ✅ 11 tests |
| Task 12 | Controller 集成测试 | ✅ 完成 | ✅ | ✅ 18 tests |

### 测试结果

```
Tests run: 214, Failures: 0, Errors: 0, Skipped: 5
BUILD SUCCESS
```

### 隐性约定对齐情况

| 约定 | 对齐情况 |
|------|----------|
| #1 统一响应结构 | ✅ 所有 Controller 返回 `Result<T>` |
| #2 异常处理 | ✅ 统一 `BusinessException` + `ErrorCodes` |
| #3 分页参数 | ✅ page 从 1 开始，size 默认 10 |
| #4 时间格式 | ✅ `LocalDateTime`，MyBatis-Plus 自动填充 |
| #9 @Transactional | ✅ 写方法均指定 `rollbackFor = Exception.class` |

### 额外修改

- `HomeworkSubmissionDetail.java`：新增 `studentId` 字段（归属校验需要）
- `schema.sql`：新增 `student_id` 列至 `homework_submission_detail` 表

### 建议下一步

1. 执行 `/opsx:verify` 进行 OpenSpec 对齐校验
2. 执行 `/java-architecture-review` 进行架构分层检查
3. 执行 `/sql-risk-review` 进行 SQL 风险审查
4. 执行 `@reviewer` 进行独立代码审查
