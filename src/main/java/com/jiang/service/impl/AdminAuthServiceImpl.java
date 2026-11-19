package com.jiang.service.impl;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.handler.TenantHandler;
import com.jiang.mapper.AdminUserMapper;
import com.jiang.model.entity.AdminUser;
import com.jiang.security.JwtUtil;
import com.jiang.service.AdminAuthService;
import com.jiang.vo.AdminInfoVO;
import com.jiang.vo.AdminLoginResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 管理员认证 Service 实现（账号落库 + PBKDF2 密码哈希）
 *
 * <p>上线加固要点：</p>
 * <ul>
 *   <li><b>不再硬编码任何账号/密码</b>：登录走 {@code admin_user} 表（V17 迁移）</li>
 *   <li>密码只存 PBKDF2WithHmacSHA256 哈希（随机盐，格式
 *       {@code pbkdf2$<iterations>$<saltBase64>$<hashBase64>}），校验用常量时间比较</li>
 *   <li>首个管理员由环境变量 {@code ADMIN_BOOTSTRAP_USERNAME}（默认 {@code admin}）+
 *       {@code ADMIN_BOOTSTRAP_PASSWORD} 初始化；<b>未设置密码则不创建</b>并 WARN 提示</li>
 *   <li>登录失败统一 {@link ErrorCodes#UNAUTHORIZED}，不区分「用户不存在 / 密码错误」（避免账号枚举）；
 *       日志只记 username/状态，<b>不打印密码与哈希</b></li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-05
 */
@Slf4j
@Service
public class AdminAuthServiceImpl implements AdminAuthService {

    /** PBKDF2 算法（JDK 内置，无需新增依赖） */
    static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 哈希格式前缀 */
    static final String HASH_PREFIX = "pbkdf2";

    /** 迭代次数（OWASP 对 PBKDF2-HMAC-SHA256 的建议量级） */
    static final int PBKDF2_ITERATIONS = 210_000;

    /** 随机盐长度（字节） */
    static final int SALT_BYTES = 16;

    /** 派生密钥长度（bit） */
    static final int KEY_BITS = 256;

    /** 管理员角色（与 {@link JwtUtil#ROLE_ADMIN} 一致） */
    static final String ADMIN_ROLE = "admin";

    /** 账号状态：启用 */
    static final String STATUS_ACTIVE = "active";

    /** 默认首个管理员登录名（仅当 ADMIN_BOOTSTRAP_USERNAME 未设置/为空时使用） */
    static final String DEFAULT_BOOTSTRAP_USERNAME = "admin";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AdminUserMapper adminUserMapper;

    /**
     * 首个管理员登录名（环境变量 {@code ADMIN_BOOTSTRAP_USERNAME:admin}）
     */
    @Value("${ADMIN_BOOTSTRAP_USERNAME:" + DEFAULT_BOOTSTRAP_USERNAME + "}")
    private String bootstrapUsername;

    /**
     * 首个管理员初始密码（环境变量 {@code ADMIN_BOOTSTRAP_PASSWORD}，默认空=不初始化）
     *
     * <p>密码只从环境变量/启动参数读取，绝不落仓库、不落日志、不落库明文。</p>
     */
    @Value("${ADMIN_BOOTSTRAP_PASSWORD:}")
    private String bootstrapPassword;

    /**
     * 启动时初始化首个管理员（admin_user 为空且设置了初始密码才创建）
     */
    @PostConstruct
    public void initAdminUser() {
        bootstrapAdmin();
    }

    /**
     * 首次初始化管理员账号（幂等：表非空直接跳过）
     *
     * <p>表未迁移（V17 未执行）或数据库暂不可用时只 WARN，不阻断应用启动——
     * 此时管理端登录会因查不到账号而失败，排障日志会指明原因。</p>
     */
    public void bootstrapAdmin() {
        try {
            Long existing = adminUserMapper.selectCount(null);
            if (existing != null && existing > 0) {
                return;
            }
        } catch (Exception e) {
            log.warn("管理员账号初始化跳过（admin_user 不可用，请确认已执行 db/V17__admin_user.sql）: {}",
                    e.getMessage());
            return;
        }

        String rawPassword = bootstrapPassword;
        if (rawPassword == null || rawPassword.isBlank()) {
            log.warn("admin_user 为空且未设置 ADMIN_BOOTSTRAP_PASSWORD，跳过首个管理员初始化；"
                    + "请设置环境变量 ADMIN_BOOTSTRAP_USERNAME（默认 admin）与 ADMIN_BOOTSTRAP_PASSWORD 后重启");
            return;
        }

        String username = (bootstrapUsername == null || bootstrapUsername.isBlank())
                ? DEFAULT_BOOTSTRAP_USERNAME
                : bootstrapUsername.trim();
        try {
            AdminUser admin = new AdminUser();
            admin.setUsername(username);
            admin.setPasswordHash(hashPassword(rawPassword));
            admin.setRole(ADMIN_ROLE);
            admin.setStatus(STATUS_ACTIVE);
            adminUserMapper.insert(admin);
            // 不打印密码/哈希
            log.info("已初始化首个管理员账号: username={}（密码来自环境变量，仅保存 PBKDF2 哈希）", username);
        } catch (Exception e) {
            log.warn("管理员账号初始化失败（跳过，不影响启动）: username={}, error={}", username, e.getMessage());
        }
    }

    @Override
    public AdminLoginResponse login(String username, String password) {
        // 1. 查库（租户条件由租户插件追加；登录接口无请求上下文 → 默认租户）
        AdminUser admin = adminUserMapper.selectByUsername(username);

        // 2. 账号不存在 / 密码错误 → 统一 UNAUTHORIZED（不区分，避免账号枚举）
        if (admin == null || !verifyPassword(password, admin.getPasswordHash())) {
            log.warn("管理员登录失败（账号或密码错误）: username={}", username);
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "用户名或密码错误");
        }

        // 3. 状态校验（停用账号即使密码正确也不放行）
        if (!STATUS_ACTIVE.equals(admin.getStatus())) {
            log.warn("管理员登录被拒（账号非启用状态）: username={}, status={}", admin.getUsername(), admin.getStatus());
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "账号已停用，请联系管理员");
        }

        // 4. 签发管理员 JWT（role=admin；sub=管理员 ID；租户取账号所属租户）
        String tenantId = (admin.getTenantId() == null || admin.getTenantId().isBlank())
                ? TenantHandler.DEFAULT_TENANT_ID
                : admin.getTenantId();
        String openid = "admin_" + admin.getUsername();
        String token = jwtUtil.generateAdminToken(openid, admin.getAdminId(), tenantId);

        // 5. 构造响应（不回传密码哈希）
        AdminLoginResponse response = new AdminLoginResponse();
        response.setToken(token);

        AdminInfoVO adminInfo = new AdminInfoVO();
        adminInfo.setAdminId(admin.getAdminId());
        adminInfo.setUsername(admin.getUsername());
        adminInfo.setNickname("管理员");
        adminInfo.setRole(admin.getRole() == null || admin.getRole().isBlank() ? ADMIN_ROLE : admin.getRole());
        response.setAdminInfo(adminInfo);

        log.info("管理员登录成功: username={}, adminId={}", admin.getUsername(), admin.getAdminId());

        return response;
    }

    /**
     * 生成 PBKDF2 密码哈希（随机盐）
     *
     * @param rawPassword 明文密码（调用方负责来源安全，如环境变量）
     * @return {@code pbkdf2$<iterations>$<saltBase64>$<hashBase64>}
     */
    public static String hashPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, PBKDF2_ITERATIONS);
        return HASH_PREFIX + "$" + PBKDF2_ITERATIONS
                + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 校验密码与库中哈希是否匹配（格式非法/哈希缺失一律返回 false，不抛异常）
     *
     * @param rawPassword   明文密码
     * @param passwordHash  {@link #hashPassword(String)} 生成的哈希
     * @return true=匹配
     */
    public static boolean verifyPassword(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null) {
            return false;
        }
        String[] parts = passwordHash.split("\\$");
        if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (iterations <= 0 || salt.length == 0 || expected.length == 0) {
            return false;
        }
        byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations);
        // 常量时间比较，避免时序侧信道
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 不可用: " + e.getMessage(), e);
        } finally {
            spec.clearPassword();
        }
    }
}
