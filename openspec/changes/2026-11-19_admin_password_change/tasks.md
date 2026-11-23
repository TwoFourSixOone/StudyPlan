# Tasks：管理端修改密码接口

> **Change**：`2026-11-19_admin_password_change`　**状态**：已实施（2026-11-19）

## T1 后端接口

- [x] `ErrorCodes`：新增 `ADMIN_PASSWORD_MISMATCH(1194)`、`ADMIN_PASSWORD_UNCHANGED(1195)`
- [x] 新增 `ChangePasswordRequest`（`@NotBlank` 原密码；`@Size(6,64)` 新密码；Swagger 注解）
- [x] `AdminAuthService#changePassword(adminId, oldPassword, newPassword)` + 实现：
      账号层统一 401（不存在/停用/无身份）→ 原密码错 1194 → 新旧相同 1195 → 更新 `password_hash`（`@Transactional`）
- [x] `AdminAuthController#changePassword`：`POST /api/admin/auth/change-password`，adminId 取 `AdminContext`，
      并把 `@SecurityRequirements` 从类级移到 `login` 方法（改密接口需鉴权，Swagger 文案同步）
- **验收**：原密码错 → 1194 且不写库；新旧相同 → 1195 且不写库；成功 → 新密码可验、旧密码失效

## T2 前端入口

- [x] `admin-web/src/api/auth.js`：新增 `changePassword(payload)`
- [x] `AppLayout.vue`：顶部「管理员」改为下拉（修改密码 / 退出登录）；改密弹窗含原密码、新密码、确认新密码，
      校验 6~64 位与两次一致；成功提示后清除 `VITE_DEMO_TOKEN` 并 `router.replace('/login')`
- **验收**：`cd admin-web && npx vite build` 通过

## T3 测试

- [x] `AdminAuthServiceTest` +4：改密成功（新哈希可验、旧密码失效）、原密码错 1194、新旧相同 1195、
      账号不存在/停用/无身份统一 401（均断言不写库）
- [x] 新增 `AdminAuthControllerTest`（5 例）：admin token 改密成功且 adminId 取自上下文、学生 token 403、
      原密码错返回 1194、新密码过短被参数校验拦下、`@AdminApi` 注解存在
- **验收**：定向 19 例全绿

## T4 文档

- [x] 根 README「修改密码」改为：①管理端自助改密（推荐）②忘记密码运维兜底（删号重建 / 改哈希）
- [x] `admin-web/README.md` 注意事项补改密入口与"改密后需重新登录"说明
- [x] `docs/product/index.md` 规则 #7 补管理员改密约定（含"服务端不主动吊销旧 token"的已知限制）
- [x] 本 change 工件（proposal/design/tasks/verify）

## T5 回归

- [x] `mvn -o -q compile` → exit 0
- [x] `mvn -o '-Dtest=AdminAuthServiceTest,AdminAuthControllerTest' test` → Tests run: 19, 0 failures
- [x] `cd admin-web && npx vite build` → built successfully
- [ ] 全量 `mvn -o test` 与真机改密冒烟（需真实 MySQL + admin 账号）
