# Design：学生登录与 JWT 鉴权

> **Change ID**: `2026-10-30_student_auth`  
> **关联 Proposal**: proposal.md

---

## 1. 技术方案

### 1.1 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| jjwt | 0.12.5 | JWT 实现库（已在 pom.xml 中） |
| Spring Boot | 3.2.5 | Web 框架 |
| MyBatis-Plus | 3.5.6 | ORM 框架 |
| MySQL | 5.7+ | 数据库 |
| RestTemplate | — | 调用微信 API |

### 1.2 包结构

```
com.jiang
├── controller
│   └── AuthController          # 登录接口
├── service
│   ├── AuthService             # 登录业务逻辑
│   └── impl
│       └── AuthServiceImpl
├── config
│   ├── JwtConfig               # JWT 配置类
│   └── WebMvcConfig            # MVC 配置（注册拦截器）
├── security
│   ├── JwtUtil                 # JWT 工具类
│   ├── JwtInterceptor          # JWT 拦截器
│   └── StudentContext          # 学生上下文（ThreadLocal）
├── dto
│   └── LoginRequest            # 登录请求 DTO
└── vo
    ├── LoginResponse           # 登录响应 VO
    └── StudentVO               # 学生信息 VO
```

---

## 2. 核心流程设计

### 2.1 微信小程序登录流程

```
学生端小程序                    后端服务                      微信服务器
    |                            |                            |
    |-- wx.login() ------------->|                            |
    |   (获取 code)              |                            |
    |                            |                            |
    |-- POST /api/auth/login --->|                            |
    |   { code: "xxx" }          |                            |
    |                            |-- GET /sns/jscode2session ->|
    |                            |   (appid, secret, code)    |
    |                            |                            |
    |                            |<-- { openid, session_key } -|
    |                            |                            |
    |                            |-- 查询/创建学生记录         |
    |                            |-- 签发 JWT                 |
    |                            |                            |
    |<-- { token, studentInfo } -|                            |
    |                            |                            |
```

### 2.2 JWT 刷新流程

```
学生端小程序                    后端服务
    |                            |
    |-- 请求接口 --------------->|
    |   (携带 JWT)               |
    |                            |-- 校验 JWT 有效性
    |                            |
    |                            |-- [JWT 过期前 5 分钟内]
    |                            |   |-- 签发新 JWT
    |<-- 响应 + Header: --------|   |-- 通过响应头返回
    |   X-Refresh-Token: new_jwt |
    |                            |
    |                            |-- [JWT 已过期]
    |<-- 401 Unauthorized -------|   |-- 返回 401
    |                            |
    |-- 重新 wx.login() -------->|
    |-- 重放原请求 ------------->|
    |                            |
```

---

## 3. 详细设计

### 3.1 JWT 配置类

```java
@Configuration
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtConfig {
    @ToString.Exclude  // 防止日志泄露密钥
    private String secret;           // JWT 密钥（从 application.yml 读取，必须 >= 32 字节）
    private Long expiration;         // 过期时间（毫秒），默认 7200000（2 小时）
    private Long refreshThreshold;   // 刷新阈值（毫秒），默认 300000（5 分钟）
    
    /**
     * 启动时校验密钥长度
     */
    @PostConstruct
    public void validate() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT 密钥长度必须 >= 32 字节（256 位）");
        }
    }
}
```

**安全说明**：
- 签名算法：HS256
- 密钥构造：`Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))`
- application.yml 中不设默认值，使用 `${JWT_SECRET}` 占位符，启动时缺失即失败（fail-fast）

### 3.2 JWT 工具类

