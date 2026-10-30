# AGENTS.md — AI 导航地图

> 本文件是 AI 进入仓库后的**第一份导航文件**，告诉 AI "先看什么、按什么流程做"。
> 项目知识、业务规则、隐性约定等详细内容统一放在 `docs/` 目录下，本文件只做导航。
> 本文件不承载业务规则；与 `docs/` 冲突时，以 `docs/product/index.md` 等专项文档为准，并回头修正本文件。

---

## 1. 仓库概述

- **项目名称**：智学伴（StudyMate）— AI 自适应学习辅助小程序
- **项目定位**：Harness 工程实践示范项目（面向初中、高中学生的 AI 自适应学习辅助小程序，含学生端小程序，跑通“设定目标 → Agent 规划每日计划 → 完成每日作业 → 知识点评测 → 考试试卷分析 → Agent 动态调整计划”的完整学习闭环）
- **后端技术栈**：
    - 语言：Java 17
    - 框架：Spring Boot 3.2.x
    - 构建工具：Maven
    - ORM：MyBatis-Plus
    - 鉴权：JWT (JSON Web Token)
    - 接口文档：Knife4j / Swagger
- **Agent 技术栈**：AgentScope Java（嵌入 Spring Boot）
- **前端技术栈**：
    - **学生端小程序**：uni-app (Vue 3 语法，编译至微信小程序)
    - **管理后端 PC 端**：Vue 3 + Element Plus + Vite
- **数据存储**：
    - 数据库：MySQL 5.7+
- **工作流**：OpenSpec 变更生命周期管理
- **AI 平台**：QoderCn

---

## 2. AI 进入仓库后的必读顺序

按顺序阅读，前一份未读完不要跳到下一份：

1. **本文件（AGENTS.md）** — 了解工作流和入口
2. **`docs/product/index.md`** — 产品规则、业务逻辑边界、数据表清单（**业务判断的唯一依据**）
3. **`docs/architecture/implicit-contracts.md`** — 隐性业务约定（**关键，先于架构总览阅读**）
4. **`docs/architecture/index.md`** — 项目整体架构
5. **`docs/standards/testing.md`** — 测试规范
6. **`docs/standards/database.md`** — 数据库与 SQL 规范
7. **`REVIEW.md`** — 评审标准

> **兜底规则**：上述任一文件不存在时，**停止编码**，在回复中明确列出缺失文件并请求人工补充；不得凭猜测继续。

---

## 3. 核心工作流：OpenSpec 变更生命周期

**铁律：没有 change，不允许直接开始开发。**

```
/opsx:propose → /opsx:apply → /opsx:verify → /opsx:archive
```

> 命令前缀以仓库实际安装的 OpenSpec 插件为准（如非 `opsx`，以 `openspec/` 目录下的配置为准）；上述代码块中的 `opsx` 仅为示例。

| 阶段 | 职责 | 产出物 |
|------|------|--------|
| **propose** | 需求拆解，生成变更工件 | `proposal.md`、`design.md`、`tasks.md` |
| **apply** | 按已确认方案实施代码变更 | 代码改动 |
| **verify** | 核对实现与工件是否对齐 | 验证报告 |
| **archive** | 归档已完成的 change | 移动到 `openspec/changes/archive/` |

### 关键规则

- `openspec/changes/` 下每个子目录对应一个进行中的 change
- 第一版 proposal 往往只是草案，**人工审查不能省**，必要时果断废弃重来
- apply 阶段**只做 tasks.md 范围内的事**，不允许自行扩展
- verify 只检查"实现与工件是否对齐"，**不是代码评审**
- propose 阶段必须先读 `docs/product/index.md` 确认需求边界（对应其维护规则第 2 条），越界需求直接打回

---

## 4. 实现、评审、验证 — 必须分离

以下职责**严禁混在一起**，必须分开执行：

