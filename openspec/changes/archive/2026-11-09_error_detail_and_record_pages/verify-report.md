# Verify Report：错题详情接口与学习记录独立页

> **Change ID**: `2026-11-09_error_detail_and_record_pages`
> **校验日期**: 2026-11-10
> **校验结论**: ✅ **对齐** — 可以进入归档

---

## 前置检查

| 检查项 | 结果 |
|--------|------|
| `openspec/changes/` 下存在进行中的 change | ✅ |
| 包含 proposal.md / design.md / tasks.md | ✅ |
| 代码已实施 | ✅（7 个后端文件 + 9 个前端文件，工作区未提交） |

---

## Proposal 对齐（6 个功能点）

| # | 功能点 | 状态 | 代码位置 |
|---|--------|------|---------|
| 1 | 新增 `GET /api/error-questions/{errorQuestionId}` | ✅ | `controller/ErrorQuestionController.java:66-83` |
| 2 | `selectDetailById` 单条 JOIN 查询 + 越权条件 | ✅ | `mapper/ErrorQuestionMapper.java:101-124` |
| 3 | `RecordType.code` 加 `@JsonValue` | ✅ | `model/enums/RecordType.java:27` |
| 4 | `LearningRecordMapper.xml` 的 `recordType` 别名改大写 | ✅ | `resources/mapper/LearningRecordMapper.xml:12/31/48` |
| 5 | 学生端：错题列表 → 详情跳转、记录独立页、个人中心改造、返回按钮 | ✅ | `pages/error/Error.vue:214-215`、`pages/error-detail/ErrorDetail.vue:142`、`pages/record-list/RecordList.vue`、`pages/profile/Profile.vue`、`pages/homework-detail/HomeworkDetail.vue` |
| 6 | 附带修复：登录响应解包、移除 `debugger` | ✅ | `stores/auth.ts:97-104`、`utils/request.ts`（`debugger;` 已删除） |

> 路由冲突核对：`GET /statistics` 与 `GET /{errorQuestionId}` 共存，Spring 精确匹配优先，`/statistics` 不会被变量路径吞掉；controller 测试中原有 statistics 用例全部仍通过。

---

## Design 对齐

| 设计要点 | 状态 | 说明 |
|---------|------|------|
| §1 分层表（Controller/Service/Mapper/VO/枚举/XML/前端） | ✅ | 与实现一一对应，无越层 |
| §2 路径与鉴权（`studentId`/`tenantId` 仅来自 `StudentContext`） | ✅ | Controller 未新增任何客户端身份参数 |
| §2 越权与不存在返回同一错误码 | ✅ | 均抛 `ERROR_QUESTION_NOT_FOUND`（1100） |
| §2 响应示例含 14 个字段 | ✅ | `ErrorQuestionDetailVO` 字段与示例一致 |
| §2 错误码语义 | ✅ 已修正 | 实现为 **HTTP 200 + body.code=1100**（`GlobalExceptionHandler` 对非 UNAUTHORIZED 的 `BusinessException` 统一返回 200）；design 原「404/业务码」表述已改为如实描述 |
| §3 SQL：主表按 PK 过滤、`student_id/tenant_id` 仅作越权 | ✅ | `WHERE error_question_id = ? AND student_id = ? AND tenant_id = ?` |
| §3 两处 `LEFT JOIN` 且关联条件带 `tenant_id` | ✅ | `kp` / `qb` 均在 `ON` 中带 `tenant_id` |
| §3 `COALESCE` 兜底题干与难度 | ✅ | 并有 Service 二次判空（双层） |
| §4 双形态契约（DB 大写 / API 小写 / 前端中文） | ✅ | XML 大写别名 ↔ `@JsonValue` ↔ `recordTypeLabel()` 三处成对 |
| §5 列表页跳转详情 | ✅ | `navigateTo('/pages/error-detail/ErrorDetail?errorId=')` |
| §5 详情页渲染题干/选项/答案/解析 | ✅ | `ErrorDetail.vue:142` 调 `getErrorQuestionDetail` |
| §5 记录独立页（分页 + 类型筛选 + 进详情） | ✅ | `RecordList.vue:16-21`（picker 筛选）、`:130-159`（分页）、`:186`（详情）；`size=10` |
| §5 个人中心 `size:5` + 「查看更多」 | ✅ | `Profile.vue`：`size: 5`、`recordTotal > recordList.length` 时显示入口 |
| §5 `pages.json` 注册 record-list | ✅ | `student-app/src/pages.json:63-69` |
| §5 返回按钮纯 CSS 箭头 | ✅ | `HomeworkDetail.vue`：`.back-icon` + `::after { border: none }` |
| §6 详情失败不弹全局 toast | ✅ | `api/error.ts` 传 `{ showError: false }`；页面用 `EmptyState("错题不存在")` |
| §6 关联题目被删仍返回 200 | ✅ | `COALESCE` + Service 判空 |
| §7 约定 #18 已过时（现状 `api/*.ts` 可用） | ✅ 已声明 | 工件内已标注需后续修订约定，未在本 change 擅改受保护文档 |
| §8 测试策略 4 项 | ✅ | 见下节 |