```java
@Component
public class JwtUtil {
    
    @Autowired
    private JwtConfig jwtConfig;
    
    /**
     * 签发 JWT
     * @param openid 微信 openid
     * @param studentId 学生 ID
     * @param tenantId 租户 ID
     * @return JWT 字符串
     */
    public String generateToken(String openid, Long studentId, String tenantId);
    
    /**
     * 解析 JWT
     * @param token JWT 字符串
     * @return Claims 对象
     */
    public Claims parseToken(String token);
    
    /**
     * 校验 JWT 是否有效
     * @param token JWT 字符串
     * @return true=有效，false=无效或过期
     */
    public boolean validateToken(String token);
    
    /**
     * 判断 JWT 是否需要刷新
     * 边界条件：exp - now <= refreshThreshold && exp - now > 0
     * @param token JWT 字符串
     * @return true=需要刷新（过期前 5 分钟内，且尚未过期）
     */
    public boolean shouldRefresh(String token);
    
    /**
     * 从 JWT 中提取 openid
     */
    public String getOpenidFromToken(String token);
    
    /**
     * 从 JWT 中提取 studentId
     */
    public Long getStudentIdFromToken(String token);
    
    /**
     * 从 JWT 中提取 tenantId
     */
    public String getTenantIdFromToken(String token);
}
```

**JWT Payload 结构**：
```json
{
  "sub": "studentId",           // 主题：学生 ID
  "openid": "wx_openid",        // 微信 openid
  "tenantId": "default",        // 租户 ID
  "iat": 1698652800,            // 签发时间
  "exp": 1698660000             // 过期时间
}
```

### 3.3 JWT 拦截器

```java
@Component
public class JwtInterceptor implements HandlerInterceptor {
    
    @Autowired
    private JwtUtil jwtUtil;
    
    @Autowired
    private JwtConfig jwtConfig;
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                            HttpServletResponse response, 
                            Object handler) {
        // 1. 从 Header 中获取 JWT
        String token = request.getHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED);
        }
        
        // 2. 去除 "Bearer " 前缀
        token = token.substring(7);
        
        // 3. 校验 JWT 有效性
        if (!jwtUtil.validateToken(token)) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "JWT 已过期");
        }
        
        // 4. 提取学生信息并存入 ThreadLocal
        Long studentId = jwtUtil.getStudentIdFromToken(token);
        String openid = jwtUtil.getOpenidFromToken(token);
        String tenantId = jwtUtil.getTenantIdFromToken(token);
        
        StudentContext.setStudentId(studentId);
        StudentContext.setOpenid(openid);
        StudentContext.setTenantId(tenantId);
        
        // 5. 判断是否需要刷新（边界：exp - now <= refreshThreshold && exp - now > 0）
        if (jwtUtil.shouldRefresh(token)) {
            String newToken = jwtUtil.generateToken(openid, studentId, tenantId);
            response.setHeader("X-Refresh-Token", newToken);  // 使用专用响应头
        }
        
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, 
                               HttpServletResponse response, 
                               Object handler, 
                               Exception ex) {
        // 清除 ThreadLocal，避免内存泄漏
        // 注意：preHandle 抛异常时不会调用此方法，但 StudentContext 使用 ThreadLocal.set() 覆盖写入，
        // 未 set 的字段 get() 返回 null，不会泄露其他请求的数据
        StudentContext.clear();
    }
}
```

**拦截器异常处理说明**：
- `preHandle` 中先校验 JWT，校验通过后才 `set` ThreadLocal
- 若校验失败抛异常，ThreadLocal 未被设置，无需清理
- `afterCompletion` 仅在 `preHandle` 成功返回 `true` 后才会被调用
- `GlobalExceptionHandler` 不依赖 `StudentContext`，可正常处理拦截器抛出的异常

### 3.4 学生上下文（ThreadLocal）

```java
public class StudentContext {
    private static final ThreadLocal<Long> STUDENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> OPENID = new ThreadLocal<>();
    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    
    public static void setStudentId(Long studentId) {
        STUDENT_ID.set(studentId);
    }
    
    public static Long getStudentId() {
        return STUDENT_ID.get();
    }
    
    public static void setOpenid(String openid) {
        OPENID.set(openid);
    }
    
    public static String getOpenid() {
        return OPENID.get();
    }
    
    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }
    
    public static String getTenantId() {
        return TENANT_ID.get();
    }
    
    public static void clear() {
        STUDENT_ID.remove();
        OPENID.remove();
        TENANT_ID.remove();
    }
}
```

