package com.jiang.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员修改密码请求 DTO
 *
 * <p>管理员身份只从服务端上下文（{@code AdminContext}）取，请求体不携带 adminId。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@Data
@Schema(description = "管理员修改密码请求")
public class ChangePasswordRequest {

    /**
     * 原密码
     */
    @NotBlank(message = "原密码不能为空")
    @Schema(description = "原密码", example = "oldPassw0rd", requiredMode = Schema.RequiredMode.REQUIRED)
    private String oldPassword;

    /**
     * 新密码（长度与登录页保持一致：6~64）
     */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度需为 6~64 位")
    @Schema(description = "新密码（6~64 位）", example = "newPassw0rd", requiredMode = Schema.RequiredMode.REQUIRED)
    private String newPassword;
}
