package com.jiang.service;

import com.jiang.agent.QuestionCheckAgent;
import com.jiang.agent.QuestionGenAgent;
import com.jiang.common.exception.AgentOutputInvalidException;
import com.jiang.common.exception.AgentTimeoutException;
import com.jiang.common.util.SimilarityDetector;
import com.jiang.mapper.AgentDegradationLogMapper;
import com.jiang.model.entity.AgentDegradationLog;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.QuestionBank;
import com.jiang.service.impl.SimilarQuestionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SimilarQuestionService 单元测试（T-08 集成场景）
 * <p>
 * 覆盖：循环重试、降级、BLOCK-7 超时、IMP-16 格式异常、IMP-9 防御性复制
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@ExtendWith(MockitoExtension.class)
class SimilarQuestionServiceTest {

    @Mock private QuestionGenAgent questionGenAgent;
    @Mock private QuestionCheckAgent questionCheckAgent;
    @Mock private SimilarityDetector similarityDetector;
    @Mock private AgentDegradationLogMapper degradationLogMapper;

    @InjectMocks
    private SimilarQuestionServiceImpl similarQuestionService;

    private static final Long STUDENT_ID = 1L;
    private static final String TENANT_ID = "default";
    private static final String KP_ID = "kp_001";
    private static final BigDecimal MASTERY = new BigDecimal("0.5");
    private static final String SOURCE_CONTENT = "{\"stem\":\"原题题干\"}";

    private QuestionBank sourceQuestion;

    @BeforeEach
    void setUp() {
        sourceQuestion = new QuestionBank();
        sourceQuestion.setQuestionId(1L);
        sourceQuestion.setKnowledgePointId(KP_ID);
        sourceQuestion.setContent(SOURCE_CONTENT);
        sourceQuestion.setAnswer("原题答案");
        sourceQuestion.setAnalysis("原题解析");
        sourceQuestion.setDifficulty("standard");
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("首次生成全部通过 → 返回所有新题")
    void should_returnAll_when_firstRoundAllPass() {
        List<GeneratedQuestion> candidates = buildCandidates(3);
        when(questionGenAgent.generateSimilar(eq(KP_ID), eq(MASTERY), eq(sourceQuestion), eq(3), eq("progressive")))
                .thenReturn(candidates);
        when(similarityDetector.detectSimilarity(anyString(), anyList())).thenReturn(0.2);
        when(questionCheckAgent.check(any(), eq(sourceQuestion)))
                .thenReturn(QuestionCheckAgent.CheckResult.pass());
        when(similarityDetector.computeContentHash(anyString())).thenReturn("abc123");

        List<GeneratedQuestion> result = similarQuestionService.generateWithValidation(
                STUDENT_ID, TENANT_ID, KP_ID, MASTERY, sourceQuestion,
                3, SOURCE_CONTENT, List.of());

        assertEquals(3, result.size());
        verify(degradationLogMapper, never()).insert(any());
    }

    @Test
    @DisplayName("第 1 轮部分失败 → 第 2 轮补充 → 全部通过")
    void should_retryAndPass_when_partialFailure() {
        List<GeneratedQuestion> round1 = buildCandidates(3);
        List<GeneratedQuestion> round2 = buildCandidates(1);

        when(questionGenAgent.generateSimilar(eq(KP_ID), eq(MASTERY), eq(sourceQuestion), anyInt(), eq("progressive")))
                .thenReturn(round1)
                .thenReturn(round2);

        // round1: 0,1 pass similarity, 2 fail; round2: 0 pass
        when(similarityDetector.detectSimilarity(anyString(), anyList()))
                .thenReturn(0.2, 0.2, 0.8)
                .thenReturn(0.2);
        when(questionCheckAgent.check(any(), eq(sourceQuestion)))
                .thenReturn(QuestionCheckAgent.CheckResult.pass());
        when(similarityDetector.computeContentHash(anyString())).thenReturn("hash");

        List<GeneratedQuestion> result = similarQuestionService.generateWithValidation(
                STUDENT_ID, TENANT_ID, KP_ID, MASTERY, sourceQuestion,
                3, SOURCE_CONTENT, new java.util.ArrayList<>(List.of()));

        assertEquals(3, result.size());
    }

    // ==================== 降级路径 ====================

    @Test
    @DisplayName("5 轮仍有失败 → 记录 1 条降级日志 + 返回空")
    void should_logDegradation_when_maxRetriesExhausted() {
        when(questionGenAgent.generateSimilar(anyString(), any(), any(), anyInt(), eq("progressive")))
                .thenReturn(buildCandidates(1));
        when(similarityDetector.detectSimilarity(anyString(), anyList())).thenReturn(0.9);
        when(degradationLogMapper.insert(any())).thenReturn(1);

        List<GeneratedQuestion> result = similarQuestionService.generateWithValidation(
                STUDENT_ID, TENANT_ID, KP_ID, MASTERY, sourceQuestion,
                1, SOURCE_CONTENT, List.of());

        assertTrue(result.isEmpty());
        verify(degradationLogMapper, times(1)).insert(any(AgentDegradationLog.class));
    }

    // ==================== BLOCK-7: Agent 超时降级 ====================

    @Test
    @DisplayName("BLOCK-7: 检查 Agent 超时 + 规则校验通过 → 新题放行")
    void should_passWhen_agentTimeoutAndRulesPass() {
        List<GeneratedQuestion> candidates = buildCandidates(1);
        when(questionGenAgent.generateSimilar(anyString(), any(), any(), anyInt(), eq("progressive")))
                .thenReturn(candidates);
        when(similarityDetector.detectSimilarity(anyString(), anyList())).thenReturn(0.2);
        when(questionCheckAgent.check(any(), eq(sourceQuestion)))
                .thenThrow(new AgentTimeoutException("Agent 调用超时"));
        when(questionCheckAgent.validateRulesOnly(any(), eq(sourceQuestion))).thenReturn(true);
        when(similarityDetector.computeContentHash(anyString())).thenReturn("hash");

        List<GeneratedQuestion> result = similarQuestionService.generateWithValidation(
                STUDENT_ID, TENANT_ID, KP_ID, MASTERY, sourceQuestion,
                1, SOURCE_CONTENT, List.of());

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getSimilarityChecked());
    }

