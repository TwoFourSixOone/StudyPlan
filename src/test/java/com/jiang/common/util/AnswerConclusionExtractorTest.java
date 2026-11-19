package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 解析结论 → 标准答案兜底抽取（2026-11-17）
 *
 * <p>回归用例来自真实卷：试卷详情页出现「标准答案 AB / 解析写故选 ABD → 学生答 ABD 被判错」。</p>
 */
@DisplayName("AnswerConclusionExtractor 解析结论抽取")
class AnswerConclusionExtractorTest {

    @Test
    @DisplayName("真实卷单选：'第 4 步：故 f(-3/4)=-1/2，选 A。' → A（弱引导词「选」+ 结论区）")
    void should_extractSingleChoiceConclusion_withWeakPhrase() {
        String explanation = "第 1 步：f 为偶函数，f(-3/4)=f(3/4)。\n"
                + "第 2 步：周期为 2，f(3/4)=f(3/4+2)=f(11/4)。\n"
                + "第 3 步：11/4∈[2,3]，f(11/4)=5-2×11/4=5-11/2=-1/2。\n"
                + "第 4 步：故 f(-3/4)=-1/2，选 A。";
        assertEquals("A", AnswerConclusionExtractor.extractChoiceAnswer(explanation));
    }

    @Test
    @DisplayName("干扰项分析不算结论：'若选 A 则…；故选 C' → C")
    void should_ignoreDistractorAnalysis() {
        assertEquals("C", AnswerConclusionExtractor.extractChoiceAnswer("若选 A 则 B 错，故选 C。"));
        assertEquals("D", AnswerConclusionExtractor.extractChoiceAnswer("选 A、B 都错，故选 D。"));
        assertEquals("AB", AnswerConclusionExtractor.extractChoiceAnswer("选 A、B 都正确，故选 AB。"));
    }

    @Test
    @DisplayName("结论在末尾而前文有别的引导词 → 取末尾（结论区优先）")
    void should_preferConclusionTail() {
        String explanation = "第 1 步：答案为 B 的说法不成立。\n第 2 步：由题意逐项验证。\n"
                + "第 3 步：C 错。\n第 4 步：故最终选 D。";
        assertEquals("D", AnswerConclusionExtractor.extractChoiceAnswer(explanation));
    }

    @Test
    @DisplayName("多选：'故选 ABD' → ABD（真实卷复现）")
    void should_extractMultiChoiceConclusion() {
        String explanation = "第 1 步：A 对。\n第 2 步：B 对。\n第 3 步：C 错。\n第 4 步：D 对。"
                + "故选 ABD（按印刷选项修正为 ABD）。";
        assertEquals("ABD", AnswerConclusionExtractor.extractChoiceAnswer(explanation));
    }

    @Test
    @DisplayName("常见引导词：故选 / 应选 / 答案为 / 正确答案是 / 正确选项：（含小写与空格）")
    void should_extractCommonPhrases() {
        assertEquals("C", AnswerConclusionExtractor.extractChoiceAnswer("分析后应选 C。"));
        assertEquals("AB", AnswerConclusionExtractor.extractChoiceAnswer("所以答案为 AB"));
        assertEquals("D", AnswerConclusionExtractor.extractChoiceAnswer("综上，正确答案是 D。"));
        assertEquals("BD", AnswerConclusionExtractor.extractChoiceAnswer("正确选项：BD"));
        assertEquals("AC", AnswerConclusionExtractor.extractChoiceAnswer("故选择 ac"));
    }

    @Test
    @DisplayName("多个结论词 → 取最后一次（结论通常在末尾）")
    void should_takeLastConclusion() {
        String explanation = "若误选 A 则不正确。故选 C。\n（重新核对后）故选 D。";
        assertEquals("D", AnswerConclusionExtractor.extractChoiceAnswer(explanation));
    }

    @Test
    @DisplayName("标签去重升序：'故选 DBA' → ABD")
    void should_normalizeOrder() {
        assertEquals("ABD", AnswerConclusionExtractor.extractChoiceAnswer("故选 DBA"));
    }

    @Test
    @DisplayName("没有结论词 / 空文本 → null（不改动原答案）")
    void should_returnNull_whenNoConclusion() {
        assertNull(AnswerConclusionExtractor.extractChoiceAnswer(null));
        assertNull(AnswerConclusionExtractor.extractChoiceAnswer("   "));
        assertNull(AnswerConclusionExtractor.extractChoiceAnswer("第 1 步：由勾股定理求得多项式值为 12。"));
        assertNull(AnswerConclusionExtractor.extractChoiceAnswer("答案显然正确，但没有给出选项。"));
    }
}
