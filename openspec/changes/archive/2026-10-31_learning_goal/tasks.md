# Tasks：学习目标管理

> **Change ID**: `2026-10-31_learning_goal`  
> **关联 Design**: design.md

---

## Task 1：扩展 ErrorCodes 枚举 ✅

**任务描述**：
- 在 `ErrorCodes` 枚举中新增科目相关错误码（1090-1099）：`SUBJECT_NOT_FOUND`、`SUBJECT_NO_ACTIVE_KP`
- 新增目标相关扩展错误码（1032-1034）：`GOAL_ALREADY_ACTIVE`、`GOAL_NOT_MODIFIABLE`、`DATE_INVALID`
- **注意**：不新增 `SCORE_INVALID`，目标分数校验由 Bean Validation 注解在 DTO 层拦截

**预估时间**：0.25h

**涉及文件**：
- `src/main/java/com/jiang/common/exception/ErrorCodes.java`（修改）

---

## Task 2：创建 DTO/VO 类 ✅

**任务描述**：
- 创建 `CreateGoalRequest` 请求 DTO（含 @NotNull、@Size、@Min、@Max 校验注解）
- 创建 `UpdateGoalRequest` 请求 DTO（含 @Size、@Min、@Max 校验注解；null 语义：direction 传空字符串=清空、targetScore 不支持清空、endDate 只能延长）
- 创建 `GoalVO` 视图对象（含 subjectName 关联字段；N3 安全断言：学生端返回的 studentId 恒为当前登录学生）
- 创建 `SubjectVO` 视图对象（含 activeKnowledgePointCount 统计字段）

**预估时间**：0.5h

**涉及文件**：
- `src/main/java/com/jiang/dto/CreateGoalRequest.java`（新建）
- `src/main/java/com/jiang/dto/UpdateGoalRequest.java`（新建）
- `src/main/java/com/jiang/vo/GoalVO.java`（新建）
- `src/main/java/com/jiang/vo/SubjectVO.java`（新建）

---

## Task 3：创建 Service 接口 ✅

**任务描述**：
- 创建 `GoalService` 接口，定义 8 个方法：
  - `createGoal(CreateGoalRequest)` — 创建目标
  - `getActiveGoal(Long subjectId)` — 查看当前活跃目标（可选 subjectId 过滤，N2）
  - `getGoalList(int page, int size)` — 查看目标列表（学生端）
  - `getGoalDetail(Long goalId)` — 查看目标详情（学生端）
  - `updateGoal(Long goalId, UpdateGoalRequest)` — 修改目标
  - `completeGoal(Long goalId)` — 结束目标
  - `adminGetGoalList(Long studentId, Long subjectId, String status, int page, int size)` — 管理端目标列表
  - `adminGetGoalDetail(Long goalId)` — 管理端目标详情
- 创建 `SubjectService` 接口，定义 1 个方法：
  - `getAvailableSubjects()` — 查询可用科目列表

**预估时间**：0.25h

**涉及文件**：
- `src/main/java/com/jiang/service/GoalService.java`（新建）
- `src/main/java/com/jiang/service/SubjectService.java`（新建）

---

## Task 4：实现 GoalServiceImpl ✅

**任务描述**：
- 实现 `GoalService` 接口的全部 8 个方法
- 实现业务校验逻辑：
  - 创建目标：校验科目存在、科目有已启用知识点、无重复活跃目标、日期合法（endDate > startDate 且 startDate >= today）
  - 修改目标：校验目标存在且属于当前学生、目标状态为 active 且未过期、结束日期只能延长（不能缩短）
  - 结束目标：校验目标存在且属于当前学生、目标状态为 active
  - 查询接口（学生端）：强制 studentId + tenantId 过滤；getActiveGoal 判定口径为 status='active' AND endDate >= today；支持按 subjectId 过滤（N2）；无活跃目标返回 null（I2）
  - 查询接口（管理端）：仅 tenantId 过滤，不做 studentId 所有权校验；支持按 studentId/subjectId/status 筛选；tenantId 从 `StudentContext.getTenantId()` 获取（N1：Demo 阶段复用学生上下文）
