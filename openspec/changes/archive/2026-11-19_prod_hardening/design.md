# Design：上线加固（多租户 / CORS / admin 账号）

> **Change**：`2026-11-19_prod_hardening`

## D1 租户解析：一个入口，读写同源

```
TenantHandler#resolveTenantId()
  ├─ AdminContext.getTenantId()      ← 管理端请求（JwtInterceptor 写入）
  ├─ StudentContext.getTenantId()    ← 学生端请求（JWT claims 写入）
  └─ DEFAULT_TENANT_ID = "default"   ← 定时任务 / MQ / Worker / 启动期
```

- 空白一律视为"无上下文"（`hasText`），避免 `tenant_id=""` 污染 SQL；
- `TenantLineInnerInterceptor`（`MybatisPlusConfig`）统一调 `getTenantId()` → 所有读/写 SQL 自动追加租户条件；
- **写路径同源**：`AutoFillHandler#insertFill` 的 `tenantId` 调用同一个 `resolveTenantId()`。
  原因：MyBatis-Plus 租户插件对**实体已带 `tenant_id` 列**的 INSERT 不再追加/覆盖，若这里写死 `default`，
  请求租户 `t1` 写入的行会被 `t1` 自己读不到（读按 `t1` 过滤、写落 `default`）；
- `ignoreTable` 当前返回 `false`：没有任何表忽略租户过滤；后续新增全局表必须逐表说明。

## D2 CORS 白名单（安全默认）

```java
@Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:*}")
private String allowedOrigins;

registry.addMapping("/api/**")
        .allowedOrigins(new String[0])            // 清掉 CorsRegistration 默认的 "*"
        .allowedOriginPatterns(parseAllowedOrigins(allowedOrigins))
        .allowedMethods("GET","POST","PUT","DELETE","OPTIONS")
        .allowedHeaders("*")
        .exposedHeaders("X-Refresh-Token")
        .allowCredentials(true)
        .maxAge(3600);
```

- `parseAllowedOrigins`：逗号分隔、trim、丢弃空项（容忍 `a, ,b,`）；无有效项 → `String[0]`；
- 空数组时 **不放行任何跨域来源**（同源请求不经过 CORS 校验，仍可用），并 WARN 一次提示如何配置；
- 生产覆盖：`APP_CORS_ALLOWED_ORIGINS=https://admin.example.com` 或 `--app.cors.allowed-origins=...`；
- 不写 `application*.yml`（受保护文件），配置项用代码内默认值。

## D3 admin_user 表与密码哈希

`src/main/resources/db/V17__admin_user.sql`：

| 列 | 说明 |
|---|---|
| `admin_id` | PK，自增 |
| `username` | 登录名（**租户内**唯一） |
| `password_hash` | `pbkdf2$<iterations>$<saltBase64>$<hashBase64>` |
| `role` | 角色（`admin`） |
| `status` | `active` / `disabled` |
| `tenant_id` | 租户（`uk_username(username, tenant_id)`） |

- `AdminAuthServiceImpl#hashPassword`：`PBKDF2WithHmacSHA256`、210000 迭代、16 字节 `SecureRandom` 盐、256-bit 派生；
- `verifyPassword`：解析格式 → 重新派生 → `MessageDigest.isEqual` 常量时间比较；格式非法/哈希缺失返回 `false`（不抛异常）；
- 库中只有哈希；源码、迁移、前端**没有**明文密码或固定哈希；单次登录哈希耗时约 60–80ms（可接受）。

## D4 bootstrap 首个管理员

- `AdminAuthServiceImpl#bootstrapAdmin`（`@PostConstruct`）：
  1. `selectCount(null)` 非空 → 直接返回（幂等）；
  2. `ADMIN_BOOTSTRAP_PASSWORD` 空白 → WARN「未设置密码，跳过初始化」并返回；
  3. 否则用 `ADMIN_BOOTSTRAP_USERNAME`（默认 `admin`，空白回退 admin）+ 密码哈希插入一条 `active/admin`；
  4. 表缺失/DB 异常 → WARN（提示执行 `V17`），不拖垮启动。