---

## Tasks 对齐（20 个节点）

全部 ✅ 完成（Task 1-7，勾选 20 / 未勾选 0）。

---

## 隐性约定对齐

| 约定 | 状态 | 说明 |
|------|------|------|
| #1 统一响应结构 | ✅ | `Result<ErrorQuestionDetailVO>` |
| #3 分页参数规范 | ✅ | `page` 从 1、`size` 默认 10 |
| 产品规则 #7 JWT 登录态 | ✅ | 身份仅取 `StudentContext` |
| #4 时间格式 | ✅ | `LocalDate` 出参 `yyyy-MM-dd` |
| #13 UNIQUE 列非空 | ✅ | 无 DDL |
| #18 uni-app `api/` 目录 | ⚠️ 约定过时 | 代码在 `student-app/src/api/*.ts` 正常工作；工件已声明，需走 docs change 修订 |
| 规则 #9 学习记录只读不删 | ✅ | 详情为纯查询，本 change 无写接口 |

---

## 超出范围改动

| 文件 | 归属 |
|------|------|
| `src/main/resources/application-dev.yml` | chore（本地 DB 口令） |
| `src/main/resources/db/V1__init_schema.sql` | P0 修复（建表冲突） |
| `src/main/resources/db/V3__error_question_table.sql` | chore（排序规则） |
| `src/test/.../HomeworkServiceTest.java`、`SubjectServiceTest.java` | P0 修复（测试基线） |
| `2026-11-09_plan_adjustment_context` 的全部文件 | 另一个 change |

---

## 偏离项与处理建议

| # | 偏离 | 严重度 | 处理 |
|---|------|--------|------|
| N-1 | design §2 写「404/业务码」，实际为 HTTP 200 + 业务码 1100 | 低 | ✅ 已修正 design 表述（实现未改） |
| N-2 | 隐性约定 #18 与实现冲突（`api/*.ts` 实际可用） | 低 | ⏳ 待独立 docs change 修订 `implicit-contracts.md`（受保护文档，不在本 change 内改） |

> N-1 已闭环；N-2 属跨 change 的文档债，已作为后续 docs change 的输入登记，不阻塞归档。

---

## 测试结果

- **编译**：BUILD SUCCESS
- **测试**：`521 tests, 0 failures, 0 errors, 5 skipped`
- 本 change 新增/扩展：`ErrorQuestionServiceTest` +4（详情正常/查不到/COALESCE 兜底/越权参数）、`ErrorQuestionControllerTest` +2（200 + 字段、业务码 1100）、`RecordTypeTest` 5 例 = 11 例，全部通过
- 回归价值：`RecordTypeTest.should_serializeCode_when_jsonSerialized` 钉住 `@JsonValue`，防止后续为修 MyBatis 映射而删注解导致接口又返回大写

---

## 结论

✅ **对齐** — 6 个功能点、7 个任务节点（20 项）全部落地，11 个新增测试通过，无功能缺口。

N-1（错误码表述）已随本次校验修正；N-2（隐性约定 #18 过时）属跨 change 的文档债，随归档登记为后续 docs change 的输入。

**可进入 archive。**
