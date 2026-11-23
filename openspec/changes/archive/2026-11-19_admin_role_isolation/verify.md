# Verify：管理端接口按角色隔离

> **Change**：`2026-11-19_admin_role_isolation`　**验证**：2026-11-19（两轮复核；先 needs_revision，修复后 pass）

## 缺陷复盘（现象 → 根因 → 修复）

| 现象 | 根因 | 修复 |
|---|---|---|
| 学生 token 可调 `/api/admin/**`：读任意学生目标/掌握度，或走写接口（导入） | `WebMvcConfig#addInterceptors` 只注册一个 `JwtInterceptor`（仅排除登录路径），`JwtUtil` 无 `role` claim，`AdminAuthServiceImpl#login` 复用学生 token | JWT 增加 `role=student/admin`；拦截器按角色断言，非 admin 直写 403 |
| admin token 的 `sub=1L` 被写入 `StudentContext.studentId`，管理端操作可能落到真实学生数据 | 原 `JwtInterceptor` 不看角色一律 `setStudentId(claims.subject)` | admin 只写 `AdminContext` + `StudentContext.tenantId`，不写 studentId/openid |
| 第一版按原始 `requestURI` 前缀判定，`/api/admin;x=1/goal/1`、`/api/%61dmin/goal/1` 仍可执行 admin handler（R2 实测 HTTP 200） | 字符串前缀与 Spring MVC 解码/matrix 匹配语义不一致 | 改为 `@AdminApi` 注解权威判定（SPRING 实际路由结果）+ 规范化路径兜底 |
| 旧 admin 会话升级后页面「每页 403 弹提示」 | admin-web 403 分支只提示、不清 token | `admin-web/src/api/request.js` 403 分支清 token + 回登录页（与 401 一致） |

## 需求 → 实现

| 需求 | 实现 |
|---|---|
| 管理端只允许 admin | `JwtUtil#isAdmin` + `JwtInterceptor#preHandle`；`@AdminApi` 权威判定，非 admin → `writeForbidden`（HTTP 403 + `code=403`） |
| 学生端零回归 | `JwtInterceptor` 学生分支原样写 `StudentContext`；旧无 role token 按 student（学生接口可用）；`JwtInterceptorTest` 覆盖 4 类学生接口路径 |
| admin 身份隔离 | `AdminContext`（adminId/openid/tenantId）；只复用 `StudentContext.tenantId`；`afterCompletion` 双清 |
| 无绕过口子 | `@AdminApi` 注解覆盖 + `UrlPathHelper` 解码+剥 matrix + `sanitizePath` + 预编译 `PathPattern` 兜底；路径变体测试 |
| 漏标可发现 | `AdminGoalControllerTest#should_annotateAllAdminMappedHandlers_when_adminApiContract` 扫描 ≥ 9 个映射并断言注解 |
| 旧 token 兼容 | 缺失/非法 role 按 student；旧 token 访问学生接口正常、管理端 403 |

## 复核对账

| 轮次 | 结论 | 说明 |
|---|---|---|
| R2 round-1（t5） | **needs_revision** | 实测 matrix/percent 路径绕过 + 刷新用例 flaky |
| R2 修复（t10） | 完成 | `@AdminApi` 权威 + 路径兜底；near-expiry 1000ms→60s；定向 10 类连续 2 次 69/0/0/0 |
| R2 round-2（t11） | **verdict=pass** | 独立快照（避免与 worker 全量 mvn 撞 target）：学生 token 对 `/api/admin/goal/1`、`;x=1`、`%61dmin`、`ad%6din` 全 403；admin token 同组 200；`//api/admin`、`/api/./admin` 404；学生控制器 200；88 例全绿 |
| 注释收尾（t13） | 完成 | `WebMvcConfig` Javadoc 修正为「@AdminApi 权威 + 路径兜底」 |

## 测试

```
定向（T2）：mvn -o -Dtest=JwtInterceptorTest,JwtUtilTest,AdminAuthServiceTest,AdminGoalControllerTest,AdminMasteryControllerTest test
  → 73 例全绿
定向（T10 修复后）：10 类测试连续 2 次 → 69/0/0/0
compile：mvn -o compile → exit 0
全量：T2 999 例全绿 → 实施侧最后一轮 mvn -o test → 1023/0/0/0（BUILD SUCCESS）
V1：verifier 将执行 mvn -o clean test 复跑，以 V1 结果为准
```

