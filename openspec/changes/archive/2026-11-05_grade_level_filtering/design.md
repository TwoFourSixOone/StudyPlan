# Design：题目与知识点年级维度过滤

> **Change 名称**：`2026-11-05_grade_level_filtering`
> **版本**：v2（2026-11-10 审核后重写）
> **对应 Proposal**：v2

---

## 1. 方案总览：年级过滤的真实落点

v1 假设存在"按知识点 → 按年级 → 按难度 → 按题型"的题目池筛选流程。**该流程不存在**——作业由 LLM 生成，不走题库。v2 按实际取题链路确定四个落点：

| # | 链路 | 现有取题方式 | 年级过滤落点 |
|---|------|-------------|-------------|
| 1 | 每日作业（主路径） | `questionGenAgent.generate(kpList, quota)` | **过滤传给 Agent 的 `kpList`**（按知识点 `gradeRange`） |
| 2 | 每日作业（降级） | `HomeworkDegradationHandler.generateDefaultPlan()` 按 `kp + difficulty='basic'` 查题库 | 取题后按 `grade` 过滤 |
| 3 | 专项评测 | `questionBankMapper.selectActiveByKp(kpId, tenantId)` | 候选集按 `grade` / `gradeRange` 过滤 |
| 4 | 错题重做 | `errorQuestionMapper.selectSourceQuestionForSimilar()` | **不过滤**（复习旧年级内容合理） |

因此本 change 的改动集中在**「决定给哪些知识点」**与**「题库候选集过滤」**两处，而不是改造一个不存在的题目池。

---

## 2. 年级编码与学生年级归一化

### 2.1 编码

| 编码 | 学段 |
|------|------|
| 1–6 | 小学一 ~ 六年级 |
| 7–9 | 初一 ~ 初三 |
| 10–12 | 高一 ~ 高三 |

### 2.2 学生年级的现实约束

`student.grade` 是 `VARCHAR(20)`，且**全库无写入点**（`StudentConverter` 只读）。实际值可能是 `"9"`、`"初三"`、`"初中"`、`NULL`，取决于人工造数。

新增归一化函数（`GradeMatcher.normalizeStudentGrade(String raw)`）：

| 输入 | 结果 |
|------|------|
| `"9"` / `" 9 "` | 9 |
| `"三年级"` / `"3年级"` | 3 |
| `"初一"` / `"初二"` / `"初三"` | 7 / 8 / 9 |
| `"高一"` / `"高二"` / `"高三"` | 10 / 11 / 12 |
| `"初中"` / `"高中"` / `"小学"`（学段级） | `null`（无法定位单一年级 → 不过滤） |
| `NULL` / `""` / 无法识别 | `null` + WARN 日志 |

**Demo 约定**：学生年级由人工 SQL 设置，建议统一写 `"1"`–`"12"`；中文别名仅为兼容存量手工数据。

---

## 3. 数据库变更（`V11__add_grade_fields.sql`）

> 现有脚本最高为 `V10`，v1 写的 `V13` 是跳号，v2 改为 `V11`。

```sql
-- V11: 知识点与题库增加年级维度
-- 依赖：V10 已 apply
ALTER TABLE knowledge_point
  ADD COLUMN grade_range VARCHAR(20) DEFAULT NULL
  COMMENT '适用年级范围："3" / "7-9" / "1,2,3"；NULL=全年级通用';

ALTER TABLE question_bank
  ADD COLUMN grade TINYINT DEFAULT NULL
  COMMENT '题目所属年级 1-12；NULL=继承知识点 grade_range';

-- 回滚方案（人工审批后执行）
-- ALTER TABLE question_bank  DROP COLUMN grade;
-- ALTER TABLE knowledge_point DROP COLUMN grade_range;
```

**语法与取值约定**（导入时强校验，**语法合法 ≠ 取值合法**）：

```
单年级   ^\d{1,2}$                    且 1 <= n <= 12
范围     ^\d{1,2}-\d{1,2}$            且 1 <= min <= max <= 12
列表     ^\d{1,2}(,\d{1,2})+$         且每个元素 1..12
```

> 只做正则匹配会放过 `"0"`、`"99"`、`"9-7"`：`"0"`/`"99"` 入库后对所有学生都不匹配，该知识点变成"内容黑洞"（永远不出题且无人察觉）；`"9-7"` 会让区间判定恒为 false。因此 `isValidRange` **必须同时校验语法与取值**，且 `min <= max`。

