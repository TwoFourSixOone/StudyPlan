package com.jiang.service;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 试卷图片上传校验测试（design §2.3：≤8 张 / 单张 9.5MB / 累计 30MB / 仅 jpg-jpeg-png）
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamUploadValidator 试卷图片校验")
class ExamUploadValidatorTest {

    private static final int MB = 1024 * 1024;

    private final ExamUploadValidator validator = new ExamUploadValidator();

    private static MockMultipartFile jpeg(String name, int sizeBytes) {
        // A5：带真实 JPEG 文件头（FF D8 FF），避免被魔数校验拒绝
        byte[] bytes = new byte[Math.max(sizeBytes, 3)];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return new MockMultipartFile("file", name, "image/jpeg", bytes);
    }

    private static MockMultipartFile png(String name, int sizeBytes) {
        byte[] bytes = new byte[Math.max(sizeBytes, 8)];
        byte[] magic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return new MockMultipartFile("file", name, "image/png", bytes);
    }

    @Test
    @DisplayName("8 张图片（每张 1MB）→ 通过")
    void should_pass_when_eightImages() {
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            files.add(jpeg("p" + i + ".jpg", MB));
        }
        assertDoesNotThrow(() -> validator.validate(files));
    }

    @Test
    @DisplayName("9 张图片 → 1182 业务错误")
    void should_throw1182_when_nineImages() {
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            files.add(jpeg("p" + i + ".jpg", 1024));
        }
        BusinessException exception = assertThrows(BusinessException.class, () -> validator.validate(files));
        assertEquals(ErrorCodes.EXAM_IMAGE_COUNT_EXCEEDED.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("空图片列表 → 1183")
    void should_throw1183_when_empty() {
        assertEquals(ErrorCodes.EXAM_IMAGE_EMPTY.getCode(),
                assertThrows(BusinessException.class, () -> validator.validate(List.of())).getCode());
        assertEquals(ErrorCodes.EXAM_IMAGE_EMPTY.getCode(),
                assertThrows(BusinessException.class, () -> validator.validate(null)).getCode());
    }

    @Test
    @DisplayName("单张 9.6MB（> 9.5MB 业务上限）→ 1184")
    void should_throw1184_when_singleImageTooLarge() {
        List<MultipartFile> files = List.of(jpeg("big.jpg", (int) (9.6 * MB)));
        BusinessException exception = assertThrows(BusinessException.class, () -> validator.validate(files));
        assertEquals(ErrorCodes.EXAM_IMAGE_TOO_LARGE.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("8 张各 4MB（累计 32MB > 30MB）→ 1185")
    void should_throw1185_when_totalTooLarge() {
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            files.add(jpeg("p" + i + ".jpg", 4 * MB));
        }
        BusinessException exception = assertThrows(BusinessException.class, () -> validator.validate(files));
        assertEquals(ErrorCodes.EXAM_IMAGE_TOTAL_TOO_LARGE.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("非 jpg/jpeg/png 类型 → 1139")
    void should_throw1139_when_typeInvalid() {
        List<MultipartFile> files = List.of(
                new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[1024]));
        BusinessException exception = assertThrows(BusinessException.class, () -> validator.validate(files));
        assertEquals(ErrorCodes.FILE_TYPE_INVALID.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("追加图片：已上传 28MB + 本次 3MB → 1185")
    void should_throw1185_when_appendedTotalExceeded() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> validator.validateTotalBytes(28L * MB, 3L * MB));
        assertEquals(ErrorCodes.EXAM_IMAGE_TOTAL_TOO_LARGE.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("创建会话声明张数：0 → 1183；9 → 1182；8 → 通过")
    void should_validateDeclaredImageCount() {
        assertEquals(ErrorCodes.EXAM_IMAGE_EMPTY.getCode(),
                assertThrows(BusinessException.class, () -> validator.validateImageCount(0)).getCode());
        assertEquals(ErrorCodes.EXAM_IMAGE_EMPTY.getCode(),
                assertThrows(BusinessException.class, () -> validator.validateImageCount(null)).getCode());
        assertEquals(ErrorCodes.EXAM_IMAGE_COUNT_EXCEEDED.getCode(),
                assertThrows(BusinessException.class, () -> validator.validateImageCount(9)).getCode());
        assertDoesNotThrow(() -> validator.validateImageCount(8));
    }

    @Test
    @DisplayName("已上传 8 张 → 1189（达上限不可再追加）")
    void should_throw1189_when_full() {
        assertEquals(ErrorCodes.EXAM_PAPER_IMAGE_LIMIT_REACHED.getCode(),
                assertThrows(BusinessException.class, () -> validator.validateNotFull(8)).getCode());
        assertDoesNotThrow(() -> validator.validateNotFull(7));
    }
    // ==================== A5：文件头魔数校验 ====================

    @Test
    @DisplayName("A5：content-type 伪装成 image/png 的文本文件 → 1139（魔数不符）")
    void should_throw1139_when_disguisedTextFile() {
        MockMultipartFile fakePng = new MockMultipartFile("file", "fake.png", "image/png",
                "this is definitely not a png".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(ErrorCodes.FILE_TYPE_INVALID.getCode(),
                assertThrows(BusinessException.class,
                        () -> validator.validateSingleFile(fakePng)).getCode());

        MockMultipartFile fakeJpeg = new MockMultipartFile("file", "fake.jpg", "image/jpeg",
                new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34});
        assertEquals(ErrorCodes.FILE_TYPE_INVALID.getCode(),
                assertThrows(BusinessException.class,
                        () -> validator.validateSingleFile(fakeJpeg)).getCode());
    }

    @Test
    @DisplayName("A5：真实 PNG / JPEG 文件头 → 通过")
    void should_pass_when_magicMatches() {
        assertDoesNotThrow(() -> validator.validateSingleFile(png("real.png", 2048)));
        assertDoesNotThrow(() -> validator.validateSingleFile(jpeg("real.jpg", 2048)));
    }

    @Test
    @DisplayName("A5：过短文件（不足 3 字节）→ 1139")
    void should_throw1139_when_tooShort() {
        MockMultipartFile shortFile = new MockMultipartFile("file", "a.png", "image/png",
                new byte[]{(byte) 0x89});
        assertEquals(ErrorCodes.FILE_TYPE_INVALID.getCode(),
                assertThrows(BusinessException.class,
                        () -> validator.validateSingleFile(shortFile)).getCode());
    }
}
