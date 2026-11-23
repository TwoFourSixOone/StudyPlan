# Verify：上线加固（多租户 / CORS / admin 账号）

> **Change**：`2026-11-19_prod_hardening`　**验证**：2026-11-19（R3 复核 pass；迁移与凭证两项见「发布必做步骤」「待人工处理」）

## 需求 → 实现

| 需求 | 实现 | 证据 |
|---|---|---|
| 真实多租户：租户取请求上下文、无上下文回退 `default` | `TenantHandler#resolveTenantId`（`AdminContext` → `StudentContext` → `default`）；`MybatisPlusConfig` 租户插件统一走该入口 | `TenantHandlerTest`(6) |
| 写路径与读过滤同源（防「写 default / 读请求租户」割裂） | `AutoFillHandler#insertFill` 的 `tenantId` 调 `TenantHandler::resolveTenantId` | `AutoFillHandlerTest`(5) |
| CORS 从全放开改为可配置白名单，空配置不放行 `*` | `WebMvcConfig#addCorsMappings` + `app.cors.allowed-origins` + `parseAllowedOrigins` + 空数组清空 `"*"` | `WebMvcConfigTest`(5) |
| admin 账号落库、移除硬编码凭证 | `V17__admin_user.sql` + `AdminUser`/`AdminUserMapper` + `AdminAuthServiceImpl` 查库登录 | `AdminAuthServiceTest`(10) |
| 密码只存 PBKDF2 哈希 | `hashPassword`/`verifyPassword`（210000 迭代、随机盐、常量时间比较） | 同上（含"库中无明文"断言） |
| 首个管理员由环境变量初始化 | `AdminAuthServiceImpl#bootstrapAdmin`（幂等；未设密码不创建 + WARN） | 同上（bootstrap 5 分支） |
| 前端明文默认凭证清理 | `admin-web/src/views/Login.vue`：删除默认账号密码提示、`loginForm` 默认值置空 | `cd admin-web && npx vite build` → exit 0；`git grep smileday159 -- admin-web` 无命中 |
| 本地工具配置口令移出跟踪 | `.qoder/settings.local.json` `git rm --cached` + `.gitignore` 精确条目 | `git check-ignore -v` 命中；`git ls-files .qoder` 仅剩 agents/skills |

## 复核记录

| 环节 | 结论 |
|---|---|
| R3 复核（t6） | **verdict=pass**：租户来源与回退、ThreadLocal 清理、异步/定时线程可用性、CORS 默认值与空值回退、V17 的 MySQL 5.7 兼容性、PBKDF2 实现与日志脱敏、bootstrap 幂等均核对通过；未发现串租户/串学生路径 |
| 凭证发现收尾（t17） | `git rm --cached` 仅取消跟踪、本地文件不变；`.gitignore` 精确条目生效 |

## 测试

```
定向（T3）：mvn -o -Dtest=TenantHandlerTest,WebMvcConfigTest,AdminAuthServiceTest test → 26 例全绿
compile：mvn -o -q compile → exit 0
全量：T3 1015 例（0 failure/error/0 skipped）→ 实施侧最后一轮 mvn -o test → 1023/0/0/0（BUILD SUCCESS）
前端：cd admin-web && npx vite build → ✓ built，exit 0（t15 修复登录页后）
V1：verifier 将执行 mvn -o clean test 复跑，以 V1 结果为准
```

| 用例 | 覆盖 |
|---|---|
| `TenantHandlerTest`（6） | 学生/管理端租户优先级、无上下文回退 `default`、空白回退、`clear()` 后回退、`tenant_id` 列名、`ignoreTable=false` |
| `AutoFillHandlerTest`（5） | 学生请求写 `StudentContext` 租户、管理端写 `AdminContext` 租户、无上下文回退 `default`、调用方显式租户不被覆盖、update 只动 `update_time` |
| `WebMvcConfigTest`（5） | 白名单解析（逗号/空格/空项）、null/空串/纯空白 → 空数组、默认只放行本地开发来源、空白名单不退化 `*`（WARN）、自定义白名单只放行配置来源 |
| `AdminAuthServiceTest`（10） | 登录成功签发 `role=admin`（`sub`=管理员 ID）、错误密码/不存在/停用统一 401、PBKDF2 正/误/非法哈希、bootstrap 幂等/创建/未设密码不创建/表缺失/用户名回退 |

## 本轮已修复 / 已清理的凭证项

