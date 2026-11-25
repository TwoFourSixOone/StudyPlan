package com.jiang.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 试卷图片 base64 上传请求（Change: 2026-11-23_upload_via_call_container）
 *
 * <p>用于云托管 {@code wx.cloud.callContainer} 通道：小程序把图片读成 base64 放进 JSON body，
 * 避免走公网 HTTPS 域名（云托管默认域名不能用于正式环境的「uploadFile 合法域名」）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-23
 */
@Data
@Schema(description = "试卷图片 base64 上传请求")
public class ExamImageBase64Request {

    /**
     * 图片 base64（不含 data URI 前缀）
     */
    @NotBlank(message = "图片数据不能为空")
    @Schema(description = "图片 base64 字符串（不含 data:image/...;base64, 前缀）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String dataBase64;

    /**
     * 图片 MIME 类型（缺省按 JPEG 处理；最终仍以魔数校验为准）
     */
    @Schema(description = "MIME 类型，缺省 image/jpeg", example = "image/jpeg")
    private String contentType;

    /**
     * 卷面页序（1-based，可空：空则由后端按当前张数顺延）
     */
    @Schema(description = "卷面页序（1-based，可空）", example = "1")
    private Integer pageNo;
}