- 实现 VO 转换：`toGoalVO(LearningGoal, String subjectName)` 方法

**预估时间**：2h

**涉及文件**：
- `src/main/java/com/jiang/service/impl/GoalServiceImpl.java`（新建）

---

## Task 5：实现 SubjectServiceImpl ✅

**任务描述**：
- 实现 `SubjectService` 接口的 `getAvailableSubjects()` 方法
- 采用单次 GROUP BY 聚合查询：`SELECT subject_id, COUNT(*) ... WHERE status='active' AND tenant_id=? GROUP BY subject_id`
- 在内存中与科目列表 join，过滤出有已启用知识点的科目
- 统计每个科目的已启用知识点数量，填入 `SubjectVO.activeKnowledgePointCount`

**预估时间**：0.5h

**涉及文件**：
- `src/main/java/com/jiang/service/impl/SubjectServiceImpl.java`（新建）

---

## Task 6：创建 GoalController ✅

**任务描述**：
- 实现学生端 6 个接口：
  - `POST /api/student/goal` — 创建目标
  - `GET /api/student/goal/active?subjectId=xxx` — 查看当前活跃目标（subjectId 可选，N2）
  - `GET /api/student/goal` — 查看目标列表（分页）
  - `GET /api/student/goal/{goalId}` — 查看目标详情
  - `PUT /api/student/goal/{goalId}` — 修改目标
  - `POST /api/student/goal/{goalId}/complete` — 结束目标
- 所有接口添加 Knife4j 注解（@Tag、@Operation）
- 所有接口返回 `Result<T>` 统一响应结构
- 所有接口通过 `StudentContext` 获取当前学生 ID

**预估时间**：1h

**涉及文件**：
- `src/main/java/com/jiang/controller/GoalController.java`（新建）

---

## Task 7：创建 SubjectController ✅

**任务描述**：
- 实现 1 个接口：
  - `GET /api/student/subject` — 查询可用科目列表
- 添加 Knife4j 注解
- 返回 `Result<List<SubjectVO>>`

**预估时间**：0.25h

**涉及文件**：
- `src/main/java/com/jiang/controller/SubjectController.java`（新建）

---

## Task 7A：创建 AdminGoalController（管理端） ✅

**任务描述**：
- 实现管理端 2 个查询接口：
  - `GET /api/admin/goal` — 查询目标列表（筛选 + 分页，参数：studentId/subjectId/status/page/size）
  - `GET /api/admin/goal/{goalId}` — 查询目标详情
- 管理端不做 studentId 所有权过滤，仅做 tenantId 隔离
- 管理端 tenantId 从 `StudentContext.getTenantId()` 获取（N1：Demo 阶段复用学生上下文）
- 添加 Knife4j 注解
- 返回 `Result<T>` 统一响应结构

**预估时间**：0.5h

**涉及文件**：
- `src/main/java/com/jiang/controller/AdminGoalController.java`（新建）

---

## Task 8：编写 GoalService 单元测试 ✅

**任务描述**：
- 测试创建目标成功场景（字段映射正确、status=active）
- 测试创建目标失败场景：科目不存在
- 测试创建目标失败场景：科目无已启用知识点
- 测试创建目标失败场景：同科目已有活跃目标
- 测试创建目标失败场景：日期不合法（endDate <= startDate）
- 测试创建目标失败场景：startDate 早于今天
- 测试查看活跃目标成功场景（status=active 且 endDate>=today）
- 测试查看活跃目标：按 subjectId 过滤（N2 多科目场景）
- 测试查看活跃目标：不传 subjectId 返回最新创建（N2）
- 测试查看活跃目标：已过期但未标记的目标不返回
- 测试查看目标列表成功场景（分页）
- 测试查看目标详情成功
- 测试查看目标详情失败：无权操作他人目标
- 测试修改目标成功场景（更新方向/分数/结束日期）
- 测试修改目标成功：direction 传空字符串时清空
- 测试修改目标失败场景：目标已完成
- 测试修改目标失败场景：目标已过期
- 测试修改目标失败场景：结束日期缩短被拒绝
- 测试结束目标成功场景（status 改为 completed）
- 测试结束目标失败场景：目标已完成
- 测试管理端查询目标列表成功（筛选+分页）
- 测试管理端查询目标详情成功（不做所有权校验）
- 测试管理端查询不存在目标失败
- Mock 策略：使用 Mockito mock Mapper 层，使用 `@MockedStatic<StudentContext>` 模拟登录态

