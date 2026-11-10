# Proposal：错题详情接口与学习记录独立页

> **Change 名称**：`2026-11-09_error_detail_and_record_pages`
> **创建日期**：2026-11-09（工件于 2026-11-10 补齐，与已落地代码对齐）
> **版本**：v1
> **状态**：代码已实施，待 verify

---

## 1. 需求背景

联调错题本与学习记录时暴露 4 个问题：

1. **错题点不进详情**：前端 `ErrorDetail.vue` 已存在，但后端只有列表/统计接口，没有「按 ID 查单条」的接口，详情页只能拿到列表行的残缺字段，缺选项、正确答案、解析。
2. **学习记录无独立页**：个人中心内嵌列表一屏放不下，20 条后无法继续浏览。
3. **记录类型标识不一致**：`learning_record` 的 UNION 查询返回大写枚举名（`HOMEWORK`），而前端筛选与展示按小写 code（`homework`）处理，标签直接显示英文。
4. **登录态写入失败 + 调试残留**：`request.post()` 已解包为 `body.data`，`auth.ts` 仍按 `response.code/response.data` 判断，token 存不进去；`request.ts` 残留 `debugger;`，开 DevTools 时会中断请求流程。

## 2. 核心目标

1. 补齐「单条错题详情」后端接口，详情页可展示题干、选项、正确答案、解析
2. 新增学习记录独立页（分页），个人中心保留最近 5 条 + 「查看更多」入口
3. 记录类型统一为小写 code 输出，前端做 code → 中文标签映射
4. 修复登录响应解包与 `debugger` 残留

## 3. 功能范围

### 包含

| 模块 | 变更内容 |
|------|---------|
| 后端接口 | 新增 `GET /api/error-questions/{errorQuestionId}`，返回 `Result<ErrorQuestionDetailVO>` |
| 后端查询 | `ErrorQuestionMapper.selectDetailById`：`error_question` LEFT JOIN `knowledge_point` / `question_bank`，`student_id + tenant_id` 作越权校验 |
| 后端枚举 | `RecordType.code` 加 `@JsonValue`，API 输出小写 code |
| 后端 SQL | `LearningRecordMapper.xml` 的 `recordType` 别名改为大写枚举名，与 `RecordType` 映射一致 |
| 学生端 | 错题列表页/详情页改造；新增 `pages/record-list/RecordList.vue` 并注册路由；个人中心改造为「最近 5 条 + 查看更多」 |
| 学生端 | 答题详情页返回按钮改为纯 CSS 箭头（原来为文字「← 退出」） |
| 附带修复 | `stores/auth.ts` 登录响应解包；`utils/request.ts` 移除 `debugger;` |

### 不包含

- 错题的导出、批量操作、手动移除
- 学习记录的图表统计与按知识点趋势（产品规则 #9 明确 Demo 不做）
- 错题详情的图片/公式渲染优化
- `Error.vue` 之外的其它列表页统一改版

## 4. 隐性约定检查

| 约定 / 规则 | 是否涉及 | 说明 |
|------|---------|------|
| #1 统一响应结构 | ✅ | 新接口返回 `Result<ErrorQuestionDetailVO>` |
| #3 分页参数规范 | ✅ | 学习记录列表 `page` 从 1 开始、`size` 默认 10 |
| #7 JWT 登录态（产品规则 #7） | ✅ | `studentId` / `tenantId` 全部取自 `StudentContext`，不接受客户端传入 |
| #18 uni-app `api/` 目录 | ⚠️ | 约定称 `api/` 不可用、应放 `utils/`；实际 `student-app/src/api/*.ts` 编译运行正常，约定已过时（见 design §6） |
| 规则 #9 学习记录只读不删 | ✅ | 详情接口为纯查询，不产生写操作 |
| #4 时间格式 | ✅ | 日期字段沿用 `LocalDate` + `yyyy-MM-dd` |
| #13 UNIQUE 列非空 | ✅ | 无 DDL 变更 |

## 5. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|---------|
| `RecordType` 加 `@JsonValue` 影响所有出参，若有调用方按大写枚举名解析会失效 | 中 | 前端已改为 code → 中文映射；管理端未使用该枚举 |
| 错题关联题目被删除时详情为空 | 低 | SQL `COALESCE(qb.content, '[题目已删除]')` + Service 二次判空兜底 |
| 详情接口靠 `WHERE student_id` 越权，漏传即越权 | 中 | `studentId` 只能来自 JWT；查不到统一抛 `ERROR_QUESTION_NOT_FOUND`（不暴露存在性） |
| 新增页面未做微信开发者工具缓存清理验证 | 低 | 联调时按约定 #20 清除缓存 |

## 6. 前置依赖

- `2026-11-02_error_question_management`（已归档）— 错题表与艾宾浩斯调度
- `2026-11-03_learning_record_query`（已归档）— 三类记录 UNION 查询
- `2026-11-05_student_business_pages`（进行中）— 页面骨架、`request.ts`、auth store