### 3.5 MVC 配置

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    
    @Autowired
    private JwtInterceptor jwtInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")              // 拦截所有 /api/** 接口
                .excludePathPatterns("/api/auth/login"); // 仅排除登录接口
    }
    
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Refresh-Token")  // 暴露刷新令牌响应头
                .allowCredentials(true)
                .maxAge(3600);
    }
}
```

**前端契约**：
- 前端收到 `X-Refresh-Token` 响应头时，无条件覆盖本地存储的 JWT
- CORS 配置中 `exposedHeaders` 暴露 `X-Refresh-Token`，确保浏览器/小程序可读取

### 3.6 登录请求 DTO

```java
@Data
public class LoginRequest {
    @NotBlank(message = "code 不能为空")
    private String code;  // 微信登录凭证
}
```

### 3.7 登录响应 VO

```java
@Data
public class LoginResponse {
    private String token;           // JWT
    private StudentVO studentInfo;  // 学生信息
}
```

### 3.8 学生信息 VO

```java
@Data
public class StudentVO {
    private Long studentId;
    // openid 不下发前端（隐私保护），前端仅需 JWT 即可
    private String nickname;
    private String avatar;
    private String grade;
}
```

### 3.9 AuthService 接口

```java
public interface AuthService {
    /**
     * 微信小程序登录
     * @param code 微信登录凭证
     * @return 登录响应（含 JWT 和学生信息）
     */
    LoginResponse login(String code);
    
    /**
     * 获取当前登录学生信息
     * @return 学生信息 VO
     */
    StudentVO getCurrentStudent();
}
```

### 3.10 AuthServiceImpl 实现

```java
@Service
@Slf4j
public class AuthServiceImpl implements AuthService {
    
    @Autowired
    private StudentMapper studentMapper;
    
    @Autowired
    private JwtUtil jwtUtil;
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Value("${wechat.appid}")
    private String appid;
    
    @Value("${wechat.secret}")
    private String secret;
    
    @Override
    public LoginResponse login(String code) {
        // 1. 调用微信 API 换取 openid（事务外，避免长事务占用 DB 连接）
        String openid = fetchOpenidFromWechat(code);
        
        // 2. 事务内执行数据库操作
        return transactionalLogin(openid);
    }
    
    /**
     * 事务内登录逻辑（仅包含 DB 操作）
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse transactionalLogin(String openid) {
        // 查询或创建学生记录
        Student student = studentMapper.selectOne(
            new LambdaQueryWrapper<Student>()
                .eq(Student::getOpenid, openid)
                .eq(Student::getTenantId, "default")
        );
        
        if (student == null) {
            try {
                // 首次登录，自动创建学生记录
                student = new Student();
                student.setOpenid(openid);
                student.setTenantId("default");
                student.setCreateTime(LocalDateTime.now());
                student.setUpdateTime(LocalDateTime.now());
                studentMapper.insert(student);
            } catch (DuplicateKeyException e) {
                // 并发首登兑底：唯一索引冲突，回查已有记录
                log.warn("并发首登触发唯一索引冲突，回查学生记录: openid={}", openid);
                student = studentMapper.selectOne(
                    new LambdaQueryWrapper<Student>()
                        .eq(Student::getOpenid, openid)
                        .eq(Student::getTenantId, "default")
                );
                if (student == null) {
                    throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "登录失败，请重试");
                }
            }
        }
        
        // 签发 JWT
        String token = jwtUtil.generateToken(
            openid, 
            student.getStudentId(), 
            "default"
        );
        
        // 构造响应
        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setStudentInfo(convertToVO(student));
        
        return response;
    }
    
    @Override
    public StudentVO getCurrentStudent() {
        Long studentId = StudentContext.getStudentId();
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            // JWT 有效但学生不存在，属于数据一致性问题，返回 401 提示重新登录
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "学生数据异常，请重新登录");
        }
        return convertToVO(student);
    }
    
    /**
     * 调用微信 API 换取 openid
     * 重试策略：仅对网络/超时异常重试，业务失败（errcode != 0）直接抛出
     */
    private String fetchOpenidFromWechat(String code) {
        String url = String.format(
            "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
            appid, secret, code
        );
        
        int maxRetries = 2;
        long retryBackoffMs = 200;  // 重试退避时间
        
        for (int i = 0; i < maxRetries; i++) {
            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                Map body = response.getBody();
                
                if (body == null) {
                    throw new BusinessException(ErrorCodes.OPENID_INVALID, "微信 API 返回为空");
                }
                
                // 微信业务错误（errcode 存在且不为 0）
                if (body.containsKey("errcode") && !"0".equals(String.valueOf(body.get("errcode")))) {
                    log.warn("微信 API 业务错误: errcode={}, errmsg={}", body.get("errcode"), body.get("errmsg"));
                    throw new BusinessException(ErrorCodes.OPENID_INVALID, "微信登录失败: " + body.get("errmsg"));
                }
                
                // 成功返回 openid
                if (body.containsKey("openid")) {
                    return (String) body.get("openid");
                }
                
                // 无 openid 也无 errcode，异常情况
                throw new BusinessException(ErrorCodes.OPENID_INVALID, "微信 API 响应格式异常");
                
            } catch (ResourceAccessException e) {
                // 网络/超时异常，可重试
                log.warn("微信 API 网络异常，重试中 ({}/{}): {}", i + 1, maxRetries, e.getMessage());
                if (i == maxRetries - 1) {
                    log.error("调用微信 API 网络失败，已达最大重试次数", e);
                    throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "微信登录失败，请稍后重试");
                }
                // 重试退避
                try {
                    Thread.sleep(retryBackoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (BusinessException e) {
                // 业务异常不重试，直接抛出
                throw e;
            } catch (Exception e) {
                // 其他未知异常不重试
                log.error("调用微信 API 未知异常", e);
                throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "微信登录失败");
            }
        }
        
        throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "微信登录失败");
    }
    
    /**
     * Entity 转 VO（openid 不下发前端）
     */
    private StudentVO convertToVO(Student student) {
        StudentVO vo = new StudentVO();
        vo.setStudentId(student.getStudentId());
        vo.setNickname(student.getNickname());
        vo.setAvatar(student.getAvatar());
        vo.setGrade(student.getGrade());
        return vo;
    }
}
```

### 3.11 AuthController

```java
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证管理", description = "学生登录与鉴权接口")
public class AuthController {
    
