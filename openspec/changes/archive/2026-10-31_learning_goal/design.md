# Design：学习目标管理

> **Change ID**: `2026-10-31_learning_goal`  
> **关联 Proposal**: proposal.md

---

## 1. 技术方案

### 1.1 技术栈

| 技术 | 说明 |
|------|------|
| Spring Boot 3.2.x | Web 框架 |
| MyBatis-Plus | ORM 框架 |
| MySQL 5.7+ | 数据库 |
| JWT | 鉴权 |
| Knife4j | 接口文档 |

### 1.2 包结构

```
com.jiang
├── controller
│   ├── GoalController              # 学生端目标管理接口（新建）
│   ├── AdminGoalController         # 管理端目标管理接口（新建）
│   └── SubjectController           # 科目查询接口（新建）
├── service
│   ├── GoalService                 # 目标管理业务接口（新建）
│   ├── SubjectService              # 科目查询业务接口（新建）
│   └── impl
│       ├── GoalServiceImpl         # 目标管理业务实现（新建）
│       └── SubjectServiceImpl      # 科目查询业务实现（新建）
├── dto
│   ├── CreateGoalRequest           # 创建目标请求 DTO（新建）
│   └── UpdateGoalRequest           # 修改目标请求 DTO（新建）
├── vo
│   ├── GoalVO                      # 目标视图对象（新建）
│   └── SubjectVO                   # 科目视图对象（新建）
└── mapper
    ├── LearningGoalMapper          # 已存在
    └── SubjectMapper               # 已存在
```

---

## 2. 核心流程设计

### 2.1 创建目标流程

```
学生端                    GoalController           GoalService                DB
  |                           |                        |                       |
  |-- POST /goal ------------>|                        |                       |
  |   {subjectId, direction,  |                        |                       |
  |    targetScore, startDate,|                        |                       |
  |    endDate}               |                        |                       |
  |                           |-- 参数校验 ------------>|                       |
  |                           |                        |-- 校验科目存在 -------->|
  |                           |                        |-- 校验科目有 active KP ->|
  |                           |                        |-- 校验无重复 active ---->|
  |                           |                        |-- 校验日期合法 ---------->|
  |                           |                        |                         |
  |                           |                        |-- 创建目标 ------------->|
  |                           |                        |   (status=active)        |
  |                           |                        |                         |
  |                           |<--- GoalVO ------------|                         |
  |<-- Result<GoalVO> --------|                        |                         |
```

### 2.2 业务校验规则

| 校验项 | 规则 | 错误码 |
|--------|------|--------|
| 科目存在 | subjectId 在 subject 表中存在 | 1090 SUBJECT_NOT_FOUND |
| 科目有已启用知识点 | knowledge_point 表中该科目下存在 status='active' 的记录 | 1091 SUBJECT_NO_ACTIVE_KP |
| 无重复活跃目标 | 同一 studentId + subjectId 不存在 status='active' 的目标 | 1032 GOAL_ALREADY_ACTIVE |
| 日期合法（创建） | endDate > startDate，且 startDate >= today | 1034 DATE_INVALID |
| 日期合法（修改） | 新 endDate >= 原 endDate（只能延长） | 1034 DATE_INVALID |

> **I1 说明**：`DATE_INVALID(1034)` 同时承载创建和修改两种日期校验失败场景。前端统一提示"日期不合法"即可，无需按场景区分提示文案。若后续产品需要精细化提示（如"结束日期不能早于开始日期" vs "结束日期只能延长不能缩短"），可拆分为 `DATE_RANGE_INVALID` 和 `DATE_SHORTEN_INVALID` 两个错误码。
| 目标分数范围 | targetScore ∈ [0, 150]（满分按 150 计）或 null | — Bean Validation 拦截 |
| 目标可修改 | 目标 status='active' 且 endDate >= today | 1033 GOAL_NOT_MODIFIABLE |
| 目标所有权 | studentId 匹配（学生端接口） | 1030 GOAL_NOT_FOUND |

### 2.3 目标状态流转

```
         创建
          ↓
      [active] ──── 学生结束 ────→ [completed]
          ↓
      到期（Demo 阶段不自动，后续 change 实现）
          ↓
      [expired]
```

### 2.4 认证上下文说明（N1）

> **背景**：当前系统仅有学生端微信小程序登录（WeChat openid → JWT），不存在独立的管理员认证体系。`StudentContext`（ThreadLocal）虽以"Student"命名，但实际是 **所有已认证请求的唯一用户上下文载体**。

| 项目 | 说明 |
|------|------|
| 拦截器 | `JwtInterceptor` 拦截 `/api/**`（含 `/api/admin/**`），排除 `/api/auth/login` |
| 上下文注入 | JWT 解析后将 `studentId`、`openid`、`tenantId` 写入 `StudentContext`（ThreadLocal） |
| 管理端 tenantId 来源 | Demo 阶段管理端请求复用同一 JWT 体系，通过 `StudentContext.getTenantId()` 获取租户 ID |
| 已知限制 | `StudentContext` 命名对学生端有强语义，管理端复用存在语义混淆；后续引入管理员 RBAC 时应重构为通用 `UserContext` 或独立 `AdminContext` |
| 本 change 策略 | **不做命名重构**（超出本 change 范围），但所有管理端方法处显式注释说明 tenantId 来源，避免实现者误用 |

