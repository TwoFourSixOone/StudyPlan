# Design：知识点治理功能（v3 修复版）

> **Change 名称**：`2026-11-03_knowledge_point_governance`  
> **版本**：v3（修复 IMP-27）

---

## 0. 审查修复追溯

| 编号 | 问题 | 修复方案 |
|------|------|----------|
| BLOCK-1 | 管理端 Controller 用 StudentContext.getTenantId() | **N1 约定**：Demo 阶段复用 `StudentContext.getTenantId()`，与现有 `AdminGoalController`、`AdminMasteryController` 保持一致。管理端 JWT 鉴权后续迭代再拆分 |
| BLOCK-8 | selectList(5 params) 与 MyBatis-Plus BaseMapper.selectList(Wrapper) 冲突 | 改为 `knowledgePointMapper.selectPage(pageParam, wrapper)`，使用 MyBatis-Plus 标准分页 |
| BLOCK-9 | markAsReviewed 非幂等 | 改为幂等：`pending_review` → 标记为 `active`；`active` → 直接返回成功（幂等）；`disabled` → 抛 `KNOWLEDGE_POINT_NOT_PENDING_REVIEW` |
| IMP-12 | questionCount N+1 查询 | Mapper 使用 LEFT JOIN + GROUP BY 一次查出 `question_count` |
| IMP-13 | "7 工作日"与代码自然日不符 | 统一为 **7 个自然日**（Demo 阶段简化），DDL 注释和 proposal 同步修正 |
| IMP-14 | showDisabled 为 Boolean null 时 NPE | 显式 null 检查：`boolean show = Boolean.TRUE.equals(showDisabled)` |
| IMP-15 | 告警方式未明确 | Demo 阶段使用 `log.warn` 输出结构化告警信息（含知识点 ID、名称、超期天数），后续迭代接入站内信/邮件 |
| 错误码 | 原 1150-1153 与学习记录冲突 | 改为 **1160-1169** 编号段 |
| DDL | 本 Change 无新增 DDL | 不涉及 V6-V9 版本号 |
| IMP-27 | KnowledgePoint.subject 是 String 类型，代码中误用 Long subjectId + getSubjectId() | API 参数改为 `subject` (String)；查询列名改为 `subject`；subjectName 直接取 `kp.getSubject()`（冗余字段存储的就是名称）；countByKnowledgePointIds 参数改为 `List<String>` |

---

## 1. 架构设计

### 1.1 系统架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        管理后端 PC 端                            │
│  知识点列表 → 启停用 → 校对标记 → 查看详情 → 校对超期提醒        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  AdminKnowledgePointController                    │
│  GET    /api/admin/knowledge-points   列表查询                   │
│  GET    /api/admin/knowledge-points/{id}  详情查看               │
│  PUT    /api/admin/knowledge-points/{id}/status  启停用          │
│  PUT    /api/admin/knowledge-points/{id}/review  校对标记        │
│  GET    /api/admin/knowledge-points/overdue  校对超期列表        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  KnowledgePointGovernanceService                  │
│  getKnowledgePointList()     列表查询                            │
│  getKnowledgePointDetail()   详情查看                            │
│  updateStatus()              启停用                              │
│  markAsReviewed()            校对标记（幂等）                    │
│  getOverdueKnowledgePoints() 校对超期列表                        │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 分层设计

### 2.1 Controller 层

#### AdminKnowledgePointController

