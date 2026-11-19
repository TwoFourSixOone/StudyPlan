# Proposal：管理端接口按角色隔离（学生 token 不得访问 `/api/admin/**`）

> **Change**：`2026-11-19_admin_role_isolation`

## 背景（2026-11-19 缺陷）

管理端接口（9 个 Controller，路径 `/api/admin/**`）此前只校验「token 是否有效」，不区分角色：

- `src/main/java/com/jiang/config/WebMvcConfig.java#addInterceptors` 只注册一个 `JwtInterceptor`，仅排除两个登录路径，没有角色/路径区分；
- `src/main/java/com/jiang/security/JwtUtil.java#generateToken` 没有 `role` claim；
- `src/main/java/com/jiang/service/impl/AdminAuthServiceImpl.java#login` 复用学生 JWT 结构
  （`generateToken(openid="admin_"+username, DEMO_ADMIN_ID=1L, tenantId)`）。

因此**学生 token 可直接调用管理端接口**：读任意学生的目标（`AdminGoalController`）、掌握度（`AdminMasteryController`）、
题目/知识点/课程标准/依赖/导入日志列表，以及写接口（`DataImportController` 等）。
同时 admin token 的 `sub=1L` 会被 `JwtInterceptor` 写入 `StudentContext.studentId`，管理端操作可能被当成 `student_id=1` 的真实学生数据。

## 根因（file#symbol）

| 环节 | 问题 |
|---|---|
| `WebMvcConfig#addInterceptors` | 只注册一个拦截器、只排除两个登录路径 → 无角色门禁 |
| `JwtUtil`（原实现） | 无 `role` claim；`generateToken` 不分学生/管理员 |
| `JwtInterceptor`（原实现） | 只验签 + 把 `claims.subject` 一律写入 `StudentContext.studentId` |
| `AdminAuthServiceImpl#login` | 复用学生 token 结构，admin 身份与学生身份无法区分 |

## 方案（含 R2 绕过的二次修复）

1. **JWT 角色**：`JwtUtil` 新增 `CLAIM_ROLE` / `ROLE_STUDENT` / `ROLE_ADMIN`、4 参 `generateToken(...)` 与
   `generateAdminToken(...)`；旧 3 参 `generateToken` 保持（默认 `role=student`，学生登录/刷新零改动）；
   `resolveRole` / `isAdmin` 对「缺失或非法 `role` claim」统一按 student（**兼容旧 token**，不强制重新登录）。
2. **判定口径（权威 + 兜底）**：新增类级注解 `com.jiang.security.AdminApi`，9 个管理端 Controller 全部标注；
   `JwtInterceptor#isAdminRequest` 在 handler 是 `HandlerMethod` 时只看 `handlerMethod.getBeanType()` 是否带该注解（权威）；
   路径匹配仅对非 `HandlerMethod`（静态资源）兜底，且必须用 `UrlPathHelper#getPathWithinApplication` 解码 + 剥 matrix +
   `sanitizePath` 折叠/去点段 + 预编译 `PathPattern`——**不能再按原始 `requestURI` 前缀判定**（R2 实测绕过点：
   `/api/admin;x=1/goal/1`、`/api/%61dmin/goal/1` 会命中管理端 handler）。
3. **身份上下文分离**：新增 `AdminContext`（adminId/openid/tenantId）；admin 请求只写 `AdminContext` +
   `StudentContext#setTenantId`（管理端服务仍依赖租户过滤），**不写** `StudentContext.studentId/openid`；
   `JwtInterceptor#afterCompletion` 双清两个 ThreadLocal。
4. **403 语义**：非 admin 访问管理端由 `JwtInterceptor#writeForbidden` 直写 **HTTP 403** +
   `Result.error(FORBIDDEN)` JSON，`return false` 不进业务层；WARN 记规范化 path/openid/role（不打印 token）。
   CORS 预检（`CorsUtils.isPreFlightRequest`）先行放行。
5. **刷新保持角色**：admin token 临近过期时刷新仍签发 admin token。
6. **回归护栏**：`AdminGoalControllerTest#should_annotateAllAdminMappedHandlers_when_adminApiContract` 动态扫描
   `RequestMappingHandlerMapping` 全部 `/api/admin` 映射，断言 beanType 带 `@AdminApi` 且数量 ≥ 9；
   `JwtInterceptorTest` 用真实签发/验签覆盖 matrix/percent/重复斜杠/点段/尾斜杠变体。
7. **admin-web 收尾**：`admin-web/src/api/request.js` 的 403 分支与 401 对齐（清 `VITE_DEMO_TOKEN` + 回登录页），
   避免旧 admin token 升级后卡在「每页 403 弹提示」。

## 不包含

- 不引入 Spring Security；不改造管理端业务查询语义（管理端按 `tenant_id` 看本租户学生数据，这是管理端角色能力）；
- 不改 admin-web 登录流程（仅 403 分支）；
- 不改管理端 Controller 的入参/出参契约（token 对前端不透明）。

## 验收

- 学生 token / 旧无 `role` token 访问任意 `/api/admin/**` → HTTP 403 + `code=403`，`verifyNoInteractions` 证明未进业务层；
- admin token 访问管理端 → 正常（既有 `Admin*ControllerTest` 全绿），路径等价变体（matrix/percent）仍可访问；
- 学生端接口（`/api/student/**`、`/api/homework/**`、`/api/error-questions/**`、`/api/assessment/**`）零回归；
- admin 请求不写 `StudentContext.studentId`，`afterCompletion` 后两个上下文均清空；
- `mvn -o test` 全绿（实施侧最终基线 1023/0/0/0；V1 以 `mvn -o clean test` 复跑为准）。
