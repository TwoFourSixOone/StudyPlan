package com.jiang.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.config.JwtConfig;
import com.jiang.mapper.AdminUserMapper;
import com.jiang.model.entity.AdminUser;
import com.jiang.security.JwtUtil;
import com.jiang.service.impl.AdminAuthServiceImpl;
import com.jiang.vo.AdminLoginResponse;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminAuthService 单元测试（上线加固：账号落库 + PBKDF2 + 环境变量初始化）
 *
 * <p>覆盖：登录成功签发 role=admin token、密码错误/账号不存在/账号停用统一 401、
 * PBKDF2 哈希可验证、bootstrap 的四种分支（表已有账号 / 表空且有密码 / 表空且无密码 / 表缺失）。</p>
 *
 * <p>环境变量用可注入字段 + ReflectionTestUtils 模拟，不读取真实进程环境。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAuthService 账号落库与 PBKDF2")
class AdminAuthServiceTest {

    private static final String SECRET = "this-is-a-test-secret-key-for-jwt-signing-256-bits";
    private static final String TENANT_ID = "default";
    private static final Long ADMIN_ID = 1L;
    private static final String PASSWORD = "S3cret-pass!";

    @Mock
    private AdminUserMapper adminUserMapper;

    private AdminAuthServiceImpl adminAuthService;
    private JwtUtil jwtUtil;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setSecret(SECRET);
        config.setExpiration(7200000L);
        config.setRefreshThreshold(300000L);
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "jwtConfig", config);

        adminAuthService = new AdminAuthServiceImpl();
        ReflectionTestUtils.setField(adminAuthService, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(adminAuthService, "adminUserMapper", adminUserMapper);
        // 模拟环境变量：默认登录名 admin、初始密码为空（不初始化）
        ReflectionTestUtils.setField(adminAuthService, "bootstrapUsername", "admin");
        ReflectionTestUtils.setField(adminAuthService, "bootstrapPassword", "");

        logger = (Logger) LoggerFactory.getLogger(AdminAuthServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    // ==================== 登录 ====================

    @Test
    @DisplayName("账号密码正确 → 签发 role=admin 的 token（sub=管理员ID，openid=admin_<username>）")
    void should_issueAdminToken_when_credentialsValid() {
        when(adminUserMapper.selectByUsername("admin"))
                .thenReturn(buildAdmin(ADMIN_ID, "admin", PASSWORD, "active"));

        AdminLoginResponse response = adminAuthService.login("admin", PASSWORD);

        assertNotNull(response.getToken());
        Claims claims = jwtUtil.parseToken(response.getToken());
        assertEquals(JwtUtil.ROLE_ADMIN, claims.get(JwtUtil.CLAIM_ROLE, String.class),
                "管理员 token 必须带 role=admin，否则无法通过 /api/admin/** 角色隔离");
        assertTrue(JwtUtil.isAdmin(claims));
        assertEquals(String.valueOf(ADMIN_ID), claims.getSubject());
        assertEquals("admin_admin", claims.get("openid", String.class));
        assertEquals(TENANT_ID, claims.get("tenantId", String.class));

        assertNotNull(response.getAdminInfo());
        assertEquals(ADMIN_ID, response.getAdminInfo().getAdminId());
        assertEquals("admin", response.getAdminInfo().getUsername());
        assertEquals("admin", response.getAdminInfo().getRole());
    }

    @Test
    @DisplayName("密码错误 → 统一 401（不签发 token）")
    void should_throwUnauthorized_when_passwordWrong() {
        when(adminUserMapper.selectByUsername("admin"))
                .thenReturn(buildAdmin(ADMIN_ID, "admin", PASSWORD, "active"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminAuthService.login("admin", "wrong-password"));

        assertEquals(ErrorCodes.UNAUTHORIZED.getCode(), ex.getCode());
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("username=admin")),
                "登录失败应记 WARN（仅 username，不含密码/哈希）");
        assertFalse(logAppender.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains(PASSWORD)),
                "日志不得出现明文密码");
    }

    @Test
    @DisplayName("账号不存在 → 统一 401（不区分用户是否存在，避免账号枚举）")
    void should_throwUnauthorized_when_userNotFound() {
        when(adminUserMapper.selectByUsername("ghost")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminAuthService.login("ghost", PASSWORD));

        assertEquals(ErrorCodes.UNAUTHORIZED.getCode(), ex.getCode());
        assertEquals("用户名或密码错误", ex.getMessage());
    }

    @Test
    @DisplayName("账号停用 → 即使密码正确也拒绝（401）")
    void should_throwUnauthorized_when_userDisabled() {
        when(adminUserMapper.selectByUsername("admin"))
                .thenReturn(buildAdmin(ADMIN_ID, "admin", PASSWORD, "disabled"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminAuthService.login("admin", PASSWORD));

        assertEquals(ErrorCodes.UNAUTHORIZED.getCode(), ex.getCode());
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("status=disabled")),
                "停用账号应记 WARN 便于排障");
    }

    // ==================== PBKDF2 ====================

    @Test
    @DisplayName("PBKDF2：同一密码可验证通过，错误密码/非法哈希失败；存的是哈希不是明文")
    void should_hashAndVerifyPassword() {
        String hash = AdminAuthServiceImpl.hashPassword(PASSWORD);

        assertTrue(hash.startsWith("pbkdf2$"), "哈希格式必须是 pbkdf2$<iterations>$<salt>$<hash>: " + hash);
        assertEquals(4, hash.split("\\$").length);
        assertNotEquals(PASSWORD, hash);
        assertFalse(hash.contains(PASSWORD), "哈希中不得出现明文密码");

        assertTrue(AdminAuthServiceImpl.verifyPassword(PASSWORD, hash));
        assertFalse(AdminAuthServiceImpl.verifyPassword("wrong-password", hash));
        assertFalse(AdminAuthServiceImpl.verifyPassword(PASSWORD, null));
        assertFalse(AdminAuthServiceImpl.verifyPassword(null, hash));
        assertFalse(AdminAuthServiceImpl.verifyPassword(PASSWORD, "plain-text-password"));
        assertFalse(AdminAuthServiceImpl.verifyPassword(PASSWORD, "pbkdf2$abc$xxx$yyy"));
        assertFalse(AdminAuthServiceImpl.verifyPassword(PASSWORD, "pbkdf2$0$c2FsdA==$aGFzaA=="));

        // 随机盐：同一密码两次哈希不同，但都能验证通过
        String another = AdminAuthServiceImpl.hashPassword(PASSWORD);
        assertNotEquals(hash, another);
        assertTrue(AdminAuthServiceImpl.verifyPassword(PASSWORD, another));
    }

    // ==================== bootstrap（环境变量初始化） ====================

    @Test
    @DisplayName("admin_user 已有账号 → bootstrap 幂等跳过（不重复插入）")
    void should_skipBootstrap_when_adminUserExists() {
        when(adminUserMapper.selectCount(null)).thenReturn(1L);

        adminAuthService.bootstrapAdmin();

        verify(adminUserMapper, never()).insert(any(AdminUser.class));
    }

    @Test
    @DisplayName("admin_user 为空 + 设置了初始密码 → 创建首个管理员（库中只有 PBKDF2 哈希）")
    void should_createBootstrapAdmin_when_tableEmptyAndPasswordConfigured() {
        when(adminUserMapper.selectCount(null)).thenReturn(0L);
        ReflectionTestUtils.setField(adminAuthService, "bootstrapUsername", "root");
        ReflectionTestUtils.setField(adminAuthService, "bootstrapPassword", "InitPass-123!");

        adminAuthService.bootstrapAdmin();

        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        verify(adminUserMapper).insert(captor.capture());
        AdminUser created = captor.getValue();
        assertEquals("root", created.getUsername());
        assertEquals("admin", created.getRole());
        assertEquals("active", created.getStatus());
        assertNotNull(created.getPasswordHash());
        assertNotEquals("InitPass-123!", created.getPasswordHash(), "库中不得存明文密码");
        assertTrue(AdminAuthServiceImpl.verifyPassword("InitPass-123!", created.getPasswordHash()),
                "bootstrap 创建的账号必须能用初始密码登录");
        assertFalse(logAppender.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("InitPass-123!")),
                "日志不得打印初始密码");
    }

    @Test
    @DisplayName("admin_user 为空但未设置初始密码 → 不创建账号，WARN 提示如何初始化")
    void should_notCreateAdmin_when_tableEmptyAndNoBootstrapPassword() {
        when(adminUserMapper.selectCount(null)).thenReturn(0L);

        adminAuthService.bootstrapAdmin();

        verify(adminUserMapper, never()).insert(any(AdminUser.class));
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("ADMIN_BOOTSTRAP_PASSWORD")),
                "应 WARN 提示设置 ADMIN_BOOTSTRAP_PASSWORD");
    }

    @Test
    @DisplayName("表缺失/DB 异常 → bootstrap 不拖垮启动（WARN + 可操作提示）")
    void should_notFailStartup_when_adminUserTableMissing() {
        when(adminUserMapper.selectCount(null))
                .thenThrow(new RuntimeException("Table 'admin_user' doesn't exist"));

        assertDoesNotThrow(() -> adminAuthService.bootstrapAdmin());

        verify(adminUserMapper, never()).insert(any(AdminUser.class));
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("V17__admin_user.sql")),
                "应提示执行 V17 迁移");
    }

    @Test
    @DisplayName("初始登录名为空 → 回退默认 admin")
    void should_useDefaultUsername_when_bootstrapUsernameBlank() {
        when(adminUserMapper.selectCount(null)).thenReturn(0L);
        ReflectionTestUtils.setField(adminAuthService, "bootstrapUsername", "   ");
        ReflectionTestUtils.setField(adminAuthService, "bootstrapPassword", "InitPass-123!");

        adminAuthService.bootstrapAdmin();

        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        verify(adminUserMapper).insert(captor.capture());
        assertEquals("admin", captor.getValue().getUsername());
    }

    // ==================== 修改密码（Change: 2026-11-19_admin_password_change）====================

    @Test
    @DisplayName("原密码正确 → 更新 PBKDF2 哈希，新密码可验证、旧密码失效")
    void should_updatePasswordHash_when_oldPasswordCorrect() {
        when(adminUserMapper.selectById(ADMIN_ID))
                .thenReturn(buildAdmin(ADMIN_ID, "admin", PASSWORD, "active"));
        String newPassword = "N3w-pass-word!";

        adminAuthService.changePassword(ADMIN_ID, PASSWORD, newPassword);

        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        verify(adminUserMapper).updateById(captor.capture());
        AdminUser updated = captor.getValue();
        assertEquals(ADMIN_ID, updated.getAdminId());
        assertNotNull(updated.getPasswordHash());
        assertTrue(AdminAuthServiceImpl.verifyPassword(newPassword, updated.getPasswordHash()),
                "新密码必须与落库哈希匹配");
        assertFalse(AdminAuthServiceImpl.verifyPassword(PASSWORD, updated.getPasswordHash()),
                "旧密码必须失效");
        assertNotEquals(AdminAuthServiceImpl.hashPassword(PASSWORD), updated.getPasswordHash());
    }

    @Test
    @DisplayName("原密码错误 → 抛 1194 且不写库（业务码，不踢下线）")
    void should_throwPasswordMismatch_when_oldPasswordWrong() {
        when(adminUserMapper.selectById(ADMIN_ID))
                .thenReturn(buildAdmin(ADMIN_ID, "admin", PASSWORD, "active"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminAuthService.changePassword(ADMIN_ID, "wrong-pass", "N3w-pass-word!"));

        assertEquals(ErrorCodes.ADMIN_PASSWORD_MISMATCH.getCode(), ex.getCode());
        verify(adminUserMapper, never()).updateById(any(AdminUser.class));
    }

    @Test
    @DisplayName("新密码与原密码相同 → 抛 1195 且不写库")
    void should_throwPasswordUnchanged_when_newPasswordSameAsOld() {
        when(adminUserMapper.selectById(ADMIN_ID))
                .thenReturn(buildAdmin(ADMIN_ID, "admin", PASSWORD, "active"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminAuthService.changePassword(ADMIN_ID, PASSWORD, PASSWORD));

        assertEquals(ErrorCodes.ADMIN_PASSWORD_UNCHANGED.getCode(), ex.getCode());
        verify(adminUserMapper, never()).updateById(any(AdminUser.class));
    }

    @Test
    @DisplayName("账号不存在 / 已停用 / 无身份 → 统一 401（不区分存在性）")
    void should_throwUnauthorized_when_adminNotUsable() {
        when(adminUserMapper.selectById(ADMIN_ID)).thenReturn(null);

        BusinessException notFound = assertThrows(BusinessException.class,
                () -> adminAuthService.changePassword(ADMIN_ID, PASSWORD, "N3w-pass-word!"));
        assertEquals(ErrorCodes.UNAUTHORIZED.getCode(), notFound.getCode());

        when(adminUserMapper.selectById(ADMIN_ID))
                .thenReturn(buildAdmin(ADMIN_ID, "admin", PASSWORD, "disabled"));
        BusinessException disabled = assertThrows(BusinessException.class,
                () -> adminAuthService.changePassword(ADMIN_ID, PASSWORD, "N3w-pass-word!"));
        assertEquals(ErrorCodes.UNAUTHORIZED.getCode(), disabled.getCode());

        BusinessException noIdentity = assertThrows(BusinessException.class,
                () -> adminAuthService.changePassword(null, PASSWORD, "N3w-pass-word!"));
        assertEquals(ErrorCodes.UNAUTHORIZED.getCode(), noIdentity.getCode());

        verify(adminUserMapper, never()).updateById(any(AdminUser.class));
    }

    // ==================== 辅助方法 ====================

    private AdminUser buildAdmin(Long adminId, String username, String rawPassword, String status) {
        AdminUser admin = new AdminUser();
        admin.setAdminId(adminId);
        admin.setUsername(username);
        admin.setPasswordHash(AdminAuthServiceImpl.hashPassword(rawPassword));
        admin.setRole("admin");
        admin.setStatus(status);
        admin.setTenantId(TENANT_ID);
        return admin;
    }
}
