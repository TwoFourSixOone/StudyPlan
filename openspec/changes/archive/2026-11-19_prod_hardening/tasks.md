# Tasks：上线加固（多租户 / CORS / admin 账号）

> **Change**：`2026-11-19_prod_hardening`　**状态**：已实施（2026-11-19，含 R3 复核与 captain amend 的写路径租户一致性）

## T1 真实多租户（读/写同源）

- [x] `TenantHandler#resolveTenantId`：`AdminContext.tenantId` → `StudentContext.tenantId` → `default`；空白视为无上下文
- [x] `getTenantId()` 返回 `StringValue(resolveTenantId())`；`ignoreTable` 保持 `false` + 注释「后续全局表逐表说明」
- [x] `AutoFillHandler#insertFill` 的 `tenantId` 改调 `TenantHandler::resolveTenantId`（写路径与读过滤同源）
- [x] `MybatisPlusConfig` 注释同步；不改表结构/迁移/yml
- **验收**：`TenantHandlerTest`(6) + `AutoFillHandlerTest`(5) 通过；无上下文系统路径回退 `default`

## T2 CORS 收敛

- [x] `WebMvcConfig`：`@Value("${app.cors.allowed-origins:...}")`，默认只放行本地开发来源；生产用 `APP_CORS_ALLOWED_ORIGINS` /
      `--app.cors.allowed-origins` 覆盖（不改 yml）
- [x] 安全默认：先 `.allowedOrigins(new String[0])` 清掉默认 `"*"`；空白名单 → 只允许同源 + WARN 一次（不退化为 `*`）
- [x] `parseAllowedOrigins` 抽为 package-private static（逗号/空格/空项容错）
- **验收**：`WebMvcConfigTest`(5) 覆盖解析、默认值、空白名单不退化、自定义白名单

## T3 admin 账号落库 + PBKDF2

- [x] 新增 `src/main/resources/db/V17__admin_user.sql`（utf8mb4、中文注释、回滚注释、`uk_username(username, tenant_id)`）
- [x] 新增 `com.jiang.model.entity.AdminUser` + `com.jiang.mapper.AdminUserMapper#selectByUsername`；H2 `schema.sql` 同步
- [x] `AdminAuthServiceImpl` 移除 `DEMO_USERNAME`/`DEMO_PASSWORD`：查库校验 + PBKDF2WithHmacSHA256
      （210000 迭代、16B 随机盐、`pbkdf2$<iterations>$<saltBase64>$<hashBase64>`、`MessageDigest.isEqual`）
- [x] 登录失败统一 401（不区分账号是否存在）；日志只记 username/status，不打印密码/哈希；不回传哈希
- **验收**：源码/迁移/前端无明文密码或固定哈希；`AdminAuthServiceTest` 的 PBKDF2/登录分支通过

## T4 首个管理员 bootstrap

- [x] `AdminAuthServiceImpl#bootstrapAdmin`（`@PostConstruct`）：表非空跳过（幂等）；`ADMIN_BOOTSTRAP_PASSWORD` 空白不创建 + WARN；
      有密码 → 创建 `active/admin`（`ADMIN_BOOTSTRAP_USERNAME` 默认 `admin`，空白回退）；表缺失/DB 异常 WARN 不拖垮启动
- **验收**：`AdminAuthServiceTest` 覆盖幂等/创建/不创建/表缺失/用户名回退 5 个分支

## T5 测试与文档

- [x] 新增/重写 4 个测试类共 26 例：`TenantHandlerTest`(6)、`AutoFillHandlerTest`(5)、`WebMvcConfigTest`(5)、`AdminAuthServiceTest`(10)
- [x] 定向 26 例、`mvn -o -q compile`、全量 1015/0/0/0（后续基线 1023）全绿
- [x] 隐性约定 #47、产品规则 #7、`docs/architecture/index.md` 与 `docs/standards/database.md` 租户表述同步
- **验收**：V1 `mvn -o clean test` 复跑以 V1 结果为准

## T6 凭证清理（本轮附带）

- [x] `admin-web/src/views/Login.vue`：删除默认账号密码提示 + `loginForm` 默认值置空（t15）；`npx vite build` exit 0
- [x] `.qoder/settings.local.json`：`git rm --cached` 移出跟踪 + `.gitignore` 精确条目（t17）；`git check-ignore` 命中、`git ls-files .qoder` 不再含该文件
- [ ] `src/main/resources/application-dev.yml` 明文 DB 口令：**未修复**（受保护文件，需 owner 审批 + 轮换，见 verify「待人工处理」）

## T7 发布步骤（写进 verify）

- [x] 迁移人工执行顺序：`V16__exam_paper_image_page_nos.sql`（目标库未执行则补）→ `V17__admin_user.sql` → 设置
      `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` 启动 → bootstrap 幂等创建首个管理员
- [x] 注明：未执行 V17 时应用仍能启动（bootstrap 只 WARN），但管理员无法登录；V17 只建表、不写入任何账号
- **验收**：verify 的「发布必做步骤」与「待人工处理」两节可直接作为上线 checklist

## 归档说明（2026-11-19）

- **归档依据**：三项加固全部落地 —— ①多租户读过滤（`TenantHandler.resolveTenantId`）与写填充（`AutoFillHandler#insertFill`）同源，消除"写 default / 读请求租户"；②CORS 改为 `app.cors.allowed-origins` 白名单，空配置不退化 `*`；③`V17__admin_user.sql` + `AdminUser/AdminUserMapper` + PBKDF2（210k / 随机盐 / 常量时间比较）+ 环境变量 bootstrap，删除硬编码账号；另清理 admin-web 明文默认凭证与 `.qoder/settings.local.json` 跟踪。定向 26 例、全量 **1023 通过 / 0 跳过**。
- **评审**：R3（t6）pass。
- **发布必做（工程未接入 Flyway）**：按版本序手工执行 `V16`、`V17` → 设置 `ADMIN_BOOTSTRAP_USERNAME`/`ADMIN_BOOTSTRAP_PASSWORD` 启动（幂等建号）→ **上线前改初始密码**；未执行 V17 时应用可启动但管理员无法登录。
- **owner 决策**：`application-dev.yml` 明文 DB 口令按 **C 方案只登记、本轮不修改**，需 owner 自行改环境变量占位并轮换口令。
- **回归方式**：`mvn -o -Dtest=TenantHandlerTest,AutoFillHandlerTest,WebMvcConfigTest,AdminAuthServiceTest test` + 全量 `mvn -o clean test`。