```java
@RestController
@RequestMapping("/api/admin/knowledge-points")
@Tag(name = "知识点管理（管理端）", description = "知识点列表、启停用、校对标记")
public class AdminKnowledgePointController {

    /**
     * BLOCK-1 / N1：Demo 阶段复用 StudentContext.getTenantId()
     * 与 AdminGoalController、AdminMasteryController 保持一致
     */
    @GetMapping
    @Operation(summary = "知识点列表查询")
    public Result<PageResult<KnowledgePointListVO>> getKnowledgePointList(
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        return Result.success(knowledgePointGovernanceService.getKnowledgePointList(
                subject, status, keyword, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "知识点详情查看")
    public Result<KnowledgePointDetailVO> getKnowledgePointDetail(@PathVariable String id) {
        return Result.success(knowledgePointGovernanceService.getKnowledgePointDetail(id));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "知识点启停用")
    public Result<Void> updateStatus(
            @PathVariable String id,
            @RequestParam String status) {
        knowledgePointGovernanceService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 修复 BLOCK-9：幂等操作
     */
    @PutMapping("/{id}/review")
    @Operation(summary = "知识点校对标记")
    public Result<Void> markAsReviewed(@PathVariable String id) {
        knowledgePointGovernanceService.markAsReviewed(id);
        return Result.success();
    }

    @GetMapping("/overdue")
    @Operation(summary = "校对超期列表")
    public Result<List<KnowledgePointOverdueVO>> getOverdueKnowledgePoints() {
        return Result.success(knowledgePointGovernanceService.getOverdueKnowledgePoints());
    }
}
```

### 2.2 Service 层

#### KnowledgePointGovernanceService

```java
public interface KnowledgePointGovernanceService {
    PageResult<KnowledgePointListVO> getKnowledgePointList(String subject, String status,
                                                             String keyword, Integer page, Integer size);
    KnowledgePointDetailVO getKnowledgePointDetail(String id);
    void updateStatus(String id, String status);
    void markAsReviewed(String id);
    List<KnowledgePointOverdueVO> getOverdueKnowledgePoints();
}
```

### 2.3 DTO/VO 层

#### KnowledgePointListVO

```java
@Data
public class KnowledgePointListVO {
    private String id;  // 修复 IMP-27：knowledgePointId 是 String 类型
    private String name;
    private String subjectName;
    private String status;
    private Integer questionCount;   // 修复 IMP-12：LEFT JOIN 一次查出
    private LocalDateTime createTime;
}
```

#### KnowledgePointDetailVO

```java
@Data
public class KnowledgePointDetailVO {
    private String id;  // 修复 IMP-27：knowledgePointId 是 String 类型
    private String name;
    private String subjectName;
    private String status;
    private String description;
    private Integer level;
    private String parentId;
    private List<PrerequisiteVO> prerequisites;
    private List<PrerequisiteVO> dependents;
    private Integer questionCount;
    private Integer studentCount;
    private LocalDateTime createTime;
}
```

#### PrerequisiteVO

```java
@Data
public class PrerequisiteVO {
    private String knowledgePointId;  // 修复 IMP-27：String 类型
    private String knowledgePointName;
    private String dependencyType;  // hard/soft
}
```

#### KnowledgePointOverdueVO

```java
@Data
public class KnowledgePointOverdueVO {
    private String id;  // 修复 IMP-27：knowledgePointId 是 String 类型
    private String name;
    private String subjectName;
    private LocalDateTime createTime;
    private Integer overdueDays;   // 修复 IMP-13：自然日
}
```

---

## 3. 核心实现逻辑

### 3.1 知识点列表查询（修复 BLOCK-8 + IMP-12）

