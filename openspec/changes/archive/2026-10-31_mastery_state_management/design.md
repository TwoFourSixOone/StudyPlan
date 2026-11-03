# Design：掌握度状态管理

> **Change ID**: `2026-10-31_mastery_state_management`  
> **关联 Proposal**: `proposal.md`

---

## 1. 技术方案

### 1.1 整体架构

```
Controller (MasteryController / AdminMasteryController)
    ↓
Service (MasteryService / MasteryServiceImpl)
    ↓                    ↓
Mapper (MyBatis-Plus)    MasteryFormulaUtil (纯计算)
    ↓
DB (student_knowledge_state / mastery_change_log / knowledge_point / question_bank / generated_question)
```

### 1.2 包结构

| 类型 | 包路径 | 类名 |
|------|--------|------|
| 枚举 | `com.jiang.model.enums` | `ChangeType` |
| 工具 | `com.jiang.common.util` | `MasteryFormulaUtil` |
| Service 接口 | `com.jiang.service` | `MasteryService` |
| Service 实现 | `com.jiang.service.impl` | `MasteryServiceImpl` |
| 学生端 Controller | `com.jiang.controller` | `MasteryController` |
| 管理端 Controller | `com.jiang.controller` | `AdminMasteryController` |
| VO | `com.jiang.vo` | `MasteryDashboardVO`, `MasteryDetailVO`, `MasteryChangeLogVO` |
| DTO | `com.jiang.dto` | `MasteryUpdateRequest`, `AdminMasteryQueryRequest` |
| 实体修改 | `com.jiang.model.entity` | `StudentKnowledgeState`（新增 `@Version version` 字段，对应审查 F 项乐观锁方案） |

---

## 2. 掌握度公式设计

### 2.1 公式定义（产品规则 #2）

**答对时（掌握度上升）：**

```
delta_m_up = alpha * W_source * D_norm * C_norm * (1 - m_current)
m_new = min(1.0, m_current + delta_m_up)
```

**答错时（掌握度下降）：**

```
delta_m_down = alpha * W_source * D_norm * C_norm * m_current
m_new = max(0.05, m_current - delta_m_down)
```

### 2.2 参数定义

| 参数 | 含义 | 取值 | 来源 |
|------|------|------|------|
| `alpha` | 基础学习率 | 0.3 | 常量（允许配置范围 0.1–0.5） |
| `W_source` | 来源权重 | homework=0.4, assessment=0.7, exam=1.0 | `ChangeType` 枚举属性 |
| `D_norm` | 难度归一化值 | 0.3–1.0 | 原题：题库 `difficulty` 映射；新题：声明值偏离均值 > 0.2 时降级为 0.5 |
| `C_norm` | 置信度衰减因子 | 0.5–1.0 | `1.0 - 0.5 * confidence` |
| `m_current` | 当前掌握度 | 0.0–1.0 | `student_knowledge_state.mastery_score`；首次创建记录时初始值为 **0.50**（见 §6.2） |

### 2.3 难度枚举映射

| difficulty 值 | D_norm 数值 |
|---------------|-------------|
| `basic` | 0.3 |
| `standard` | 0.6 |
| `challenge` | 0.9 |

### 2.4 D_norm 校验逻辑

```
IF question_source == "bank":
    D_norm = difficultyEnum(question_bank.difficulty)
ELIF question_source == "generated":
    avgDifficulty = AVG(question_bank.difficulty) WHERE knowledge_point_id = ?
    IF avgDifficulty IS NULL:
        D_norm = 0.5  // 新知识点无历史数据，使用默认值
    ELIF |declaredDifficulty - avgDifficulty| > 0.2:
        D_norm = 0.5  // 偏离过大，降级
    ELSE:
        D_norm = difficultyEnum(declaredDifficulty)
```

