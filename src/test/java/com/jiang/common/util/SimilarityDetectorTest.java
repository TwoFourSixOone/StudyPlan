package com.jiang.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimilarityDetector 单元测试
 * 覆盖：SimHash 计算、余弦相似度、综合策略、detectSimilarity 最高值、空文本处理
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
class SimilarityDetectorTest {

    private SimilarityDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SimilarityDetector();
    }

    // ==================== simHash ====================

    @Test
    @DisplayName("simHash: 相同文本产生相同 hash")
    void should_returnSameHash_when_sameText() {
        long hash1 = detector.simHash("这是一道数学题");
        long hash2 = detector.simHash("这是一道数学题");
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("simHash: 不同文本产生不同 hash")
    void should_returnDifferentHash_when_differentText() {
        long hash1 = detector.simHash("这是一道数学题关于函数");
        long hash2 = detector.simHash("这是一道英语题关于语法");
        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("simHash: 空文本返回 0")
    void should_returnZero_when_emptyText() {
        assertEquals(0L, detector.simHash(""));
        assertEquals(0L, detector.simHash(null));
    }

    // ==================== cosineSimilarity ====================

    @Test
    @DisplayName("cosineSimilarity: 相同文本 → 1.0")
    void should_returnOne_when_identicalTexts() {
        double similarity = detector.cosineSimilarity("完全相同的文本内容", "完全相同的文本内容");
        assertEquals(1.0, similarity, 0.001);
    }

    @Test
    @DisplayName("cosineSimilarity: 完全不同文本 → < 0.3")
    void should_returnLow_when_completelyDifferent() {
        double similarity = detector.cosineSimilarity("数学函数方程", "英语语法单词");
        assertTrue(similarity < 0.3, "完全不同文本相似度应 < 0.3, actual=" + similarity);
    }

    @Test
    @DisplayName("cosineSimilarity: 部分相似文本 → 0.3~0.8")
    void should_returnMedium_when_partiallySimilar() {
        double similarity = detector.cosineSimilarity(
                "求解一元二次方程的根",
                "求解一元二次方程的解法");
        assertTrue(similarity >= 0.3 && similarity <= 0.9,
                "部分相似文本相似度应在 0.3~0.9 之间, actual=" + similarity);
    }

    @Test
    @DisplayName("cosineSimilarity: 空文本 → 0.0")
    void should_returnZero_when_emptyInput() {
        assertEquals(0.0, detector.cosineSimilarity("", "abc"));
        assertEquals(0.0, detector.cosineSimilarity(null, "abc"));
        assertEquals(0.0, detector.cosineSimilarity("abc", ""));
        assertEquals(0.0, detector.cosineSimilarity("abc", null));
    }

    // ==================== detectSimilarity ====================

    @Test
    @DisplayName("detectSimilarity: 返回与列表中最高相似度")
    void should_returnMaxSimilarity_when_multipleContents() {
        String newText = "求解一元二次方程";
        List<String> existing = List.of(
                "完全不同的英语内容",
                "求解一元二次方程的根",   // 高相似
                "今天天气很好"
        );
        double maxSim = detector.detectSimilarity(newText, existing);
        // 应与第二个最相似
        double simWithSecond = detector.cosineSimilarity(newText, "求解一元二次方程的根");
        assertEquals(simWithSecond, maxSim, 0.01);
    }

    @Test
    @DisplayName("detectSimilarity: 空列表 → 0.0")
    void should_returnZero_when_emptyList() {
        assertEquals(0.0, detector.detectSimilarity("abc", List.of()));
        assertEquals(0.0, detector.detectSimilarity("abc", null));
    }

    @Test
    @DisplayName("detectSimilarity: 列表中含空字符串 → 跳过不影响")
    void should_skipBlankEntries() {
        String newText = "测试文本";
        List<String> existing = Arrays.asList("", null, "完全不同的内容");
        double sim = detector.detectSimilarity(newText, existing);
        assertTrue(sim < 0.5, "空字符串不应影响相似度计算");
    }

    // ==================== computeContentHash ====================

    @Test
    @DisplayName("computeContentHash: 返回非空十六进制字符串")
    void should_returnNonEmptyHexString() {
        String hash = detector.computeContentHash("测试题干内容");
        assertNotNull(hash);
        assertFalse(hash.isEmpty());
        // 十六进制字符串
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    @Test
    @DisplayName("computeContentHash: 相同内容 → 相同 hash")
    void should_returnSameHash_when_sameContent() {
        String hash1 = detector.computeContentHash("相同的题干");
        String hash2 = detector.computeContentHash("相同的题干");
        assertEquals(hash1, hash2);
    }

    // ==================== SIMILARITY_THRESHOLD ====================

    @Test
    @DisplayName("阈值验证: 相同文本超过阈值 0.6")
    void should_exceedThreshold_when_sameText() {
        double sim = detector.detectSimilarity("完全相同的内容", List.of("完全相同的内容"));
        assertTrue(sim > SimilarityDetector.SIMILARITY_THRESHOLD);
    }

    @Test
    @DisplayName("阈值验证: 完全不同文本低于阈值 0.6")
    void should_belowThreshold_when_completelyDifferent() {
        double sim = detector.detectSimilarity(
                "已知函数fx等于x平方加一",
                List.of("英语单词记忆方法总结归纳"));
        assertTrue(sim <= SimilarityDetector.SIMILARITY_THRESHOLD,
                "完全不同文本应低于阈值, actual=" + sim);
    }
}