| 职责 | 执行方式 | 说明 |
|------|----------|------|
| OpenSpec 对齐校验 | `/opsx:verify` | 只检查实现与 change 工件是否一致 |
| PR 评审摘要 | `/prepare-review` | 整理本次变更内容，方便人工评审 |
| 架构分层检查 | `/java-architecture-review` | 检查 Java 分层架构是否规范 |
| SQL 风险审查 | `/sql-risk-review` | 检查 SQL、批量操作等数据层风险（含 `tenant_id` 默认值、索引覆盖） |
| 独立代码审查 | `@reviewer` 子代理 | 只读、独立的代码质量审查 |

> 同一轮变更中，**实施者不得同时担任上述任一评审角色**。

---

## 5. 受保护区域

以下目录/文件属于**高风险区域**，AI 不得直接修改：

- `src/main/resources/application*.yml` / `application*.properties` — 配置文件
- `src/main/resources/db/` / `sql/` — 数据库脚本
- `src/main/resources/json/` — 初始数据文件（题库、知识点、先修关系、课程标准 JSON 文件）
- `src/main/resources/prompts/` — Prompt 与 JSON Schema（对应产品规则 #6，变更需同步版本）
- `deploy/` / `infra/` — 部署相关文件
- `secrets/` — 密钥文件
- `openspec/changes/archive/` — 已归档的 change（只读）
- `docs/product/index.md` — 业务规则源文件（变更须走维护规则，不得由 AI 顺手改）
- `docs/architecture/index.md`、`docs/architecture/implicit-contracts.md` — 架构文档与隐性约定（AI 可在 change 中同步更新，但不得在无 change 时自行修改）
- `admin-web/` 为普通业务代码，按 change 流程正常修改；其构建产物部署配置参照 `deploy/` 受保护规则

如需修改上述文件，必须通过 change 流程并经人工审批。

---

## 6. 安全命令白名单

**允许执行**：

- `mvn compile`、`mvn test`、`mvn package -DskipTests`
- `git status`、`git diff`、`git log`、`git branch`（只读类）

**禁止执行**（除非人工在当轮对话中明确授权）：

- `git push`、`git reset --hard`、`git rebase`
- `rm -rf`、`drop`、`truncate` 等破坏性命令
- 任何生产部署相关命令
- 对 `secrets/` 的任何读写

> 白名单之外的新命令，先询问人工，不得自行尝试。

---

## 7. 目录结构速览

```
repo/
├─ AGENTS.md                  ← 你正在看的文件
├─ REVIEW.md                  ← 评审标准
├─ admin-web/                 ← 管理后端 PC 端前端（Vue 3 + Element Plus + Vite）
├─ docs/                      ← 项目知识库
│  ├─ architecture/           ← 架构知识 & 隐性约定
│  ├─ product/                ← 产品规则（index.md 为业务唯一依据）
│  ├─ standards/              ← 测试 & 数据库规范
│  └─ changelog/              ← 公式/规范变更记录（如 mastery-formula.md）
├─ openspec/                  ← OpenSpec 变更管理
│  ├─ changes/                ← 进行中的 change
│  └─ specs/                  ← 当前系统工作原理
├─ src/                       ← 后端源代码
│  ├─ main/java/com/jiang/    ← 主代码目录（包根以 pom.xml 为准，当前为 com.jiang）
│  └─ main/resources/
│     ├─ db/                ← 数据库脚本（受保护）
│     ├─ json/                ← 初始数据文件（题库、知识点、先修关系、课程标准）
│     └─ prompts/             ← Prompt 版本与 Schema（受保护）
└─ pom.xml                    ← Maven 配置
```

---

## 8. 核心原则（十六字诀）

> **需求先工件化，知识先显性化，执行先加护栏，评审与验证必须分离。**

---

## 9. 本文件自身的维护规则

1. `docs/` 目录结构、必读顺序、受保护区域、命令白名单发生变化时，必须同步更新本文件。
2. 本文件不记录业务规则细节；发现业务规则变化，改 `docs/product/index.md`，本文件只改导航指向。
3. 本文件与 `docs/` 冲突时，以 `docs/` 专项文档为准，并在当轮变更中修正本文件。