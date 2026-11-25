package com.jiang.controller;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.ExamImageBase64Request;
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

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExamPaperController base64 上传通道测试（Change: 2026-11-23_upload_via_call_container）
 *
 * <p>覆盖：base64 正常解码后交给 service（字节数与 contentType 正确）、contentType 缺省为 image/jpeg、
 * 空数据抛 1183、非法 base64 抛 1183、超大 base64 前置拦截抛 1184。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExamPaperController base64 上传通道")
class ExamPaperBase64UploadTest {

    private static final Long PAPER_ID = 4L;

    @Mock
    private ExamPaperService examPaperService;

    @InjectMocks
    private ExamPaperController controller;

    private ExamImageBase64Request buildRequest(String dataBase64, String contentType, Integer pageNo) {
        ExamImageBase64Request request = new ExamImageBase64Request();
        request.setDataBase64(dataBase64);
        request.setContentType(contentType);
        request.setPageNo(pageNo);
        return request;
    }

    @Test
    @DisplayName("base64 正常 → 解码后字节与 contentType 原样交给 service，pageNo 透传")
    void should_passDecodedBytesToService_when_base64Valid() throws Exception {
        byte[] imageBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        when(examPaperService.uploadImage(eq(PAPER_ID), any(MultipartFile.class), eq(2)))
                .thenReturn(ExamImageUploadVO.builder().paperId(PAPER_ID).uploadedCount(2).status("uploading").build());

        controller.uploadImageBase64(PAPER_ID, buildRequest(base64, "image/png", 2));

        ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(examPaperService).uploadImage(eq(PAPER_ID), captor.capture(), eq(2));
        MultipartFile file = captor.getValue();
        assertArrayEquals(imageBytes, file.getBytes(), "解码后的字节必须与原始一致");
        assertEquals("image/png", file.getContentType());
        assertEquals(imageBytes.length, file.getSize());
    }

    @Test
    @DisplayName("contentType 缺省 → 按 image/jpeg 处理（最终仍由魔数校验兜底）")
    void should_defaultContentTypeToJpeg_when_missing() {
        String base64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        when(examPaperService.uploadImage(eq(PAPER_ID), any(MultipartFile.class), eq(null)))
                .thenReturn(ExamImageUploadVO.builder().paperId(PAPER_ID).uploadedCount(1).status("uploading").build());

        controller.uploadImageBase64(PAPER_ID, buildRequest(base64, null, null));

        ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(examPaperService).uploadImage(eq(PAPER_ID), captor.capture(), eq(null));
        assertEquals("image/jpeg", captor.getValue().getContentType());
    }

    @Test
    @DisplayName("空/空白 base64 → 抛 1183（与 multipart 空文件同口径）")
    void should_throwImageEmpty_when_base64Blank() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.uploadImageBase64(PAPER_ID, buildRequest("   ", "image/jpeg", 1)));
        assertEquals(ErrorCodes.EXAM_IMAGE_EMPTY.getCode(), ex.getCode());

        BusinessException nullEx = assertThrows(BusinessException.class,
                () -> controller.uploadImageBase64(PAPER_ID, buildRequest(null, "image/jpeg", 1)));
        assertEquals(ErrorCodes.EXAM_IMAGE_EMPTY.getCode(), nullEx.getCode());
    }

    @Test
    @DisplayName("非法 base64 → 抛 1183，不进入 service")
    void should_throwImageEmpty_when_base64Invalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.uploadImageBase64(PAPER_ID, buildRequest("!!!not-base64!!!", "image/jpeg", 1)));
        assertEquals(ErrorCodes.EXAM_IMAGE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("超大 base64（解码后 >10MB）→ 前置抛 1184，避免无谓解码")
    void should_throwTooLarge_when_base64Huge() {
        String huge = "A".repeat(15 * 1024 * 1024); // base64 约 15MB → 估算解码 11MB
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.uploadImageBase64(PAPER_ID, buildRequest(huge, "image/jpeg", 1)));
        assertEquals(ErrorCodes.EXAM_IMAGE_TOO_LARGE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("容忍换行/空白的 base64（MIME 解码）")
    void should_decodeMimeBase64_when_containsLineBreaks() throws Exception {
        byte[] imageBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, 9, 9, 9};
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String withBreaks = base64.substring(0, 2) + "\r\n" + base64.substring(2);
        when(examPaperService.uploadImage(eq(PAPER_ID), any(MultipartFile.class), eq(1)))
                .thenReturn(ExamImageUploadVO.builder().paperId(PAPER_ID).uploadedCount(1).status("uploading").build());

        controller.uploadImageBase64(PAPER_ID, buildRequest(withBreaks, "image/jpeg", 1));

        ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(examPaperService).uploadImage(eq(PAPER_ID), captor.capture(), eq(1));
        assertArrayEquals(imageBytes, captor.getValue().getBytes());
        assertTrue(captor.getValue().getOriginalFilename() != null);
    }
}
