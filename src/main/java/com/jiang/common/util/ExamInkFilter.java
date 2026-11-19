package com.jiang.common.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 试卷双版本派生图（2026-11-17，Change 2026-11-17_exam_dual_channel_parse）
 *
 * <ul>
 *   <li><b>clean</b>（印刷题目版）：刷白红/蓝/绿彩色笔迹，尽量只留印刷内容；</li>
 *   <li><b>student</b>（学生作答版）：只刷白红色（老师批改），保留学生黑/蓝笔迹。</li>
 * </ul>
 *
 * <p><b>已知边界 1</b>：黑笔手写与印刷同色，像素级无法分离——此时两版都等于原图，
 * 由两次调用的 Prompt 分别"只读印刷"/"只读手写"隔离（日志标 prompt_only）。</p>
 *
 * <p><b>已知边界 2</b>：蓝/绿像素无法区分「学生蓝笔作答」与「彩色印刷内容」（蓝表格线、绿图表、
 * 彩色题头）。默认（{@code removeCoolInk=true}）按笔迹处理，彩色印刷较多的卷可通过
 * {@code exam.parse.dual-channel.remove-cool-ink=false} 关闭，改为只去红 + Prompt 隔离。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
public final class ExamInkFilter {

    private ExamInkFilter() {
    }

    /**
     * 派生结果
     *
     * @param bytes         处理后的字节（无彩色像素时与原图相同）
     * @param mediaType     媒体类型（改写后统一 image/png）
     * @param removedPixels 刷白像素数（0 = 未处理）
     * @param colored       是否检测到彩色笔迹（false = prompt-only 隔离）
     */
    public record Result(byte[] bytes, String mediaType, int removedPixels, boolean colored) {
    }

    /**
     * 印刷题目版：去红/蓝/绿（默认行为）
     */
    public static Result clean(byte[] imageBytes, String mediaType) {
        return clean(imageBytes, mediaType, true);
    }

    /**
     * 印刷题目版：按开关决定是否连蓝/绿一起去掉
     *
     * @param removeCoolInk true = 去红/蓝/绿（默认）；false = 只去红（彩色印刷较多的卷）
     */
    public static Result clean(byte[] imageBytes, String mediaType, boolean removeCoolInk) {
        return filter(imageBytes, mediaType, removeCoolInk);
    }

    /**
     * 学生作答版：只去红（老师批改）
     */
    public static Result student(byte[] imageBytes, String mediaType) {
        return filter(imageBytes, mediaType, false);
    }

    private static Result filter(byte[] imageBytes, String mediaType, boolean removeCoolInk) {
        if (imageBytes == null || imageBytes.length == 0) {
            return new Result(imageBytes, mediaType, 0, false);
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return new Result(imageBytes, mediaType, 0, false);
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
                boolean colored = RedInkSuppressor.isRed(red, green, blue)
                        || (removeCoolInk && (isBlue(red, green, blue) || isGreen(red, green, blue)));
                if (colored) {
                    pixels[index] = 0xFFFFFF;
                    removed++;
                }
            }
            if (removed == 0) {
                return new Result(imageBytes, mediaType, 0, false);
            }
            rgb.setRGB(0, 0, width, height, pixels, 0, width);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgb, "png", out);
            return new Result(out.toByteArray(), "image/png", removed, true);
        } catch (Exception e) {
            return new Result(imageBytes, mediaType, 0, false);
        }
    }

    /** 蓝笔：蓝通道明显高于红/绿（容忍扫描偏色） */
    static boolean isBlue(int red, int green, int blue) {
        return blue >= 60 && blue - red >= 45 && blue - green >= 25;
    }

    /** 绿笔：绿通道明显高于红/蓝 */
    static boolean isGreen(int red, int green, int blue) {
        return green >= 60 && green - red >= 45 && green - blue >= 25;
    }
}
