# Proposal：基础框架搭建

> **Change ID**: `2026-10-30_backend_foundation`  
> **创建日期**: 2026-10-30  
> **状态**: 已批准（2026-10-30 人工审查通过）

---

## 1. 需求背景

智学伴（StudyMate）项目需要从零搭建后端服务框架。当前项目仅包含基础 Maven 配置，无任何 Java 代码实现。本 change 负责搭建完整的 Spring Boot 基础框架，为后续功能模块开发提供基础设施。

---

## 2. 核心目标

1. **建立 Spring Boot 3.2.x 项目框架**：配置 Maven 依赖、应用启动类、配置文件结构
2. **实现统一的分层架构**：Controller / Service / DAO / Model / DTO / VO / Config 各层职责清晰
3. **提供统一的基础设施**：
   - 统一响应结构 `Result<T>`
   - 全局异常处理 `GlobalExceptionHandler`
   - 分页支持 `PageResult<T>`
   - MyBatis-Plus 自动填充（createTime / updateTime / tenant_id）
4. **完成数据库表结构设计**：根据产品规则设计所有数据表的 DDL 脚本

---

## 3. 功能范围

### 包含（In Scope）

| 模块 | 说明 |
|------|------|
| Maven 依赖管理 | Spring Boot 3.2.x、MyBatis-Plus、MySQL、JWT (jjwt)、Knife4j、Lombok、JUnit 5、Mockito |
| Spring Boot 配置 | application.yml 基础配置（数据源、MyBatis-Plus、JWT 等） |
| 分层结构搭建 | 各层包结构、基础类定义 |
| 统一响应结构 | `Result<T>`、`PageResult<T>` |
| 全局异常处理 | `BusinessException`、`ErrorCodes`、`GlobalExceptionHandler` |
| MyBatis-Plus 配置 | 自动填充处理器、分页插件、租户插件 |
| 数据库 DDL 脚本 | 所有数据表的建表语句（含索引、外键说明） |
| 基础实体类 | 所有数据表对应的 Entity 类 |

### 不包含（Out of Scope）

| 模块 | 说明 |
|------|------|
| 业务 Service 实现 | 各功能模块的业务逻辑（后续 change 实现） |
| Controller 接口 | 具体业务接口（后续 change 实现） |
| Agent 层实现 | AI Agent 相关代码（后续 change 实现） |
| 前端代码 | 管理后端前端、学生端小程序（不在本 change 范围） |

---

## 4. 隐性约定检查

根据 `docs/architecture/implicit-contracts.md`，本 change 需满足：

| 约定 | 实现方式 |
|------|----------|
| 约定 #1：统一响应结构 | 创建 `Result<T>` 类，包含 code / message / data |
| 约定 #2：异常处理规范 | 创建 `BusinessException`、`ErrorCodes` 枚举、`GlobalExceptionHandler` |
| 约定 #3：分页参数规范 | 创建 `PageResult<T>`，分页参数 page 从 1 开始 |
| 约定 #4：时间格式规范 | Entity 使用 `LocalDateTime`，MyBatis-Plus 自动填充 |
| 约定 #9：@Transactional rollbackFor | 在文档中明确规范，后续 Service 实现时遵循 |

---

## 5. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| MySQL 5.7 不支持 JSONB | 低 | JSON 字段使用 `json` 类型，不建函数索引，不用于查询条件 |
| MySQL 5.7 不支持 CHECK 约束 | 低 | 数值范围校验在应用层实现 |
| 多租户预留字段 | 低 | 所有表包含 `tenant_id`，Demo 阶段固定为 `'default'` |
| 依赖版本冲突 | 中 | Spring Boot BOM 管理核心依赖；MyBatis-Plus、Knife4j 等非 BOM 依赖显式锁版本，apply 阶段执行 `mvn dependency:tree` 核验冲突 |

---

## 6. 验收标准

- [ ] 项目可正常启动（`mvn spring-boot:run`）
- [ ] 数据库连接正常，DDL 脚本可执行
- [ ] 统一响应结构 `Result<T>` 已定义
- [ ] 全局异常处理器已配置
- [ ] MyBatis-Plus 自动填充已生效
- [ ] 所有 Entity 类已创建，字段与 DDL 对应
- [ ] Knife4j 接口文档可访问（`/doc.html`）

---

## 7. 参考文档

- `docs/product/index.md` — 产品规则
- `docs/architecture/index.md` — 项目架构总览
- `docs/architecture/implicit-contracts.md` — 隐性业务约定
- `docs/standards/database.md` — 数据库与 SQL 规范
