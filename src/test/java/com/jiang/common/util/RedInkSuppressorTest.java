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
 * 红笔抑制（Change 2026-11-17_exam_parse_reliability）
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@DisplayName("RedInkSuppressor 红笔抑制")
class RedInkSuppressorTest {

    private static byte[] whiteImage(int width, int height, int[][] coloredPoints) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int[] point : coloredPoints) {
            image.setRGB(point[0], point[1], point[2]);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static int pixel(byte[] png, int x, int y) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    @Test
    @DisplayName("红笔像素刷白、黑色/灰色不动，返回 PNG")
    void should_whitenRedOnly() throws Exception {
        int red = 0xDC1E1E;
        int black = 0x0A0A0A;
        int gray = 0x808080;
        byte[] source = whiteImage(20, 20, new int[][]{
                {1, 1, red}, {2, 2, black}, {3, 3, gray}});

        RedInkSuppressor.Result result = RedInkSuppressor.suppress(source, "image/png");

        assertEquals(1, result.removedPixels());
        assertEquals("image/png", result.mediaType());
        assertEquals(0xFFFFFF, pixel(result.bytes(), 1, 1), "红笔应被刷白");
        assertEquals(black, pixel(result.bytes(), 2, 2), "黑色印刷不能被改");
        assertEquals(gray, pixel(result.bytes(), 3, 3), "灰色不能被改");
    }

    @Test
    @DisplayName("无红笔（灰度扫描）→ 原样返回，不重编码")
    void should_returnSameBytes_when_noRed() throws Exception {
        byte[] source = whiteImage(10, 10, new int[][]{{1, 1, 0x333333}});
        RedInkSuppressor.Result result = RedInkSuppressor.suppress(source, "image/jpeg");
        assertEquals(0, result.removedPixels());
        assertEquals("image/jpeg", result.mediaType());
        assertSame(source, result.bytes());
    }

    @Test
    @DisplayName("非图片字节 → 原样返回，不抛异常")
    void should_fallback_when_notImage() {
        byte[] junk = {1, 2, 3};
        RedInkSuppressor.Result result = RedInkSuppressor.suppress(junk, "image/png");
        assertEquals(0, result.removedPixels());
        assertSame(junk, result.bytes());
    }

    @Test
    @DisplayName("红色判定：深红/暗红命中，黑/白/灰/暖色纸不命中")
    void should_judgeRedThreshold() {
        assertTrue(RedInkSuppressor.isRed(220, 40, 40));
        assertTrue(RedInkSuppressor.isRed(180, 90, 80));
        assertTrue(RedInkSuppressor.isRed(120, 40, 40), "暗红笔芯也应命中");
        assertTrue(RedInkSuppressor.isRed(80, 16, 16), "深红笔芯也应命中");
        assertFalse(RedInkSuppressor.isRed(40, 40, 40));
        assertFalse(RedInkSuppressor.isRed(255, 255, 255));
        assertFalse(RedInkSuppressor.isRed(220, 180, 180), "浅粉/肤色不算红笔");
        assertFalse(RedInkSuppressor.isRed(240, 220, 200), "暖色纸张不算红笔");
    }
}
