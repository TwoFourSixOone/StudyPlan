package com.jiang.common.util;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * 题目配图裁剪（2026-11-12）
 *
 * <p>视觉模型以归一化坐标给出图形区域（{@code x/y/width/height ∈ [0,1]}，相对来源图片），
 * 这里从原卷图片字节里裁出该区域并编码为 JPEG，供试卷详情页展示。</p>
 *
 * <p>鲁棒性约定：任何异常/区域过小/越界都返回 {@code null}，由调用方回退为"整页原图"，
 * 不允许因为裁剪失败影响解析落库。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-12
 */
@Slf4j
public final class ImageCropper {

    /** 裁剪时四周留白比例（避免把坐标轴/标注裁掉） */
    private static final double PADDING_RATIO = 0.03;

    /** 裁剪结果最小边长（像素），过小视为定位失败 */
    private static final int MIN_SIDE_PX = 40;

    private ImageCropper() {
    }

    /**
     * 按归一化区域裁剪并编码为 JPEG
     *
     * @param source 原图字节（jpg/png）
     * @param rect   归一化区域：x/y/width/height
     * @return JPEG 字节；失败返回 null
     */
    public static byte[] cropJpeg(byte[] source, Map<String, Double> rect) {
        if (source == null || source.length == 0 || rect == null || rect.isEmpty()) {
            return null;
        }
        Double rectX = rect.get("x");
        Double rectY = rect.get("y");
        Double rectW = rect.get("width");
        Double rectH = rect.get("height");
        if (rectX == null || rectY == null || rectW == null || rectH == null) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null || image.getWidth() < MIN_SIDE_PX || image.getHeight() < MIN_SIDE_PX) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();

            double x = clamp01(rectX) - rectW * PADDING_RATIO;
            double y = clamp01(rectY) - rectH * PADDING_RATIO;
            double w = clamp01(rectW) * (1 + 2 * PADDING_RATIO);
            double h = clamp01(rectH) * (1 + 2 * PADDING_RATIO);
            x = Math.max(0d, x);
            y = Math.max(0d, y);
            w = Math.min(w, 1d - x);
            h = Math.min(h, 1d - y);

            int px = (int) Math.floor(x * width);
            int py = (int) Math.floor(y * height);
            int pw = Math.min((int) Math.ceil(w * width), width - px);
            int ph = Math.min((int) Math.ceil(h * height), height - py);
            if (pw < MIN_SIDE_PX || ph < MIN_SIDE_PX) {
                log.warn("配图区域过小，放弃裁剪: px={}, py={}, pw={}, ph={}", px, py, pw, ph);
                return null;
            }

            BufferedImage sub = image.getSubimage(px, py, pw, ph);
            // JPEG 不支持透明通道：统一铺白底转 RGB
            BufferedImage rgb = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, pw, ph);
                graphics.drawImage(sub, 0, 0, null);
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(rgb, "jpg", out)) {
                log.warn("配图编码失败：无可用 JPEG writer");
                return null;
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("配图裁剪异常，按失败处理: {}", e.getMessage());
            return null;
        }
    }

    private static double clamp01(double value) {
        if (value < 0d) {
            return 0d;
        }
        return Math.min(value, 1d);
    }
}
