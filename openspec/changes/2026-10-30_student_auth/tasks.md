# Tasks：学生登录与 JWT 鉴权

> **Change ID**: `2026-10-30_student_auth`  
> **关联 Design**: design.md

---

## Task 1：创建 JWT 配置类与微信配置类

**目标**：读取 application.yml 中的 JWT 和微信配置，并添加 fail-fast 校验。

**涉及文件**：
- `src/main/java/com/jiang/config/JwtConfig.java`
- `src/main/java/com/jiang/config/WechatConfig.java`

**具体任务**：
1. `JwtConfig`：使用 `@ConfigurationProperties(prefix = "jwt")` 绑定配置
2. `JwtConfig` 包含字段：`secret`、`expiration`、`refreshThreshold`
3. `JwtConfig` 添加 `@Data` 注解
4. `JwtConfig.secret` 字段添加 `@ToString.Exclude` 注解（防止日志泄露密钥）
5. `JwtConfig` 添加 `@PostConstruct validate()` 方法，启动时校验密钥长度 >= 32 字节
6. `WechatConfig`：使用 `@ConfigurationProperties(prefix = "wechat")` 绑定配置
7. `WechatConfig` 包含字段：`appid`、`secret`，均添加 `@ToString.Exclude`
8. `WechatConfig` 添加 `@PostConstruct validate()` 方法，校验 `appid` 和 `secret` 非空且非空串

**验收标准**：
- 配置类可正确读取 yml 中的值
- JWT 密钥长度不足 32 字节时启动失败
- `wechat.appid` / `wechat.secret` 空值或空串时启动失败
- `toString()` 不包含 secret 字段

---

## Task 2：创建 RestTemplate 配置

**目标**：配置 RestTemplate 用于调用微信 API。

**涉及文件**：
- `src/main/java/com/jiang/config/RestTemplateConfig.java`

**具体任务**：
1. 创建 `RestTemplate` Bean
2. 设置连接超时 5 秒，读取超时 10 秒

**验收标准**：
- RestTemplate 可正常注入

---

## Task 3：实现 JWT 工具类

**目标**：提供 JWT 签发、解析、校验功能。

**涉及文件**：
- `src/main/java/com/jiang/security/JwtUtil.java`

**具体任务**：
1. `generateToken(openid, studentId, tenantId)` - 签发 JWT
2. `parseToken(token)` - 解析 JWT（过期抛 `ExpiredJwtException`，格式错误抛 `MalformedJwtException` 等）
3. `validateToken(token)` - 校验 JWT 有效性（内部捕获所有 jjwt 异常，返回 false）
4. `shouldRefresh(token)` - 判断是否需要刷新（边界：exp - now <= refreshThreshold && exp - now > 0）
5. `getOpenidFromToken(token)` - 提取 openid
6. `getStudentIdFromToken(token)` - 提取 studentId
7. `getTenantIdFromToken(token)` - 提取 tenantId

**异常契约**：
- `parseToken()` 对过期/无效 token 直接抛出 jjwt 异常，调用方需自行捕获
- `validateToken()` 吞掉所有 jjwt 异常，统一返回 `false`
- `getXxxFromToken()` 内部调用 `parseToken()`，调用前必须先通过 `validateToken()` 确认有效

**验收标准**：
- 单元测试覆盖所有方法
- JWT 格式正确，包含所需字段
- 过期 token：`parseToken()` 抛 `ExpiredJwtException`，`validateToken()` 返回 `false`

---

## Task 4：实现学生上下文（ThreadLocal）

**目标**：在请求线程中存储当前登录学生信息。

**涉及文件**：
- `src/main/java/com/jiang/security/StudentContext.java`

**具体任务**：
1. 使用 ThreadLocal 存储 `studentId`、`openid`、`tenantId`
2. 提供 `set`、`get`、`clear` 静态方法

**验收标准**：
- 可在拦截器中设置，在 Service 中获取

---

## Task 5：实现 JWT 拦截器

**目标**：拦截请求，校验 JWT，注入学生信息。

**涉及文件**：
- `src/main/java/com/jiang/security/JwtInterceptor.java`