> **⚠ 类型转换说明**：`declaredDifficulty` 为枚举字符串（`"basic"` / `"standard"` / `"challenge"`），与 `avgDifficulty`（BigDecimal 数值）做差值比较前，**必须先调用 `MasteryFormulaUtil.mapDifficulty(declaredDifficulty)` 转为数值**（0.3 / 0.6 / 0.9），严禁直接用字符串做算术运算。

### 2.5 置信度更新

- 初始值：0.10（创建 `student_knowledge_state` 记录时）
- 每次有效评测后递增：+0.05
- 上限：1.00
- 公式：`confidence_new = min(1.00, confidence_current + 0.05)`

---

## 3. MasteryFormulaUtil 设计

纯静态工具类，无状态，不依赖 Spring 容器。

```java
public final class MasteryFormulaUtil {

    public static final BigDecimal ALPHA = new BigDecimal("0.3");
    public static final BigDecimal CONFIDENCE_INITIAL = new BigDecimal("0.10");
    public static final BigDecimal CONFIDENCE_INCREMENT = new BigDecimal("0.05");
    public static final BigDecimal CONFIDENCE_MAX = BigDecimal.ONE;
    public static final BigDecimal MASTERY_MIN = new BigDecimal("0.05");
    public static final BigDecimal MASTERY_MAX = BigDecimal.ONE;
    public static final BigDecimal D_NORM_DEFAULT = new BigDecimal("0.5");
    public static final BigDecimal D_NORM_DEVIATION_THRESHOLD = new BigDecimal("0.2");
    public static final BigDecimal MASTERY_INITIAL = new BigDecimal("0.50");  // 首次创建记录时的初始掌握度

    /** 计算答对时的新掌握度 */
    public static BigDecimal calculateMasteryCorrect(
            BigDecimal mCurrent, BigDecimal wSource,
            BigDecimal dNorm, BigDecimal confidence) { ... }

    /** 计算答错时的新掌握度 */
    public static BigDecimal calculateMasteryIncorrect(
            BigDecimal mCurrent, BigDecimal wSource,
            BigDecimal dNorm, BigDecimal confidence) { ... }

    /** 计算置信度衰减因子 C_norm = 1.0 - 0.5 * confidence */
    public static BigDecimal calculateCNorm(BigDecimal confidence) { ... }

    /** 校验 D_norm：新题偏离均值 > 0.2 时降级为 0.5 */
    public static BigDecimal validateDNorm(
            BigDecimal declaredDifficulty, BigDecimal avgDifficulty) { ... }

    /** 难度枚举映射：basic→0.3, standard→0.6, challenge→0.9 */
    public static BigDecimal mapDifficulty(String difficulty) { ... }

    /** 更新置信度：min(1.00, current + 0.05) */
    public static BigDecimal updateConfidence(BigDecimal current) { ... }
}
```

> **全局精度约定**：所有计算方法的结果统一保留 **4 位小数**（`scale=4`），舍入模式为 `RoundingMode.HALF_UP`。常量定义不受此约束，仅在参与运算后对结果应用精度控制。存储到数据库时字段定义为 `DECIMAL(5,4)`，与 Java 侧计算精度对齐，避免断言偏差。

---

## 4. ChangeType 枚举设计

```java
@Getter
public enum ChangeType {
    HOMEWORK("homework", "每日作业", new BigDecimal("0.4")),
    ASSESSMENT("assessment", "专项评测", new BigDecimal("0.7")),
    EXAM("exam", "考试", BigDecimal.ONE);

    private final String code;
    private final String description;
    private final BigDecimal sourceWeight;  // W_source

    public static ChangeType fromCode(String code) { ... }
}
```

---

## 5. MasteryService 接口设计