    @Test
    @DisplayName("BLOCK-7: 检查 Agent 超时 + 规则校验失败 → 新题跳过")
    void should_skipWhen_agentTimeoutAndRulesFail() {
        List<GeneratedQuestion> candidates = buildCandidates(1);
        when(questionGenAgent.generateSimilar(anyString(), any(), any(), anyInt(), eq("progressive")))
                .thenReturn(candidates);
        when(similarityDetector.detectSimilarity(anyString(), anyList())).thenReturn(0.2);
        when(questionCheckAgent.check(any(), eq(sourceQuestion)))
                .thenThrow(new AgentTimeoutException("Agent 调用超时"));
        when(questionCheckAgent.validateRulesOnly(any(), eq(sourceQuestion))).thenReturn(false);
        when(degradationLogMapper.insert(any())).thenReturn(1);

        List<GeneratedQuestion> result = similarQuestionService.generateWithValidation(
                STUDENT_ID, TENANT_ID, KP_ID, MASTERY, sourceQuestion,
                1, SOURCE_CONTENT, List.of());

        assertTrue(result.isEmpty());
    }

    // ==================== IMP-16: Agent 输出格式异常 ====================

    @Test
    @DisplayName("IMP-16: 检查 Agent 输出格式异常 → 计入失败重试")
    void should_retryWhen_agentOutputInvalid() {
        List<GeneratedQuestion> round1 = buildCandidates(1);
        List<GeneratedQuestion> round2 = buildCandidates(1);

        // 第 1 轮返回 round1，第 2 轮返回 round2
        when(questionGenAgent.generateSimilar(eq(KP_ID), eq(MASTERY), eq(sourceQuestion), anyInt(), eq("progressive")))
                .thenReturn(round1)
                .thenReturn(round2);
        when(similarityDetector.detectSimilarity(anyString(), anyList())).thenReturn(0.2);
        // 第 1 轮：格式异常；第 2 轮：通过
        when(questionCheckAgent.check(any(), eq(sourceQuestion)))
                .thenThrow(new AgentOutputInvalidException("JSON 解析失败"))
                .thenReturn(QuestionCheckAgent.CheckResult.pass());
        when(similarityDetector.computeContentHash(anyString())).thenReturn("hash");

        List<GeneratedQuestion> result = similarQuestionService.generateWithValidation(
                STUDENT_ID, TENANT_ID, KP_ID, MASTERY, sourceQuestion,
                1, SOURCE_CONTENT, List.of());

        assertEquals(1, result.size());
    }

    // ==================== IMP-9: 防御性复制 ====================

    @Test
    @DisplayName("IMP-9: 传入不可变 List 不抛异常")
    void should_notThrow_when_immutableListInput() {
        when(questionGenAgent.generateSimilar(anyString(), any(), any(), anyInt(), eq("progressive")))
                .thenReturn(buildCandidates(1));
        when(similarityDetector.detectSimilarity(anyString(), anyList())).thenReturn(0.2);
        when(questionCheckAgent.check(any(), eq(sourceQuestion)))
                .thenReturn(QuestionCheckAgent.CheckResult.pass());
        when(similarityDetector.computeContentHash(anyString())).thenReturn("hash");

        // List.of() 返回不可变 List
        assertDoesNotThrow(() -> similarQuestionService.generateWithValidation(
                STUDENT_ID, TENANT_ID, KP_ID, MASTERY, sourceQuestion,
                1, SOURCE_CONTENT, List.of()));
    }

    // ==================== DETAIL-3: passed=true + reason 非空 ====================

    @Test
    @DisplayName("DETAIL-3: passed=true + reason 非空 → 以 passed 为准，放行")
    void should_passWhen_passedTrueWithReason() {
        List<GeneratedQuestion> candidates = buildCandidates(1);
        when(questionGenAgent.generateSimilar(anyString(), any(), any(), anyInt(), eq("progressive")))
                .thenReturn(candidates);
        when(similarityDetector.detectSimilarity(anyString(), anyList())).thenReturn(0.2);
        // Agent 返回 passed=true 但附带 reason（DETAIL-3：以 passed 为准）
        when(questionCheckAgent.check(any(), eq(sourceQuestion)))
                .thenReturn(QuestionCheckAgent.CheckResult.pass());
        when(similarityDetector.computeContentHash(anyString())).thenReturn("hash");

        List<GeneratedQuestion> result = similarQuestionService.generateWithValidation(
                STUDENT_ID, TENANT_ID, KP_ID, MASTERY, sourceQuestion,
                1, SOURCE_CONTENT, List.of());

        assertEquals(1, result.size());
    }

    // ==================== Helper ====================

    private List<GeneratedQuestion> buildCandidates(int count) {
        List<GeneratedQuestion> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            GeneratedQuestion gq = new GeneratedQuestion();
            gq.setKnowledgePointId(KP_ID);
            gq.setContent("{\"stem\":\"新题题干" + i + "\",\"answer\":\"答案\",\"formatType\":\"numeric\",\"difficulty\":\"standard\"}");
            gq.setDifficulty("standard");
            gq.setSimilarityChecked(0);
            candidates.add(gq);
        }
        return candidates;
    }
}