---

## 3. 详细设计

### 3.1 DTO 设计

#### CreateGoalRequest

```java
@Data
public class CreateGoalRequest {
    @NotNull(message = "科目 ID 不能为空")
    private Long subjectId;
    
    @Size(max = 500, message = "学习方向描述不能超过 500 字")
    private String direction;
    
    @Min(value = 0, message = "目标分数不能小于 0")
    @Max(value = 150, message = "目标分数不能大于 150")
    private Integer targetScore;
    
    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;
    
    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;
}
```

#### UpdateGoalRequest

> **null 语义说明**：`direction` 和 `targetScore` 为 null 时表示**不修改该字段**（而非清空）。若需清空 `direction`，前端传空字符串 `""`；`targetScore` 不支持清空（业务上无意义）。`endDate` 为 null 时表示不修改结束日期。

```java
@Data
public class UpdateGoalRequest {
    @Size(max = 500, message = "学习方向描述不能超过 500 字")
    private String direction;        // null=不修改，空字符串=清空
    
    @Min(value = 0, message = "目标分数不能小于 0")
    @Max(value = 150, message = "目标分数不能大于 150")
    private Integer targetScore;     // null=不修改；不支持清空（业务无意义）
    
    private LocalDate endDate;       // null=不修改；只能延长，不能缩短
}
```

### 3.2 VO 设计

#### GoalVO

> **N3 安全断言**：学生端所有查询接口（`getActiveGoal`、`getGoalList`、`getGoalDetail`）均强制 `studentId = StudentContext.getStudentId()` 过滤 + 所有权校验，因此学生端返回的 `GoalVO.studentId` **恒为当前登录学生**，不存在越权泄露他人 studentId 的可能。管理端返回实际学生 ID，用于管理员查看不同学生的目标。
>
> **后续增强（不在本 change 范围）**：管理端 GoalVO 可增加学生姓名/学号等可读信息，提升管理员使用体验。

```java
@Data
@Builder
public class GoalVO {
    private Long goalId;
    private Long studentId;          // 学生端恒等于当前登录学生；管理端返回实际学生 ID
    private Long subjectId;
    private String subjectName;      // 关联查询科目名称
    private String direction;
    private Integer targetScore;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;           // active / completed / expired（见下方 GoalStatus 枚举定义）
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

#### GoalStatus 枚举定义

> 对应 `com.jiang.model.enums.GoalStatus`（已在 foundation change 中创建），此处列出完整定义以确保 Design 与代码一致：

| 枚举值 | code 字符串 | 含义 |
|--------|------------|------|
| `ACTIVE` | `"active"` | 进行中 |
| `COMPLETED` | `"completed"` | 已完成（学生主动结束） |
| `EXPIRED` | `"expired"` | 已过期（Demo 阶段不自动标记，后续 change 实现） |

#### SubjectVO

```java
@Data
@Builder
public class SubjectVO {
    private Long subjectId;
    private String name;
    private String stage;            // 初中 / 高中
    private String description;
    private Integer activeKnowledgePointCount;  // 已启用知识点数量
}
```

### 3.3 GoalService 接口

> **I5 说明**：`PageResult.of(Long total, Integer page, Integer size, List<T> records)` 为已存在的工厂方法（参见 `com.jiang.common.result.PageResult`），本 change 直接复用，不修改签名。

#### LearningGoal 实体字段映射（N2）

> 以下列出 GoalVO 与 LearningGoal 实体的字段对应关系，避免实现时字段名不匹配：

| GoalVO 字段 | LearningGoal 字段 | 数据库列名 | 类型 |
|------------|-------------------|------------|------|
| goalId | goalId | goal_id | Long |
| studentId | studentId | student_id | Long |
| subjectId | subjectId | subject_id | Long |
| subjectName | — (关联查询) | — | String |
| direction | direction | direction | String |
| targetScore | targetScore | target_score | Integer |
| startDate | startDate | start_date | LocalDate |
| endDate | endDate | end_date | LocalDate |
| status | status | status | String |
| createdAt | createTime (BaseEntity) | create_time | LocalDateTime |
| updatedAt | updateTime (BaseEntity) | update_time | LocalDateTime |
| — | tenantId (BaseEntity) | tenant_id | String |

```java
public interface GoalService {
    /** 创建学习目标 */
    GoalVO createGoal(CreateGoalRequest request);
    
    /** 查看当前活跃目标（学生端，按 studentId + 可选 subjectId 查询） */
    GoalVO getActiveGoal(Long subjectId);
    
    /** 查看目标列表（学生端，含历史，分页） */
    PageResult<GoalVO> getGoalList(int page, int size);
    
    /** 查看目标详情（学生端，校验所有权） */
    GoalVO getGoalDetail(Long goalId);
    
    /** 修改目标 */
    GoalVO updateGoal(Long goalId, UpdateGoalRequest request);
    