```java
public interface MasteryService {

    // ===== 写操作（掌握度更新）=====

    /**
     * 根据每日作业提交记录批量更新掌握度。
     * 内部逻辑：归属校验 → 查询 submission detail → 获取题目难度 → 计算 D_norm → 内存缓存同知识点状态 → 逐知识点更新 + 写日志。
     * 同一知识点在同一批次中多次出现时，采用内存 Map 缓存 state，确保每个知识点只执行一次 DB 更新，防止后写覆盖前写导致增量丢失。
     */
    @Transactional(rollbackFor = Exception.class)
    void updateMasteryFromHomework(Long studentId, String tenantId, List<Long> submissionIds);

    /**
     * 根据单次评测/考试结果更新掌握度。
     * 调用方（AssessmentService / ExamService）负责提供已计算好的 D_norm 值。
     */
    @Transactional(rollbackFor = Exception.class)
    void updateMasteryFromResult(Long studentId, String tenantId,
                                  String knowledgePointId, ChangeType changeType,
                                  Long sourceId, boolean isCorrect, BigDecimal dNorm);

    // ===== 学生端查询 =====

    /**
     * 获取学生掌握度看板。仅返回 active 知识点。
     * @param subject 科目名称，可选（null 时返回所有科目的 active 知识点）
     */
    List<MasteryDashboardVO> getStudentDashboard(String subject);

    /**
     * 获取学生指定知识点的掌握度变更历史（按时间倒序）。
     */
    List<MasteryChangeLogVO> getStudentChangeHistory(String knowledgePointId, int page, int size);

    // ===== 管理端查询 =====

    /**
     * 管理端掌握度看板查询。支持按学生/知识点/科目筛选，返回含知识点名称和状态。
     */
    PageResult<MasteryDetailVO> getAdminDashboard(AdminMasteryQueryRequest request);

    /**
     * 管理端查看指定学生的知识点掌握度变更历史。
     */
    List<MasteryChangeLogVO> getAdminChangeHistory(Long studentId, String knowledgePointId,
                                                    int page, int size);
}
```

---

## 6. MasteryServiceImpl 核心逻辑

### 6.1 updateMasteryFromHomework 流程

```
1. 【归属校验】根据 submissionIds 批量查询 homework_submission_detail，
   校验每条记录的 student_id 和 tenant_id 与入参严格一致；
   不一致则抛出 BusinessException(HOMEWORK_NOT_FOUND)，防止越权更新他人掌握度
2. 按 question_source 分组查询题目难度：
   - "bank" → question_bank.selectById → difficulty 字段
   - "generated" → generated_question.selectById → difficulty 字段
3. 初始化内存缓存：Map<String, StudentKnowledgeState> stateCache = new HashMap<>()
4. 对每条 submission（按 submissionId 升序保证确定性）：
   a. 计算 D_norm（含新题偏离校验，declaredDifficulty 先 mapDifficulty 转为数值再比较）
   b. 从 stateCache 获取当前知识点的 state；若不存在则查询 DB（findOrCreateState）并放入缓存
   c. 调用 MasteryFormulaUtil 计算新掌握度（基于缓存中的 mCurrent 值）
   d. 更新缓存中的 state（mastery_score + confidence + source + last_assessed_at）
   e. 插入 mastery_change_log（delta, mastery_before, mastery_after）— 逐条插入保证追溯粒度
5. 循环结束后，对 stateCache 中每个 state 执行一次 DB updateById（批量写回）
6. 【乐观锁冲突处理（对应审查 F 项）】
   - `StudentKnowledgeState` 实体新增 `@Version private Integer version;` 字段
   - MyBatis-Plus 自动在 UPDATE 时拼接 `WHERE version = ?` 并 SET `version = version + 1`
   - 若 updateById 返回受影响行数为 0（说明其他线程已抢先修改），
     抛出 `BusinessException(MASTERY_CONCURRENT_CONFLICT)`，由全局异常处理器返回 409 提示前端重试
   - 整个事务回滚，保证 mastery_change_log 与 state 的最终一致性
```

