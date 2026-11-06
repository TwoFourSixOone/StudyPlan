package com.jiang.controller;

import com.jiang.common.result.Result;
import com.jiang.dto.AdminLoginRequest;
import com.jiang.service.AdminAuthService;
import com.jiang.vo.AdminLoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员认证 Controller
 *
 * @author StudyMate Team
 * @since 2026-11-05
 */
@RestController
@RequestMapping("/api/admin/auth")
@Tag(name = "管理员认证", description = "管理端登录接口")
@SecurityRequirements
public class AdminAuthController {

    @Autowired
    private AdminAuthService adminAuthService;

    @PostMapping("/login")
    @Operation(summary = "管理员登录", description = "用户名 + 密码登录，返回 JWT token")
    public Result<AdminLoginResponse> login(@RequestBody @Valid AdminLoginRequest request) {
        AdminLoginResponse response = adminAuthService.login(request.getUsername(), request.getPassword());
        return Result.success(response);
    }
}
