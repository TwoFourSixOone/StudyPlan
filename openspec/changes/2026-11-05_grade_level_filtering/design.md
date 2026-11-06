# Design：题目与知识点年级维度过滤

> **Change 名称**：`2026-11-05_grade_level_filtering`
> **版本**：草案 v1

---

## 1. 技术方案

### 1.1 年级编码规范

全学段统一使用整数编码：

| 编码 | 年级 | 学段 |
|------|------|------|
| 1-6 | 小学一年级 ~ 六年级 | 小学 |
| 7-9 | 初一 ~ 初三 | 初中 |
| 10-12 | 高一 ~ 高三 | 高中 |

### 1.2 数据库变更

#### `knowledge_point` 表新增字段

```sql
ALTER TABLE knowledge_point
  ADD COLUMN grade_range VARCHAR(20) DEFAULT NULL
  COMMENT '适用年级范围，如 "3" 表示仅三年级，"7-9" 表示初中全段，NULL 表示全年级通用';
```

- 类型 `VARCHAR(20)`：支持单年级（`"3"`）、年级范围（`"7-9"`）、多年级（`"1,2,3"`）
- `DEFAULT NULL`：未标注年级的知识点视为全年级通用
- 不加 NOT NULL 约束（存量数据兼容）

#### `question_bank` 表新增字段

```sql
ALTER TABLE question_bank
  ADD COLUMN grade INT DEFAULT NULL
  COMMENT '题目所属年级（1-12），NULL 时继承关联知识点的 grade_range';
```

- 类型 `INT`：单值，表示该题目最适合的年级
- `DEFAULT NULL`：未标注时继承知识点的 `grade_range`
- 不加 NOT NULL 约束（存量数据兼容）

### 1.3 后端 Entity 变更

#### `KnowledgePoint.java`

```java
/**
 * 适用年级范围（如 "3"、"7-9"、"1,2,3"），NULL 表示全年级通用
 */
private String gradeRange;
```

#### `QuestionBank.java`

```java
/**
 * 题目所属年级（1-12），NULL 时继承关联知识点的 grade_range
 */
private Integer grade;
```

### 1.4 年级过滤核心逻辑

#### 1.4.1 年级匹配工具类

新建 `GradeMatcher.java`（`com.jiang.common.util`）：

```java
public class GradeMatcher {

    /**
     * 判断学生年级是否匹配知识点的年级范围
     * @param studentGrade 学生年级（1-12）
     * @param gradeRange 知识点年级范围（如 "3"、"7-9"、"1,2,3"，NULL 表示全年级）
     * @return true 表示匹配
     */
    public static boolean matchesKnowledgePoint(Integer studentGrade, String gradeRange) {
        if (gradeRange == null || gradeRange.isBlank()) return true; // 未标注 = 全年级
        if (studentGrade == null) return true; // 学生无年级 = 不过滤

        // 解析范围
        if (gradeRange.contains("-")) {
            String[] parts = gradeRange.split("-");
            int min = Integer.parseInt(parts[0].trim());
            int max = Integer.parseInt(parts[1].trim());
            return studentGrade >= min && studentGrade <= max;
        }
        if (gradeRange.contains(",")) {
            return Arrays.stream(gradeRange.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .anyMatch(g -> g.equals(studentGrade));
        }
        // 单年级
        return Integer.parseInt(gradeRange.trim()) == studentGrade;
    }

    /**
     * 判断学生年级是否匹配题目年级
     * 题目 grade 非空时精确匹配；为空时回退到知识点 gradeRange
     */
    public static boolean matchesQuestion(Integer studentGrade, Integer questionGrade, String kpGradeRange) {
        if (questionGrade != null) {
            return studentGrade == null || questionGrade.equals(studentGrade);
        }
        // 题目未标注年级，继承知识点
        return matchesKnowledgePoint(studentGrade, kpGradeRange);
    }
}
```

#### 1.4.2 出题流程中的年级过滤

在 `HomeworkServiceImpl` 的题目池筛选阶段增加年级过滤：

```
现有流程：按知识点 → 按难度 → 按题型 → 出题
新增流程：按知识点 → 按年级过滤 → 按难度 → 按题型 → 出题
```

具体改动点：
1. `HomeworkServiceImpl.generateDailyHomework()` 中获取可用题目时，传入 `studentGrade`
2. 题目池筛选增加 `GradeMatcher.matchesQuestion(studentGrade, q.grade, kp.gradeRange)` 条件
3. **降级策略**：若本年级题目不足（< 2 道），自动扩展至相邻年级（±1），仍不足则不过滤年级

#### 1.4.3 评测出题中的年级过滤

`AssessmentServiceImpl` 的知识点诊断题目选择同样增加年级过滤，逻辑同上。

### 1.5 科目接口扩展

`SubjectController.getAvailableSubjects()` 返回的 `SubjectVO` 新增字段：

```java
/** 该科目覆盖的年级列表（从该科目下所有知识点的 grade_range 聚合） */
private List<Integer> availableGrades;
```

聚合逻辑：查询该科目下所有 `active` 知识点的 `grade_range`，解析并去重排序。

### 1.6 数据导入变更

#### 知识点导入（`topics.json`）

新增可选字段 `gradeRange`：

```json
{
  "id": "kp_math_001",
  "name": "分数加法",
  "subject": "数学",
  "gradeRange": "3",
  ...
}
```

#### 题目导入（`questions.json`）

新增可选字段 `grade`：

```json
{
  "topicId": "kp_math_001",
  "content": "1/2 + 1/3 = ?",
  "grade": 3,
  ...
}
```

导入时：
- `gradeRange` / `grade` 为可选字段，缺失时写 NULL
- 不校验年级值的合理性（由管理端校对流程处理）

### 1.7 管理后端变更

- 知识点列表页：新增"年级"列，展示 `gradeRange`
- 题目列表页：新增"年级"列，展示 `grade`（为空时显示"继承知识点"）
- 搜索/筛选：支持按年级筛选知识点和题目

---

## 2. 隐性约定对齐

| 约定/规则 | 对齐方式 |
|------|---------|
| 隐性约定 #13 UNIQUE 索引列禁止 DEFAULT NULL | 新增字段不参与唯一索引，允许 NULL |
| 隐性约定 #7 JWT 登录态 | 从 JWT 解析 studentId → 查 Student.grade → 传入出题 Service |
| 产品规则 #3 每日作业 | 年级过滤在题目池筛选阶段完成，不影响 Agent 调用和配额计算 |
| 产品规则 #8 知识点治理 | `gradeRange` 作为知识点元数据，待校对知识点也可标注年级 |
| 产品规则 #10 数据导入 | 新字段为可选，旧 JSON 格式向后兼容 |

---

## 3. 异常处理

| 场景 | 处理方式 |
|------|---------|
| 学生 `grade` 为 NULL | 不做年级过滤，返回全部题目（与现有行为一致） |
| 知识点 `gradeRange` 为 NULL | 该知识点下所有题目对所有年级可见 |
| 题目 `grade` 为 NULL | 继承知识点的 `gradeRange` |
| 本年级题目不足 2 道 | 自动扩展至相邻年级（±1），仍不足则取消年级过滤 |
| `gradeRange` 格式异常 | 日志警告，视为全年级通用（NULL 处理） |

---

## 4. 不涉及前端学生端改动

学生端已在 `2026-11-05_student_business_pages` 中完成年级展示。本 change 的年级过滤全部在后端 Service 层完成，对前端透明。
