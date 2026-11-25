package com.jiang.client;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CloudStorageImageDownloader 单元测试（Change: 2026-11-23_upload_via_cloud_storage）
 *
 * <p>覆盖：受信域名下载成功、非 https 拒绝、非白名单域名拒绝（防 SSRF）、
 * 空响应抛 1183、超 10MB 抛 1184、下载异常抛 1183。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudStorageImageDownloader 云存储下载与 SSRF 防护")
class CloudStorageImageDownloaderTest {

    private static final String TRUSTED_URL =
            "https://7072-prod-d8g1ado543d76cfe4-1520028634.tcb.qcloud.la/exam-upload/1700000000-1.jpg";

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private CloudStorageImageDownloader downloader;

    private void withDefaultSuffixes() {
        ReflectionTestUtils.setField(downloader, "allowedSuffixes",
                "tcb.qcloud.la,qcloud.la,myqcloud.com,tcloudbase.com");
    }

    @Test
    @DisplayName("受信 https 域名 → 返回字节并调用一次 GET")
    void should_download_when_trustedHttpsUrl() {
        withDefaultSuffixes();
        byte[] body = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        when(restTemplate.getForEntity(any(URI.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(body));

        byte[] result = downloader.download(TRUSTED_URL);

        assertEquals(body.length, result.length);
        verify(restTemplate).getForEntity(any(URI.class), eq(byte[].class));
    }

    @Test
    @DisplayName("http（非 https）→ 拒绝且不发请求")
    void should_reject_when_notHttps() {
        withDefaultSuffixes();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloader.download("http://7072-prod-x.tcb.qcloud.la/a.jpg"));
        assertEquals(ErrorCodes.FILE_TYPE_INVALID.getCode(), ex.getCode());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("非白名单域名 → 拒绝（防 SSRF）")
    void should_reject_when_hostNotTrusted() {
        withDefaultSuffixes();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> downloader.download("https://evil.example.com/a.jpg"));
        assertEquals(ErrorCodes.FILE_TYPE_INVALID.getCode(), ex.getCode());
        verifyNoInteractions(restTemplate);

        // 相似后缀不能被误判为白名单（evil-tcb.qcloud.la 命中后缀但 evil.com 不命中）
        assertThrows(BusinessException.class,
                () -> downloader.download("https://tcb.qcloud.la.evil.com/a.jpg"));
    }

    @Test
    @DisplayName("响应体为空 → 抛 1183")
    void should_throwEmpty_when_bodyBlank() {
        withDefaultSuffixes();
        when(restTemplate.getForEntity(any(URI.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(new byte[0]));

        BusinessException ex = assertThrows(BusinessException.class, () -> downloader.download(TRUSTED_URL));
        assertEquals(ErrorCodes.EXAM_IMAGE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("响应体超过 10MB → 抛 1184")
    void should_throwTooLarge_when_bodyExceedsLimit() {
        withDefaultSuffixes();
        when(restTemplate.getForEntity(any(URI.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(new byte[(int) CloudStorageImageDownloader.MAX_BYTES + 1]));

        BusinessException ex = assertThrows(BusinessException.class, () -> downloader.download(TRUSTED_URL));
        assertEquals(ErrorCodes.EXAM_IMAGE_TOO_LARGE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("下载异常 → 抛 1183（不把底层异常泄漏给调用方）")
    void should_throwEmpty_when_downloadFails() {
        withDefaultSuffixes();
        when(restTemplate.getForEntity(any(URI.class), eq(byte[].class)))
                .thenThrow(new RestClientException("timeout"));

        BusinessException ex = assertThrows(BusinessException.class, () -> downloader.download(TRUSTED_URL));
        assertEquals(ErrorCodes.EXAM_IMAGE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("支持自定义后缀配置（exam.upload.allowed-url-suffixes）")
    void should_allowCustomSuffix() {
        ReflectionTestUtils.setField(downloader, "allowedSuffixes", "example.com");
        when(restTemplate.getForEntity(any(URI.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(new byte[]{1}));

        assertTrue(downloader.download("https://img.example.com/a.jpg").length == 1);
    }
}