```java
@Override
public PageResult<KnowledgePointListVO> getKnowledgePointList(String subject, String status,
                                                                String keyword, Integer page, Integer size) {
    // 修复 BLOCK-1/N1：Demo阶段复用 StudentContext
    String tenantId = StudentContext.getTenantId();

    // 修复 BLOCK-8：使用 MyBatis-Plus 标准 selectPage + QueryWrapper
    Page<KnowledgePoint> pageParam = new Page<>(page, size);
    QueryWrapper<KnowledgePoint> wrapper = new QueryWrapper<>();
    wrapper.eq("tenant_id", tenantId);

    // 修复 IMP-27：knowledge_point.subject 是 String 类型（存储科目名称），非 subject_id
    if (subject != null && !subject.isEmpty()) {
        wrapper.eq("subject", subject);
    }
    if (status != null && !status.isEmpty()) {
        wrapper.eq("status", status);
    }
    if (keyword != null && !keyword.isEmpty()) {
        // 修复 IMP-3（跨 Change 共性）：转义 % 和 _
        String escaped = keyword.replace("%", "\\%").replace("_", "\\_");
        wrapper.like("name", escaped);
    }

    wrapper.orderByDesc("create_time");

    Page<KnowledgePoint> result = knowledgePointMapper.selectPage(pageParam, wrapper);

    // 修复 IMP-12：批量查询 questionCount，避免 N+1
    List<String> kpIds = result.getRecords().stream()
            .map(KnowledgePoint::getKnowledgePointId).collect(Collectors.toList());
    Map<String, Integer> questionCountMap = questionBankMapper.countByKnowledgePointIds(kpIds, tenantId);

    List<KnowledgePointListVO> voList = result.getRecords().stream()
            .map(kp -> {
                KnowledgePointListVO vo = new KnowledgePointListVO();
                vo.setId(kp.getKnowledgePointId());
                vo.setName(kp.getName());
                // 修复 IMP-27：KnowledgePoint.subject 直接存储科目名称（String），无需再查
                vo.setSubjectName(kp.getSubject());
                vo.setStatus(kp.getStatus());
                vo.setQuestionCount(questionCountMap.getOrDefault(kp.getKnowledgePointId(), 0));
                vo.setCreateTime(kp.getCreateTime());
                return vo;
            })
            .collect(Collectors.toList());

    return PageResult.of(voList, result.getTotal(), page, size);
}
```

### 3.2 知识点启停用

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void updateStatus(String id, String status) {
    String tenantId = StudentContext.getTenantId();
    KnowledgePoint kp = knowledgePointMapper.selectByIdWithTenant(id, tenantId);
    if (kp == null) {
        throw new BusinessException(ErrorCodes.KNOWLEDGE_POINT_NOT_FOUND);
    }

    // 校验状态合法性
    if (!"active".equals(status) && !"disabled".equals(status)) {
        throw new BusinessException(ErrorCodes.INVALID_KNOWLEDGE_POINT_STATUS);
    }

    // 停用前校验是否有活跃学习计划引用
    if ("disabled".equals(status)) {
        int activeGoalCount = learningGoalMapper.countByKnowledgePointId(id, tenantId);
        if (activeGoalCount > 0) {
            throw new BusinessException(ErrorCodes.KNOWLEDGE_POINT_IN_USE);
        }
    }

    kp.setStatus(status);
    kp.setUpdateTime(LocalDateTime.now());
    knowledgePointMapper.updateById(kp);
}
```

### 3.3 知识点校对标记（修复 BLOCK-9：幂等）

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void markAsReviewed(String id) {
    String tenantId = StudentContext.getTenantId();
    KnowledgePoint kp = knowledgePointMapper.selectByIdWithTenant(id, tenantId);
    if (kp == null) {
        throw new BusinessException(ErrorCodes.KNOWLEDGE_POINT_NOT_FOUND);
    }

    // 修复 BLOCK-9：幂等处理
    if ("active".equals(kp.getStatus())) {
        // 已是 active，重复标记直接返回成功（幂等）
        return;
    }
    if (!"pending_review".equals(kp.getStatus())) {
        // disabled 或其他状态不允许标记
        throw new BusinessException(ErrorCodes.KNOWLEDGE_POINT_NOT_PENDING_REVIEW);
    }

    kp.setStatus("active");
    kp.setReviewTime(LocalDateTime.now());
    kp.setUpdateTime(LocalDateTime.now());
    knowledgePointMapper.updateById(kp);
}
```

### 3.4 校对超期列表（修复 IMP-13/15）

