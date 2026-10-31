package com.jiang.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求 DTO
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
public class LoginRequest {

    @NotBlank(message = "code 不能为空")
    private String code;
}