    /** 结束目标 */
    void completeGoal(Long goalId);
    
    /** 查询目标列表（管理端，筛选+分页） */
    PageResult<GoalVO> adminGetGoalList(Long studentId, Long subjectId, String status, int page, int size);
    
    /** 查看目标详情（管理端） */
    GoalVO adminGetGoalDetail(Long goalId);
}
```

### 3.4 SubjectService 接口

```java
public interface SubjectService {
    /** 查询可用科目列表（有已启用知识点的科目） */
    List<SubjectVO> getAvailableSubjects();
}
```

### 3.5 GoalServiceImpl 核心逻辑

#### 创建目标

```java
@Override
public GoalVO createGoal(CreateGoalRequest request) {
    Long studentId = StudentContext.getStudentId();
    String tenantId = StudentContext.getTenantId();
    
    // 1. 校验科目存在
    Subject subject = subjectMapper.selectById(request.getSubjectId());
    if (subject == null || !tenantId.equals(subject.getTenantId())) {
        throw new BusinessException(ErrorCodes.SUBJECT_NOT_FOUND);
    }
    
    // 2. 校验科目有已启用知识点
    long activeKpCount = knowledgePointMapper.selectCount(
        new LambdaQueryWrapper<KnowledgePoint>()
            .eq(KnowledgePoint::getSubjectId, request.getSubjectId())
            .eq(KnowledgePoint::getStatus, "active")
            .eq(KnowledgePoint::getTenantId, tenantId)
    );
    if (activeKpCount == 0) {
        throw new BusinessException(ErrorCodes.SUBJECT_NO_ACTIVE_KP);
    }
    
    // 3. 校验无重复活跃目标
    long activeGoalCount = learningGoalMapper.selectCount(
        new LambdaQueryWrapper<LearningGoal>()
            .eq(LearningGoal::getStudentId, studentId)
            .eq(LearningGoal::getSubjectId, request.getSubjectId())
            .eq(LearningGoal::getStatus, GoalStatus.ACTIVE.getCode())
            .eq(LearningGoal::getTenantId, tenantId)
    );
    if (activeGoalCount > 0) {
        throw new BusinessException(ErrorCodes.GOAL_ALREADY_ACTIVE);
    }
    
    // 4. 校验日期
    if (!request.getEndDate().isAfter(request.getStartDate())) {
        throw new BusinessException(ErrorCodes.DATE_INVALID);
    }
    // I2：startDate 不得早于今天（本系统不支持补录历史目标）
    if (request.getStartDate().isBefore(LocalDate.now())) {
        throw new BusinessException(ErrorCodes.DATE_INVALID);
    }
    
    // 5. 创建目标
    LearningGoal goal = new LearningGoal();
    goal.setStudentId(studentId);
    goal.setSubjectId(request.getSubjectId());
    goal.setDirection(request.getDirection());
    goal.setTargetScore(request.getTargetScore());
    goal.setStartDate(request.getStartDate());
    goal.setEndDate(request.getEndDate());
    goal.setStatus(GoalStatus.ACTIVE.getCode());
    goal.setTenantId(tenantId);
    
    learningGoalMapper.insert(goal);
    
    return toGoalVO(goal, subject.getName());
}
```

#### 修改目标

```java
@Override
public GoalVO updateGoal(Long goalId, UpdateGoalRequest request) {
    Long studentId = StudentContext.getStudentId();
    String tenantId = StudentContext.getTenantId();
    
    // 1. 查询目标（校验所有权）
    LearningGoal goal = learningGoalMapper.selectById(goalId);
    if (goal == null || !studentId.equals(goal.getStudentId()) || !tenantId.equals(goal.getTenantId())) {
        throw new BusinessException(ErrorCodes.GOAL_NOT_FOUND);
    }
    
    // 2. 校验目标可修改（status=active 且未过期）
    if (!GoalStatus.ACTIVE.getCode().equals(goal.getStatus())) {
        throw new BusinessException(ErrorCodes.GOAL_NOT_MODIFIABLE);
    }
    if (goal.getEndDate().isBefore(LocalDate.now())) {
        throw new BusinessException(ErrorCodes.GOAL_EXPIRED);
    }
    
    // 3. 更新字段（null = 不修改，空字符串 = 清空 direction）
    if (request.getDirection() != null) {
        goal.setDirection(request.getDirection());  // 空字符串""时清空
    }
    if (request.getTargetScore() != null) {
        goal.setTargetScore(request.getTargetScore());
    }
    if (request.getEndDate() != null) {
        // 结束日期只能延长，不能缩短（M1：删除冗余的"不能早于今天"校验，
        // 因为 goal.getEndDate() >= today 已在上方过期校验中保证）
        if (request.getEndDate().isBefore(goal.getEndDate())) {
            throw new BusinessException(ErrorCodes.DATE_INVALID);
        }
        goal.setEndDate(request.getEndDate());
    }
    
    learningGoalMapper.updateById(goal);
    
    Subject subject = subjectMapper.selectById(goal.getSubjectId());
    return toGoalVO(goal, subject.getName());
}
```

#### 结束目标

```java
@Override
public void completeGoal(Long goalId) {
    Long studentId = StudentContext.getStudentId();
    String tenantId = StudentContext.getTenantId();
    
    LearningGoal goal = learningGoalMapper.selectById(goalId);
    if (goal == null || !studentId.equals(goal.getStudentId()) || !tenantId.equals(goal.getTenantId())) {
        throw new BusinessException(ErrorCodes.GOAL_NOT_FOUND);
    }
    
    if (!GoalStatus.ACTIVE.getCode().equals(goal.getStatus())) {
        throw new BusinessException(ErrorCodes.GOAL_NOT_MODIFIABLE);
    }
    
    goal.setStatus(GoalStatus.COMPLETED.getCode());
    learningGoalMapper.updateById(goal);
}
```

#### 查看当前活跃目标（B2 + N2）

> **判定口径**：`status='active' AND end_date >= today`。若目标 status 仍为 active 但已过期（Demo 阶段不自动标记 expired），则不视为活跃目标，返回 null。
>
> **多科目语义（N2）**：同一学生可拥有多个不同科目的 active 目标。`subjectId` 为可选参数：
> - 传 subjectId：返回该科目的活跃目标（精确匹配）
> - 不传 subjectId：返回最新创建的活跃目标（按 createTime 倒序取第一条）
>
> **subjectId 校验策略（R1）**：`subjectId` 仅作过滤条件，不校验其存在性或 tenant 归属。查询条件已包含 `studentId + tenantId`，无跨租户泄露风险。传入无效 subjectId 时返回 null，语义等同“该科目无活跃目标”。
>
> **null 返回约定（I2）**：无活跃目标时返回 `Result.success(null)`，前端展示“暂无活跃目标”并引导学生创建。
>
> **实体字段映射（N2）**：`LearningGoal` 继承 `BaseEntity`，时间字段为 `createTime`（非 `createDate`），对应数据库列 `create_time`。

```java
@Override
public GoalVO getActiveGoal(Long subjectId) {
    Long studentId = StudentContext.getStudentId();
    String tenantId = StudentContext.getTenantId();
    
    LambdaQueryWrapper<LearningGoal> wrapper = new LambdaQueryWrapper<LearningGoal>()
        .eq(LearningGoal::getStudentId, studentId)
        .eq(LearningGoal::getStatus, GoalStatus.ACTIVE.getCode())
        .ge(LearningGoal::getEndDate, LocalDate.now())  // 未过期
        .eq(LearningGoal::getTenantId, tenantId);
    
    if (subjectId != null) {
        wrapper.eq(LearningGoal::getSubjectId, subjectId);
    }
    
    wrapper.orderByDesc(LearningGoal::getCreateTime)  // N2：字段名为 createTime（继承 BaseEntity）
           .last("LIMIT 1");
    
    LearningGoal goal = learningGoalMapper.selectOne(wrapper);
    
    if (goal == null) {
        return null;  // I2：无活跃目标，Controller 返回 Result.success(null)
    }
    
    Subject subject = subjectMapper.selectById(goal.getSubjectId());
    return toGoalVO(goal, subject != null ? subject.getName() : null);
}
```

#### 查看目标列表（学生端，B2）

```java
@Override
public PageResult<GoalVO> getGoalList(int page, int size) {
    Long studentId = StudentContext.getStudentId();
    String tenantId = StudentContext.getTenantId();
    
    Page<LearningGoal> pageParam = new Page<>(page, size);
    Page<LearningGoal> result = learningGoalMapper.selectPage(pageParam,
        new LambdaQueryWrapper<LearningGoal>()
            .eq(LearningGoal::getStudentId, studentId)
            .eq(LearningGoal::getTenantId, tenantId)
            .orderByDesc(LearningGoal::getStartDate)
    );
    
    List<GoalVO> voList = result.getRecords().stream()
        .map(goal -> {
            Subject subject = subjectMapper.selectById(goal.getSubjectId());
            return toGoalVO(goal, subject != null ? subject.getName() : null);
        })
        .collect(Collectors.toList());
    
    return PageResult.of(result.getTotal(), page, size, voList);
}
```

#### 查看目标详情（学生端，含所有权校验，B2）

```java
@Override
public GoalVO getGoalDetail(Long goalId) {
    Long studentId = StudentContext.getStudentId();
    String tenantId = StudentContext.getTenantId();
    
    LearningGoal goal = learningGoalMapper.selectById(goalId);
    // 所有权校验：studentId + tenantId 必须匹配
    if (goal == null || !studentId.equals(goal.getStudentId()) || !tenantId.equals(goal.getTenantId())) {
        throw new BusinessException(ErrorCodes.GOAL_NOT_FOUND);
    }
    
    Subject subject = subjectMapper.selectById(goal.getSubjectId());
    return toGoalVO(goal, subject != null ? subject.getName() : null);
}
```

#### 管理端查询目标列表（B1）

> **与学生端差异**：管理端不做 studentId 所有权过滤，仅做 tenantId 隔离。支持按 studentId、subjectId、status 筛选。
>
> **N1 认证上下文说明**：管理端 tenantId 同样从 `StudentContext.getTenantId()` 获取（参见 §2.4），Demo 阶段无独立管理员认证体系。

```java
@Override
public PageResult<GoalVO> adminGetGoalList(Long studentId, Long subjectId, String status, int page, int size) {
    String tenantId = StudentContext.getTenantId();  // N1：Demo 阶段管理端复用 StudentContext（参见 §2.4）
    
    LambdaQueryWrapper<LearningGoal> wrapper = new LambdaQueryWrapper<LearningGoal>()
        .eq(LearningGoal::getTenantId, tenantId);
    
    if (studentId != null) {
        wrapper.eq(LearningGoal::getStudentId, studentId);
    }
    if (subjectId != null) {
        wrapper.eq(LearningGoal::getSubjectId, subjectId);
    }
    if (status != null && !status.isBlank()) {
        wrapper.eq(LearningGoal::getStatus, status);
    }
    wrapper.orderByDesc(LearningGoal::getStartDate);
    
    Page<LearningGoal> pageParam = new Page<>(page, size);
    Page<LearningGoal> result = learningGoalMapper.selectPage(pageParam, wrapper);
    
    List<GoalVO> voList = result.getRecords().stream()
        .map(goal -> {
            Subject subject = subjectMapper.selectById(goal.getSubjectId());
            return toGoalVO(goal, subject != null ? subject.getName() : null);
        })
        .collect(Collectors.toList());
    
    return PageResult.of(result.getTotal(), page, size, voList);
}
```

#### 管理端查询目标详情（B1 + M4）

> **语义说明**：管理端不做 studentId 所有权校验，仅校验 tenantId 隔离。目标不存在时复用 `GOAL_NOT_FOUND(1030)` 错误码。
>
> **N1 认证上下文说明**：管理端 tenantId 同样从 `StudentContext.getTenantId()` 获取（参见 §2.4）。

```java
@Override
public GoalVO adminGetGoalDetail(Long goalId) {
    String tenantId = StudentContext.getTenantId();  // N1：Demo 阶段管理端复用 StudentContext（参见 §2.4）
    
    LearningGoal goal = learningGoalMapper.selectById(goalId);
    // 仅校验 tenantId，不做 studentId 所有权过滤
    if (goal == null || !tenantId.equals(goal.getTenantId())) {
        throw new BusinessException(ErrorCodes.GOAL_NOT_FOUND);
    }
    
    Subject subject = subjectMapper.selectById(goal.getSubjectId());
    return toGoalVO(goal, subject != null ? subject.getName() : null);
}
```

### 3.6 SubjectServiceImpl 核心逻辑

> **M3 修正**：采用单次 GROUP BY 聚合查询替代 N+1 循环查询，避免科目数量增长带来的性能问题。

```java
@Override
public List<SubjectVO> getAvailableSubjects() {
    String tenantId = StudentContext.getTenantId();
    
    // 单次聚合查询：按 subject_id 分组统计已启用知识点数量
    List<Map<String, Object>> counts = knowledgePointMapper.selectMaps(
        new QueryWrapper<KnowledgePoint>()
            .select("subject_id AS subjectId", "COUNT(*) AS cnt")
            .eq("status", "active")
            .eq("tenant_id", tenantId)
            .groupBy("subject_id")
    );
    
    // 构建 subjectId → count 映射
    Map<Long, Integer> countMap = counts.stream()
        .collect(Collectors.toMap(
            m -> ((Number) m.get("subjectId")).longValue(),
            m -> ((Number) m.get("cnt")).intValue()
        ));
    
    // 查询所有科目，过滤出有已启用知识点的科目
    List<Subject> subjects = subjectMapper.selectList(
        new LambdaQueryWrapper<Subject>().eq(Subject::getTenantId, tenantId)
    );
    
    return subjects.stream()
        .filter(s -> countMap.containsKey(s.getSubjectId()))
        .map(s -> SubjectVO.builder()
            .subjectId(s.getSubjectId())
            .name(s.getName())
            .stage(s.getStage())
            .description(s.getDescription())
            .activeKnowledgePointCount(countMap.get(s.getSubjectId()))
            .build())
        .collect(Collectors.toList());
}
```

> **技术债登记**：
> - **TD-01**：`knowledge_point` 表当前缺少 `(tenant_id, subject, status)` 联合索引，GROUP BY 查询可能全表扫描。当前科目数量有限（<20）且知识点表数据量可控，性能可接受。后续数据量增长时需补充索引。
> - **TD-02**：`GoalServiceImpl.getGoalList()` 和 `adminGetGoalList()` 中，将 LearningGoal 转为 GoalVO 时逐条调用 `subjectMapper.selectById()` 获取科目名称，存在 N+1 查询模式。当前分页 size 默认 10，影响可控。后续可优化为先批量 `selectBatchIds()` 获取科目列表，再在内存中 map join。

### 3.7 Controller 设计

#### GoalController（学生端）

```java
@RestController
@RequestMapping("/api/student/goal")
@Tag(name = "学生学习目标", description = "学生端学习目标管理接口")
public class GoalController {
    