> **⚠ 关键设计决策（对应审查 B 项 + F 项）**：
> - **B 项**：步骤 3 引入内存缓存，步骤 4b 从缓存读取而非每次查 DB，步骤 5 统一批量写回。保证同一知识点在批次内出现多次时，每次计算基于上一次计算后的值（而非 DB 旧值），最终只执行一次 DB UPDATE，避免后写覆盖前写导致增量丢失。
> - **F 项**：步骤 6 引入 `@Version` 乐观锁。虽然 Demo 阶段并发概率低，但内存缓存方案拉长了事务内「读→写」窗口，乐观锁是必要的防御措施。`version` 字段由 MyBatis-Plus 自动管理，对业务代码侵入极小。

### 6.2 updateMasteryFromResult 流程

```
1. 查询/创建 student_knowledge_state（findOrCreateState）
2. 调用 MasteryFormulaUtil 计算新掌握度
3. 更新 student_knowledge_state
4. 插入 mastery_change_log
```

> **findOrCreateState 首次创建时的初始化逻辑（对应审查 I1/I4）**：
> 当 DB 中不存在该学生+知识点的状态记录时，新建记录并初始化以下字段：
> - `mastery_score` = **0.50**（`MasteryFormulaUtil.MASTERY_INITIAL`，中等初始掌握度，符合教育测量惯例）
> - `confidence` = **0.10**（`MasteryFormulaUtil.CONFIDENCE_INITIAL`，最低置信度，鼓励前期快速收敛）
> - `source` = 当前 `ChangeType.getCode()`（如 "homework" / "assessment" / "exam"）
> - `lastAssessedAt` = `LocalDateTime.now()`
> - `studentId` / `knowledgePointId` / `tenantId` = 方法入参

### 6.3 学生看板查询逻辑

```
1. 从 StudentContext 获取 studentId, tenantId
2. 查询 knowledge_point WHERE tenant_id=? AND subject=? AND status='active'
3. 查询 student_knowledge_state WHERE student_id=? AND tenant_id=? AND knowledge_point_id IN (active 知识点列表)
4. 【lastChangeDirection 组装（对应审查 I6 项）】
   批量查询这批知识点的最新一条 mastery_change_log：
   - 调用 MasteryChangeLogMapper.selectLatestByKpIds(studentId, tenantId, kpIds)
   - 返回 Map<knowledgePointId, MasteryChangeLog>（每个知识点最多一条最新记录）
   - 根据最新日志的 delta 符号确定方向：
     * delta > 0 → "up"
     * delta < 0 → "down"
     * delta == 0 → "stable"
     * 无历史记录 → null（首次评测，尚未产生变更））
5. 在内存中组装 MasteryDashboardVO（含知识点名称、掌握度、置信度、最近变更方向）
```

> **ℹ lastChangeDirection 设计选型说明**：
> 采用「批量查询最新日志」方案，而非在 `student_knowledge_state` 表增加冗余字段。
> 优势：不增加 Schema 变更，数据源单一（日志表为唯一真相源），避免冗余字段与实际日志不同步的风险。
> 性能：一次 IN 查询覆盖所有知识点，无 N+1 问题。

### 6.4 管理端看板查询逻辑

```
1. 解析 AdminMasteryQueryRequest 筛选条件
2. 若指定 subject → 先查 knowledge_point 获取知识点 ID 列表
3. 构建 QueryWrapper 查询 student_knowledge_state
4. JOIN knowledge_point 获取知识点名称和状态（一次性查出，避免循环内逐条 selectById 产生 N+1 问题）
5. 在内存中组装 MasteryDetailVO（直接使用 JOIN 结果中的 kp_name、kp_subject、kp_status 字段）
6. 分页返回 MasteryDetailVO
```

---

## 7. Controller 设计

### 7.1 MasteryController（学生端）

| HTTP 方法 | 路径 | 说明 | 响应 |
|-----------|------|------|------|
| GET | `/api/student/mastery/dashboard` | 掌握度看板 | `Result<List<MasteryDashboardVO>>` |
| GET | `/api/student/mastery/history/{knowledgePointId}` | 变更历史 | `Result<PageResult<MasteryChangeLogVO>>` |

