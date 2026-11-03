package com.jiang.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 相似度检测器
 * 用于举一反三新题与已有题目的相似度比较
 * <p>
 * 算法：SimHash 海明距离快速筛选 + 字符 bigram 余弦相似度精确计算
 * Change: 2026-11-03_similar_question_generation
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Component
@Slf4j
public class SimilarityDetector {

    /** 相似度阈值（字符级粗筛，语义层由检查 Agent 兜底） */
    public static final double SIMILARITY_THRESHOLD = 0.6;

    /** SimHash 位数 */
    private static final int HASH_BITS = 64;

    /** SimHash 海明距离快速筛选阈值：> 10 直接返回低相似度 */
    private static final int HAMMING_DISTANCE_THRESHOLD = 10;

    /**
     * 检测新题与已有题目的最高相似度
     *
     * @param newQuestionContent 新题题干
     * @param existingContents   已有题目题干列表（题库原题 + 已生成新题）
     * @return 最高相似度值（0.0~1.0）
     */
    public double detectSimilarity(String newQuestionContent, java.util.List<String> existingContents) {
        if (newQuestionContent == null || newQuestionContent.isBlank()
                || existingContents == null || existingContents.isEmpty()) {
            return 0.0;
        }

        long newHash = simHash(newQuestionContent);
        double maxSimilarity = 0.0;

        for (String existing : existingContents) {
            if (existing == null || existing.isBlank()) {
                continue;
            }
            double similarity = combinedSimilarity(newHash, newQuestionContent, existing);
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
            }
        }

        return maxSimilarity;
    }

    /**
     * 计算文本的 SimHash（64 位）
     *
     * @param text 输入文本
     * @return SimHash 值
     */
    public long simHash(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }

        // 按字符 bigram 分词，计算每个 token 的 hash 并加权
        int[] vector = new int[HASH_BITS];
        String[] tokens = extractBigrams(text);

        for (String token : tokens) {
            long hash = token.hashCode() & 0xFFFFFFFFL;
            // 扩展为 64 位：高 32 位和低 32 位异或混合
            hash = hash | (hash << 32);

            for (int i = 0; i < HASH_BITS; i++) {
                if (((hash >> i) & 1) == 1) {
                    vector[i]++;
                } else {
                    vector[i]--;
                }
            }
        }

        // 合并为最终 hash
        long result = 0;
        for (int i = 0; i < HASH_BITS; i++) {
            if (vector[i] > 0) {
                result |= (1L << i);
            }
        }
        return result;
    }

    /**
     * 计算两段文本的余弦相似度（基于字符 bigram 词频向量）
     *
     * @param text1 文本 1
     * @param text2 文本 2
     * @return 余弦相似度（0.0~1.0）
     */
    public double cosineSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null || text1.isBlank() || text2.isBlank()) {
            return 0.0;
        }

        Map<String, Integer> freq1 = buildBigramFreq(text1);
        Map<String, Integer> freq2 = buildBigramFreq(text2);

        // 计算点积
        double dotProduct = 0.0;
        for (Map.Entry<String, Integer> entry : freq1.entrySet()) {
            Integer count2 = freq2.get(entry.getKey());
            if (count2 != null) {
                dotProduct += (double) entry.getValue() * count2;
            }
        }

        // 计算模
        double norm1 = 0.0;
        for (int v : freq1.values()) {
            norm1 += (double) v * v;
        }
        norm1 = Math.sqrt(norm1);

        double norm2 = 0.0;
        for (int v : freq2.values()) {
            norm2 += (double) v * v;
        }
        norm2 = Math.sqrt(norm2);

        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        return dotProduct / (norm1 * norm2);
    }

    /**
     * 综合相似度得分：SimHash 海明距离快速筛选 + 余弦相似度精确计算
     */
    public double combinedSimilarity(String text1, String text2) {
        long hash1 = simHash(text1);
        return combinedSimilarity(hash1, text1, text2);
    }

    private double combinedSimilarity(long hash1, String text1, String text2) {
        long hash2 = simHash(text2);
        int hammingDistance = Long.bitCount(hash1 ^ hash2);

        // SimHash 海明距离快速筛选：距离远 → 直接返回低相似度
        if (hammingDistance > HAMMING_DISTANCE_THRESHOLD) {
            // 海明距离 64 位中 > 10，粗略映射为低相似度
            return Math.max(0.0, 1.0 - (hammingDistance / 32.0));
        }

        // 海明距离较近 → 精确计算余弦相似度
        return cosineSimilarity(text1, text2);
    }

    /**
     * 计算 SimHash 字符串（用于 DB 存储）
     *
     * @param content 题干内容
     * @return SimHash 的十六进制字符串
     */
    public String computeContentHash(String content) {
        return Long.toHexString(simHash(content));
    }

    /**
     * 提取字符 bigram（中文分词简化方案）
     */
    private String[] extractBigrams(String text) {
        // 清理：去除空白和标点
        String cleaned = text.replaceAll("[\\s\\p{Punct}]", "").toLowerCase();
        if (cleaned.length() < 2) {
            return new String[]{cleaned};
        }

        String[] bigrams = new String[cleaned.length() - 1];
        for (int i = 0; i < cleaned.length() - 1; i++) {
            bigrams[i] = cleaned.substring(i, i + 2);
        }
        return bigrams;
    }

    /**
     * 构建 bigram 词频向量
     */
    private Map<String, Integer> buildBigramFreq(String text) {
        String[] bigrams = extractBigrams(text);
        Map<String, Integer> freq = new HashMap<>();
        for (String bigram : bigrams) {
            freq.merge(bigram, 1, Integer::sum);
        }
        return freq;
    }
}