- 密码只从环境变量/启动参数读，绝不落仓库；日志不打印密码/哈希；
- `admin_user` 为空且未设密码时：应用可启动，但管理端无法登录（`admin_user` 不存在）。

## D5 迁移执行方式（当前工程未接入 Flyway）

- `pom.xml` 无 flyway 依赖、`application*.yml` 无 `spring.flyway`；`src/main/resources/db/` 只是**按版本号排列、人工执行**的 DDL 脚本
  （`V6/V7/V8` 注释里的 "Flyway 保证脚本只执行一次" 是历史表述，已同步修正 `docs/standards/database.md`）；
- 执行约定：执行前备份、按版本号顺序、每个脚本含回滚注释（V17 尾部有 `DROP TABLE` 注释）；
- 本次发布顺序：
  1. 首次部署/升级前：按版本顺序执行 `V16__exam_paper_image_page_nos.sql`（已归档 change 的迁移，目标库未执行则补）、
     `V17__admin_user.sql`（本次新增，仅建表、不写入任何账号）；
  2. 设置 `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` 后启动应用 → bootstrap 幂等创建首个管理员；
  3. 首次登录后按运维流程改密（bootstrap 只在表为空时执行，不会覆盖已有账号）。

## D6 凭证清理（本轮附带）

| 位置 | 处理 | 说明 |
|---|---|---|
| `admin-web/src/views/Login.vue` | **本轮已修复** | 删除「默认用户名/默认密码」提示（改为"管理员账号由运维在 admin_user 表创建（密码以 PBKDF2 哈希存储）"+ 环境变量说明）；`loginForm` 默认值置空；表单校验/提交逻辑未动 |
| `.qoder/settings.local.json` | **本轮已清理** | `git rm --cached` 移出跟踪（本地文件保留、工具配置可用）+ `.gitignore` 精确条目 `/.qoder/settings.local.json`；`git ls-files .qoder` 仅剩 agents/skills |
| `src/main/resources/application-dev.yml` | **未修复（待人工审批）** | 受保护文件；L7 `DB_USERNAME: root`、L8 `DB_PASSWORD: <明文>`；见 verify「待人工处理」 |
| `openspec/changes/archive/2026-11-11_exam_paper_upload_llm/verify.md` | **不修改** | 归档只读，含旧口令命令示例；仅记录 |

## D7 回归护栏

| 类 | 用例数 | 覆盖 |
|---|---|---|
| `TenantHandlerTest` | 6 | 学生/管理端租户优先级、无上下文回退、空白回退、clear 后回退、列名、ignoreTable |
| `AutoFillHandlerTest` | 5（替换原 5 个 `@Disabled` 占位） | 学生/管理端租户写入、无上下文 default、调用方显式租户不被覆盖、update 只动 updateTime |
| `WebMvcConfigTest` | 5 | 白名单解析（逗号/空格/空项）、null/空串/纯空白→空数组、默认只放行本地、空白名单不退化 `*`（WARN）、生产白名单只放行配置来源 |
| `AdminAuthServiceTest` | 10 | 登录成功签发 admin token、密码错误/不存在/停用统一 401、PBKDF2 校验、bootstrap 幂等/创建/不创建/表缺失/用户名回退 |

## D8 风险与边界（含 R3 非阻塞观察）

- **租户只覆盖 HTTP 请求线程**：`ExamAnalysisWorker`、`GoalServiceImpl#submitRefine`、`ExamSignalAdjustServiceImpl`、
  `DailyHomeworkGenerationTask` 首个查询等无上下文路径回退 `default`；admin 登录固定 `default` 租户；
- **登录时序可枚举用户名**：admin 不存在时跳过 PBKDF2（约 60–80ms 差），后续可加 dummy PBKDF2 抹平；
- **V17 手工迁移**：未在真实 MySQL 5.7 实跑；表缺失时应用可启动但管理端登录失败；
- **admin 登录缺端到端测试**：V1 只做静态 + 服务层覆盖；
- **`application-dev.yml` 明文 DB 口令**：本轮未改（受保护），阻断上线，需审批 + 轮换；
- 无 DDL 变更复用既有表；`admin_user` 是唯一新增表，回滚 = `DROP TABLE admin_user`（先确认无引用）。
