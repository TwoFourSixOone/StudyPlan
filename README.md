# QoderCnHarness

> Java 17 + Maven 项目脚手架，内置 OpenSpec 变更管理流程与 QoderCn AI 协作体系。


这是一个**开箱即用的 Java 项目脚手架**，预置了：

- 标准化的项目目录结构
- AI 辅助开发的完整工作流（OpenSpec）
- QoderCn AI 代理配置（Skills、Agents、评审标准）
- 文档驱动的知识管理体系

**适合场景**：快速启动一个新的 Java 后端项目，并立即拥有 AI 协作能力。

---

## 本地启动（智学伴 StudyMate）

> 本节为**本项目**（智学伴）的本地启动说明；README 其余部分为脚手架模板介绍。

### 0. 环境要求

Java 17+、Maven 3.6+、MySQL 5.7+、Redis 6+（默认 `localhost:6379`）、Node.js 18+（前端），推荐 IntelliJ IDEA。

### 1. 初始化数据库（迁移脚本需**手工按序执行**）

> ⚠️ 本工程**未接入 Flyway**，`src/main/resources/db/V*.sql` 不会随启动自动执行。

```powershell
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS studyplan DEFAULT CHARSET utf8mb4;"
Get-ChildItem .\src\main\resources\db\V*.sql | Sort-Object Name | ForEach-Object {
    Get-Content $_.FullName | mysql -uroot -p studyplan
}
```

- 只需执行**尚未执行过**的版本，且必须按版本号顺序；每个脚本内含回滚注释；
- `V16__exam_paper_image_page_nos.sql`：试卷页序字段；`V17__admin_user.sql`：管理员账号表；
- 数据源配置在 `src/main/resources/application-dev.yml`（`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`，属受保护文件，改动需走 change 流程并经人工审批）。

### 2. 启动后端

```powershell
mvn -o spring-boot:run
# 或者：mvn -o package -DskipTests ; java -jar target\StudyPlan-1.0-SNAPSHOT.jar
```

默认端口 **80**，`spring.profiles.active=dev`，依赖本地 MySQL 与 Redis。

### 3. 初始化第一个管理员账号（admin-web 登录用）

管理端账号存放在 `admin_user` 表，**密码只以 PBKDF2 哈希入库**（源码与迁移里没有任何明文账号密码）。
首个账号由**启动时的环境变量**创建，逻辑幂等：`admin_user` 非空则直接跳过，不会重复建号或覆盖密码。

```powershell
# PowerShell 当前会话（本地 demo 最省事）
$env:ADMIN_BOOTSTRAP_USERNAME = "admin"            # 可选，缺省 admin
$env:ADMIN_BOOTSTRAP_PASSWORD = "换成一个强密码"      # 必填；为空则不创建账号并 WARN
mvn -o spring-boot:run
```

其他设置方式：

| 方式 | 做法 | 说明 |
|---|---|---|
| IDEA | Run/Debug Configurations → Environment variables 填两行 | ⚠️ IDE 启动**不继承**终端环境变量，必须在 Run Configuration 里配 |
| jar 启动参数 | `java -jar target\StudyPlan-1.0-SNAPSHOT.jar --ADMIN_BOOTSTRAP_USERNAME=admin --ADMIN_BOOTSTRAP_PASSWORD=xxx` | 密码会出现在进程列表，仅建议本地使用 |
| 永久用户变量 | `[Environment]::SetEnvironmentVariable("ADMIN_BOOTSTRAP_PASSWORD","xxx","User")` | 需重开终端 / 重启 IDE 才生效 |

**看启动日志判断结果**：

| 日志 | 含义 |
|---|---|
| `已初始化首个管理员账号: username=admin（密码来自环境变量，仅保存 PBKDF2 哈希）` | ✅ 成功 |
| `admin_user 为空且未设置 ADMIN_BOOTSTRAP_PASSWORD，跳过首个管理员初始化…` | 密码变量没生效（漏设 / IDE 未配 / 名称拼写错误） |
| `管理员账号初始化跳过（admin_user 不可用，请确认已执行 db/V17__admin_user.sql）` | 表没建 → 先执行 V17 再启动 |

