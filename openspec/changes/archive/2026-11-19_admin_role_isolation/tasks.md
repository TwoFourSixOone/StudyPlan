# Tasks：管理端接口按角色隔离

> **Change**：`2026-11-19_admin_role_isolation`　**状态**：已实施（2026-11-19，含 R2 复核后的绕过修复 round-2）

## T1 JWT 角色 claim

- [x] `JwtUtil` 新增 `CLAIM_ROLE` / `ROLE_STUDENT` / `ROLE_ADMIN`、`generateToken(openid, subjectId, tenantId, role)`、
      `generateAdminToken(openid, adminId, tenantId)`；旧 3 参 `generateToken` 默认 `role=student`（学生登录/刷新零改动）
- [x] `resolveRole` / `isAdmin`：缺失/非法 `role` 统一按 student（旧 token 兼容，不强制重登）
- **验收**：学生 token 无行为变化；admin 登录 token `role=admin`、`sub`=管理员 ID

## T2 拦截器角色门禁（第一版）

- [x] `JwtInterceptor#preHandle`：`/api/admin` 与 `/api/admin/**` 且非 admin → 403；CORS 预检放行；
      admin 分支只写 `AdminContext` + `StudentContext.tenantId`；刷新保持原角色；`afterCompletion` 双清
- [x] 新增 `AdminContext`（adminId/openid/tenantId + `clear()`）
- **验收**：学生 token 访问 `/api/admin/**` → 403 + 统一响应体，不进业务层

## T3 admin 登录签发 admin token

- [x] `AdminAuthServiceImpl#login` 改签 `generateAdminToken`（本轮仍为占位账号校验，账号落库在 `prod_hardening`）
- [x] `AdminGoalControllerTest` / `AdminMasteryControllerTest` 补 403 用例；其余 6 个管理端 Controller 测试补 `role=admin` stub
- **验收**：既有管理端接口行为不变（admin token 放行）

## T4 admin-web 403 收尾

- [x] `admin-web/src/api/request.js` 403 分支与 401 对齐：清 `VITE_DEMO_TOKEN` + 跳登录页
- **验收**：旧 admin token 升级后不卡在「每页 403 弹提示」，直接回登录页

## T5 测试

- [x] 新建 `JwtInterceptorTest`（真实签发/验签）、`AdminAuthServiceTest`（3 例，role/sub/openid 断言）
- [x] `JwtUtilTest` +4 例；定向 73 例全绿；全量 999 例全绿
- **验收**：`mvn -o -Dtest=JwtInterceptorTest,JwtUtilTest,AdminAuthServiceTest,AdminGoalControllerTest,AdminMasteryControllerTest test`

## T6 R2 复核修复（round-2，R2-F1 blocker）

- [x] 新增类级注解 `com.jiang.security.AdminApi`，9 个管理端 Controller（Admin* 8 + `DataImportController`）全部标注
- [x] `JwtInterceptor#isAdminRequest` 改为「HandlerMethod → beanType 是否带 `@AdminApi`（权威）」；
      路径仅作非 HandlerMethod 兜底，且用 `UrlPathHelper#getPathWithinApplication` 解码+剥 matrix + `sanitizePath` +
      预编译 `PathPattern`（**移除 `request.getRequestURI()` 前缀判定**）
- [x] 绕过回归：`/api/admin;x=1/goal/1`、`/api/%61dmin/goal/1`、`//api/admin/goal/1`、`/api/./admin/goal/1`、
      `/api/admin/goal/1/` 全部 403/404 且不执行 handler；admin token 同组不误伤
- [x] 注解覆盖护栏：`AdminGoalControllerTest` 动态扫描 `/api/admin` 映射，断言带 `@AdminApi` 且数量 ≥ 9
- [x] 修 flaky：刷新用例 near-expiry 由 1000ms 改 60s（JWT exp 秒级截断）
- [x] `WebMvcConfig#addInterceptors` Javadoc 改为「@AdminApi 权威 + 路径兜底」
- **验收**：定向 10 类连续 2 次 69/0/0/0；全量 1023/0/0/0；R2 round-2 复核 verdict=pass

## T7 文档

- [x] 隐性约定 #45、产品规则 #7、本 change 四件套
- **验收**：文档与实现一致（403 语义、旧 token 兼容、@AdminApi 漏标风险均有对应说明）
