# Tasks：学生端年级设置

> **Change 名称**：`2026-11-10_student_grade_setting`
> **版本**：v1
> **状态**：待实施

---

## 任务清单

### Task 1：后端接口（年级设置）

- [x] `ErrorCodes` 新增 `GRADE_NOT_SET(1180)` / `GRADE_INVALID(1181)`
- [x] 新增 `UpdateGradeRequest`（`@NotNull @Min(1) @Max(12)`）
- [x] 新增 `StudentProfileService` + `Impl.updateGrade`：查学生 → `setGrade(String.valueOf(grade))` → `updateById`
- [x] 新增 `StudentProfileController`：`PUT /api/student/profile/grade`

**涉及文件**：
- `src/main/java/com/jiang/common/exception/ErrorCodes.java`
- `src/main/java/com/jiang/dto/UpdateGradeRequest.java`（新增）
- `src/main/java/com/jiang/service/StudentProfileService.java`（新增）
- `src/main/java/com/jiang/service/impl/StudentProfileServiceImpl.java`（新增）
- `src/main/java/com/jiang/controller/StudentProfileController.java`（新增）

**验证**：`mvn -q test-compile` 通过

---

### Task 2：创建目标前置校验

- [x] `GoalServiceImpl` 注入 `StudentMapper`
- [x] `createGoal` 第一步校验 `student.grade` 非空，否则抛 `GRADE_NOT_SET`
- [x] 学生记录为 null 时放行（仅测试场景出现）

**涉及文件**：
- `src/main/java/com/jiang/service/impl/GoalServiceImpl.java`

**验证**：`mvn -q test-compile` 通过

---

### Task 3：后端测试

- [x] 新增 `StudentProfileServiceTest`：正常更新 / 越界抛错 / 学生不存在抛错
- [x] `GoalServiceTest`：补 `@Mock StudentMapper`；新增"年级为空 → 抛 `GRADE_NOT_SET`"用例
- [x] 既有 `GoalServiceTest` 用例不回归（`studentMapper` 未 stub 时返回 null → 放行）

**涉及文件**：
- `src/test/java/com/jiang/service/StudentProfileServiceTest.java`（新增）
- `src/test/java/com/jiang/service/GoalServiceTest.java`

**验证**：`mvn test` 全绿

---

### Task 4：学生端年级设置交互

- [x] 在 `api/auth.ts` 内新增 `updateGrade(grade)`（**不新建 `api/student.ts`**：mp-weixin 运行期不会注册新增模块，会报 `module 'api/student.js' is not defined`）
- [x] 年级名称表内联在 `Profile.vue`（**不新建 `utils/grade.ts`**，同上）
- [x] `stores/auth.ts` 增加 `setGrade(grade)`：更新 `studentInfo.grade` 并写入 Storage
- [x] `Profile.vue` 用户卡片：年级行可点击，未设置显示"未设置年级"

**涉及文件**：
- `student-app/src/api/auth.ts`（新增 `updateGrade`，不新建模块）
- `student-app/src/stores/auth.ts`
- `student-app/src/pages/profile/Profile.vue`

**验证**：`npm run build:mp-weixin` 通过

---

### Task 5：创建目标拦截

- [x] 年级为空时禁用「创建目标」按钮并给提示
- [x] `handleCreateGoal` 内前置守卫（兜底，防按钮状态与实际不一致）

**涉及文件**：
- `student-app/src/pages/profile/Profile.vue`

**验证**：`npm run build:mp-weixin` 通过

---

### Task 6：产品规则与文档

- [x] 规则 #11「数据来源」：`student.grade` 由学生端自行设置、可修改
- [x] 规则 #1「边界条件」：未设置年级不可创建学习目标
- [x] `openspec/specs/learning-loop.md`：设定目标环节补充"年级前置校验"

**涉及文件**：
- `docs/product/index.md`
- `openspec/specs/learning-loop.md`

**验证**：文档与实现一致

---

### Task 7：构建与联调

- [x] `mvn test` 全量通过 → **569 tests, 0 failures, 5 skipped**
- [x] `npm run build:mp-weixin` 通过
- [x] 联调：未设置年级 → 建目标被拦 → 设置年级 → 可建目标（用户确认，2026-11-10）

> 联调中额外发现：新增 `api/student.ts` 在 mp-weixin 运行期不注册（`module not defined`），已改为并入 `api/auth.ts`，并把约束补充进隐性约定 #18。

**预估时间**：约 2 小时