- 参数 `subject` 为可选查询参数
- `knowledgePointId` 为路径参数
- 分页参数 page 从 1 开始，size 默认 10

### 7.2 AdminMasteryController（管理端）

| HTTP 方法 | 路径 | 说明 | 响应 |
|-----------|------|------|------|
| GET | `/api/admin/mastery/dashboard` | 掌握度看板 | `Result<PageResult<MasteryDetailVO>>` |
| GET | `/api/admin/mastery/student/{studentId}/history/{knowledgePointId}` | 学生变更历史 | `Result<PageResult<MasteryChangeLogVO>>` |

- 筛选参数：`studentId`、`knowledgePointId`、`subject`（均为可选）
- 分页参数 page 从 1 开始，size 默认 10

---

## 8. VO / DTO 设计

### 8.1 MasteryDashboardVO（学生看板条目）

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MasteryDashboardVO {
    private String knowledgePointId;   // 知识点 ID
    private String knowledgePointName; // 知识点名称
    private String subject;            // 科目
    private BigDecimal masteryScore;   // 掌握度（0.00–1.00）
    private BigDecimal confidence;     // 置信度（0.00–1.00）
    private String lastChangeDirection;// 最近变更方向：up / down / stable
    private LocalDateTime lastAssessedAt; // 最近评测时间
}
```

### 8.2 MasteryDetailVO（管理端看板条目）

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MasteryDetailVO {
    private Long studentId;            // 学生 ID
    private String knowledgePointId;   // 知识点 ID
    private String knowledgePointName; // 知识点名称
    private String subject;            // 科目
    private String kpStatus;           // 知识点状态：active / disabled
    private BigDecimal masteryScore;   // 掌握度
    private BigDecimal confidence;     // 置信度
    private String source;             // 最近来源
    private LocalDateTime lastAssessedAt; // 最近评测时间
}
```

### 8.3 MasteryChangeLogVO（变更历史条目）

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MasteryChangeLogVO {
    private Long logId;                // 日志 ID
    private String changeType;         // 变更类型
    private Long sourceId;             // 来源 ID
    private BigDecimal delta;          // 变动量
    private BigDecimal masteryBefore;  // 变更前掌握度
    private BigDecimal masteryAfter;   // 变更后掌握度
    private String knowledgePointName; // 知识点名称（JOIN knowledge_point.name）
    private String subject;            // 科目（JOIN knowledge_point.subject）
    private LocalDateTime createdAt;   // 变更时间
}
```

### 8.4 AdminMasteryQueryRequest（管理端查询请求）

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AdminMasteryQueryRequest {
    private Long studentId;        // 可选
    private String knowledgePointId; // 可选
    private String subject;        // 可选
    private Integer page = 1;
    private Integer size = 10;
}
```

---

## 9. 自定义 Mapper 方法

### 9.1 StudentKnowledgeStateMapper

```java
/** 查询学生 active 知识点的掌握度状态（JOIN knowledge_point） */
@Select("SELECT s.*, k.name AS kp_name, k.subject AS kp_subject, k.status AS kp_status " +
        "FROM student_knowledge_state s " +
        "JOIN knowledge_point k ON s.knowledge_point_id = k.knowledge_point_id " +
        "AND s.tenant_id = k.tenant_id " +
        "WHERE s.student_id = #{studentId} AND s.tenant_id = #{tenantId} " +
        "AND k.status = 'active' " +
        "AND (#{subject} IS NULL OR k.subject = #{subject})")
List<StudentKnowledgeState> selectActiveStatesWithKpInfo(
    @Param("studentId") Long studentId,
    @Param("tenantId") String tenantId,
    @Param("subject") String subject);
```

### 9.2 MasteryChangeLogMapper

