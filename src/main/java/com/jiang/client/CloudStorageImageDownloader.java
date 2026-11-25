package com.jiang.client;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 云存储图片下载器（Change: 2026-11-23_upload_via_cloud_storage）
 *
 * <p>小程序把试卷图片传到云存储后，把 {@code wx.cloud.getTempFileURL} 得到的临时 HTTPS 地址传给后端，
 * 由本类下载字节再走既有校验/落盘流程。</p>
 *
 * <p><b>安全约束（防 SSRF）</b>：</p>
 * <ul>
 *   <li>只允许 {@code https}；</li>
 *   <li>域名必须命中白名单后缀（默认覆盖微信云存储/云托管域名），可用 {@code exam.upload.allowed-url-suffixes} 覆盖；</li>
 *   <li>响应体上限 {@link #MAX_BYTES}（10MB），超限直接拒绝，避免大文件打爆内存。</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-23
 */
@Component
@Slf4j
public class CloudStorageImageDownloader {

    /** 单张图片下载上限：与后端业务上限 9.5MB 留余量 */
    static final long MAX_BYTES = 10L * 1024 * 1024;

    /** 默认受信域名后缀（微信云存储 / 云托管） */
    private static final String DEFAULT_SUFFIXES = "tcb.qcloud.la,qcloud.la,myqcloud.com,tcloudbase.com";

    @Autowired
    private RestTemplate restTemplate;

    @Value("${exam.upload.allowed-url-suffixes:" + DEFAULT_SUFFIXES + "}")
    private String allowedSuffixes = DEFAULT_SUFFIXES;

    /**
     * 下载云存储临时 URL 的图片字节
     *
     * @param url 临时 HTTPS 地址
     * @return 图片字节（≤ {@link #MAX_BYTES}）
     * @throws BusinessException URL 非法/不在白名单（1139）、下载失败或无内容（1183）、超过上限（1184）
     */
    public byte[] download(String url) {
        ensureTrustedUrl(url);
        try {
            ResponseEntity<byte[]> response = restTemplate.getForEntity(URI.create(url), byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                log.warn("云存储图片下载为空: url={}", maskUrl(url));
                throw new BusinessException(ErrorCodes.EXAM_IMAGE_EMPTY);
            }
            if (body.length > MAX_BYTES) {
                log.warn("云存储图片超过下载上限: url={}, size={}", maskUrl(url), body.length);
                throw new BusinessException(ErrorCodes.EXAM_IMAGE_TOO_LARGE);
            }
            log.info("云存储图片下载完成: url={}, size={}", maskUrl(url), body.length);
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("云存储图片下载失败: url={}, error={}", maskUrl(url), e.getMessage());
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_EMPTY);
        }
    }

    /**
     * 校验 URL 协议与域名白名单（包级可见，便于单测）
     */
    void ensureTrustedUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCodes.FILE_TYPE_INVALID);
        }
        if (uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            log.warn("拒绝非 https 图片地址: {}", maskUrl(url));
            throw new BusinessException(ErrorCodes.FILE_TYPE_INVALID);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new BusinessException(ErrorCodes.FILE_TYPE_INVALID);
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        List<String> suffixes = Arrays.stream(allowedSuffixes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        boolean trusted = suffixes.stream().anyMatch(s -> lowerHost.equals(s) || lowerHost.endsWith("." + s));
        if (!trusted) {
            log.warn("拒绝非受信域名的图片地址: host={}", lowerHost);
            throw new BusinessException(ErrorCodes.FILE_TYPE_INVALID);
        }
    }

    /** 日志脱敏：只保留 host 与文件名的前 24 字符 */
    private String maskUrl(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            return uri.getHost() + path.substring(0, Math.min(24, path.length()));
        } catch (Exception e) {
            return "<invalid-url>";
        }
    }
}
