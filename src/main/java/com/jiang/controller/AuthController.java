package com.jiang.controller;

import com.jiang.common.result.Result;
import com.jiang.dto.LoginRequest;
import com.jiang.service.AuthService;
import com.jiang.vo.LoginResponse;
import com.jiang.vo.StudentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 Controller
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证管理", description = "学生登录与鉴权接口")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "微信小程序登录")
    @SecurityRequirements
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