**登录**：启动 admin-web 后用该账号登录（接口 `POST /api/admin/auth/login`），签发的 token 内含 `role=admin`。

**修改密码**：

1. **推荐：管理端自助改密**（`2026-11-19_admin_password_change`）——
   登录 admin-web 后点右上角「管理员」→「修改密码」，填写原密码 + 新密码（6~64 位）确认即可；
   接口为 `POST /api/admin/auth/change-password`（校验原密码，原密码错返回 `code=1194`、新旧相同返回 `code=1195`），
   **成功后前端自动清除 token 并跳回登录页**（JWT 无状态，旧 token 在过期前仍有效，故需重新登录）。
2. **兜底：忘记密码**（运维手工，两种办法）：
   - **删号重建（最简单）**：`DELETE FROM admin_user WHERE username='admin' AND tenant_id='default';`，换新 `ADMIN_BOOTSTRAP_PASSWORD` 重启即可重新建号；
   - **直接更新哈希**（与代码参数一致：PBKDF2-HMAC-SHA256、210000 次迭代、16 字节随机盐、32 字节密钥，格式 `pbkdf2$迭代$盐Base64$哈希Base64`）：

```powershell
$plain = "新强密码"    # 不要命名为 $pwd（PowerShell 自动变量）
$salt  = New-Object byte[] 16
[System.Security.Cryptography.RandomNumberGenerator]::Fill($salt)
$kdf   = [System.Security.Cryptography.Rfc2898DeriveBytes]::new($plain, $salt, 210000, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
'pbkdf2$210000$' + [Convert]::ToBase64String($salt) + '$' + [Convert]::ToBase64String($kdf.GetBytes(32))
```

```sql
UPDATE admin_user SET password_hash = '<上一步输出>' WHERE username = 'admin' AND tenant_id = 'default';
```

**上线前必做**：改掉初始密码，并把 `ADMIN_BOOTSTRAP_PASSWORD` 从环境变量中移除。

### 4. 启动前端

```powershell
# 管理端（PC）
cd admin-web
npm install
npm run dev            # http://localhost:3000，/api 代理到 http://localhost:80

# 学生端（微信小程序）
cd student-app
npm install
npm run dev:mp-weixin  # 产物在 dist/dev/mp-weixin，用微信开发者工具导入
```

### 5. 身份与权限（两套登录互不影响）

| 端 | 登录接口 | 账号来源 | token 中的 role |
|---|---|---|---|
| 管理端 admin-web | `POST /api/admin/auth/login` | `admin_user` 表（用户名 + PBKDF2 密码） | `role=admin` |
| 学生端小程序 | `POST /api/auth/login` | 小程序 openid 自动建档（无密码） | `role=student` |

- **学生 token 访问 `/api/admin/**` 一律 HTTP 403**（拦截器按 `@AdminApi` 注解判定，matrix 参数 / percent 编码 / 重复斜杠等路径变体无法绕过）；
- 管理端 403 时 admin-web 会自动清除本地 token 并跳回登录页；
- 升级前签发的旧 token（无 `role` claim）按学生处理：学生端不受影响，管理端需重新登录一次。

---


| 分支 | 说明 | 使用方式 |
|------|------|----------|
| **main** | 原始项目结构 | 作为 GitHub 模板仓库或克隆后直接使用 |
| **original-archetype** | Maven Archetype 版本 | 通过 IDEA 的 Maven Archetype 功能创建项目 |

---



1. 点击右上角 **Use this template** → **Create a new repository**
2. 克隆新仓库到本地：
   ```bash
   git clone https://github.com/<your-username>/<your-repo>.git
   cd <your-repo>
   ```
3. 修改项目信息：
   - `pom.xml`：替换 `groupId` 和 `artifactId`
   - `AGENTS.md`：替换项目名称
   - `docs/`：按需修改文档内容
4. 开始开发：
   ```
   /opsx:propose 你的需求描述
   ```


```bash
git clone https://github.com/TwoFourSixOone/QoderCnHarness.git
cd QoderCnHarness
mvn compile
```

