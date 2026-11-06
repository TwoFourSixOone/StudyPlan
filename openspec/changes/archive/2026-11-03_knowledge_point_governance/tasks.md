# Tasks：知识点治理功能（v3 修复版）

> **Change 名称**：`2026-11-03_knowledge_point_governance`  
> **版本**：v3（修复 IMP-27）

---

## DDL 版本声明

- 本 Change **无新增 DDL**
- 依赖 V1~V5 既有表（knowledge_point、question_bank、learning_goal、student_knowledge_state）
- 不涉及 V6-V9 版本号依赖，可独立 apply

---

## 任务清单

### T-01：DTO/VO 类与错误码定义

**任务描述**：
1. 创建 VO 类（修复 IMP-27：id 字段类型为 String，因 knowledge_point_id 是 VARCHAR）：
   - `KnowledgePointListVO.java`（id: String）
   - `KnowledgePointDetailVO.java`（id: String）
   - `PrerequisiteVO.java`（knowledgePointId: String）
   - `KnowledgePointOverdueVO.java`（id: String）
   - `KnowledgePointMasteryVO.java`
   - `MasteryDashboardVO.java`
2. 扩展错误码（1160-1169 编号段）：
   - `KNOWLEDGE_POINT_NOT_FOUND(1160)`
   - `KNOWLEDGE_POINT_NOT_PENDING_REVIEW(1161)`
   - `KNOWLEDGE_POINT_IN_USE(1162)`
   - `INVALID_KNOWLEDGE_POINT_STATUS(1163)`

**涉及文件**：
- `src/main/java/com/jiang/vo/KnowledgePointListVO.java`（新增）
- `src/main/java/com/jiang/vo/KnowledgePointDetailVO.java`（新增）
- `src/main/java/com/jiang/vo/PrerequisiteVO.java`（新增）
- `src/main/java/com/jiang/vo/KnowledgePointOverdueVO.java`（新增）
- `src/main/java/com/jiang/vo/KnowledgePointMasteryVO.java`（新增）
- `src/main/java/com/jiang/vo/MasteryDashboardVO.java`（新增）
- `src/main/java/com/jiang/common/exception/ErrorCodes.java`（修改）

**验收标准**：
- [x] VO 类字段完整
- [x] 错误码定义正确（1160-1169 段）
- [x] 编译通过

---

### T-02：Mapper 扩展（修复 BLOCK-8 + IMP-12）

**任务描述**：
1. 在 `KnowledgePointMapper` 中：
   - `selectByIdWithTenant()` — 带租户隔离的查询（**IMP-27：id 参数为 String**）
   - `selectOverdue()` — 超期未校对知识点查询
   - **不新增 5 参数 selectList**（修复 BLOCK-8：使用 selectPage + QueryWrapper）
2. 在 `QuestionBankMapper` 中：
   - `countByKnowledgePointIds()` — **修复 IMP-12**：批量查询，LEFT JOIN + GROUP BY，避免 N+1（**IMP-27：参数类型为 `List<String>`**）
3. 在 `LearningGoalMapper` 中：
   - `countByKnowledgePointId()` — 校验知识点是否被活跃目标引用
4. 在 `StudentKnowledgeStateMapper` 中：
   - `selectByStudentAndSubject()` — 按学生和科目查询掌握度状态

**涉及文件**：
- `src/main/java/com/jiang/mapper/KnowledgePointMapper.java`（修改）
- `src/main/java/com/jiang/mapper/QuestionBankMapper.java`（修改）
- `src/main/java/com/jiang/mapper/LearningGoalMapper.java`（修改）
- `src/main/java/com/jiang/mapper/StudentKnowledgeStateMapper.java`（修改）

**验收标准**：
- [x] Mapper 方法完整
- [x] SQL 查询正确（countByKnowledgePointIds 使用 GROUP BY）
- [x] 编译通过

---

### T-03：KnowledgePointGovernanceService 核心实现（修复 BLOCK-8/9）

**任务描述**：
1. 创建 `KnowledgePointGovernanceService` 接口
2. 实现 `KnowledgePointGovernanceServiceImpl`，包含：
   - `getKnowledgePointList()`：使用 `selectPage(pageParam, wrapper)`（修复 BLOCK-8）；参数 `subject` 为 String（修复 IMP-27）
   - `getKnowledgePointDetail()`：详情查看
   - `updateStatus()`：启停用（含活跃目标引用校验）
   - `markAsReviewed()`：**修复 BLOCK-9**：幂等处理（active 重复标记返回成功）
   - `getOverdueKnowledgePoints()`：校对超期列表（7 个自然日）

**涉及文件**：
- `src/main/java/com/jiang/service/KnowledgePointGovernanceService.java`（新增）
- `src/main/java/com/jiang/service/impl/KnowledgePointGovernanceServiceImpl.java`（新增）

**验收标准**：
- [x] 列表查询使用 selectPage + QueryWrapper
- [x] markAsReviewed 幂等正确（active 重复调用不抛异常）
- [x] 启停用校验逻辑完整
- [x] 超期列表使用 7 个自然日
- [x] 编译通过

---

### T-04：AdminKnowledgePointController 实现（修复 BLOCK-1/N1）

**任务描述**：
1. 创建 `AdminKnowledgePointController`，包含：
   - `GET /api/admin/knowledge-points`：列表查询（`subject` 参数为 String，IMP-27 修复）
   - `GET /api/admin/knowledge-points/{id}`：详情查看
   - `PUT /api/admin/knowledge-points/{id}/status`：启停用
   - `PUT /api/admin/knowledge-points/{id}/review`：校对标记
   - `GET /api/admin/knowledge-points/overdue`：超期列表