| 位置 | 处置 | 内容与证据 |
|---|---|---|
| `admin-web/src/views/Login.vue` | **本轮已修复** | 删除「默认用户名 / 默认密码」提示（原第 43–44 行）与 `loginForm` 预填的同名明文密码（原第 62–63 行）；改为中性引导「管理员账号由运维在 admin_user 表创建（密码以 PBKDF2 哈希存储）」+「首次部署请用环境变量 `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` 初始化；未设置密码不会创建账号」。表单校验/提交逻辑未动。证据：`cd admin-web && npx vite build` → exit 0；`git grep smileday159 -- admin-web` 无命中 |
| `.qoder/settings.local.json` | **本轮已清理（仅跟踪层面）** | `git rm --cached` 移出跟踪（本地文件保留、内容零改动、工具配置照常可用）；`.gitignore` 新增精确条目 `/.qoder/settings.local.json`（skills/agents 继续跟踪）。证据：`git check-ignore -v` 命中；`git ls-files .qoder` 仅剩 agents/skills 8 项 |
| 后端 admin 凭证 | **本轮已修复** | `AdminAuthServiceImpl` 移除 `DEMO_USERNAME`/`DEMO_PASSWORD` 硬编码，登录查 `admin_user` 表，密码只存 PBKDF2 哈希；源码/迁移/前端无明文密码或固定哈希 |

> ⚠ 上述 `admin-web` 登录页与 `.qoder` 配置两处口令**历史上已进过 commit**：取消跟踪/删除明文只阻止后续扩散，**必须轮换数据库与管理员口令**，仓库外发时还需评估历史清理。

## 发布必做步骤（本次 change 专属）

> 当前工程**未接入 Flyway**（`pom.xml` 无依赖、`application*.yml` 无 `spring.flyway`），`src/main/resources/db/` 是人工按版本顺序执行的 DDL 脚本。

1. **执行迁移前先备份目标库**（每个脚本尾部有回滚注释，可按需注释执行）；
2. 按版本顺序执行：
   - `src/main/resources/db/V16__exam_paper_image_page_nos.sql`（已归档 change 的迁移，目标库尚未执行则补齐）；
   - `src/main/resources/db/V17__admin_user.sql`（本次新增；**只建表、不写入任何账号**，凭证不进仓库）；
3. 设置环境变量后启动应用：`ADMIN_BOOTSTRAP_USERNAME`（默认 `admin`）、`ADMIN_BOOTSTRAP_PASSWORD`（必填否则不建号）→
   `AdminAuthServiceImpl#bootstrapAdmin` 幂等创建首个管理员（表非空则跳过，不会覆盖已有账号）；
4. 首次登录后按运维流程**修改初始密码**；
5. 若目标库选择多租户：管理端登录当前固定按 `default` 租户查账号（`AdminUserMapper#selectByUsername`），
   指定租户登录需后续 header 方案，本次未实现。

**失败模式**：未执行 `V17` 时应用仍能启动（bootstrap 只 WARN 提示执行 V17），但管理员无法登录（`admin_user` 不存在）；
未设置 `ADMIN_BOOTSTRAP_PASSWORD` 时不会创建账号（WARN 提示初始化方式）。

## 待人工处理（⚠ 阻断上线）

### 1. `src/main/resources/application-dev.yml` 明文数据库口令

- **现象**：L7 `DB_USERNAME: root`、L8 `DB_PASSWORD: <明文口令>`（真实开发库凭证，已跟踪入库）；属 AGENTS.md 受保护文件，
  本轮无权限修改，**未修复**。
- **建议做法**：改为环境变量占位（如 `password: ${DB_PASSWORD:}`），真值放环境变量或已 gitignore 的
  `src/main/resources/application-local.yml`（`.gitignore` 已有该规则）。
- **必须轮换该 DB 口令**：历史 commit 已含明文，仅改文件不能消除历史泄漏；若仓库需要外发，还要评估历史清理
  （`git filter-repo` 等，另行审批）。
- **owner 决策（2026-11-19）**：**C —— 只登记风险，本轮不修改该文件**（`application-dev.yml` 保持现状）。
  由 owner 自行安排：①改为环境变量占位（或 `application-local.yml`）②**轮换该 DB 口令**。
  登记口径：本条作为「上线前必修」的已知风险留在本 change，不阻塞本次代码提交；未完成前不建议对仓库外发或切换生产库。
- **补充**：受保护文件变更须走 change 流程 + 人工审批（AGENTS.md §5），故本次不代 owner 修改。

