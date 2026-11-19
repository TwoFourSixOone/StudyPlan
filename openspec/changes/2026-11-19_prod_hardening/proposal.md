# Proposal：上线加固（真实多租户 + CORS 收敛 + admin 账号落库）

> **Change**：`2026-11-19_prod_hardening`

## 背景（三项"上线前必须处理"的占位实现）

### A. 多租户是假的

`src/main/java/com/jiang/common/handler/TenantHandler.java#getTenantId` 固定返回 `tenant_id='default'`（Demo 占位），
但 JWT claims 里已有真实 `tenantId`（`JwtInterceptor` 会写入 `StudentContext`）→ 所有 SQL 的租户条件与请求身份脱节；
同时 `AutoFillHandler#insertFill` 也固定填 `default`，一旦读侧改成请求租户，就会出现「写 default / 读请求租户」的数据分割。

### B. CORS 全放开

`src/main/java/com/jiang/config/WebMvcConfig.java#addCorsMappings` 使用 `allowedOriginPatterns("*")` +
`allowCredentials(true)`（代码里已留 TODO）→ 任意站点可带凭证调用 `/api/**`。

### C. admin 账号硬编码

`src/main/java/com/jiang/service/impl/AdminAuthServiceImpl.java#login` 把用户名/密码硬编码在源码
（`DEMO_USERNAME` / `DEMO_PASSWORD`），没有 admin 表、没有密码哈希 → 凭证随仓库扩散，且无法改密/停用。

### D. 本轮顺带修复的凭证暴露

- `admin-web/src/views/Login.vue` 登录页渲染「默认用户名：admin / 默认密码：…」并在 `loginForm` 预填同名密码（仓库内硬编码凭证，Critical）；
- `.qoder/settings.local.json` 被 git 跟踪且含 MySQL 口令（本地工具配置，不应入库）。

## 根因（file#symbol）

| 项 | 根因 |
|---|---|
| 多租户 | `TenantHandler#getTenantId` 固定 `DEFAULT_TENANT_ID`；`AutoFillHandler#insertFill` 的 `tenantId` 固定填 `'default'` |
| CORS | `WebMvcConfig#addCorsMappings` 放行 `*` 且 `allowCredentials(true)` |
| admin 账号 | `AdminAuthServiceImpl` 硬编码常量；无 `admin_user` 表/实体/Mapper；密码无哈希 |
| 凭证暴露 | `admin-web/src/views/Login.vue` 默认提示与表单预填；`.qoder/settings.local.json` 未被 `.gitignore` 覆盖 |

## 方案

### A. 真实多租户（读/写同源）

`TenantHandler#resolveTenantId()`：`AdminContext.tenantId` → `StudentContext.tenantId` → `default`（空白视为无上下文）；
`getTenantId()` 用它；`ignoreTable` 保持 `false` 并注释"后续全局表逐表说明"；
`AutoFillHandler#insertFill` 的 `tenantId` 改调 `TenantHandler::resolveTenantId`（**写路径与读过滤同源**）。
不改表结构、不改迁移、不改 yml。

### B. CORS 收敛为可配置白名单

`app.cors.allowed-origins`（默认 `http://localhost:5173,http://127.0.0.1:5173,http://localhost:*`），
生产用 `APP_CORS_ALLOWED_ORIGINS` 环境变量或 `--app.cors.allowed-origins` 覆盖；
**空白名单不退化 `*`**：先 `.allowedOrigins(new String[0])` 清掉 `CorsRegistration` 默认 `"*"`，空 → 只允许同源 + WARN 一次；
`parseAllowedOrigins` 抽成 package-private static 便于单测。

### C. admin 账号落库 + PBKDF2 + 环境变量 bootstrap

- 新增 `src/main/resources/db/V17__admin_user.sql`（utf8mb4、中文注释、回滚注释；`uk_username(username, tenant_id)`）；
- 新增 `AdminUser` 实体 + `AdminUserMapper`（`selectByUsername`）；H2 测试 `schema.sql` 同步；
- `AdminAuthServiceImpl` 移除硬编码：登录查库 + `PBKDF2WithHmacSHA256`（210000 迭代、16B 随机盐、
  `pbkdf2$<iterations>$<saltBase64>$<hashBase64>`、`MessageDigest.isEqual` 常量时间比较）；
- `@PostConstruct#bootstrapAdmin`：`admin_user` 为空且 `ADMIN_BOOTSTRAP_PASSWORD` 非空时创建首个管理员
  （`ADMIN_BOOTSTRAP_USERNAME` 默认 `admin`），幂等；未设密码不创建 + WARN；表缺失/DB 异常只 WARN 不拖垮启动；
- 登录失败统一 401（不区分账号是否存在），日志只记 username/status，不打印密码/哈希。

### D. 凭证清理

- `admin-web/src/views/Login.vue`：删除默认账号密码提示，改为中性引导；`loginForm` 默认值置空；
- `.qoder/settings.local.json`：`git rm --cached` 移出跟踪（本地文件保留）+ `.gitignore` 精确条目 `/.qoder/settings.local.json`（skills/agents 继续跟踪）。

## 不包含

- 不改 `src/main/resources/application*.yml`（受保护文件；`application-dev.yml` 明文口令待人工审批，见 verify）；
- 不引入 Flyway（当前工程未接入，迁移人工执行；仅修正数据库规范文档表述）；
- 不给已有表加 `tenant_id` 列、不改既有迁移、不改 `pom.xml`（PBKDF2 用 JDK 内置）；
- 不做多租户"指定租户登录"（admin 登录固定默认租户，后续 header 方案）。

## 验收

- 租户：HTTP 请求用身份租户、无上下文回退 `default`，读写同源；`TenantHandlerTest`(6) + `AutoFillHandlerTest`(5) 通过；
- CORS：默认只放行本地开发来源、空白名单不放行任何跨域（不退化 `*`），`WebMvcConfigTest`(5) 通过；
- admin 账号：`V17` 迁移 + 实体/Mapper；登录走库 + PBKDF2；源码/迁移/前端无明文密码或固定哈希；
  `AdminAuthServiceTest`(10) 覆盖登录/哈希/bootstrap 四分支；
- 全量 `mvn -o test` 全绿（实施侧最终基线 1023/0/0/0；V1 以 `mvn -o clean test` 复跑为准）；
- `git grep smileday159` 仅剩受保护配置与归档历史（见 verify「待人工处理」）。
