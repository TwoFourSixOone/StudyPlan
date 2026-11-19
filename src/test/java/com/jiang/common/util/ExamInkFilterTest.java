package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双版本派生图（Change 2026-11-17_exam_dual_channel_parse）
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@DisplayName("ExamInkFilter 双版本派生图")
class ExamInkFilterTest {

    private static final int RED = 0xC81E1E;
    private static final int BLUE = 0x1E3CC8;
    private static final int GREEN = 0x1EC81E;
    private static final int BLACK = 0x111111;

    private static byte[] image(int[][] points) throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        for (int[] point : points) {
            image.setRGB(point[0], point[1], point[2]);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static int pixel(byte[] png, int x, int y) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png)).getRGB(x, y) & 0xFFFFFF;
    }

    @Test
    @DisplayName("clean：红/蓝/绿刷白，黑色印刷保留")
    void should_cleanColoredInk() throws Exception {
        byte[] source = image(new int[][]{
                {1, 1, RED}, {2, 2, BLUE}, {3, 3, GREEN}, {4, 4, BLACK}});
        ExamInkFilter.Result clean = ExamInkFilter.clean(source, "image/png");

        assertTrue(clean.colored());
        assertEquals(3, clean.removedPixels());
        assertEquals(0xFFFFFF, pixel(clean.bytes(), 1, 1));
        assertEquals(0xFFFFFF, pixel(clean.bytes(), 2, 2));
        assertEquals(0xFFFFFF, pixel(clean.bytes(), 3, 3));
        assertEquals(BLACK, pixel(clean.bytes(), 4, 4), "印刷黑色不能动");
    }

    @Test
    @DisplayName("student：只去红，蓝/绿/黑保留")
    void should_studentKeepCoolInk() throws Exception {
        byte[] source = image(new int[][]{
                {1, 1, RED}, {2, 2, BLUE}, {3, 3, GREEN}, {4, 4, BLACK}});
        ExamInkFilter.Result student = ExamInkFilter.student(source, "image/png");

        assertEquals(1, student.removedPixels());
        assertEquals(0xFFFFFF, pixel(student.bytes(), 1, 1));
        assertEquals(BLUE, pixel(student.bytes(), 2, 2));
        assertEquals(GREEN, pixel(student.bytes(), 3, 3));
        assertEquals(BLACK, pixel(student.bytes(), 4, 4));
    }

    @Test
    @DisplayName("无彩色笔迹（黑笔同色卷）→ 原样返回，标记 prompt_only")
    void should_returnSameBytes_when_noColoredInk() throws Exception {
        byte[] source = image(new int[][]{{1, 1, BLACK}});
        ExamInkFilter.Result clean = ExamInkFilter.clean(source, "image/jpeg");
        ExamInkFilter.Result student = ExamInkFilter.student(source, "image/jpeg");

        assertFalse(clean.colored());
        assertFalse(student.colored());
        assertSame(source, clean.bytes());
        assertSame(source, student.bytes());
        assertEquals("image/jpeg", clean.mediaType());
    }

    @Test
    @DisplayName("颜色判定：蓝/绿命中，黑/白/暖纸不命中")
    void should_judgeCoolInk() {
        assertTrue(ExamInkFilter.isBlue(30, 60, 200));
        assertTrue(ExamInkFilter.isGreen(30, 200, 60));
        assertFalse(ExamInkFilter.isBlue(200, 30, 30));
        assertFalse(ExamInkFilter.isGreen(200, 30, 30));
        assertFalse(ExamInkFilter.isBlue(40, 40, 40));
        assertFalse(ExamInkFilter.isGreen(255, 255, 255));
        assertFalse(ExamInkFilter.isBlue(240, 230, 220));
    }

    @Test
    @DisplayName("非图片字节 → 原样返回，不抛异常")
    void should_fallback_when_notImage() {
        byte[] junk = {9, 8, 7};
        ExamInkFilter.Result result = ExamInkFilter.clean(junk, "image/png");
        assertFalse(result.colored());
        assertSame(junk, result.bytes());
    }

    @Test
    @DisplayName("removeCoolInk=false（彩色印刷较多的卷）→ 只去红，蓝/绿保留")
    void should_keepCoolInk_when_switchOff() throws Exception {
        byte[] source = image(new int[][]{
                {1, 1, RED}, {2, 2, BLUE}, {3, 3, GREEN}, {4, 4, BLACK}});
        ExamInkFilter.Result clean = ExamInkFilter.clean(source, "image/png", false);

        assertEquals(1, clean.removedPixels(), "只应刷白红色");
        assertEquals(0xFFFFFF, pixel(clean.bytes(), 1, 1));
        assertEquals(BLUE, pixel(clean.bytes(), 2, 2), "蓝色像素不能被刷白（可能是彩色印刷）");
        assertEquals(GREEN, pixel(clean.bytes(), 3, 3), "绿色像素不能被刷白（可能是彩色图表）");
        assertEquals(BLACK, pixel(clean.bytes(), 4, 4));
    }
}