**索引决策：不新增索引。** 理由：`grade_range` 用于 Java 层过滤知识点列表（单科目下 ≤ 数百条）；`question_bank.grade` 的过滤发生在"按 `knowledge_point_id` 已命中索引"的候选集之后，是内存过滤，落不到 SQL 上。按 `database.md §2`「新增查询必须评估是否需要索引」，此处评估结论为**不需要**。

---

## 4. `GradeMatcher`（`com.jiang.common.util`）

纯静态工具类，无 Spring 依赖，便于单测。

```java
public final class GradeMatcher {

    /** 学生年级字符串 → 1-12；无法识别返回 null（调用方视为不过滤） */
    public static Integer normalizeStudentGrade(String raw);

    /** 年级范围语法校验（导入用）：合法返回 true */
    public static boolean isValidRange(String gradeRange);

    /** 学生年级是否落在知识点范围内；range 为 null/空/非法 → true（全年级通用） */
    public static boolean matchesRange(Integer studentGrade, String gradeRange);

    /** 题目年级判定：题目 grade 非空精确匹配；为空则回退知识点范围 */
    public static boolean matchesQuestion(Integer studentGrade, Integer questionGrade, String gradeRange);
}
```

**边界处理**（v1 缺失，v2 必须覆盖）：

| 输入 | 行为 |
|------|------|
| `studentGrade == null` | `true`（不过滤） |
| `gradeRange` 为 null / 空白 | `true` |
| `gradeRange` 语法非法 | `true` + WARN（v1 示例代码会抛 `NumberFormatException`，与文档承诺不符） |
| `"9-7"`（min > max） | 非法 → `true` + WARN |
| `"1,2,3,"`（尾逗号）/ 全角破折号 / 负数 / 0 / >12 | 非法 → `true` + WARN（判非法时**不能只靠正则**，需校验 1–12 与 min ≤ max） |
| `questionGrade` 非空 | 严格 `equals(studentGrade)` |
| `questionGrade` 为空 | 回退 `matchesRange(studentGrade, gradeRange)` |

**已知语义限制**：题目 `grade` 是"最适合年级"的单值，跨年级复用同一道题需要重复标注或复制题目。本期不解决，导入时可按知识点范围批量标注。

---

## 5. 每日作业链路

### 5.1 知识点列表过滤（主路径）

**口径：严格优先 + 整体降级**（与 proposal §5 一致）——先按学生年级严格过滤；只有当过滤后**整个知识点集合为空**（当天无法出题）时，才整体关闭年级过滤，不做逐项 ±1 扩展。

`HomeworkServiceImpl.generateDailyHomework()` 中，在组装传给 Agent 的 `kpList` 之后、调用 `questionGenAgent.generate()` 之前插入一步：

```java
Integer studentGrade = GradeMatcher.normalizeStudentGrade(getStudentGrade(studentId));
List<KnowledgePointWithMastery> filtered = kpList.stream()
        .filter(kp -> GradeMatcher.matchesRange(studentGrade, kp.getGradeRange()))
        .toList();
if (filtered.isEmpty() && !kpList.isEmpty()) {
    log.warn("年级过滤后知识点为空，降级为不过滤: studentId={}, grade={}", studentId, studentGrade);
    filtered = kpList;
}
```

> `getStudentGrade(Long)` 已存在（`HomeworkServiceImpl` 内，供 AI 解析使用），直接复用，不新增 Mapper 依赖。

**数据来源补充（v2 自查发现）**：`KnowledgePointWithMastery` 当前只有 `knowledgePointId / name / subject / masteryScore / confidence`，**没有 `gradeRange`**。它由 `StudentKnowledgeStateMapper.selectActiveStatesWithKpInfo` 的 JOIN 结果转换而来，因此需要三处配套改动：

1. `selectActiveStatesWithKpInfo` 的 SELECT 增加 `k.grade_range AS kp_grade_range`
2. `StudentKnowledgeState` 增加瞬态字段 `kpGradeRange`（`@TableField(exist = false)`，与既有 `kpName/kpSubject/kpStatus` 同款）
3. `KnowledgePointWithMastery` 增加 `gradeRange`，Service 组装时赋值

**配额不变**：过滤发生在配额计算**之前**，配额仍按过滤后的知识点集合套用 `HomeworkQuotaCalculator`，`15 题 / 单知识点 4 题 / 原题优先` 语义不变。

### 5.2 降级处理器过滤

`HomeworkDegradationHandler.generateDefaultPlan()` 取 `difficulty='basic'` 的题后按 `matchesQuestion` 过滤；过滤后为空则该知识点不产出题目（不降级到其他年级）。

