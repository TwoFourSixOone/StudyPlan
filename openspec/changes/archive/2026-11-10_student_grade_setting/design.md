# Design：学生端年级设置

> **Change 名称**：`2026-11-10_student_grade_setting`
> **版本**：v1

---

## 1. 接口契约

```
PUT /api/student/profile/grade
Authorization: Bearer <JWT>
Content-Type: application/json

{ "grade": 9 }

→ 200 { "code": 200, "message": "success", "data": null }
→ 业务码 1181：年级非法（非 1–12）
→ 业务码 1180：未设置年级时创建目标的错误（不在此接口返回，见 §3）
→ 业务码 1050：学生不存在
```

- 学生身份只取 `StudentContext.getStudentId()`，不接受客户端传 `studentId`
- 年级**落库为字符串**（`student.grade` 是 `VARCHAR(20)`），写入 `String.valueOf(grade)`，与既有手工数据（`"9"`）保持一致

## 2. 分层设计

| 层 | 类 | 职责 |
|----|----|------|
| Controller | `StudentProfileController` | 接请求、`@Valid` 校验 1–12、返回 `Result<Void>` |
| DTO | `UpdateGradeRequest` | `@NotNull @Min(1) @Max(12) Integer grade` |
| Service | `StudentProfileService` / `Impl` | 查学生 → 更新 `grade` → `updateById` |
| Mapper | `StudentMapper` | 复用既有 BaseMapper，无新增 SQL |

> **前端模块约束（联调修正）**：`updateGrade` 并入已有的 `api/auth.ts`，年级名称表内联在 `Profile.vue`，**不新建 `api/student.ts` / `utils/grade.ts`**。原因：mp-weixin 运行期不会注册新增模块，会报 `module 'api/student.js' is not defined`（隐性约定 #18 补充条款）。

**为什么新建 Service 而不是塞进 `AuthService`**：认证服务负责签发/校验 token，改资料属于"学生档案"职责，混进去会让 `AuthService` 变成第二个胖 Service。

## 3. 创建目标的前置校验

`GoalServiceImpl.createGoal` 在**第一步**（早于科目校验）插入：

```java
Student student = studentMapper.selectById(studentId);
if (student != null && (student.getGrade() == null || student.getGrade().isBlank())) {
    throw new BusinessException(ErrorCodes.GRADE_NOT_SET);
}
```

设计取舍：

| 方案 | 说明 | 采用 |
|------|------|------|
| 学生记录为 null 也报错 | 语义最严 | ❌ 会让既有单测（未 stub `studentMapper`）全部 NPE/失败，收益低 |
| 学生记录为 null 时放行 | 生产环境学生必然存在，null 只出现在单测 | ✅ |

放到第一步的理由：年级缺失是"用户资料不完整"，比"科目不存在"更前置，错误提示也更该先出现。

## 4. 学生端交互

### 4.1 个人中心年级行

```
┌──────────────────────────────┐
│  [头像] 同学          [初三 ›] │   ← 已设置
│         ID: 9                │
└──────────────────────────────┘

┌──────────────────────────────┐
│  [头像] 同学      [未设置年级 ›]│   ← 未设置（灰色 + 提示色）
│         ID: 9                │
└──────────────────────────────┘
```

点击整行 → `uni` 原生 `picker`（`mode="selector"`，12 项：一年级…高三）→ 选中后调接口。

### 4.2 设置成功后的本地同步

调接口成功后必须立即：

1. `authStore.setGrade(String(grade))` — 更新 Pinia `studentInfo.grade`
2. 同步写入 `Storage('auth_user')`（store 内部完成）

否则首页/创建目标仍按旧值判断，用户会看到"已设置年级但仍不能建目标"。

### 4.3 创建目标的拦截

- 年级为空：创建按钮 `disabled`，点击区域显示提示"请先在个人中心设置年级"
- `handleCreateGoal` 内再做一次守卫（防止按钮状态与实际不一致）
- 后端 `GRADE_NOT_SET` 作为最后一道防线

## 5. 异常处理

| 场景 | 行为 |
|------|------|
| 年级 < 1 或 > 12 | 前端 picker 不可能产生；后端 DTO 校验 → `GRADE_INVALID`(1181) |
| 学生记录不存在 | `STUDENT_NOT_FOUND` |
| 更新影响行数 0 | 视为学生不存在，抛 `STUDENT_NOT_FOUND` |
| 未设置年级创建目标 | `GRADE_NOT_SET`(1180)，前端提示"请先设置年级" |

## 6. 隐性约定对齐

| 约定 | 落实方式 |
|------|---------|
| 产品规则 #7 | 身份仅取 `StudentContext` |
| #1 统一响应 / #2 异常 | `Result<Void>` + `BusinessException` + `ErrorCodes` |
| #3 分页 | 不涉及 |
| 产品规则 #11 | 年级 1–12，落库字符串，与 `GradeMatcher.normalizeStudentGrade` 兼容 |

## 7. 测试策略

| 层 | 用例 |
|----|------|
| `StudentProfileServiceTest` | 正常更新（写入字符串）/ 年级越界抛错 / 学生不存在抛错 |
| `GoalServiceTest` | 学生年级为空 → 抛 `GRADE_NOT_SET`；有年级 → 正常创建 |
| 前端 | `npm run build:mp-weixin` 通过 + 人工联调（未设置 → 建目标被拦 → 设置 → 可建） |

## 8. 产品文档改动

- 规则 #11 数据来源：`student.grade` 由**学生端个人中心自行设置**（可修改），不再依赖手工 SQL
- 规则 #1 边界条件：新增"未设置年级不可创建学习目标"
