package com.jiang.controller;

import com.jiang.client.CloudStorageImageDownloader;
import com.jiang.dto.ExamImageUrlRequest;
import com.jiang.service.ExamPaperService;
import com.jiang.vo.ExamImageUploadVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExamPaperController 云存储直传通道测试（Change: 2026-11-23_upload_via_cloud_storage）
 *
 * <p>覆盖：下载字节后原样交给 service（contentType/pageNo 透传）、contentType 缺省 jpeg、
 * URL 交给下载器（不在控制器里直接发请求）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExamPaperController 云存储直传通道")
class ExamPaperUrlUploadTest {

    private static final Long PAPER_ID = 7L;
    private static final String STORAGE_URL =
            "https://7072-prod-d8g1ado543d76cfe4-1520028634.tcb.qcloud.la/exam-upload/a.jpg";

    @Mock
    private ExamPaperService examPaperService;

    @Mock
    private CloudStorageImageDownloader cloudStorageImageDownloader;

    @InjectMocks
    private ExamPaperController controller;

    private ExamImageUrlRequest buildRequest(String contentType, Integer pageNo) {
        ExamImageUrlRequest request = new ExamImageUrlRequest();
        request.setUrl(STORAGE_URL);
        request.setContentType(contentType);
        request.setPageNo(pageNo);
        return request;
    }

    @Test
    @DisplayName("下载字节 → 原样交给 service，contentType/pageNo 透传")
    void should_passDownloadedBytesToService() throws Exception {
        byte[] bytes = new byte[]{(byte) 0xFF, (byte) 0xD8, 7, 8};
        when(cloudStorageImageDownloader.download(STORAGE_URL)).thenReturn(bytes);
        when(examPaperService.uploadImage(eq(PAPER_ID), any(MultipartFile.class), eq(1)))
                .thenReturn(ExamImageUploadVO.builder().paperId(PAPER_ID).uploadedCount(1).status("uploading").build());

        controller.uploadImageByUrl(PAPER_ID, buildRequest("image/png", 1));

        ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(examPaperService).uploadImage(eq(PAPER_ID), captor.capture(), eq(1));
        assertArrayEquals(bytes, captor.getValue().getBytes());
        assertEquals("image/png", captor.getValue().getContentType());
        // 控制器只负责下载后适配，不直接发 HTTP
        verify(cloudStorageImageDownloader).download(STORAGE_URL);
    }

    @Test
    @DisplayName("contentType 缺省 → image/jpeg；pageNo 为空透传 null")
    void should_defaultContentType_when_missing() throws Exception {
        when(cloudStorageImageDownloader.download(STORAGE_URL)).thenReturn(new byte[]{1, 2});
        when(examPaperService.uploadImage(eq(PAPER_ID), any(MultipartFile.class), eq(null)))
                .thenReturn(ExamImageUploadVO.builder().paperId(PAPER_ID).uploadedCount(1).status("uploading").build());

        controller.uploadImageByUrl(PAPER_ID, buildRequest(null, null));

        ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(examPaperService).uploadImage(eq(PAPER_ID), captor.capture(), eq(null));
        assertEquals("image/jpeg", captor.getValue().getContentType());
    }
}