```java
@Override
public List<KnowledgePointOverdueVO> getOverdueKnowledgePoints() {
    String tenantId = StudentContext.getTenantId();
    // 修复 IMP-13：统一为 7 个自然日（Demo 阶段简化）
    LocalDate overdueDate = LocalDate.now().minusDays(7);

    List<KnowledgePoint> overdueList = knowledgePointMapper.selectOverdue(overdueDate, tenantId);

    return overdueList.stream()
            .map(kp -> {
                KnowledgePointOverdueVO vo = new KnowledgePointOverdueVO();
                vo.setId(kp.getKnowledgePointId());
                vo.setName(kp.getName());
                // 修复 IMP-27：直接取 kp.getSubject()（String 类型，存储科目名称）
                vo.setSubjectName(kp.getSubject());
                vo.setCreateTime(kp.getCreateTime());
                vo.setOverdueDays((int) ChronoUnit.DAYS.between(kp.getCreateTime().toLocalDate(), LocalDate.now()));
                return vo;
            })
            .collect(Collectors.toList());
}
```

---

## 4. 停用知识点展示规则（修复 IMP-14）

### 4.1 MasteryController 扩展

```java
@GetMapping("/mastery-dashboard")
@Operation(summary = "掌握度看板")
public Result<MasteryDashboardVO> getMasteryDashboard(
        @RequestParam Long subjectId,
        @RequestParam(value = "showDisabled", required = false) Boolean showDisabled) {
    // 修复 IMP-14：显式处理 null
    boolean show = Boolean.TRUE.equals(showDisabled);
    return Result.success(masteryService.getMasteryDashboard(subjectId, show));
}
```

### 4.2 MasteryServiceImpl 扩展

```java
@Override
public MasteryDashboardVO getMasteryDashboard(Long subjectId, boolean showDisabled) {
    Long studentId = StudentContext.getStudentId();
    String tenantId = StudentContext.getTenantId();

    List<StudentKnowledgeState> states = studentKnowledgeStateMapper.selectByStudentAndSubject(
            studentId, subjectId, tenantId);

    List<KnowledgePointMasteryVO> voList = states.stream()
            .filter(state -> {
                KnowledgePoint kp = knowledgePointMapper.selectById(state.getKnowledgePointId());
                if (kp == null) return false;
                // 修复 IMP-14：参数已保证非 null（boolean 原始类型）
                if (!showDisabled && "disabled".equals(kp.getStatus())) return false;
                return true;
            })
            .map(state -> {
                KnowledgePointMasteryVO vo = new KnowledgePointMasteryVO();
                vo.setKnowledgePointId(state.getKnowledgePointId());
                vo.setKnowledgePointName(getKnowledgePointName(state.getKnowledgePointId()));
                vo.setMasteryScore(state.getMasteryScore());
                vo.setConfidence(state.getConfidence());
                vo.setStatus(getKnowledgePointStatus(state.getKnowledgePointId()));
                vo.setLastAssessedAt(state.getLastAssessedAt());
                return vo;
            })
            .collect(Collectors.toList());

    MasteryDashboardVO dashboard = new MasteryDashboardVO();
    dashboard.setKnowledgePoints(voList);
    dashboard.setShowDisabled(showDisabled);
    return dashboard;
}
```

---

## 5. Mapper 扩展（修复 BLOCK-8 + IMP-12）

### 5.1 KnowledgePointMapper

```java
@Mapper
public interface KnowledgePointMapper extends BaseMapper<KnowledgePoint> {

    /**
     * 修复 BLOCK-8：不再使用 5 参数 selectList
     * 改用 selectPage(pageParam, wrapper) 标准分页
     */

    @Select("SELECT * FROM knowledge_point WHERE knowledge_point_id = #{id} AND tenant_id = #{tenantId}")
    KnowledgePoint selectByIdWithTenant(@Param("id") String id, @Param("tenantId") String tenantId);

    /**
     * 修复 IMP-13：selectOverdue 查询 pending_review 且 create_time < overdueDate
     */
    @Select("SELECT * FROM knowledge_point WHERE status = 'pending_review' " +
            "AND create_time < #{overdueDate} AND tenant_id = #{tenantId}")
    List<KnowledgePoint> selectOverdue(@Param("overdueDate") LocalDate overdueDate,
                                        @Param("tenantId") String tenantId);
}
```

