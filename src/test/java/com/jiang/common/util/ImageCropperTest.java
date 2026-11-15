package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题目配图裁剪测试（2026-11-12）
 */
@DisplayName("ImageCropper 配图裁剪")
class ImageCropperTest {

    private byte[] sourcePng() throws Exception {
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 400, 200);
            g.setColor(Color.RED);
            g.fillRect(200, 40, 120, 80);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("正常区域 → 返回 JPEG 字节（FF D8 开头）")
    void should_cropToJpeg() throws Exception {
        byte[] result = ImageCropper.cropJpeg(sourcePng(),
                Map.of("x", 0.5, "y", 0.2, "width", 0.3, "height", 0.4));
        assertNotNull(result);
        assertTrue(result.length > 100);
        assertTrue((result[0] & 0xFF) == 0xFF && (result[1] & 0xFF) == 0xD8, "应为 JPEG 魔数");
    }

    @Test
    @DisplayName("区域过小 / 越界 / 非法坐标 → null（回退整页原图）")
    void should_returnNull_forInvalidRect() throws Exception {
        byte[] source = sourcePng();
        assertNull(ImageCropper.cropJpeg(source, Map.of("x", 0.1, "y", 0.1, "width", 0.01, "height", 0.01)));
        assertNull(ImageCropper.cropJpeg(source, Map.of("x", 1.5, "y", 0.1, "width", 0.2, "height", 0.2)));
        assertNull(ImageCropper.cropJpeg(source, Map.of("x", 0.1, "y", 0.1)));
        assertNull(ImageCropper.cropJpeg(null, Map.of("x", 0.1, "y", 0.1, "width", 0.2, "height", 0.2)));
        assertNull(ImageCropper.cropJpeg(source, null));
    }

    @Test
    @DisplayName("非图片字节 → null（不抛异常）")
    void should_returnNull_forBrokenImage() {
        assertNull(ImageCropper.cropJpeg(new byte[]{1, 2, 3},
                Map.of("x", 0.1, "y", 0.1, "width", 0.5, "height", 0.5)));
    }
}