```java
/** 分页查询变更历史（按时间倒序） */
@Select("SELECT * FROM mastery_change_log " +
        "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
        "AND knowledge_point_id = #{knowledgePointId} " +
        "ORDER BY create_time DESC LIMIT #{offset}, #{size}")
List<MasteryChangeLog> selectHistoryByStudentAndKp(
    @Param("studentId") Long studentId,
    @Param("tenantId") String tenantId,
    @Param("knowledgePointId") String knowledgePointId,
    @Param("offset") int offset,
    @Param("size") int size);

/**
 * 批量查询指定知识点列表的最新一条变更日志（用于看板 lastChangeDirection 组装，对应审查 I6 项）。
 * 返回 List 中每个知识点最多一条记录（log_id 最大者）。
 */
@Select("<script>" +
        "SELECT mcl.* FROM mastery_change_log mcl " +
        "INNER JOIN (" +
        "  SELECT knowledge_point_id, MAX(log_id) AS max_log_id " +
        "  FROM mastery_change_log " +
        "  WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
        "  AND knowledge_point_id IN " +
        "  <foreach item='kpId' collection='kpIds' open='(' separator=',' close=')'>#{kpId}</foreach> " +
        "  GROUP BY knowledge_point_id" +
        ") latest ON mcl.log_id = latest.max_log_id" +
        "</script>")
List<MasteryChangeLog> selectLatestByKpIds(
    @Param("studentId") Long studentId,
    @Param("tenantId") String tenantId,
    @Param("kpIds") List<String> kpIds);
```

### 9.3 QuestionBankMapper（新增辅助方法）

```java
/** 查询指定知识点的平均难度（用于 D_norm 校验） */
@Select("SELECT AVG(CASE difficulty " +
        "WHEN 'basic' THEN 0.3 " +
        "WHEN 'standard' THEN 0.6 " +
        "WHEN 'challenge' THEN 0.9 " +
        "ELSE 0.5 END) " +
        "FROM question_bank WHERE knowledge_point_id = #{kpId} AND tenant_id = #{tenantId}")
BigDecimal selectAvgDifficultyByKp(@Param("kpId") String kpId, @Param("tenantId") String tenantId);
```

---

## 10. 数据库变更

### 10.1 测试 Schema 补充

需在 `src/test/resources/schema.sql` 中补充以下表定义（生产 Schema 已在 V1 中存在）：

- `student_knowledge_state`（**含 `version INT NOT NULL DEFAULT 0` 字段**，对应审查 F 项乐观锁）
- `mastery_change_log`
- `knowledge_point`（集成测试中 Mapper JOIN 查询需要）

> **⚠ 生产 Schema 同步要求**：`student_knowledge_state` 表的 `version` 字段需通过 DDL 变更脚本添加到生产库。
> 该脚本属于受保护区域（`src/main/resources/db/`），需在 apply 阶段通过 change 流程提交，由人工审批后执行。

### 10.2 索引确认

| 表 | 索引 | 用途 |
|----|------|------|
| `student_knowledge_state` | `uk_student_kp (student_id, knowledge_point_id, tenant_id)` | 唯一约束 + 查询 |
| `mastery_change_log` | `idx_student_kp (tenant_id, student_id, knowledge_point_id)` | 变更历史查询 |
| `knowledge_point` | `idx_subject_status (tenant_id, subject, status)` | 按科目筛选 active 知识点 |

---

## 11. 异常处理

| 场景 | ErrorCodes | HTTP 状态码 |
|------|-----------|-------------|
| 知识点不存在 | `KNOWLEDGE_POINT_NOT_FOUND(1010)` | 200（业务码） |
| 知识点已停用 | `KNOWLEDGE_POINT_DISABLED(1011)` | 200（业务码） |
| 掌握度状态不存在（查询场景） | `MASTERY_STATE_NOT_FOUND(1060)` | 200（业务码） |
| 提交记录不存在 | `HOMEWORK_NOT_FOUND(1050)` | 200（业务码） |
| 请求参数错误 | `BAD_REQUEST(400)` | 200（业务码） |
| 掌握度并发更新冲突（乐观锁失败） | `MASTERY_CONCURRENT_CONFLICT(1061)` | 200（业务码，前端提示重试） |