    @Autowired
    private AuthService authService;
    
    @PostMapping("/login")
    @Operation(summary = "微信小程序登录")
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        LoginResponse response = authService.login(request.getCode());
        return Result.success(response);
    }
    
    @GetMapping("/current")
    @Operation(summary = "获取当前登录学生信息")
    public Result<StudentVO> getCurrentStudent() {
        StudentVO student = authService.getCurrentStudent();
        return Result.success(student);
    }
}
```

---

## 4. 配置项设计

### 4.1 application.yml 新增配置

```yaml
# 微信小程序配置
wechat:
  appid: ${WECHAT_APPID}
  secret: ${WECHAT_SECRET}

# JWT 配置（已存在，补充 refreshThreshold）
jwt:
  secret: ${JWT_SECRET}  # 不设默认值，启动时缺失即失败（fail-fast）
  expiration: 7200000  # 2小时（毫秒）
  refresh-threshold: 300000  # 5分钟（毫秒）
```

**安全说明**：
- `jwt.secret` 不设默认值，必须在环境变量或配置中心中配置
- 启动时 `JwtConfig.validate()` 校验密钥长度 >= 32 字节，不满足直接抛异常

### 4.2 RestTemplate 配置

```java
@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }
}
```

---

## 5. 异常处理设计

| 异常场景 | 错误码 | HTTP 状态码 | 提示信息 |
|---------|--------|------------|----------|
| code 为空 | 400 | 400 | "code 不能为空" |
| 微信 API 网络失败 | 500 | 500 | "微信登录失败，请稍后重试" |
| 微信 API 业务错误 | 1002 | 401 | "微信登录失败: {errmsg}" |
| JWT 缺失 | 401 | 401 | "未登录或登录已过期" |
| JWT 已过期 | 401 | 401 | "JWT 已过期" |
| 学生数据异常 | 401 | 401 | "学生数据异常，请重新登录" |

**异常→Result→HTTP 状态码映射规则**：
- `BusinessException` → `Result.error(code, message)` + HTTP 200（业务异常）
- `MethodArgumentNotValidException` → `Result.error(400, message)` + HTTP 400
- `BusinessException(UNAUTHORIZED)` → `Result.error(401, message)` + HTTP 401
- `Exception` → `Result.error(500, "系统异常")` + HTTP 500
- 拦截器抛出的 `BusinessException` 由 `GlobalExceptionHandler` 统一处理，HTTP 状态码通过 `@ResponseStatus` 设置

---

## 6. 隐性约定对齐

| 约定 | 本 change 实现方式 |
|------|-------------------|
| 约定 #1：统一响应结构 | 所有接口返回 `Result<T>` |
| 约定 #2：异常处理规范 | 登录失败抛出 `BusinessException` |
| 约定 #7：tenant_id 注入 | JWT 中包含 `tenant_id`，拦截器注入到 `StudentContext` |
| 约定 #9：@Transactional rollbackFor | `transactionalLogin()` 方法添加 `@Transactional(rollbackFor = Exception.class)`，微信 API 调用在事务外执行 |

---

## 7. 数据库变更

### 7.1 student 表唯一索引（并发首登兑底）

> 对应 M2 问题修复：并发首次登录可能创建重复学生记录。

```sql
-- 为 student 表添加唯一索引（若 foundation change 未创建）
-- 注意：此操作属于受保护目录，需人工审批
ALTER TABLE `student` 
    ADD UNIQUE INDEX `uk_openid_tenant` (`openid`, `tenant_id`);
