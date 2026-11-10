# Verify Report：学生端年级设置

> **Change ID**：`2026-11-10_student_grade_setting`
> **校验日期**：2026-11-10
> **校验结论**：✅ **对齐** — 已归档

---

## 前置检查

| 检查项 | 结果 |
|--------|------|
| contains proposal / design / tasks | ✅ |
| 依赖 change 已归档 | ✅ `2026-11-05_grade_level_filtering` |
| 代码已实施 | ✅ 后端 6 个文件、前端 3 个文件 |
| 联调 | ✅ 用户确认（2026-11-10） |

---

## Proposal 对齐

| 目标 | 状态 | 落点 |
|------|------|------|
| 学生端可设置/修改年级（1–12） | ✅ | `PUT /api/student/profile/grade` + 个人中心年级行 |
| 未设置时展示「未设置年级」 | ✅ | `studentGradeText` 计算属性（灰色 + 可点击） |
| 未设置年级不允许创建学习目标 | ✅ | 前端按钮禁用 + `handleCreateGoal` 守卫 + 后端 `GRADE_NOT_SET(1180)` |

**不包含项核对**：年级变更后历史数据未重算 ✅；未提供管理端修改学生年级 ✅；未做年级自动推断 ✅；未加审计日志 ✅。

---

## Design 对齐

| 设计要点 | 状态 | 说明 |
|---------|------|------|
| §1 接口契约（PUT + 1–12） | ✅ | DTO `@NotNull @Min(1) @Max(12)`，BusinessException 1181 |
| §2 分层（Controller/DTO/Service/Mapper） | ✅ | 新建 `StudentProfileService(+Impl)`，未污染 `AuthService` |
| §2 前端模块约束 | ⚠️ 联调修正 | 原计划新建 `api/student.ts` / `utils/grade.ts`，实际**并入 `api/auth.ts` + 内联到 `Profile.vue`**（mp-weixin 运行期不注册新增模块） |
| §3 目标创建前置校验 | ✅ | 校验放在 createGoal 第一步；学生记录为 null 时放行（仅单测场景） |
| §4.1 年级行交互 | ✅ | picker 12 项，未设置为灰色"未设置年级 ›" |
| §4.2 本地同步 | ✅ | `authStore.setGrade` 同时写 Pinia + Storage，无需重登 |
| §4.3 创建拦截 | ✅ | 按钮 `disabled` + 橙色提示 + 函数守卫 |
| §5 异常处理 | ✅ | 1181 越界 / 1000 学生不存在 / 更新 0 行视同不存在 |
| §8 产品文档 | ✅ | 规则 #1 边界、规则 #11 数据来源已更新 |

---

## Tasks 对齐（22 项）

全部 ✅。

---

## 隐性约定对齐

| 约定 | 状态 | 说明 |
|------|------|------|
| 产品规则 #7 学生登录态 | ✅ | 身份仅取 `StudentContext` |
| #1 统一响应 / #2 异常 | ✅ | `Result<Void>` + `BusinessException` |
| #9 学习记录只读 | ✅ | 未改历史记录 |
| **#18 mp-weixin 模块注册** | ⚠️ 补充条款 | 新增 `api/student.ts` 运行期报 `module not defined`；已并入 `api/auth.ts`，并在约定 #18 增加"新增接口一律并入已有模块"的补充说明 |
| #20 开发者工具缓存 | ✅ | 联调中需清缓存/重新导入才生效，已记录 |

---

## 联调记录

| 现象 | 根因 | 修复 |
|------|------|------|
| 页面无任何变化 | 开发者工具打开的 `dist/dev` 是旧产物，而构建的是 `dist/build` | 启动 `dev:mp-weixin` watcher，产物对齐 `dist/dev` |
| `module 'api/student.js' is not defined` | mp-weixin 运行期不注册**新增**的 api 模块 | `updateGrade` 并入 `api/auth.ts`；年级名称表内联进 `Profile.vue`；删除两个新建文件 |

---

## 测试结果

- **后端**：`mvn test` → `569 tests, 0 failures, 0 errors, 5 skipped`
- **前端**：`npm run build:mp-weixin` → DONE；`dist/dev` 由 watcher 重建
- **联调**：用户确认（未设置 → 拦截 → 设置 → 可建目标）

---

## 结论

✅ **对齐**。3 个功能目标全部落地，联调中暴露的 2 个问题（产物目录不一致、新增模块不注册）已修复，并把"新增模块"约束回写到隐性约定 #18。

**已具备归档条件。**