**具体任务**：
1. `preHandle()` - 从 Header 获取 JWT，校验有效性
2. 提取学生信息存入 `StudentContext`
3. 判断是否需要刷新（边界：exp - now <= refreshThreshold && exp - now > 0），刷新则通过响应头 `X-Refresh-Token` 返回新 JWT
4. `afterCompletion()` - 清除 ThreadLocal

**验收标准**：
- 未携带 JWT 返回 401
- JWT 过期返回 401
- JWT 即将过期（<5 分钟）通过 `X-Refresh-Token` 响应头返回新 JWT
- 学生信息正确注入 ThreadLocal
- 先校验 JWT 再 set ThreadLocal（顺序约束，防止异常时泄露数据）

---

## Task 6：配置 MVC 拦截器

**目标**：注册 JWT 拦截器，配置拦截路径。

**涉及文件**：
- `src/main/java/com/jiang/config/WebMvcConfig.java`

**具体任务**：
1. 实现 `WebMvcConfigurer`
2. 注册 `JwtInterceptor`
3. 拦截 `/api/**`，仅排除 `/api/auth/login`（注意：`/api/auth/current` 必须经过鉴权）
4. 配置 CORS：`exposedHeaders` 暴露 `X-Refresh-Token`

**验收标准**：
- 登录接口（`/api/auth/login`）不需要 JWT
- `/api/auth/current` 接口必须经过鉴权
- 其他 `/api/**` 接口需要 JWT
- CORS 配置正确暴露 `X-Refresh-Token` 响应头

---

## Task 7：创建 DTO/VO 类与公共转换器

**目标**：定义接口入参、出参及 Entity↔VO 转换器。

**涉及文件**：
- `src/main/java/com/jiang/dto/LoginRequest.java`
- `src/main/java/com/jiang/vo/LoginResponse.java`
- `src/main/java/com/jiang/vo/StudentVO.java`
- `src/main/java/com/jiang/converter/StudentConverter.java`

**具体任务**：
1. `LoginRequest` - 包含 `code` 字段，添加 `@NotBlank` 校验
2. `LoginResponse` - 包含 `token`、`studentInfo`
3. `StudentVO` - 包含 `studentId`、`nickname`、`avatar`、`grade`（**不含 openid**，隐私保护）
4. `StudentConverter.toVO(Student)` - 公共静态方法，供 `StudentLoginTxService` 和 `AuthServiceImpl` 共用

**验收标准**：
- 字段与设计文档一致
- 校验注解生效

---

## Task 8：实现 AuthService 接口

**目标**：定义认证业务逻辑接口。

**涉及文件**：
- `src/main/java/com/jiang/service/AuthService.java`

**具体任务**：
1. `login(code)` - 微信登录
2. `getCurrentStudent()` - 获取当前学生信息

**验收标准**：
- 接口定义清晰

---

## Task 9：实现 AuthServiceImpl

**目标**：实现认证业务逻辑（微信调用 + 委托事务服务）。

**涉及文件**：
- `src/main/java/com/jiang/service/impl/AuthServiceImpl.java`

**具体任务**：
1. `login()` - 调用微信 API 换取 openid（**事务外**），再调用 `StudentLoginTxService.transactionalLogin()`（跨 Bean，走代理）
2. `getCurrentStudent()` - 从 StudentContext 获取 studentId，查询学生。学生不存在时返回 `UNAUTHORIZED`（非 `STUDENT_NOT_FOUND`），提示重新登录
3. `fetchOpenidFromWechat()` - 调用微信 API，**仅对网络/超时异常重试**（`ResourceAccessException`），业务失败（`errcode != 0`）直接抛出 `OPENID_INVALID`，重试间加 200ms 退避
4. 中断处理：`Thread.sleep` 被中断时直接抛出 `BusinessException(INTERNAL_ERROR, "登录被中断")`，不继续重试
5. 注入 `WechatConfig` 获取 appid/secret（而非 `@Value`）

**验收标准**：
- 微信 API 网络失败时重试（业务失败不重试）
- 中断后直接抛出，不继续循环
- 单元测试覆盖核心逻辑

---

## Task 10：实现 StudentLoginTxService

**目标**：将事务性数据库操作拆分到独立 Bean，确保 `@Transactional` 经过 Spring AOP 代理生效。

**涉及文件**：
- `src/main/java/com/jiang/service/StudentLoginTxService.java`