| 用例 | 覆盖 |
|---|---|
| `JwtInterceptorTest`（14 个场景） | 学生/旧 token 访问管理端 403（不写上下文、WARN 含 path+openid）；admin 放行且不污染 studentId；matrix/percent/`//`/点段/尾斜杠变体 403 且不执行 handler；非 HandlerMethod 兜底；未标 `@AdminApi` 的学生 handler 放行；预检放行；401 既有行为 |
| `JwtUtilTest`（+4） | 默认 student、admin token、旧 token 按 student、非法 role 归一 |
| `AdminGoalControllerTest`（11 例含新增） | 学生/旧 token → HTTP 403 + `code=403` + `verifyNoInteractions`；admin token 路径变体正常；注解覆盖扫描 ≥ 9 |
| `AdminMasteryControllerTest`（+2） | 同上（掌握度读接口） |
| `AdminAuthServiceTest`（10） | 登录签发 `role=admin`、`sub=1`；密码错误/不存在/停用统一 401；PBKDF2 校验；bootstrap 四分支 |
| 6 个管理端 Controller 测试（各 +2~3 行） | 补 `role=admin` stub，管理端既有行为不变 |

## 未验证 / 后续改进

| 项 | 说明 |
|---|---|
| 旧 admin token 真实部署重登 | 单测覆盖「旧 token → 403」，但「升级后旧会话经 admin-web 自动清 token 回登录页」需真实浏览器会话验证 |
| admin 登录端到端（H2/HTTP） | `AdminAuthServiceTest` 是服务层 + Mockito，缺「POST /api/admin/auth/login → 拿 token → 调管理端接口」的容器级联调；V1 只做静态 + 服务层覆盖 |
| 管理端业务跨学生可见 | 本轮只关闭角色通道；admin 仍按 `tenant_id` 看本租户全部学生（管理端角色能力，非缺陷） |
| **后续改进（非本轮修复）**：`GlobalExceptionHandler` 映射 | `GlobalExceptionHandler#handleBusinessException` 只把 `UNAUTHORIZED` 映射为 HTTP 状态码，`FORBIDDEN` 仍返回 HTTP 200；本轮 403 由拦截器直写 HTTP 状态码规避。验证方式：`MockMvc` 断言 `status().isForbidden()` + `jsonPath("$.code").value(403)`。建议后续统一异常层状态码映射 |

## 残留风险清单

1. **旧 admin token 部署后 403 需重登**：升级后旧会话（无 `role` claim）访问管理端一律 403；admin-web 已改为 403 清 token 并回登录页（`admin-web/src/api/request.js`），需重新登录。
2. **首次部署仍需 bootstrap 初始化**：`ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` 未设置时不创建管理员（仅 WARN）；`admin_user` 为空时管理端无法登录；**上线前必须修改初始密码**。
3. **迁移未在真实 MySQL 实跑**：当前工程未接入 Flyway，`V16__exam_paper_image_page_nos.sql`、`V17__admin_user.sql` 需人工按版本顺序执行；本轮仅在 H2 schema 与单测中验证。
4. **01:30 自动调整 + 考试结算组合路径无装配级测试**：两者分别有单测，但「同一目标同一天先考试结算再 01:30 自动调整」的组合未做容器级联调。
5. **`src/main/resources/application-dev.yml` 明文 DB 口令待人工审批**：受保护文件本轮未改；需 owner 审批后改环境变量占位并**轮换口令**（历史 commit 已含明文）。
6. **归档历史文档含旧口令引用**：`openspec/changes/archive/2026-11-11_exam_paper_upload_llm/verify.md` 含旧口令命令示例；归档只读，不修改。

## 已知取舍

- 不引入 Spring Security / 方法级安全注解，保持最小改动；角色只有 student/admin 两类（不做复杂 RBAC，与产品规则 §3 边界一致）；
- admin 请求复用 `StudentContext.tenantId` 而不迁移所有管理端服务到 `AdminContext`（避免扩大改造面）；
- admin token 打学生端接口会拿到 `null studentId`，当前无调用路径，未加额外防护；
- `@AdminApi` 漏标由测试护栏兜住，运行时不做"路径像 admin 但没注解就拒绝"的反向推断（避免误伤非管理端接口）。

## 回滚

回滚 = 还原 `AdminApi` / `AdminContext` / `JwtUtil` / `JwtInterceptor` / `WebMvcConfig` 与 9 个管理端 Controller 的注解、
以及 `admin-web/src/api/request.js` 的 403 分支；同时需回退相关测试（`JwtInterceptorTest`、`JwtUtilTest`、`Admin*ControllerTest`、`AdminAuthServiceTest`）。
回滚会重新打开"学生 token 可访问管理端"的通道，**不建议**；若只是回滚 admin-web 的 403 分支，管理端鉴权仍生效，仅旧 token 会卡在提示。

## 归档（2026-11-19）

- 归档时状态：两轮评审（needs_revision → pass）均绑定最终字节；定向 10 类 69 例、全量 `mvn -o clean test` **1023 通过**。
- 未验证：生产若前置 Nginx/网关，需确认不会把 `/api/admin;x=1/...` 与 percent 编码路径改写成可绕过形式（当前已在 Spring MVC + Tomcat 10.1 组合下实测覆盖）。