签名增加 `Integer studentGrade` 参数，调用点（`HomeworkServiceImpl:339`）同步传入。**该签名变更会影响 `HomeworkServiceTest` 中对 `generateDefaultPlan(anyList(), eq(TENANT_ID))` 的 stub，需同步修改。**

### 5.3 错题重做：不过滤，但与年级过滤存在交互

`selectSourceQuestionForSimilar` 保持原样（复习旧年级内容合理，错题本就来自学生做过的题）。但过滤 `kpList` 会与错题配额产生三处交互，必须显式处理：

| 交互点 | 现状 | 处理 |
|--------|------|------|
| `errorKpIds` 来自 `dueErrorMap.keySet()`，可能包含被年级过滤掉的知识点 | `kpMasteryMap` 由过滤后的 `kpList` 构建，缺失 key 时 `getOrDefault(kpId, 1.0)` 会把该知识点当成"掌握度最高"，影响配额排序 | **传给 `HomeworkQuotaCalculator` 之前**先把 `errorKpIds` 收敛为 `gradeKpIds ∩ errorKpIds`：被排除的知识点既不参与排序，也不分配举一反三配额，但原题仍照常重做 |
| `similarQuotaPerKp` 可能给已排除知识点分配举一反三配额 | 会为不在 `kpList` 中的知识点生成新题 | **从相似题配额中剔除**这些知识点（仅保留原题重做） |
| 极端模式（到期错题 ≥ 15）下 15 题全是原题 | 年级过滤对错题完全无效 | 接受：错题来自学生实际做过内容，属合理复习；在日志中记录当日为纯错题模式 |

对应产品规则 #11 的边界条件："若该错题所属知识点已被年级过滤排除，则该知识点当日只安排原题重做，不生成举一反三新题。"

---

## 6. 专项评测链路

`AssessmentServiceImpl` 中，`questionBankMapper.selectActiveByKp(kpId, tenantId)` 之后按 `matchesQuestion(studentGrade, q.getGrade(), kp.getGradeRange())` 过滤。

**回退口径**：只有过滤后**为空**才回退为未过滤候选；若只是数量不足 `QUESTIONS_PER_KP`（=3），按实际数量抽题，**不回退**。原因：每个知识点本来就只有 3 题左右的候选，若"不足就回退"，年级过滤在评测链路上会退化成几乎不生效。

知识点实体已在 `activeKps` 中加载，构造 `Map<String, KnowledgePoint>` 供过滤时取 `gradeRange`，避免额外查询。

---

## 7. 数据导入与校验

`DataImportServiceImpl`：

| 数据 | 字段 | 处理 |
|------|------|------|
| 知识点 | `gradeRange`（可选，推荐） | 缺失 → 尝试读单值 `grade`；存在但 `!isValidRange` → **该条记录失败**，写 `data_import_error_log` |
| 知识点 | `grade`（可选，数据集兼容） | 1–12 → 转成 `grade_range = "N"`；**13–16（高中选择性必修模块）→ `"10-12"`（高中通用）**；其他值 → 落 NULL + WARN；非整数 → 该条失败 |
| 题目 | `grade`（可选） | 缺失 → NULL（继承知识点）；非 1–12 整数 → 同上处理 |

**数据集兼容说明（2026-11-10 联调补充）**：公开数据集 `topics.json`（cn-math-taxonomy）使用的字段是**单值 `grade`**（1–16），没有 `gradeRange`；`questions.json` 则完全没有年级字段。因此：

- 知识点：`gradeRange` 优先，缺失时降级读 `grade`；`grade` 13–16 表示高中"选择性必修"模块（选必二/三、选择性必修第 1–4 册），归一化为 `"10-12"`——**不能落 NULL**，NULL 是"全年级通用"，会让小学/初中学生看到高中内容（联调时实际踩到过）
- 题目：无年级字段是**正常情况**，题目通过知识点继承年级，不需要额外标注
- 已导入的历史数据不会被重导覆盖（导入是追加模式、ID 冲突跳过），需要一次性 `UPDATE` 回填（见 change 交付说明中的回填脚本）

> v1 的"不校验年级值合理性"被否决：脏值会在出题时被 `matchesRange` 静默吞掉（视为全年级），问题被隐藏到线上，且无法定位是哪条数据。按产品规则 #10 的既有机制（逐条校验 + 错误日志 + 不阻塞）处理。

---

## 8. 管理端展示