2. **修复 BLOCK-1/N1**：所有接口使用 `StudentContext.getTenantId()`（与 `AdminGoalController` 一致）

**涉及文件**：
- `src/main/java/com/jiang/controller/AdminKnowledgePointController.java`（新增）

**验收标准**：
- [x] 接口可正常访问
- [x] 参数校验正确
- [x] N1 标注：Demo 阶段复用 StudentContext
- [x] 编译通过

---

### T-05：停用知识点展示规则实现（修复 IMP-14）

**任务描述**：
1. 在 `MasteryController` 中扩展 `getMasteryDashboard()` 方法，添加 `showDisabled` 参数
2. **修复 IMP-14**：`showDisabled` 参数为 `Boolean`（可 null），使用 `Boolean.TRUE.equals(showDisabled)` 避免 NPE
3. 在 `MasteryServiceImpl` 中实现停用知识点过滤逻辑，参数类型改为 `boolean`（原始类型）

**涉及文件**：
- `src/main/java/com/jiang/controller/MasteryController.java`（修改）
- `src/main/java/com/jiang/service/MasteryService.java`（修改）
- `src/main/java/com/jiang/service/impl/MasteryServiceImpl.java`（修改）

**验收标准**：
- [x] 默认隐藏停用知识点
- [x] showDisabled=true 时显示停用知识点
- [x] showDisabled=null 时不抛 NPE
- [x] 编译通过

---

### T-06：校对超期提醒定时任务（修复 IMP-13/15）

**任务描述**：
1. 创建 `KnowledgePointReviewTask` 定时任务类
2. **修复 IMP-13**：使用 7 个自然日（非工作日）
3. **修复 IMP-15**：`log.warn` 输出结构化告警信息（含知识点 ID、名称、超期天数）
4. 定时任务 `@Scheduled(cron = "0 0 3 * * ?")` 每天凌晨 3 点执行

**涉及文件**：
- `src/main/java/com/jiang/task/KnowledgePointReviewTask.java`（新增）

**验收标准**：
- [x] 定时任务每天凌晨 3 点执行
- [x] 超期知识点正确识别（7 个自然日）
- [x] 告警日志格式结构化
- [x] 编译通过

---

### T-07：单元测试

**任务描述**：
1. `KnowledgePointGovernanceServiceTest`：覆盖核心业务逻辑
   - `should_returnList_when_validFilters`
   - `should_returnDetail_when_kpExists`
   - `should_updateStatusToDisabled_when_noActiveGoals`
   - `should_throwException_when_kpInUse`
   - `should_markAsReviewed_when_statusPending`
   - `should_returnSuccess_when_markAsReviewedAndAlreadyActive`（修复 BLOCK-9 幂等测试）
   - `should_throwException_when_markAsReviewedAndDisabled`
   - `should_returnOverdueList_when_hasOverdueKp`
2. `MasteryServiceTest`：覆盖停用知识点展示
   - `should_hideDisabledKp_when_showDisabledFalse`
   - `should_showDisabledKp_when_showDisabledTrue`
   - `should_notThrowNPE_when_showDisabledNull`（修复 IMP-14 测试）

**涉及文件**：
- `src/test/java/com/jiang/service/KnowledgePointGovernanceServiceTest.java`（新增）
- `src/test/java/com/jiang/service/MasteryServiceTest.java`（修改）

**验收标准**：
- [x] 单元测试覆盖率 ≥ 80%
- [x] 所有测试通过
- [x] 编译通过

---

### T-08：集成测试

**任务描述**：
1. `AdminKnowledgePointControllerTest`：覆盖所有接口
   - `should_returnList_when_validRequest`
   - `should_returnDetail_when_kpExists`
   - `should_updateStatus_when_validInput`
   - `should_markAsReviewed_when_statusPending`
   - `should_returnSuccess_when_markAsReviewedAndAlreadyActive`（幂等测试）
   - `should_returnOverdueList_when_hasOverdueKp`
   - `should_throwException_when_invalidStatus`

**涉及文件**：
- `src/test/java/com/jiang/controller/AdminKnowledgePointControllerTest.java`（新增）

**验收标准**：
- [x] 集成测试覆盖率 ≥ 60%
- [x] 所有测试通过
- [x] 编译通过

---

### T-09：全量编译与测试验证

**任务描述**：
1. 执行 `mvn clean compile`，确保编译通过
2. 执行 `mvn test`，确保所有测试通过
3. 执行 `mvn package -DskipTests`，确保打包成功
4. 检查代码质量，无警告

**涉及文件**：
- 所有变更文件

**验收标准**：
- [x] 编译通过，无错误
- [x] 所有测试通过
- [x] 打包成功
- [x] 代码质量检查通过

---

## 任务依赖关系

```
T-01（DTO/VO 与错误码）
    ↓
T-02（Mapper 扩展）
    ↓
T-03（KnowledgePointGovernanceService 核心实现）
    ↓
T-04（AdminKnowledgePointController）
    ↓
T-05（停用知识点展示规则）
    ↓
T-06（校对超期提醒定时任务）
    ↓
T-07（单元测试）
    ↓
T-08（集成测试）
    ↓
T-09（全量编译与验证）
```

---

## 预估工时

| 任务 | 预估工时 |
|------|----------|
| T-01 | 1h |
| T-02 | 1.5h |
| T-03 | 3h |
| T-04 | 1.5h |
| T-05 | 2h |
| T-06 | 1h |
| T-07 | 2h |
| T-08 | 1.5h |
| T-09 | 1h |
| **总计** | **14.5h** |

---

**版本**：v3（修复 IMP-27）  
**创建日期**：2026-11-03  
**最后更新**：2026-11-04