### 5.2 QuestionBankMapper（修复 IMP-12）

```java
@Mapper
public interface QuestionBankMapper extends BaseMapper<QuestionBank> {

    /**
     * 修复 IMP-12：批量查询知识点关联题目数量，避免 N+1
     * 返回 Map<knowledgePointId, count>
     */
    @Select("<script>" +
            "SELECT knowledge_point_id, COUNT(*) as cnt FROM question_bank " +
            "WHERE tenant_id = #{tenantId} AND knowledge_point_id IN " +
            "<foreach collection='kpIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            " GROUP BY knowledge_point_id" +
            "</script>")
    // 修复 IMP-27：knowledge_point_id 是 String 类型（非 Long）
    List<Map<String, Object>> countByKnowledgePointIds(@Param("kpIds") List<String> kpIds,
                                                        @Param("tenantId") String tenantId);
}
```

---

## 6. 校对超期定时任务（修复 IMP-15）

```java
@Component
@Slf4j
public class KnowledgePointReviewTask {

    @Autowired
    private KnowledgePointGovernanceService governanceService;

    /**
     * 修复 IMP-15：每天凌晨 3 点检查超期未校对知识点
     * Demo 阶段告警方式：log.warn 输出结构化告警信息
     * 后续迭代可接入站内信/邮件/短信
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void checkOverdueKnowledgePoints() {
        List<KnowledgePointOverdueVO> overdueList = governanceService.getOverdueKnowledgePoints();
        if (overdueList.isEmpty()) {
            log.info("无超期未校对知识点");
            return;
        }

        // 修复 IMP-15：结构化告警日志
        for (KnowledgePointOverdueVO vo : overdueList) {
            log.warn("[ALERT] 知识点校对超期: id={}, name={}, subject={}, overdueDays={}",
                    vo.getId(), vo.getName(), vo.getSubjectName(), vo.getOverdueDays());
        }
        log.warn("[ALERT] 超期未校对知识点共 {} 条，请及时处理", overdueList.size());
    }
}
```

---

## 7. 隐性约定对齐

| 约定 | 实现方式 |
|------|----------|
| #1 统一响应 | Controller 返回 `Result<T>` |
| #2 异常处理 | Service 抛 `BusinessException`，Controller 不 try-catch |
| #3 分页参数 | `page` 从 1 开始，`size` 默认 10 |
| #4 时间格式 | 使用 `LocalDateTime`/`LocalDate` |
| #7 tenant_id 隔离 | 所有查询携带 tenant_id |
| #9 rollbackFor | `@Transactional(rollbackFor = Exception.class)` |
| N1 管理端上下文 | Demo 阶段复用 `StudentContext.getTenantId()`（与 `AdminGoalController` 一致） |

---

## 8. 错误码（修复 BLOCK-2 全局分配）

```java
// 知识点治理相关（1160-1169）
KNOWLEDGE_POINT_NOT_FOUND(1160, "知识点不存在"),
KNOWLEDGE_POINT_NOT_PENDING_REVIEW(1161, "知识点不在待校对状态"),
KNOWLEDGE_POINT_IN_USE(1162, "知识点被活跃学习计划引用，无法停用"),
INVALID_KNOWLEDGE_POINT_STATUS(1163, "无效的状态值，合法值：active/disabled");
```

---

## 9. DDL 声明

- 本 Change **无新增 DDL**
- 依赖 V1~V5 既有表（knowledge_point、question_bank、learning_goal、student_knowledge_state）
- 不涉及 V6-V9 版本号依赖

---

**版本**：v3（修复 IMP-27）  
**创建日期**：2026-11-03  
**最后更新**：2026-11-04