**具体任务**：
1. `transactionalLogin(openid)` - 查询/创建学生，签发 JWT。添加 `@Transactional(rollbackFor = Exception.class)`
2. 并发首登兑底：`insert` 捕获 `DuplicateKeyException` 后回查已有记录
3. 极端窗口说明：回查为 null 时抛出 `INTERNAL_ERROR`（Demo 阶段接受，后续可 upsert 优化）
4. 注意：`DuplicateKeyException` 依赖 Spring 异常翻译，确保 `StudentMapper` 被 `@Repository` 或 MyBatis-Plus 异常转换器覆盖

**验收标准**：
- 首次登录自动创建学生
- 并发首登触发唯一索引冲突时正确回查
- 单元测试覆盖事务逻辑（Mock 环境，不验证 @Transactional 实际生效）

---

## Task 11：实现 AuthController

**目标**：提供登录和获取学生信息接口。

**涉及文件**：
- `src/main/java/com/jiang/controller/AuthController.java`

**具体任务**：
1. `POST /api/auth/login` - 微信登录接口
2. `GET /api/auth/current` - 获取当前学生信息
3. 添加 Swagger 注解（`@Tag`、`@Operation`）

**验收标准**：
- 接口返回统一 `Result<T>` 格式
- Swagger 文档正确显示

---

## Task 12：修改 GlobalExceptionHandler 支持 HTTP 状态码区分

**目标**：当前 `GlobalExceptionHandler` 对所有 `BusinessException` 统一返回 HTTP 200，但鉴权场景需要返回 HTTP 401。需新增 UNAUTHORIZED 专用处理器。

**涉及文件**：
- `src/main/java/com/jiang/common/exception/GlobalExceptionHandler.java`

**具体任务**：
1. 新增 `handleUnauthorizedException()` 方法，处理 `BusinessException` 且 `code == UNAUTHORIZED.getCode()`，返回 HTTP 401
2. 修改原 `handleBusinessException()` 方法，排除 UNAUTHORIZED 场景（避免被通用处理器捕获）
3. 添加 `@ResponseStatus(HttpStatus.UNAUTHORIZED)` 到新方法

**验收标准**：
- `BusinessException(UNAUTHORIZED)` 返回 HTTP 401
- 其他 `BusinessException` 仍返回 HTTP 200
- 现有单元测试不受影响

---

## Task 13：更新 application.yml

**目标**：添加微信小程序配置。

**涉及文件**：
- `src/main/resources/application.yml`（受保护，需人工审批）

**具体任务**：
1. 添加 `wechat.appid`、`wechat.secret` 配置
2. 补充 `jwt.refresh-threshold` 配置

**验收标准**：
- 配置格式正确
- 敏感信息使用环境变量占位

---

## Task 14：编写 JWT 工具类单元测试

**目标**：验证 JWT 签发、解析、校验功能。

**涉及文件**：
- `src/test/java/com/jiang/security/JwtUtilTest.java`

**具体任务**：
1. 测试签发 JWT
2. 测试解析 JWT
3. 测试校验有效/无效 JWT
4. 测试刷新判断逻辑
5. 测试提取字段
6. **测试过期 token：`parseToken()` 抛 `ExpiredJwtException`，`validateToken()` 返回 `false`**
7. **测试格式错误 token：`parseToken()` 抛 `MalformedJwtException`**

**验收标准**：
- 所有测试通过
- 覆盖正常和异常场景

---

## Task 15：编写 AuthService 与 StudentLoginTxService 单元测试

**目标**：验证登录和获取学生信息逻辑，以及事务登录逻辑。

**涉及文件**：
- `src/test/java/com/jiang/service/AuthServiceTest.java`
- `src/test/java/com/jiang/service/StudentLoginTxServiceTest.java`

**具体任务**：
1. `AuthServiceTest`：测试首次登录（自动创建学生）
2. `AuthServiceTest`：测试非首次登录（返回已有学生）
3. `AuthServiceTest`：测试微信 API 失败重试
4. `AuthServiceTest`：测试获取当前学生信息
5. `AuthServiceTest`：Mock 微信 API 和数据库
6. `StudentLoginTxServiceTest`：测试事务登录（Mock 环境，不验证 @Transactional 实际生效）
7. `StudentLoginTxServiceTest`：测试并发首登 DuplicateKeyException 回查

