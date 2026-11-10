# Design：错题详情接口与学习记录独立页

> **Change 名称**：`2026-11-09_error_detail_and_record_pages`
> **版本**：v1

---

## 1. 技术方案总览

| 层 | 变更 |
|----|------|
| Controller | `ErrorQuestionController` 新增 `GET /{errorQuestionId}` |
| Service | `ErrorQuestionService.getErrorQuestionDetail(studentId, tenantId, id)` |
| Mapper | `ErrorQuestionMapper.selectDetailById`（单条 JOIN 查询） |
| VO | `ErrorQuestionDetailVO`（新增，`@Builder`） |
| 枚举 | `RecordType.code` 加 `@JsonValue` |
| XML | `LearningRecordMapper.xml` 的 `recordType` 别名改大写 |
| 前端 | 新增 `RecordList.vue`；`Error` / `ErrorDetail` / `Profile` / `HomeworkDetail` 改造；`api/error.ts` 增方法 |

无 DDL 变更。

---

## 2. 接口契约

```
GET /api/error-questions/{errorQuestionId}
Authorization: Bearer <JWT>
→ 200 { "code": 200, "message": "success",
        "data": {
          "errorQuestionId": 1, "questionId": 100, "knowledgePointId": "kp_001",
          "knowledgePointName": "一元二次方程", "sourceType": "homework",
          "firstWrongDate": "2026-11-02", "lastWrongDate": "2026-11-05",
          "currentStage": 2, "nextDueDate": "2026-11-08", "status": "active",
          "questionContent": "...", "questionDifficulty": "standard",
          "questionType": "choice", "options": "[...]",
          "correctAnswer": "A", "analysis": "..."
        } }
→ HTTP 200 + body.code=1100（ERROR_QUESTION_NOT_FOUND）：不存在或不属于当前学生
   （GlobalExceptionHandler 对非 UNAUTHORIZED 的 BusinessException 统一返回 HTTP 200 + 业务码）
```

- 路径参数只接受 `errorQuestionId`；`studentId` / `tenantId` **只能**来自 `StudentContext`
- 越权与不存在返回同一个错误码，避免通过错误差异探测他人数据

---

## 3. SQL 设计

```sql
SELECT eq.error_question_id, eq.question_id, eq.knowledge_point_id,
       kp.name AS kp_name, eq.source_type, eq.first_wrong_date, eq.last_wrong_date,
       eq.current_stage, eq.next_due_date, eq.status,
       COALESCE(qb.content, '[题目已删除]')    AS question_content,
       COALESCE(qb.difficulty, 'unknown')      AS question_difficulty,
       qb.type AS question_type, qb.choices AS question_choices,
       qb.answer AS correct_answer, qb.analysis AS question_analysis
FROM error_question eq
LEFT JOIN knowledge_point kp ON eq.knowledge_point_id = kp.knowledge_point_id AND kp.tenant_id = eq.tenant_id
LEFT JOIN question_bank    qb ON eq.question_id       = qb.question_id        AND qb.tenant_id = eq.tenant_id
WHERE eq.error_question_id = #{errorQuestionId}
  AND eq.student_id = #{studentId}
  AND eq.tenant_id  = #{tenantId}
```

设计要点：
- 主表过滤走 `PRIMARY KEY (error_question_id)`，`student_id/tenant_id` 仅作越权校验，不引入额外索引需求
- 两处 JOIN 都用 `LEFT JOIN` + 关联条件带 `tenant_id`，题目被删/知识点被停用时仍返回错题本身
- `COALESCE` 在 SQL 层给默认值，Service 层再做一次判空（双层兜底，与既有 IMP-G 处理一致）

---

## 4. 记录类型序列化契约

`RecordType` 是「DB 存枚举名、API 出小写 code」的双形态：

| 环节 | 形态 | 实现 |
|------|------|------|
| 数据库 / UNION SQL | `HOMEWORK` / `ASSESSMENT` / `EXAM` | `LearningRecordMapper.xml` 别名必须写大写枚举名，MyBatis 才能按 `name()` 映射 |
| API JSON 出参 | `homework` / `assessment` / `exam` | `RecordType.code` 上加 `@JsonValue` |
| 前端展示 | 中文「作业 / 评测 / 考试」 | `Profile.vue`、`RecordList.vue` 内 `recordTypeLabel(code)` 映射 |

> 之前 XML 写小写、枚举按大写映射，导致 `recordType` 解析失败或前端显示英文；两处必须成对修改，只改一边会再次错位。

---

## 5. 前端设计

| 页面 | 改动 |
|------|------|
| `pages/error/Error.vue` | 列表项补充跳转详情、展示到期/阶段信息 |
| `pages/error-detail/ErrorDetail.vue` | 改为调用 `getErrorQuestionDetail`，渲染题干/选项/答案/解析 |
| `pages/record-list/RecordList.vue`（新增） | 独立分页列表，按类型筛选，点击进详情（复用 `getRecordList` / `getRecordDetail`） |
| `pages/profile/Profile.vue` | 内嵌列表 `size: 5`，记录类型 code → 中文；`total > 已展示数` 时显示「查看更多」跳列表页 |
| `pages/homework-detail/HomeworkDetail.vue` | 返回按钮改纯 CSS 箭头（`::after` 去边框，避免小程序 button 默认样式） |
| `pages.json` | 注册 `pages/record-list/RecordList` |

`Profile` 与 `RecordList` 共用 `getRecordList`，仅 `size` 与交互不同，不新增 API 文件。

---

## 6. 异常处理

| 场景 | 处理 |
|------|------|
| 错题不存在 / 非本人 | `BusinessException(ERROR_QUESTION_NOT_FOUND)` |
| 关联题目已删除 | SQL `COALESCE` 返回 `[题目已删除]`，接口仍 200 |
| 记录类型非法 | `RecordType.fromCode()` 抛 `BusinessException`（既有逻辑） |
| 前端详情请求失败 | `getErrorQuestionDetail` 传 `showError: false`，页面内用 `EmptyState` 展示空态，不弹全局 toast |

---

## 7. 隐性约定对齐

| 约定 | 落实方式 |
|------|---------|
| #1 统一响应 | `Result<T>` 包装 |
| #3 分页 | `page` 从 1 起，`size` 默认 10，超出上限截断 |
| 产品规则 #7 JWT | `StudentContext` 取 `studentId/tenantId` |
| 规则 #9 只读不删 | 详情为纯查询；未新增任何删除/覆盖接口 |
| #4 时间格式 | `LocalDate` 出参 `yyyy-MM-dd` |
| #18 api 目录 | **约定已过时**：现状 `student-app/src/api/*.ts` 经 Vite 编译后可在 mp-weixin 正常 `require`，本次沿用 `api/error.ts`。建议后续修订该约定（见交付说明） |

> 说明：多份历史 proposal 引用的「隐性约定 #7 JWT 登录态」在 `implicit-contracts.md` 中**并不存在**（该文件编号缺 #5–#8）。本 change 的工件统一改为引用「产品规则 #7」与「隐性约定 #15」，不再引用不存在的编号。

---

## 8. 测试策略

- Service 层：`ErrorQuestionServiceTest` 补充 ① 正常返回详情 ② 查不到抛 `ERROR_QUESTION_NOT_FOUND`
- Controller 层：`ErrorQuestionControllerTest` 补充 `GET /{id}` 的成功与业务码 1100 分支
- 序列化：`RecordType` 的 `@JsonValue` 用 Jackson 序列化断言 `homework`
- SQL：`selectDetailById` 的越权条件（他人 `student_id`）需有覆盖
