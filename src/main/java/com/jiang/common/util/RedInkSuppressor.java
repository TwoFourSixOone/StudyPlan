package com.jiang.common.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 红笔抑制（2026-11-17，Change 2026-11-17_exam_parse_reliability）
 *
 * <p>OCR 只应读取印刷内容；老师批改常用红笔（题号左侧写正确答案、红笔解题过程、√/×）。
 * 直接把原图送 OCR 会把红笔内容混进【OCR 文本】，再被模型当成题干/学生答案。本工具在
 * <b>仅 OCR 通道</b>把红色像素刷白（纯视觉解析仍用原图，保留颜色用于作答归属）。</p>
 *
 * <p>无红笔（灰度/黑白扫描）时原样返回，不增加任何开销与画质损失；解码失败也原样返回。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
public final class RedInkSuppressor {

    private RedInkSuppressor() {
    }

    /**
     * 抑制结果
     *
     * @param bytes        处理后的图片字节（无红笔时与原图相同）
     * @param mediaType    处理后的媒体类型（刷白后统一为 image/png）
     * @param removedPixels 刷白的红色像素数（0 = 未处理）
     */
    public record Result(byte[] bytes, String mediaType, int removedPixels) {
    }

    /**
     * 把红色像素刷白（仅用于 OCR 输入）
     *
     * @param imageBytes 原始图片字节
     * @param mediaType  原始媒体类型
     * @return 处理结果；任何异常都回退原图
     */
    public static Result suppress(byte[] imageBytes, String mediaType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return new Result(imageBytes, mediaType, 0);
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return new Result(imageBytes, mediaType, 0);
            }
            int width = source.getWidth();
            int height = source.getHeight();
            BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }

            int[] pixels = new int[width * height];
            rgb.getRGB(0, 0, width, height, pixels, 0, width);
            int removed = 0;
            for (int index = 0; index < pixels.length; index++) {
                int pixel = pixels[index];
                int red = (pixel >> 16) & 0xFF;
                int green = (pixel >> 8) & 0xFF;
                int blue = pixel & 0xFF;
                if (isRed(red, green, blue)) {
                    pixels[index] = 0xFFFFFF;
                    removed++;
                }
            }
            if (removed == 0) {
                return new Result(imageBytes, mediaType, 0);
            }
            rgb.setRGB(0, 0, width, height, pixels, 0, width);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgb, "png", out);
            return new Result(out.toByteArray(), "image/png", removed);
        } catch (Exception e) {
            return new Result(imageBytes, mediaType, 0);
        }
    }

    /**
     * 红色判定：相对偏红（深红笔也命中），而非绝对亮度
     *
     * <p>实测红笔芯是暗红（如 112/48/48、80/16/16），只看 {@code red >= 140} 会漏掉；
     * 用「红通道明显高于绿/蓝」判色，暖色纸张（如 240/220/200）差值不足不会误伤。</p>
     */
    static boolean isRed(int red, int green, int blue) {
        return red >= 60 && red - green >= 45 && red - blue >= 45;
    }
}