    @PostMapping
    @Operation(summary = "创建学习目标")
    public Result<GoalVO> createGoal(@RequestBody @Valid CreateGoalRequest request) {
        return Result.success(goalService.createGoal(request));
    }
    
    @GetMapping("/active")
    @Operation(summary = "查看当前活跃目标")
    public Result<GoalVO> getActiveGoal(
            @RequestParam(required = false) Long subjectId) {  // N2：可选参数，支持按科目查询
        return Result.success(goalService.getActiveGoal(subjectId));
    }
    
    @GetMapping
    @Operation(summary = "查看目标列表（含历史）")
    public Result<PageResult<GoalVO>> getGoalList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(goalService.getGoalList(page, size));
    }
    
    @GetMapping("/{goalId}")
    @Operation(summary = "查看目标详情")
    public Result<GoalVO> getGoalDetail(@PathVariable Long goalId) {
        return Result.success(goalService.getGoalDetail(goalId));
    }
    
    @PutMapping("/{goalId}")
    @Operation(summary = "修改目标")
    public Result<GoalVO> updateGoal(@PathVariable Long goalId,
                                      @RequestBody @Valid UpdateGoalRequest request) {
        return Result.success(goalService.updateGoal(goalId, request));
    }
    
    @PostMapping("/{goalId}/complete")
    @Operation(summary = "结束目标")
    public Result<Void> completeGoal(@PathVariable Long goalId) {
        goalService.completeGoal(goalId);
        return Result.success();
    }
}
```

#### SubjectController

```java
@RestController
@RequestMapping("/api/student/subject")
@Tag(name = "科目管理", description = "科目查询接口")
public class SubjectController {
    
