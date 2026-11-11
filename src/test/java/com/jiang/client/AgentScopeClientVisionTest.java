package com.jiang.client;

import com.jiang.common.exception.BusinessException;
import com.jiang.dto.VisionImage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AgentScopeClient 图文调用组装测试（mock LLM：只校验 Msg 结构，不发请求）
 *
 * <p>对应 design §3.1：每次调用 1 个 TextBlock + N 个 ImageBlock（Base64Source → data URL），
 * 文本路径结构零改动。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("AgentScopeClient 视觉/文本消息组装")
class AgentScopeClientVisionTest {

    private static final byte[] JPEG_BYTES = "fake-jpeg-bytes-for-vision".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("视觉消息 = 1 个 TextBlock + 1 个 ImageBlock，mediaType/base64 透传")
    void should_buildTextAndImageBlocks_when_callVision() {
        Msg msg = AgentScopeClient.buildVisionMessage("识别这张试卷",
                List.of(VisionImage.of("image/png", JPEG_BYTES)));

        List<ContentBlock> content = msg.getContent();
        assertEquals(2, content.size(), "应为 text + image 两个 content block");
        TextBlock textBlock = assertInstanceOf(TextBlock.class, content.get(0));
        assertEquals("识别这张试卷", textBlock.getText());

        ImageBlock imageBlock = assertInstanceOf(ImageBlock.class, content.get(1));
        Base64Source source = assertInstanceOf(Base64Source.class, imageBlock.getSource());
        assertEquals("image/png", source.getMediaType());
        assertEquals(Base64.getEncoder().encodeToString(JPEG_BYTES), source.getData(),
                "Base64Source.data 必须是纯 base64（不含 data URL 前缀）");
    }

    @Test
    @DisplayName("入参是 data URL 时剥离前缀后再内联")
    void should_stripDataUrlPrefix_when_buildingImage() {
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(JPEG_BYTES);
        VisionImage image = VisionImage.ofBase64("image/jpeg", dataUrl);

        assertArrayEquals(JPEG_BYTES, image.getBytes());

        Msg msg = AgentScopeClient.buildVisionMessage("p", List.of(image));
        ImageBlock imageBlock = assertInstanceOf(ImageBlock.class, msg.getContent().get(1));
        Base64Source source = assertInstanceOf(Base64Source.class, imageBlock.getSource());
        assertEquals(Base64.getEncoder().encodeToString(JPEG_BYTES), source.getData());
    }

    @Test
    @DisplayName("文本调用结构不回归：仍然只有一个 TextBlock")
    void should_keepSingleTextBlock_when_textCall() {
        Msg msg = AgentScopeClient.buildTextMessage("普通文本 Prompt");

        assertEquals(1, msg.getContent().size());
        TextBlock textBlock = assertInstanceOf(TextBlock.class, msg.getContent().get(0));
        assertEquals("普通文本 Prompt", textBlock.getText());
    }

    @Test
    @DisplayName("没有图片的视觉调用 → 业务异常（不发起请求）")
    void should_throw_when_imagesEmpty() {
        AgentScopeClient client = new AgentScopeClient();
        assertThrows(BusinessException.class, () -> client.callVision("p", List.of()));
        assertThrows(BusinessException.class, () -> client.callVision("p", null));
        assertThrows(BusinessException.class, () -> client.callVision(" ", List.of(VisionImage.of(null, JPEG_BYTES))));
    }

    @Test
    @DisplayName("空字节图片 → IllegalArgumentException（在构造处拦截）")
    void should_throw_when_imageBytesEmpty() {
        assertThrows(IllegalArgumentException.class, () -> VisionImage.of("image/jpeg", new byte[0]));
    }
}
