package com.jiang.common.util;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 图片降采样（2026-11-12：整卷单次解析时控制请求体大小）
 *
 * <p>一次把 8 张手机原图发给视觉模型，base64 后请求体可能超过网关限制；先按最大宽度缩放并重编码 JPEG。
 * 失败时返回原图（宁可让网关拒绝，也不要静默丢图）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-12
 */
@Slf4j
public final class ImageDownscaler {

    private ImageDownscaler() {
    }

    /**
     * 缩放并重编码为 JPEG
     *
     * @param source   原图字节
     * @param maxWidth 最大宽度（高度等比）
     * @param quality  JPEG 质量 0–1
     * @return 缩放后的字节；无法处理时返回原图
     */
    public static byte[] downscaleJpeg(byte[] source, int maxWidth, float quality) {
        if (source == null || source.length == 0) {
            return source;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                return source;
            }
            int width = image.getWidth();
            if (width <= maxWidth) {
                return source;
            }
            int height = Math.max(1, (int) Math.round(image.getHeight() * (maxWidth * 1.0 / width)));
            BufferedImage scaled = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(image, 0, 0, maxWidth, height, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(quality);
                }
                writer.write(null, new IIOImage(scaled, null, null), param);
            } finally {
                writer.dispose();
            }
            log.info("图片已降采样: {}x{} -> {}x{}, {}KB -> {}KB",
                    width, image.getHeight(), maxWidth, height,
                    source.length / 1024, out.size() / 1024);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("图片降采样失败，使用原图: {}", e.getMessage());
            return source;
        }
    }
}
