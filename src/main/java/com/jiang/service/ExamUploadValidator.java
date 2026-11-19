package com.jiang.service;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

/**
 * 试卷图片上传校验（design §2.3 / §13 错误码）
 *
 * <p>三段式上传的校验口径：</p>
 * <ul>
 *   <li>创建会话：声明张数 {@code imageCount ∈ [1,8]} → {@code 1182}（>8）/ {@code 1183}（<1 或空）</li>
 *   <li>逐张追加：空文件 {@code 1183} → 单张 > 9.5MB {@code 1184} → 类型非 jpg/jpeg/png {@code 1139}
 *       → 累计 > 30MB {@code 1185} → 已满 8 张 {@code 1189}</li>
 * </ul>
 *
 * <p>单张业务上限取 <b>9.5MB</b>（不是 10MB）：{@code spring.servlet.multipart.max-file-size: 10MB}
 * 会让"恰好 10MB"先被容器拒绝且不可映射，留 0.5MB 边界余量（design §2.3 S4/D18）。全部阈值走配置项
 * （代码内默认值，不改 application.yml）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Component
@Slf4j
public class ExamUploadValidator {

    /** 一次最多 8 张（产品规则） */
    @Value("${exam.parse.max-images:8}")
    private int maxImages = 8;

    /** 单张业务上限（MB） */
    @Value("${exam.parse.max-image-mb:9.5}")
    private double maxImageMb = 9.5;

    /** 同一份试卷图片累计上限（MB） */
    @Value("${exam.parse.max-total-mb:30}")
    private int maxTotalMb = 30;

    /** 长边下限（px，2026-11-17；≤0 关闭） */
    @Value("${exam.parse.min-long-side:600}")
    private int minLongSide = 600;

    /** 短边下限（px，2026-11-17；≤0 关闭） */
    @Value("${exam.parse.min-short-side:500}")
    private int minShortSide = 500;

    /**
     * 校验创建会话时的声明张数
     *
     * @param imageCount 声明张数
     * @throws BusinessException {@code 1182}（超出上限）/ {@code 1183}（未声明或小于 1）
     */
    public void validateImageCount(Integer imageCount) {
        if (imageCount == null || imageCount < 1) {
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_EMPTY);
        }
        if (imageCount > maxImages) {
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_COUNT_EXCEEDED);
        }
    }

    /**
     * 校验已上传张数是否已达上限
     *
     * @param uploadedCount 已上传张数
     * @throws BusinessException {@code 1189}
     */
    public void validateNotFull(int uploadedCount) {
        if (uploadedCount >= maxImages) {
            throw new BusinessException(ErrorCodes.EXAM_PAPER_IMAGE_LIMIT_REACHED);
        }
    }

    /**
     * 校验单张图片（空 / 单图大小 / 类型 / 魔数）
     *
     * <p>A5：只看 {@code Content-Type} 可被伪装（文本文件声明 image/png），必须补文件头魔数校验：
     * PNG {@code 89 50 4E 47} / JPEG {@code FF D8 FF}，不符 → {@code 1139}。</p>
     *
     * @throws BusinessException {@code 1183} / {@code 1184} / {@code 1139}
     */
    public void validateSingleFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_EMPTY);
        }
        long maxImageBytes = (long) (maxImageMb * 1024 * 1024);
        if (file.getSize() > maxImageBytes) {
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_TOO_LARGE);
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.matches("image/(jpeg|jpg|png)")) {
            throw new BusinessException(ErrorCodes.FILE_TYPE_INVALID);
        }
        validateMagicBytes(file);
        validateResolution(file);
    }

    /**
     * 分辨率门槛（2026-11-17）：长边/短边低于阈值 → 1193
     *
     * <p>只读图片头取宽高，不解码整图；读不出尺寸（非标准 JPEG/构造图）→ 放行 + WARN，
     * 不新增阻断点（魔数已挡住明显伪造，格式问题交给解析阶段暴露）。</p>
     */
    void validateResolution(MultipartFile file) {
        if (minLongSide <= 0 && minShortSide <= 0) {
            return;
        }
        int[] size = readImageSize(file);
        if (size == null) {
            log.warn("读取图片尺寸失败，按非标准图放行: name={}", file.getOriginalFilename());
            return;
        }
        int width = size[0];
        int height = size[1];
        int longSide = Math.max(width, height);
        int shortSide = Math.min(width, height);
        boolean tooLow = (minLongSide > 0 && longSide < minLongSide)
                || (minShortSide > 0 && shortSide < minShortSide);
        if (tooLow) {
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_RESOLUTION_TOO_LOW,
                    "图片分辨率过低（" + width + "×" + height + "），请上传原图或重新拍摄（长边≥"
                            + minLongSide + "px、短边≥" + minShortSide + "px）");
        }
    }

    private int[] readImageSize(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream();
             ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
            if (imageInputStream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInputStream, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("读取图片尺寸失败: {}", e.getMessage());
            return null;
        }
    }

    /** PNG 文件头：89 50 4E 47 0D 0A 1A 0A */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** JPEG 文件头：FF D8 FF */
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /**
     * 文件头魔数校验（A5）：只读前 8 字节，不把整张图读进内存
     *
     * @throws BusinessException {@code 1139}（不是真实的 PNG/JPEG）
     */
    void validateMagicBytes(MultipartFile file) {
        byte[] head = new byte[8];
        int read;
        try (InputStream inputStream = file.getInputStream()) {
            read = inputStream.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            log.warn("读取上传文件头失败，按类型非法处理: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.FILE_TYPE_INVALID);
        }
        boolean png = matches(head, read, PNG_MAGIC);
        boolean jpeg = matches(head, read, JPEG_MAGIC);
        if (!png && !jpeg) {
            throw new BusinessException(ErrorCodes.FILE_TYPE_INVALID);
        }
    }

    private boolean matches(byte[] head, int read, byte[] magic) {
        if (read < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验累计大小（已上传累计 + 本次文件）
     *
     * @throws BusinessException {@code 1185}
     */
    public void validateTotalBytes(long accumulatedBytes, long incomingBytes) {
        long maxTotalBytes = (long) maxTotalMb * 1024 * 1024;
        if (accumulatedBytes + incomingBytes > maxTotalBytes) {
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_TOTAL_TOO_LARGE);
        }
    }

    /**
     * 兼容批量校验（多文件一次上传时逐张 + 累计一起校验；主要供历史调用方/测试使用）
     */
    public void validate(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_EMPTY);
        }
        if (files.size() > maxImages) {
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_COUNT_EXCEEDED);
        }
        long totalBytes = 0L;
        for (MultipartFile file : files) {
            validateSingleFile(file);
            totalBytes += file.getSize();
        }
        validateTotalBytes(0L, totalBytes);
    }

    public int getMaxImages() {
        return maxImages;
    }

    public double getMaxImageMb() {
        return maxImageMb;
    }

    public int getMaxTotalMb() {
        return maxTotalMb;
    }
}