**预估时间**：2.5h

**涉及文件**：
- `src/test/java/com/jiang/service/GoalServiceTest.java`（新建）

---

## Task 9：编写 SubjectService 单元测试 ✅

**任务描述**：
- 测试查询可用科目列表成功场景（仅返回有 active KP 的科目）
- 测试查询可用科目列表空结果场景（所有科目均无 active KP）
- Mock 策略：使用 Mockito mock Mapper 层

**预估时间**：0.5h

**涉及文件**：
- `src/test/java/com/jiang/service/SubjectServiceTest.java`（新建）

---

## Task 10：编写 GoalController 集成测试 ✅

**任务描述**：
- 测试创建目标接口正常返回场景
- 测试创建目标接口参数校验失败场景（subjectId 为空、@NotNull 缺失）
- 测试查看活跃目标、按 subjectId 过滤、目标列表、目标详情接口
- 测试修改目标接口、结束目标接口
- 测试未登录场景（无 JWT 返回 401）
- 测试查看目标详情失败：无权操作他人目标

**预估时间**：1.5h

**涉及文件**：
- `src/test/java/com/jiang/controller/GoalControllerTest.java`（新建）

---

## Task 10A：编写 AdminGoalController 集成测试 ✅

**任务描述**：
- 测试查询目标列表接口（筛选+分页）
- 测试查询目标详情接口
- 测试查询不存在的目标返回 1030
- 测试未登录场景

**预估时间**：1h

**涉及文件**：
- `src/test/java/com/jiang/controller/AdminGoalControllerTest.java`（新建）

---

## Task 11：编写 SubjectController 集成测试 ✅

**任务描述**：
- 测试查询可用科目列表接口正常返回
- 测试未登录场景

**预估时间**：0.25h

**涉及文件**：
- `src/test/java/com/jiang/controller/SubjectControllerTest.java`（新建）

---

## 汇总

| Task | 预估时间 | 依赖 |
|------|---------|------|
| Task 1：扩展 ErrorCodes | 0.25h | 无 |
| Task 2：DTO/VO 类 | 0.5h | 无 |
| Task 3：Service 接口 | 0.25h | Task 2 |
| Task 4：GoalServiceImpl | 2h | Task 1, 2, 3 |
| Task 5：SubjectServiceImpl | 0.5h | Task 2, 3 |
| Task 6：GoalController | 1h | Task 4 |
| Task 7：SubjectController | 0.25h | Task 5 |
| Task 7A：AdminGoalController | 0.5h | Task 4 |
| Task 8：GoalService 单元测试 | 2.5h | Task 4 |
| Task 9：SubjectService 单元测试 | 0.5h | Task 5 |
| Task 10：GoalController 集成测试 | 1.5h | Task 6 |
| Task 10A：AdminGoalController 集成测试 | 1h | Task 7A |
| Task 11：SubjectController 集成测试 | 0.25h | Task 7 |
| **总计** | **12h** | — |

**备注**：
- 本 change 不修改 DB Schema（DDL 不变），所有表已在 foundation change 中创建
- 本 change 不实现目标自动过期（定时任务），Demo 阶段由管理端手动标记
- 本 change 不实现目标创建后触发 Agent 生成计划（后续 change 实现）
- 所有学生端接口通过 `StudentContext` 获取当前学生 ID，不依赖前端传参
- 管理端接口暂不实现角色/权限校验（Demo 阶段仅 JWT 鉴权）
- 管理端 tenantId 从 `StudentContext` 获取（N1：Demo 阶段复用，后续引入管理员 RBAC 时重构）
- `PageResult.of(Long total, Integer page, Integer size, List<T> records)` 为已存在工厂方法（I5）