**验收标准**：
- 所有测试通过
- Mock 策略正确

---

## Task 16：编写 AuthController 集成测试

**目标**：验证接口层功能。

**涉及文件**：
- `src/test/java/com/jiang/controller/AuthControllerTest.java`

**具体任务**：
1. 测试登录接口（正常/异常）
2. 测试获取学生信息接口
3. 测试 JWT 校验失败返回 401
4. 测试 `@Valid` 校验失败（`code` 为空）返回 400
5. **测试 `@Transactional` 实际生效**：构造 insert 成功后抛异常场景，验证 DB 无记录（回滚生效）
6. 使用 `@SpringBootTest` 和 `MockMvc`

**验收标准**：
- 所有测试通过
- 接口返回格式正确
- 参数校验失败返回 400

---

## 任务依赖关系

```
Task 1 (JWT 配置 + 微信配置)
    ↓
Task 2 (RestTemplate 配置)
    ↓
Task 3 (JWT 工具类) ──┬──→ Task 5 (JWT 拦截器) ──→ Task 6 (WebMvcConfig)
                   └──→ Task 4 (StudentContext) ──↗
Task 7 (DTO/VO) + Task 11-b (StudentConverter)
    ↓
Task 8 (AuthService 接口)
    ↓
Task 9 (AuthServiceImpl) ←→ Task 10 (StudentLoginTxService)
    ↓
Task 11 (AuthController)
    ↓
Task 12 (GlobalExceptionHandler 修改)
    ↓
Task 13 (application.yml)
    ↓
Task 14-16 (单元测试 + 集成测试)
```

---

## 预估工时

| Task | 预估时间 |
|------|----------|
| Task 1-2 | 0.5 小时 |
| Task 3-6 | 2 小时 |
| Task 7-8 | 1 小时 |
| Task 9-10 | 1.5 小时 |
| Task 11-13 | 1 小时 |
| Task 14-16 | 2 小时 |
| **总计** | **8 小时** |

---

## 验收标准汇总

### 功能验收
- [ ] 微信登录接口可正常调用（Mock 微信 API）
- [ ] JWT 签发正确，包含 `openid`、`student_id`、`tenant_id`
- [ ] JWT 拦截器可正确校验令牌
- [ ] JWT 过期前 5 分钟内通过 `X-Refresh-Token` 响应头返回新 JWT
- [ ] JWT 过期后返回 401
- [ ] 首次登录自动创建学生记录
- [ ] 并发首登触发唯一索引冲突时正确回查，不创建重复记录
- [ ] 获取当前学生信息接口（`/api/auth/current`）必须经过鉴权
- [ ] `StudentVO` 不包含 `openid` 字段（隐私保护）
- [ ] 微信 API 业务失败不重试，网络/超时失败重试（最多 2 次）
- [ ] JWT 密钥长度不足 32 字节时启动失败（fail-fast）
- [ ] `wechat.appid` / `wechat.secret` 空值或空串时启动失败（fail-fast）
- [ ] 微信 API 调用在事务外执行，事务内仅包含 DB 操作
- [ ] `@Transactional` 实际生效（跨 Bean 调用，经过 Spring AOP 代理）
- [ ] 过期 token：`parseToken()` 抛 `ExpiredJwtException`，`validateToken()` 返回 `false`
- [ ] 中断后 `fetchOpenidFromWechat` 直接抛出，不继续重试
- [ ] CORS 配置为 Demo 阶段放宽，上线前需收敛域名

### 测试验收
- [ ] 单元测试覆盖 JWT 工具类核心方法（含过期 token 异常契约）
- [ ] 单元测试覆盖 AuthService 核心逻辑
- [ ] 单元测试覆盖 StudentLoginTxService 事务逻辑
- [ ] 集成测试覆盖 AuthController 接口
- [ ] 集成测试覆盖 `@Valid` 校验失败返回 400 场景
- [ ] 集成测试验证 `@Transactional` 实际生效（回滚行为）
- [ ] 所有测试通过

### 编译验收
- [ ] `mvn compile` 成功
- [ ] `mvn test` 成功
- [ ] 无编译警告