```

**说明**：
- 唯一索引保证同一租户下 openid 唯一
- 并发首登时，第二个请求触发 `DuplicateKeyException`，回查已有记录即可
- 该索引已在 foundation change 的 DDL 中定义为 `uk_openid(openid, tenant_id)`，此处确认其存在即可

---

## 8. 文件清单

| 文件路径 | 说明 |
|---------|------|
| `src/main/java/com/jiang/config/JwtConfig.java` | JWT 配置类 |
| `src/main/java/com/jiang/config/WebMvcConfig.java` | MVC 配置（注册拦截器） |
| `src/main/java/com/jiang/config/RestTemplateConfig.java` | RestTemplate 配置 |
| `src/main/java/com/jiang/security/JwtUtil.java` | JWT 工具类 |
| `src/main/java/com/jiang/security/JwtInterceptor.java` | JWT 拦截器 |
| `src/main/java/com/jiang/security/StudentContext.java` | 学生上下文（ThreadLocal） |
| `src/main/java/com/jiang/dto/LoginRequest.java` | 登录请求 DTO |
| `src/main/java/com/jiang/vo/LoginResponse.java` | 登录响应 VO |
| `src/main/java/com/jiang/vo/StudentVO.java` | 学生信息 VO |
| `src/main/java/com/jiang/service/AuthService.java` | 认证 Service 接口 |
| `src/main/java/com/jiang/service/impl/AuthServiceImpl.java` | 认证 Service 实现 |
| `src/main/java/com/jiang/controller/AuthController.java` | 认证 Controller |
| `src/main/java/com/jiang/common/exception/GlobalExceptionHandler.java` | 新增 UNAUTHORIZED 专用处理器（修改现有文件） |
| `src/main/resources/application.yml` | 新增微信配置（受保护，需人工审批） |
| `src/test/java/com/jiang/security/JwtUtilTest.java` | JWT 工具类单元测试 |
| `src/test/java/com/jiang/service/AuthServiceTest.java` | AuthService 单元测试 |
| `src/test/java/com/jiang/controller/AuthControllerTest.java` | AuthController 集成测试 |

---

## 9. 测试策略

### 9.1 单元测试

| 测试类 | 测试对象 | 测试场景 |
|--------|---------|----------|
| `JwtUtilTest` | JWT 工具类 | 签发、解析、校验、刷新判断 |
| `AuthServiceTest` | AuthService | 登录（首次/非首次）、获取当前学生 |

### 9.2 集成测试

| 测试类 | 测试对象 | 测试场景 |
|--------|---------|----------|
| `AuthControllerTest` | AuthController | 登录接口、获取学生信息接口、JWT 校验失败 |

### 9.3 Mock 策略

- **微信 API**：使用 `MockRestServiceServer` Mock 微信接口返回
- **数据库**：使用 `@MybatisPlusTest` 或 H2 内存数据库

---

## 10. 验收标准

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
- [ ] 单元测试覆盖核心方法
- [ ] 集成测试覆盖接口层（含 `@Valid` 校验失败返回 400）
- [ ] 编译通过，无警告
