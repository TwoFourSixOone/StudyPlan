# Design：管理端接口按角色隔离

> **Change**：`2026-11-19_admin_role_isolation`

## D1 角色模型

| 角色 | 签发入口 | JWT 关键 claim | 可访问接口 |
|---|---|---|---|
| 学生（默认） | `AuthController#login`（微信静默登录） | `sub`=学生 ID、`openid`、`tenantId`、`role=student` | 学生端 `/api/**`（管理端 403） |
| 管理员 | `AdminAuthController#login` → `AdminAuthServiceImpl#login` | `sub`=管理员 ID、`openid`=`admin_<username>`、`tenantId`、`role=admin` | 管理端 `/api/admin/**`；学生端接口不写学生身份 |

- **旧 token 兼容**：改造前签发的 token 没有 `role` claim。`JwtUtil#resolveRole` 把「缺失/非法」统一按 `student` 处理：
  学生接口照常可用（不强制重新登录）；这类 token 访问管理端一律 403（旧 admin 会话升级后需重登，见残留风险）。
- **刷新**：`JwtInterceptor` 在临近过期时按原角色签发 `X-Refresh-Token`（admin 刷新后仍 admin，不降级）。

## D2 判定口径：`@AdminApi` 权威 + 规范化路径兜底

```
preHandle(request, response, handler)
 ├─ CorsUtils.isPreFlightRequest → 放行（OPTIONS 不带 token，避免 401 误伤）
 ├─ 解析 JWT（无效/过期 → 401，既有行为）
 ├─ isAdminRequest(handler, normalizedPath)
 │    ├─ handler instanceof HandlerMethod → AnnotatedElementUtils.hasAnnotation(beanType, AdminApi)   ← 权威
 │    └─ 否则 → isAdminPath(normalizePath(request))                                              ← 兜底
 ├─ !admin && isAdmin → writeForbidden(403) + return false（不执行 handler、不写上下文）
 ├─ admin → AdminContext(adminId/openid/tenantId) + StudentContext.tenantId（不写 studentId/openid）
 └─ student → StudentContext(studentId/openid/tenantId)
```

- **为什么注解是权威**：`HandlerMethod.getBeanType()` 是 Spring **实际路由结果**，与 matrix 参数、percent 编码、
  重复斜杠、点段、尾斜杠等变体天然一致，不依赖任何路径字符串推断；
- **为什么路径还要兜底**：静态资源等非 `HandlerMethod` 请求不会命中 controller，但仍需拒绝学生 token 访问 `/api/…` 下的管理端路径；
- **规范化实现**：`UrlPathHelper#getPathWithinApplication`（默认 `urlDecode=true`、`removeSemicolonContent=true`）+
  `sanitizePath`（折叠 `//`、丢弃 `.`、回退 `..`）+ 预编译 `PathPattern` 匹配 `/api/admin` 与 `/api/admin/**`；
  不误伤 `/api/administrator`、`/api/student/admin`。

## D3 R2 绕过复盘（为什么推翻第一版实现）

第一版（T2）用「原始 `requestURI` 是否以 `/api/admin` 为前缀」判定，R2 复核实测两个真实绕过：

| 变体 | Spring 路由结果 | 第一版字符串判定 | 后果 |
|---|---|---|---|
| `/api/admin;x=1/goal/1` | 剥离 matrix 参数后命中 `AdminGoalController#getGoalDetail` | 原始 URI 不是 `/api/admin` 也不是 `/api/admin/...` 前缀 → 判定为"非管理端" | 学生 token 执行 admin handler（HTTP 200） |
| `/api/%61dmin/goal/1` | percent 解码为 `/api/admin/goal/1`，命中同一个 handler | 原始 URI 以 `/api/%61…` 开头 → 判定为"非管理端" | 学生 token 执行 admin handler（HTTP 200） |

修复（T10）：改为 `@AdminApi` 权威判定（见 D2），并补「路径变体」回归测试（`JwtInterceptorTest` 用
`MockHttpServletRequest#setRequestURI` 构造原始 URI，避免 MockMvc 对 `%` 再编码）。

## D4 403 为什么由拦截器直写

- `GlobalExceptionHandler#handleBusinessException` **只把 `UNAUTHORIZED` 映射为 HTTP 状态码**，其余 `BusinessException`
  返回 HTTP 200（`Result.code` 才是业务码）。若拦截器抛 `BusinessException(FORBIDDEN)`，前端拿到的是 200，
  `admin-web` 的 `error.response.status === 403` 分支不会触发。
- 因此 `writeForbidden` 直接：`response.setStatus(403)` + `Content-Type: application/json;charset=UTF-8` +
  `Result.error(ErrorCodes.FORBIDDEN)`（`{code:403,message:"无权限访问",data:null,success:false}`），与 401 的响应结构同构。
- 日志：`WARN 拒绝非管理员访问管理端接口: path={规范化路径}, openid={}, role={}, handler={}`，**不打印 token**。

## D5 身份上下文分离与租户

- `AdminContext`（ThreadLocal：adminId/openid/tenantId + `clear()`）承载管理端身份；
- admin 请求**只复用** `StudentContext#setTenantId`：`AdminListQueryServiceImpl`、`GoalServiceImpl#adminGetGoalList/#adminGetGoalDetail`、
  `MasteryServiceImpl` 管理端方法等仍从 `StudentContext.getTenantId()` 取租户（本轮不扩大改造面）；
- 不写 `StudentContext.studentId/openid`：admin token 的 `sub` 是管理员 ID，写入会变成 `student_id=1` 的真实学生数据；
- `afterCompletion` 必须 `AdminContext.clear()` + `StudentContext.clear()`（线程池复用，防止串身份）。

## D6 回归护栏

| 层 | 用例 | 目的 |
|---|---|---|
| 注解覆盖 | `AdminGoalControllerTest#should_annotateAllAdminMappedHandlers_when_adminApiContract` | 扫描 `RequestMappingHandlerMapping` 全部 `/api/admin` 映射，断言 beanType 带 `@AdminApi` 且数量 ≥ 9（漏标即红） |
| 拦截器 | `JwtInterceptorTest`（14 个场景，真实签发/验签） | 学生/旧 token 403 且不进 handler；admin 放行且不污染 `StudentContext`；路径变体；兜底；预检；401 既有行为 |
| JWT | `JwtUtilTest`（+4） | 默认 student、admin token、旧 token 按 student、非法 role 归一 |
| Controller | `AdminGoalControllerTest` / `AdminMasteryControllerTest`（各 +2） | 学生 token / 旧 token → 403 + `verifyNoInteractions`；admin token 正常 |
| Service | `AdminAuthServiceTest`（10） | 登录签发 admin token、失败统一 401、bootstrap 分支 |
| 前端 | `admin-web/src/api/request.js` | 403 清 token 回登录页（与 401 一致） |

## D7 风险与边界

- 管理端「跨学生可见」是**角色能力**（按 `tenant_id` 看本租户学生数据），本轮只关闭角色通道，不改 admin 查询语义；
- admin token 若打学生端接口会拿到 `null studentId`（当前无此调用路径，前端也未使用）；
- 旧 admin 会话部署后 403，需重新登录（与新契约一致，admin-web 已处理）；
- `GlobalExceptionHandler` 未把 `FORBIDDEN` 映射为 HTTP 状态码，属后续改进项（不是本轮修复）；
- 未引入 Spring Security/方法级注解，保持最小改动。
