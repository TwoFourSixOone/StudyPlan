# Tasks：错题详情接口与学习记录独立页

> **Change 名称**：`2026-11-09_error_detail_and_record_pages`
> **版本**：v1
> **状态**：Task 1-7 已完成（代码在工作区，未提交），`mvn test` 全绿

---

## 任务清单

### Task 1：单条错题详情（后端）

- [x] `ErrorQuestionController` 新增 `GET /api/error-questions/{errorQuestionId}`
- [x] `ErrorQuestionService` / `Impl` 新增 `getErrorQuestionDetail`，查不到抛 `ERROR_QUESTION_NOT_FOUND`
- [x] `ErrorQuestionMapper.selectDetailById`：LEFT JOIN 知识点与题库，带 `student_id + tenant_id` 越权条件
- [x] 新增 `ErrorQuestionDetailVO`（含选项、正确答案、解析）

**涉及文件**：
- `src/main/java/com/jiang/controller/ErrorQuestionController.java`
- `src/main/java/com/jiang/service/ErrorQuestionService.java`
- `src/main/java/com/jiang/service/impl/ErrorQuestionServiceImpl.java`
- `src/main/java/com/jiang/mapper/ErrorQuestionMapper.java`
- `src/main/java/com/jiang/vo/ErrorQuestionDetailVO.java`（新增）

**验证**：`mvn compile` 通过

---

### Task 2：记录类型序列化对齐（后端）

- [x] `RecordType.code` 加 `@JsonValue`，API 输出小写 code
- [x] `LearningRecordMapper.xml` 三处 `recordType` 别名改大写枚举名

**涉及文件**：
- `src/main/java/com/jiang/model/enums/RecordType.java`
- `src/main/resources/mapper/LearningRecordMapper.xml`

**验证**：记录列表接口返回 `recordType = "homework"`，且 MyBatis 能映射为枚举

---

### Task 3：错题列表与详情页（前端）

- [x] `api/error.ts` 新增 `getErrorQuestionDetail(id)`
- [x] `Error.vue` 列表项支持跳转详情
- [x] `ErrorDetail.vue` 改为调用详情接口，渲染题干/选项/答案/解析

**涉及文件**：
- `student-app/src/api/error.ts`
- `student-app/src/pages/error/Error.vue`
- `student-app/src/pages/error-detail/ErrorDetail.vue`

**验证**：`npm run build:mp-weixin` 无报错

---

### Task 4：学习记录独立页（前端）

- [x] 新增 `pages/record-list/RecordList.vue`（分页 + 类型筛选 + 进详情）
- [x] `pages.json` 注册该路由
- [x] `Profile.vue` 内嵌列表改 `size: 5`，`total > 展示数` 时显示「查看更多」

**涉及文件**：
- `student-app/src/pages/record-list/RecordList.vue`（新增）
- `student-app/src/pages.json`
- `student-app/src/pages/profile/Profile.vue`

**验证**：`npm run build:mp-weixin` 无报错；清除开发者工具缓存后可进入列表页

---

### Task 5：记录类型中文标签

- [x] `Profile.vue` / `RecordList.vue` 增加 `recordTypeLabel(code)`（homework/assessment/exam → 作业/评测/考试）
- [x] 列表与详情的类型展示均改为中文

**涉及文件**：
- `student-app/src/pages/profile/Profile.vue`
- `student-app/src/pages/record-list/RecordList.vue`

**验证**：记录列表不再出现英文类型

---

### Task 6：附带修复

- [x] `stores/auth.ts` 修正登录响应解包（`post()` 已返回 `body.data`，取出 `token` / `studentInfo`）
- [x] `utils/request.ts` 移除 `debugger;`
- [x] `HomeworkDetail.vue` 返回按钮改纯 CSS 箭头，去掉小程序 button 默认边框

**涉及文件**：
- `student-app/src/stores/auth.ts`
- `student-app/src/utils/request.ts`
- `student-app/src/pages/homework-detail/HomeworkDetail.vue`

**验证**：登录后 token 正确落库；无 debugger 中断

---

### Task 7：单元/集成测试

- [x] `ErrorQuestionServiceTest`：详情正常返回（题干/选项/答案/解析）/ 查不到抛 `ERROR_QUESTION_NOT_FOUND` / 关联题目被删走 COALESCE 兜底 / 查询携带 studentId + tenantId
- [x] `ErrorQuestionControllerTest`：`GET /{id}` 成功返回 200 + 字段 / 不存在返回业务码 1100
- [x] `RecordTypeTest`：`@JsonValue` 输出小写 code / `fromCode` 大小写与 trim / 非法值抛 `INVALID_RECORD_TYPE` / 枚举名与 code 分离

**涉及文件**：
- `src/test/java/com/jiang/service/ErrorQuestionServiceTest.java`
- `src/test/java/com/jiang/controller/ErrorQuestionControllerTest.java`
- `src/test/java/com/jiang/model/enums/RecordTypeTest.java`（新增）

**验证**：`mvn test` 全绿（520 tests / 0 failures）

---

## 范围外说明

- `application-dev.yml`、`V3__error_question_table.sql` 的改动属于独立 chore，不在本 change 内。

## 验收标准

1. 错题列表点击可进入详情，展示题干、选项、正确答案、解析
2. 他人错题 ID 直接访问返回 `ERROR_QUESTION_NOT_FOUND`
3. 学习记录页可分页浏览，类型显示为中文
4. 个人中心仅展示最近 5 条，可跳转完整列表
5. 登录后 token 正常保存，401 自动重登不受影响
6. `mvn test` 与 `npm run build:mp-weixin` 均通过
