package com.jiang.controller;

import com.jiang.common.result.Result;
import com.jiang.dto.AdminLoginRequest;
import com.jiang.dto.ChangePasswordRequest;
import com.jiang.security.AdminApi;
import com.jiang.security.AdminContext;
import com.jiang.service.AdminAuthService;
import com.jiang.vo.AdminLoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员认证 Controller
 *
 * <p>登录接口免鉴权（{@code WebMvcConfig} 已排除 {@code /api/admin/auth/login}）；
 * 其余接口需 {@code role=admin}（本类标注 {@link AdminApi}，由 {@code JwtInterceptor} 判定）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-05
 */
@Slf4j
@AdminApi
@RestController
@RequestMapping("/api/admin/auth")
@Tag(name = "管理员认证", description = "管理端登录与改密接口")
public class AdminAuthController {

    @Autowired
    private AdminAuthService adminAuthService;

    @PostMapping("/login")
    @Operation(summary = "管理员登录", description = "用户名 + 密码登录，返回 JWT token")
    @SecurityRequirements
    public Result<AdminLoginResponse> login(@RequestBody @Valid AdminLoginRequest request) {
        AdminLoginResponse response = adminAuthService.login(request.getUsername(), request.getPassword());
        return Result.success(response);
    }

    /**
     * 修改当前登录管理员的密码（Change: 2026-11-19_admin_password_change）
     *
     * <p>管理员身份取自服务端上下文，不接受客户端传入；原密码错 → 1194，新旧相同 → 1195。</p>
     */
    @PostMapping("/change-password")
    @Operation(summary = "修改密码", description = "校验原密码后更新为新的 PBKDF2 哈希；成功后建议客户端重新登录")
    public Result<Void> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        Long adminId = AdminContext.getAdminId();
        log.info("管理员修改密码: adminId={}", adminId);
        adminAuthService.changePassword(adminId, request.getOldPassword(), request.getNewPassword());
        return Result.success();
    }
}