---

## 12. 隐性约定对齐

| 约定 | 对齐情况 |
|------|----------|
| #1 统一响应结构 | ✅ 所有 Controller 返回 `Result<T>` |
| #2 异常处理 | ✅ 统一 `BusinessException` + `ErrorCodes` |
| #3 分页参数 | ✅ page 从 1 开始，size 默认 10 |
| #4 时间格式 | ✅ `LocalDateTime`，MyBatis-Plus 自动填充 |
| #9 @Transactional | ✅ 写方法均指定 `rollbackFor = Exception.class` |

---

## 13. 技术债预登记

| ID | 描述 | 优先级 | 影响范围 |
|----|------|--------|----------|
| TD-03 | alpha 学习率硬编码为常量 0.3，未提供数据库配置表 | 低 | 参数调整需改代码重新部署 |
| TD-04 | 管理端看板 JOIN 查询未建专用索引，数据量大时可能性能不足 | 低 | `getAdminDashboard` 查询 |

---

## 14. 审查修正记录

> 记录本次工件审查中发现的问题及修复位置，便于追溯。

| 级别 | 编号 | 问题摘要 | 修复位置 |
|------|------|----------|----------|
| 阻断 | A | D_norm 新题偏离校验中 `declaredDifficulty` 为枚举字符串，与数值型 `avgDifficulty` 做差值前须先调用 `mapDifficulty()` 转换 | §2.4 新增类型转换说明 |
| 阻断 | B | `updateMasteryFromHomework` 批量提交中同一知识点可能出现多次，直接循环更新 DB 会导致后写覆盖前写、增量丢失 | §5 接口注释 + §6.1 重写为内存 Map 缓存方案 |
| 重要 | C | `updateMasteryFromHomework` 缺少 submissionIds 归属校验，存在越权更新他人掌握度的安全风险 | §6.1 新增步骤 1 归属校验 |
| 重要 | D | `MasteryFormulaUtil` 未显式声明全局精度约定（scale=4, HALF_UP），实现者可能遗漏导致与 DB DECIMAL 精度偏差 | §3 新增全局精度约定说明 |
| 重要 | E | `MasteryChangeLogVO` 缺少 `knowledgePointName` 和 `subject` 字段，前端切换知识点时无法展示上下文 | §8.3 补充两个字段 |
| 改进 | I1 | 首次创建 `student_knowledge_state` 时 `mastery_score` 初始值未显式定义 | §2.2 参数表 + §3 常量 + §6.2 初始化逻辑 |
| 改进 | I2 | 管理端看板查询未明确 JOIN 查询避免 N+1 风险 | §6.4 步骤 4/5 明确一次性 JOIN |
| 改进 | I3 | 边界测试 `m_current=0` 答错场景的业务前提未说明 | 同步修正 tasks.md Task 10 |
| 改进 | I4 | `findOrCreateState` 新建记录时各字段初始化逻辑未描述 | §6.2 补充完整初始化字段清单 |
| 重要 | F | `updateMasteryFromHomework` 内存缓存方案在并发场景下存在「丢失更新」漏洞（线程 A/B 同时读旧值、后写覆盖前写） | §1.2 实体修改 + §6.1 步骤 6 乐观锁重试 + §10.1 version 字段 + §11 新增错误码 |
| 重要 | G | tasks.md 遗漏 ErrorCodes 扩展任务，且未包含乐观锁相关的 Schema/测试变更 | 同步修正 tasks.md Task 0/5/9/11 |
| 改进 | I5 | 批次内同一知识点多条日志的 `mastery_before/mastery_after` 衔接一致性未纳入测试断言 | 同步修正 tasks.md Task 11 |
| 改进 | I6 | `MasteryDashboardVO.lastChangeDirection` 组装逻辑未定义，§6.3 仅笼统提及 | §6.3 步骤 4 明确批量查询最新日志 + §9.2 新增 Mapper 方法 |