    @GetMapping
    @Operation(summary = "查询可用科目列表")
    public Result<List<SubjectVO>> getAvailableSubjects() {
        return Result.success(subjectService.getAvailableSubjects());
    }
}
```

#### AdminGoalController（管理端，B1）

> **与学生端 GoalController 的差异**：
> - 管理端不做 studentId 所有权过滤（可查看任意学生的目标）
> - 支持按 studentId、subjectId、status 筛选
> - 仅提供查询接口，不提供创建/修改/结束操作（管理端为只读视图）

```java
@RestController
@RequestMapping("/api/admin/goal")
@Tag(name = "管理端 - 学习目标", description = "管理端学习目标查询接口")
public class AdminGoalController {
    
    @GetMapping
    @Operation(summary = "查询目标列表（筛选 + 分页）")
    public Result<PageResult<GoalVO>> getGoalList(
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(goalService.adminGetGoalList(studentId, subjectId, status, page, size));
    }
    
    @GetMapping("/{goalId}")
    @Operation(summary = "查看目标详情")
    public Result<GoalVO> getGoalDetail(@PathVariable Long goalId) {
        return Result.success(goalService.adminGetGoalDetail(goalId));
    }
}
```

### 3.8 ErrorCodes 扩展

需要在 `ErrorCodes` 枚举中新增以下错误码：

```java
// 科目相关（1090-1099）
SUBJECT_NOT_FOUND(1090, "科目不存在"),
SUBJECT_NO_ACTIVE_KP(1091, "科目下无已启用知识点"),

