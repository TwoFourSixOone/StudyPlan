package com.jiang.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 试卷图片「云存储直传」上传请求（Change: 2026-11-23_upload_via_cloud_storage）
 *
 * <p>小程序端先用 {@code wx.cloud.uploadFile} 把图片传到云存储（不受 100KB 请求体限制），
 * 再用 {@code wx.cloud.getTempFileURL} 取临时 HTTPS 地址，把地址传给本接口；
 * 服务端下载后走既有校验与落盘流程。避免依赖「uploadFile 合法域名」，也不受 callContainer 100KB 限制。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-23
 */
@Data
@Schema(description = "试卷图片云存储直传请求")
public class ExamImageUrlRequest {

    /**
     * 云存储临时访问 URL（必须是受信域名的 https 地址）
     */
    @NotBlank(message = "图片地址不能为空")
    @Schema(description = "云存储临时访问 URL（https，受信域名）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "https://7072-prod-xxx-1520028634.tcb.qcloud.la/exam-upload/1700000000-1.jpg")
    private String url;

    /**
     * 图片 MIME 类型（缺省 image/jpeg；最终以魔数校验为准）
     */
    @Schema(description = "MIME 类型，缺省 image/jpeg", example = "image/jpeg")
    private String contentType;

    /**
     * 卷面页序（1-based，可空）
     */
    @Schema(description = "卷面页序（1-based，可空）", example = "1")
    private Integer pageNo;
}