### 2. 归档历史文档含旧口令引用

- `openspec/changes/archive/2026-11-11_exam_paper_upload_llm/verify.md` 含旧口令的 `docker exec ... -p<口令>` 命令示例；
- 归档目录**只读**，按约定不修改；仅在此记录，供上线前排查/外发评审时参考。

## 未验证 / 后续改进

| 项 | 说明 |
|---|---|
| **迁移未在真实 MySQL 实跑** | V16/V17 仅在 H2 `schema.sql`（测试）与单测中验证；MySQL 5.7 兼容性为静态核对（utf8mb4、无 CHECK/函数索引、含回滚注释） |
| **登录时序可枚举用户名** | admin 不存在时跳过 PBKDF2，比错误密码快约 60–80ms；后续可加 dummy PBKDF2 抹平时间差 |
| **多租户只覆盖 HTTP 请求线程** | `ExamAnalysisWorker`、`GoalServiceImpl#submitRefine`、`ExamSignalAdjustServiceImpl`、`DailyHomeworkGenerationTask` 首个查询等无上下文路径回退 `default`；admin 登录固定 `default` 租户 |
| **缺 admin 登录端到端（H2/HTTP）测试** | 服务层 + Mockito 覆盖；`POST /api/admin/auth/login` → 拿 token → 调管理端接口的容器级联调缺失，V1 只做静态 + 服务层核对 |
| **01:30 自动调整 + 考试结算组合路径无装配级测试** | 两者分别有单测，组合场景（同一目标同一天先结算再 01:30 调整）未做容器级联调 |
| **`application-dev.yml` 明文口令** | 见「待人工处理（⚠ 阻断上线）」 |

## 残留风险清单

1. **旧 admin token 部署后 403 需重登**：升级后旧会话（无 `role` claim）访问管理端一律 403；admin-web 已改为 403 清 token 并回登录页（`admin-web/src/api/request.js`），需重新登录。
2. **首次部署仍需 bootstrap 初始化**：`ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` 未设置时不创建管理员（仅 WARN）；**上线前必须修改初始密码**。
3. **迁移未在真实 MySQL 实跑**：当前工程未接入 Flyway，V16/V17 需人工按版本顺序执行（见「发布必做步骤」）。
4. **01:30 自动调整 + 考试结算组合路径无装配级测试**：两者分别有单测，组合未做容器级联调。
5. **`src/main/resources/application-dev.yml` 明文 DB 口令（owner 决策 C：只登记、本轮不修）**：受保护文件；owner 自行改环境变量占位并**轮换口令**（历史 commit 已含明文）。完成前不建议仓库外发或切生产库。
6. **归档历史文档含旧口令引用**：`openspec/changes/archive/2026-11-11_exam_paper_upload_llm/verify.md`；归档只读，不修改。

## 已知取舍与回滚

- **不加 `tenant_id` 列/不改既有迁移**：租户过滤依赖现有 `tenant_id` 列，回滚 = 还原 `TenantHandler`/`AutoFillHandler`（读/写都会回到固定 `default`，多租户失效但 Demo 可用）；
- **CORS 配置放代码内默认值**：不改 `application*.yml`（受保护）；回滚 = 恢复 `allowedOriginPatterns("*")`（不建议）；
- **admin 账号**：`admin_user` 为唯一新增表，回滚 = `DROP TABLE admin_user`（需确认无引用）；`AdminAuthServiceImpl` 回滚会退回硬编码凭证（不安全，不建议）；
- **PBKDF2 迭代 210000**：单次登录约 60–80ms，换取抗离线爆破；可用 `ADMIN_BOOTSTRAP_PASSWORD` 重新初始化、不支持在线改密接口（未实现）；
- **`username` 租户内唯一**而非全局唯一：多租户下同名账号可在不同租户各存在；Demo 只有 `default`。

## 归档（2026-11-19）

- 归档时状态：R3 复核 pass；定向 26 例、全量 `mvn -o clean test` **1023 通过 / 0 跳过**；`admin-web vite build` 与 `student-app build:mp-weixin` 均通过。
- 未验证：V16/V17 未在真实 MySQL 实跑（工程未接 Flyway，需人工按版本序执行）；admin 登录无 HTTP 端到端测试；admin 登录固定 `default` 租户、登录时序可枚举用户名（后续可加 dummy PBKDF2）。
- 阻断上线项：`src/main/resources/application-dev.yml` 明文 DB 口令（owner 决策 C：只登记、不修改，需轮换口令）。