// 目标相关扩展（1030-1039 已有部分，补充）
GOAL_ALREADY_ACTIVE(1032, "该科目已有进行中的学习目标"),
GOAL_NOT_MODIFIABLE(1033, "目标不可修改（已完成或已过期）"),
DATE_INVALID(1034, "日期不合法（结束日期须晚于开始日期，或开始日期不得早于今天）"),
```

> **I1 说明**：原 `SCORE_INVALID(1035)` 已移除。目标分数范围校验完全由 DTO 的 `@Min(0)/@Max(150)` Bean Validation 注解在 Controller 层拦截，Service 层不再重复校验。若后续 Service 被非 HTTP 入口调用，可在 Service 层补充防御性校验，但当前无此场景。
>
> **M4 说明**：`GOAL_NOT_FOUND(1030)` 同时适用于学生端和管理端。学生端语义为“目标不存在或无权操作”（含 studentId 所有权校验）；管理端语义为“目标不存在或不属于当前租户”（仅含 tenantId 校验，不做 studentId 所有权过滤）。

---

## 4. 数据库变更

### 4.1 本 change 不创建新表

所有目标表已在 foundation change 中创建：
- `learning_goal`、`subject`、`knowledge_point`

### 4.2 本 change 不修改 DB Schema（DDL 不变）

所有字段映射均在现有 schema 范围内完成。

### 4.3 索引使用分析

| 查询场景 | 使用索引 | 说明 |
|----------|----------|------|
| 按 studentId 查目标列表 | `idx_student(tenant_id, student_id)` | 已存在 |
| 按 status 筛选目标 | `idx_status(tenant_id, status)` | 已存在 |
| 按 subjectId + status 聚合查询知识点 | ⚠️ 技术债 TD-01 | knowledge_point 表缺少 `(tenant_id, subject, status)` 联合索引，当前数据量可控，后续需补充 |
| 按 studentId+subjectId+status 查重 | 组合查询 | 利用现有索引组合 |
| 目标列表转 VO 时逐条查科目名称 | ⚠️ 技术债 TD-02 | GoalServiceImpl.getGoalList() / adminGetGoalList() 中 N+1 查询，每行 goal 单独 selectById 获取科目名称，建议改为批量查询 |

---

## 5. 异常处理设计

| 异常场景 | 错误码 | HTTP 状态码 | 处理方式 |
|---------|--------|------------|----------|
| 科目不存在 | 1090 | 200 | 返回 BusinessException |
| 科目无已启用知识点 | 1091 | 200 | 返回 BusinessException |
| 已有同科目活跃目标 | 1032 | 200 | 返回 BusinessException |
| 日期不合法 | 1034 | 200 | 返回 BusinessException |
| 目标不存在（学生端） | 1030 | 200 | 返回 BusinessException（含所有权校验） |
| 目标不存在（管理端） | 1030 | 200 | 返回 BusinessException（仅 tenantId 校验） |
| 目标不可修改 | 1033 | 200 | 返回 BusinessException |
| 目标已过期 | 1031 | 200 | 返回 BusinessException |
| 无权操作他人目标 | 1030 | 200 | 复用 GOAL_NOT_FOUND（学生端） |
| 目标分数不合法 | — | 400 | Bean Validation 拦截，不进 Service 层 |

---

## 6. 隐性约定对齐

| 约定 | 本 change 实现方式 |
|------|-------------------|
| 约定 #1：统一响应结构 | 所有接口返回 `Result<T>` |
| 约定 #2：异常处理规范 | 业务异常使用 `BusinessException` |
| 约定 #3：分页参数规范 | page 从 1 开始，size 默认 10 |
| 约定 #4：时间格式规范 | 使用 `LocalDateTime` / `LocalDate` |
| 约定 #7：tenant_id 隔离 | 所有查询从 `StudentContext` 获取 tenantId（管理端复用同一上下文，参见 §2.4） |

---

## 7. 文件清单

| 文件路径 | 说明 |
|---------|------|
| `src/main/java/com/jiang/controller/GoalController.java` | 学生端目标管理 Controller（新建） |
| `src/main/java/com/jiang/controller/AdminGoalController.java` | 管理端目标查询 Controller（新建） |
| `src/main/java/com/jiang/controller/SubjectController.java` | 科目查询 Controller（新建） |
| `src/main/java/com/jiang/service/GoalService.java` | 目标管理 Service 接口（新建） |
| `src/main/java/com/jiang/service/SubjectService.java` | 科目查询 Service 接口（新建） |
| `src/main/java/com/jiang/service/impl/GoalServiceImpl.java` | 目标管理 Service 实现（新建） |
| `src/main/java/com/jiang/service/impl/SubjectServiceImpl.java` | 科目查询 Service 实现（新建） |
| `src/main/java/com/jiang/dto/CreateGoalRequest.java` | 创建目标请求 DTO（新建） |
| `src/main/java/com/jiang/dto/UpdateGoalRequest.java` | 修改目标请求 DTO（新建） |
| `src/main/java/com/jiang/vo/GoalVO.java` | 目标视图对象（新建） |
| `src/main/java/com/jiang/vo/SubjectVO.java` | 科目视图对象（新建） |
| `src/main/java/com/jiang/common/exception/ErrorCodes.java` | 新增错误码（修改） |
| `src/test/java/com/jiang/service/GoalServiceTest.java` | GoalService 单元测试（新建） |
| `src/test/java/com/jiang/service/SubjectServiceTest.java` | SubjectService 单元测试（新建） |
| `src/test/java/com/jiang/controller/GoalControllerTest.java` | 学生端 GoalController 集成测试（新建） |
| `src/test/java/com/jiang/controller/AdminGoalControllerTest.java` | 管理端 AdminGoalController 集成测试（新建） |
| `src/test/java/com/jiang/controller/SubjectControllerTest.java` | SubjectController 集成测试（新建） |

---

## 8. 测试策略

### 8.1 单元测试

| 测试类 | 测试对象 | 测试场景 |
|--------|---------|----------|
| `GoalServiceTest` | GoalServiceImpl | 创建目标成功（字段映射正确、status=active） |
| `GoalServiceTest` | GoalServiceImpl | 创建目标失败：科目不存在 |
| `GoalServiceTest` | GoalServiceImpl | 创建目标失败：科目无已启用知识点 |
| `GoalServiceTest` | GoalServiceImpl | 创建目标失败：同科目已有活跃目标 |
| `GoalServiceTest` | GoalServiceImpl | 创建目标失败：日期不合法（endDate <= startDate） |
| `GoalServiceTest` | GoalServiceImpl | 创建目标失败：startDate 早于今天（I2） |
| `GoalServiceTest` | GoalServiceImpl | 查看活跃目标成功（status=active 且 endDate>=today） |
| `GoalServiceTest` | GoalServiceImpl | 查看活跃目标：按 subjectId 过滤（N2 多科目场景） |
| `GoalServiceTest` | GoalServiceImpl | 查看活跃目标：不传 subjectId 返回最新创建（N2） |
| `GoalServiceTest` | GoalServiceImpl | 查看活跃目标：已过期但未标记的目标不返回（B2 判定口径） |
| `GoalServiceTest` | GoalServiceImpl | 查看目标列表成功（分页） |
| `GoalServiceTest` | GoalServiceImpl | 查看目标详情成功 |
| `GoalServiceTest` | GoalServiceImpl | 查看目标详情失败：无权操作他人目标（B2 所有权校验） |
| `GoalServiceTest` | GoalServiceImpl | 修改目标成功（更新方向/分数/结束日期） |
| `GoalServiceTest` | GoalServiceImpl | 修改目标成功：direction 传空字符串时清空 |
| `GoalServiceTest` | GoalServiceImpl | 修改目标失败：目标已完成 |
| `GoalServiceTest` | GoalServiceImpl | 修改目标失败：目标已过期 |
| `GoalServiceTest` | GoalServiceImpl | 修改目标失败：结束日期缩短被拒绝（M1） |
| `GoalServiceTest` | GoalServiceImpl | 结束目标成功（status 改为 completed） |
| `GoalServiceTest` | GoalServiceImpl | 结束目标失败：目标已完成 |
| `GoalServiceTest` | GoalServiceImpl | 管理端查询目标列表成功（筛选+分页） |
| `GoalServiceTest` | GoalServiceImpl | 管理端查询目标详情成功（不做所有权校验） |
| `GoalServiceTest` | GoalServiceImpl | 管理端查询不存在目标失败（M4） |
| `SubjectServiceTest` | SubjectServiceImpl | 查询可用科目列表（仅返回有 active KP 的科目） |
| `SubjectServiceTest` | SubjectServiceImpl | 查询可用科目列表：所有科目均无 active KP 时返回空 |

### 8.2 集成测试

| 测试类 | 测试对象 | 测试场景 |
|--------|---------|----------|
| `GoalControllerTest` | GoalController | 创建目标接口正常返回 |
| `GoalControllerTest` | GoalController | 创建目标接口参数校验失败（subjectId 为空、@NotNull 缺失） |
| `GoalControllerTest` | GoalController | 查看活跃目标、按 subjectId 过滤、目标列表、目标详情接口 |
| `GoalControllerTest` | GoalController | 修改目标接口、结束目标接口 |
| `GoalControllerTest` | GoalController | 未登录场景（无 JWT 返回 401） |
| `GoalControllerTest` | GoalController | 查看目标详情失败：无权操作他人目标（I3） |
| `AdminGoalControllerTest` | AdminGoalController | 查询目标列表接口（筛选+分页） |
| `AdminGoalControllerTest` | AdminGoalController | 查询目标详情接口 |
| `AdminGoalControllerTest` | AdminGoalController | 查询不存在的目标返回 1030 |
| `SubjectControllerTest` | SubjectController | 查询可用科目列表接口 |

### 8.3 Mock 策略

- **数据库**：使用 `@MybatisPlusTest` 或 H2 内存数据库
- **StudentContext**：使用 `@MockedStatic` 模拟当前登录学生
- **JWT**：集成测试中模拟 JWT token

---

## 9. 验收标准

- [ ] 学生可创建学习目标（指定科目、方向、目标分数、周期）
- [ ] 创建目标时校验科目存在且有已启用知识点
- [ ] 创建目标时校验同学生同科目无重复 active 目标
- [ ] 创建目标时校验 startDate 不早于今天
- [ ] 学生可查看当前活跃目标（判定口径：status=active 且 endDate>=today）
- [ ] 活跃目标支持按 subjectId 过滤（N2：多科目场景）
- [ ] 无活跃目标时返回 `Result.success(null)`，前端展示“暂无活跃目标”（I2）
- [ ] 学生可查看目标列表（含历史，分页）
- [ ] 学生可查看目标详情（含所有权校验）
- [ ] 学生可修改目标（方向、分数、结束日期）
- [ ] 修改时校验目标状态为 active 且未过期
- [ ] 修改时结束日期只能延长不能缩短
- [ ] direction 传空字符串可清空，传 null 不修改
- [ ] 学生可结束目标（状态改为 completed）
- [ ] 科目查询接口返回有已启用知识点的科目列表（GROUP BY 聚合查询）
- [ ] 管理端可查看目标列表（按学生/科目/状态筛选，分页）
- [ ] 管理端可查看目标详情（不做所有权校验，仅 tenantId 隔离）
- [ ] 管理端 tenantId 从 `StudentContext` 获取（Demo 阶段复用，N1）
- [ ] 所有接口返回 `Result<T>` 统一结构
- [ ] 所有查询携带 tenant_id
- [ ] 单元测试覆盖 Service 核心逻辑（含查询方法、管理端方法、所有权校验）
- [ ] 集成测试覆盖学生端 + 管理端 Controller 接口
- [ ] 编译通过，测试通过