---


如果你想通过 IDEA 的 **New Project** 向导直接生成项目，可以使用 `original-archetype` 分支发布的 Maven Archetype。


1. 生成 GitHub Personal Access Token（需要 `read:packages` 权限）：
   - 打开 https://github.com/settings/tokens
   - 点击 **Generate new token (classic)**
   - 勾选 `read:packages` 权限

2. 配置 Maven `settings.xml`（位于 `~/.m2/settings.xml`）：
```xml
  <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
   <servers>
      <server>
         <id>github</id>
         <username>你的GitHub用户名</username>
         <password>你的PAT</password>
      </server>
   </servers>

   <profiles>
      <profile>
         <id>github-profile</id>
         <repositories>
            <repository>
               <id>github</id> <!-- 与 server 的 id 一致 -->
               <url>https://maven.pkg.github.com/twofoursixoone/QoderCnHarness</url>
            </repository>
         </repositories>
      </profile>
   </profiles>

   <activeProfiles>
      <activeProfile>github-profile</activeProfile>
   </activeProfiles>
</settings>
```


1. **File → New → New Project**
2. 左侧选择 **Maven Archetype**
3. 点击 **Add Archetype**，填写：
   - **GroupId**: `com.github.twofoursixoone`
   - **ArtifactId**: `qoder-cn-harness-archetype`
   - **Version**: `1.0.0`
4. 选中后填写你自己的项目信息（GroupId、ArtifactId），点击 **Create**

新项目会自动生成完整的目录结构，包含 OpenSpec 工作流和 QoderCn AI 配置。

---


```
your-project/
├─ AGENTS.md              ← AI 导航地图（入口文件）
├─ QODERCN.md             ← AI 行为准则（定义 AI 基础行为）
├─ REVIEW.md              ← 代码评审标准
├─ docs/                  ← 项目知识库
│  ├─ architecture/       ← 架构设计 & 隐性约定
│  ├─ product/            ← 产品规则
│  └─ standards/          ← 测试 & 数据库规范
├─ openspec/              ← OpenSpec 变更管理
│  ├─ changes/            ← 进行中的变更
│  └─ specs/              ← 系统工作原理
├─ .qoder/                ← QoderCn AI 配置
│  ├─ agents/             ← 子代理定义
│  └─ skills/             ← Skill 定义
├─ src/                   ← 源代码
└─ pom.xml                ← Maven 配置
```

---


所有需求变更必须走 OpenSpec 流程：

```
/opsx:propose → /opsx:apply → /opsx:verify → /opsx:archive
```

| 阶段 | 职责 | 产出物 |
|------|------|--------|
| **propose** | 需求拆解 | proposal.md、design.md、tasks.md |
| **apply** | 实施代码变更 | 代码改动 |
| **verify** | 对齐校验 | 验证报告 |
| **archive** | 归档变更 | 移动到 archive/ |

**核心原则**：需求先工件化，知识先显性化，执行先加护栏，评审与验证必须分离。

---


首次使用？查看 **[Harness 操作手册](docs/harness-playbook.md)**，以「新增用户管理模块」为例，完整演示从需求拆解到代码归档的每一步操作：

1. `/opsx-propose` — 需求拆解，生成工件
2. 人工审查 proposal — 检查边界、隐性约定
3. 补充隐性约定 — 写入 `implicit-contracts.md`
4. 确认 design — 审查技术方案
5. `/opsx-apply` — 按 tasks 逐步写代码
6. 专项审查（4 项分开执行）
7. `/opsx-verify` — 核对实现与工件是否对齐
8. `/opsx-archive` — 归档 change
9. 沉淀新隐性约定 — 知识沉淀

> 照着操作手册做一遍，就能跑通整套流程。

---


- Java 17+
- Maven 3.6+
- MySQL 5.7+（本地开发库 `studyplan`）
- Redis 6+（默认 `localhost:6379`，作业队列/缓存使用）
- Node.js 18+（`admin-web` 与 `student-app`）
- 推荐使用 IntelliJ IDEA

---


MIT