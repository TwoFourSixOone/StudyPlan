package com.jiang.dto;

import java.util.Base64;

/**
 * 视觉解析入参：一张试卷图片（媒体类型 + 原始字节）
 *
 * <p>零 DDL 的试卷链路通过 base64 data URL 把本地图片内联进 LLM 请求（本地 localhost 对模型不可达），
 * base64 仅在 {@code AgentScopeClient#callVision} 内部按图生成、用完即弃，<b>不得</b>缓存为
 * {@code List<String>}/{@code List<byte[]>} 或写进任何日志/数据库字段（design §11.3）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
public final class VisionImage {

    /** 默认媒体类型（本 change 只允许 jpg/jpeg/png） */
    public static final String DEFAULT_MEDIA_TYPE = "image/jpeg";

    private static final String BASE64_DATA_URL_MARK = "base64,";

    private final String mediaType;

    private final byte[] bytes;

    private VisionImage(String mediaType, byte[] bytes) {
        this.mediaType = (mediaType == null || mediaType.isBlank()) ? DEFAULT_MEDIA_TYPE : mediaType.trim();
        this.bytes = bytes;
    }

    /**
     * 以原始字节构造图片入参
     *
     * @param mediaType 媒体类型（image/jpeg、image/jpg、image/png），空值按 image/jpeg
     * @param bytes     原始图片字节（非空）
     * @return 图片入参
     * @throws IllegalArgumentException 字节为空时抛出
     */
    public static VisionImage of(String mediaType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片字节不能为空");
        }
        return new VisionImage(mediaType, bytes);
    }

    /**
     * 以 base64 字符串构造图片入参
     *
     * <p>容错：入参可能带 {@code data:image/png;base64,} 前缀（前端/上游直接透传 data URL），
     * 这里统一剥离后再解码；DashScope 侧要求 data 字段是<b>纯 base64</b>（design §3.1）。</p>
     *
     * @param mediaType       媒体类型
     * @param base64OrDataUrl 纯 base64 或 data URL
     * @return 图片入参
     */
    public static VisionImage ofBase64(String mediaType, String base64OrDataUrl) {
        if (base64OrDataUrl == null || base64OrDataUrl.isBlank()) {
            throw new IllegalArgumentException("图片 base64 不能为空");
        }
        return of(mediaType, Base64.getDecoder().decode(stripDataUrlPrefix(base64OrDataUrl)));
    }

    /**
     * 剥离 {@code data:image/png;base64,} 前缀；非 data URL 原样返回
     */
    public static String stripDataUrlPrefix(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("data:")) {
            int index = value.indexOf(BASE64_DATA_URL_MARK);
            if (index > 0) {
                return value.substring(index + BASE64_DATA_URL_MARK.length());
            }
        }
        return value;
    }

    public String getMediaType() {
        return mediaType;
    }

    /**
     * 原始字节（调用方禁止长期持有引用，用完即释放）
     */
    public byte[] getBytes() {
        return bytes;
    }

    public int size() {
        return bytes.length;
    }
}