| 页面 | 服务 | VO |
|------|------|----|
| 知识点列表 | `KnowledgePointGovernanceServiceImpl.getKnowledgePointList()` | `KnowledgePointListVO` 增 `gradeRange` |
| 题目列表 | `AdminListQueryServiceImpl.getQuestionList()` | `QuestionListVO` 增 `grade` |

> v1 把两个列表都归到 Controller；实际知识点列表在 `KnowledgePointGovernanceService`，题目列表在 `AdminListQueryService`。Controller 仅透传新增的筛选参数。

**筛选语义（v2 第二轮评审补充）**：

| 列表 | 是否支持年级筛选 | 原因 |
|------|----------------|------|
| 题目列表 | ✅ 按年级**精确**筛选（含继承） | `question_bank.grade` 单值可直接 `WHERE`；题目未标注时按所属知识点 `grade_range` 恰好相等命中，SQL 形如 `(grade = ? OR knowledge_point_id IN (...))` |
| 知识点列表 | ✅ 按 `grade` **精确**筛选 | `grade_range` 是 `"7-9"`/`"1,2,3"` 形式，SQL 无法可靠匹配；改为先在内存中按 `matchesExactGrade` 筛出 ID，再用 `IN` 分页，保证 total 正确 |

> **精确 vs 包含（2026-11-10 联调决策）**：管理端筛选语义是"核对标注"，因此只命中
> `grade_range` **恰好等于**该年级的记录（`"10"` 命中、`"10-12"` 不命中）。
> 出题过滤仍用 `matchesRange`（范围包含即适用），两者语义不同，不可混用。

`stage`（初中/高中）与年级的关系：`stage` 是科目学段，年级是内容粒度，两者独立；列表同时展示不冲突。

---

## 9. 隐性约定对齐

| 约定 | 落实方式 |
|------|---------|
| 产品规则 #7 JWT 登录态 | `studentId` 来自 `StudentContext`；年级由 `studentId` 查库（不引"隐性约定 #7"，该条不存在） |
| #15 管理端 JWT | 管理端列表筛选走既有拦截器 |
| #1 统一响应 | `Result<T>` 不变 |
| #3 分页 | 管理端列表分页不变 |
| #13 UNIQUE 列非空 | 新增列不参与唯一索引 |
| #11 并发写保护 | 本期无并发写入面 |
| 产品规则 #3 | 只收窄内容池，不改配额算法 |
| 产品规则 #10 | 导入逐条校验 + 错误日志 + 不阻塞 |

---

## 10. 异常处理

| 场景 | 行为 |
|------|------|
| 学生年级为空/无法识别 | 不过滤，DEBUG 日志 |
| `gradeRange` 非法 | 视为全年级通用 + WARN（含知识点 ID，便于修数据） |
| 过滤后知识点为空 | 关闭过滤 + WARN（记录 studentId/grade/知识点总数） |
| 评测候选为空 | 回退未过滤候选 |
| 导入字段非法 | 该条失败 + `data_import_error_log` |

**已知限制（v2 第二轮评审补充，接受不修）**：

1. **降级对用户不可见**：年级过滤被关闭时只写日志，接口无提示字段，学生可能看到超纲题而不自知。若要提示，需在 `HomeworkVO` 层增加 `gradeFallback` 标记并改前端，超出本期范围。
2. **极端模式下过滤完全失效**：到期错题 ≥ 15 时当日全为原题，年级过滤不起作用（见 §5.3）。
3. **学生年级无写入入口**：Demo 阶段靠手工 SQL，真实用户 `grade` 为 NULL → 过滤默认不生效。

---

## 11. 测试策略

| 层 | 用例 |
|----|------|
| `GradeMatcherTest` | 归一化（数字/中文/学段/空/非法）、范围（单值/区间/列表）、非法语法（倒序/尾逗号/负值/越界）、空值不拦截 |
| `HomeworkServiceTest` | ①年级过滤后 kpList 变少 ②过滤后为空 → 降级不过滤 ③配额仍为 15/4 |
| `AssessmentServiceTest` | 候选集过滤 + 空池回退 |
| `DataImportServiceTest` | 合法 `gradeRange` 入库；非法值写错误日志且不阻塞后续记录 |
| `AdminListQueryServiceTest` / `KnowledgePointGovernanceServiceTest` | VO 返回年级字段 |

`testing.md §7` 要求规则 #3 场景必须覆盖——年级过滤直接影响规则 #3 的内容池，故 Homework 用例为必做项。

---

## 12. 学生端前端

不涉及。年级展示已在 `2026-11-05_student_business_pages` 完成（`Profile.vue` 读 `studentInfo.grade`）；过滤对前端透明。
