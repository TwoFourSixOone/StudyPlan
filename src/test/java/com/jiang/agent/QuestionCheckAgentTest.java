package com.jiang.agent;

import com.jiang.client.AgentScopeClient;
import com.jiang.common.exception.AgentOutputInvalidException;
import com.jiang.common.exception.AgentTimeoutException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.AgentOutputValidator;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.QuestionBank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QuestionCheckAgent 单元测试
 * 覆盖：规则校验（项 1-4）、Agent 语义校验（项 5）、BLOCK-7 降级、IMP-16 输出格式异常、DETAIL-3
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@ExtendWith(MockitoExtension.class)
class QuestionCheckAgentTest {

    @Mock private AgentScopeClient agentScopeClient;
    @Mock private AgentOutputValidator validator;

    @InjectMocks
    private QuestionCheckAgent questionCheckAgent;

    private static final String KP_ID = "kp_001";
    private static final String VALID_CONTENT = "{\"stem\":\"新题干\",\"answer\":\"新答案\",\"formatType\":\"choice\"}";

    private QuestionBank sourceQuestion;

    @BeforeEach
    void setUp() {
        sourceQuestion = new QuestionBank();
        sourceQuestion.setQuestionId(1L);
        sourceQuestion.setKnowledgePointId(KP_ID);
        sourceQuestion.setContent("{\"stem\":\"原题干\"}");
        sourceQuestion.setAnswer("原答案");
        sourceQuestion.setAnalysis("原解析");
    }

    // ==================== 辅助方法 ====================

    private GeneratedQuestion buildQuestion(String content, String difficulty, String kpId) {
        GeneratedQuestion q = new GeneratedQuestion();
        q.setGeneratedQuestionId(100L);
        q.setContent(content);
        q.setDifficulty(difficulty);
        q.setKnowledgePointId(kpId);
        return q;
    }

    // ==================== 规则校验（validateRulesOnly） ====================

    @Nested
    @DisplayName("validateRulesOnly：规则校验（项 1-4）")
    class ValidateRulesOnlyTests {

        @Test
        @DisplayName("全部规则通过 → 返回 true")
        void should_pass_when_allRulesOk() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", KP_ID);
            assertTrue(questionCheckAgent.validateRulesOnly(q, sourceQuestion));
        }

        @Test
        @DisplayName("项 1：content JSON 解析失败 → 返回 false")
        void should_fail_when_contentInvalidJson() {
            GeneratedQuestion q = buildQuestion("not-json", "standard", KP_ID);
            assertFalse(questionCheckAgent.validateRulesOnly(q, sourceQuestion));
        }

        @Test
        @DisplayName("项 1：题干为空 → 返回 false")
        void should_fail_when_stemBlank() {
            GeneratedQuestion q = buildQuestion(
                    "{\"stem\":\"\",\"answer\":\"A\",\"formatType\":\"choice\"}", "standard", KP_ID);
            assertFalse(questionCheckAgent.validateRulesOnly(q, sourceQuestion));
        }

        @Test
        @DisplayName("项 1：答案为 null → 返回 false")
        void should_fail_when_answerNull() {
            GeneratedQuestion q = buildQuestion(
                    "{\"stem\":\"题干\",\"answer\":null,\"formatType\":\"choice\"}", "standard", KP_ID);
            assertFalse(questionCheckAgent.validateRulesOnly(q, sourceQuestion));
        }

        @Test
        @DisplayName("项 1：题型为空 → 返回 false")
        void should_fail_when_formatTypeBlank() {
            GeneratedQuestion q = buildQuestion(
                    "{\"stem\":\"题干\",\"answer\":\"A\",\"formatType\":\"\"}", "standard", KP_ID);
            assertFalse(questionCheckAgent.validateRulesOnly(q, sourceQuestion));
        }

        @Test
        @DisplayName("项 2：difficulty 为 null → 返回 false")
        void should_fail_when_difficultyNull() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, null, KP_ID);
            assertFalse(questionCheckAgent.validateRulesOnly(q, sourceQuestion));
        }

        @Test
        @DisplayName("项 2：difficulty 非法值 → 返回 false")
        void should_fail_when_difficultyInvalid() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "hard", KP_ID);
            assertFalse(questionCheckAgent.validateRulesOnly(q, sourceQuestion));
        }

        @Test
        @DisplayName("项 2：difficulty=basic → 通过")
        void should_pass_when_difficultyBasic() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "basic", KP_ID);
            assertTrue(questionCheckAgent.validateRulesOnly(q, sourceQuestion));
        }

        @Test
        @DisplayName("项 2：difficulty=challenge → 通过")
        void should_pass_when_difficultyChallenge() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "challenge", KP_ID);
            assertTrue(questionCheckAgent.validateRulesOnly(q, sourceQuestion));
        }

        @Test
        @DisplayName("项 3：知识点不匹配 → 返回 false")
        void should_fail_when_kpMismatch() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", "kp_999");
            assertFalse(questionCheckAgent.validateRulesOnly(q, sourceQuestion));
        }

        @Test
        @DisplayName("项 3：sourceQuestion 为 null → 跳过知识点检查")
        void should_pass_when_sourceQuestionNull() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", KP_ID);
            assertTrue(questionCheckAgent.validateRulesOnly(q, null));
        }
    }

    // ==================== check() 完整流程 ====================

    @Nested
    @DisplayName("check：规则校验 + Agent 语义校验")
    class CheckFullFlowTests {

        @Test
        @DisplayName("规则通过 + Agent passed=true → CheckResult.pass")
        void should_pass_when_rulesAndAgentPass() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", KP_ID);
            when(agentScopeClient.call(anyString()))
                    .thenReturn("{\"passed\":true,\"reason\":\"\"}");

            QuestionCheckAgent.CheckResult result = questionCheckAgent.check(q, sourceQuestion);
            assertTrue(result.isPassed());
            assertNull(result.getReason());
        }

        @Test
        @DisplayName("规则通过 + Agent passed=false → CheckResult.fail")
        void should_fail_when_agentRejects() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", KP_ID);
            when(agentScopeClient.call(anyString()))
                    .thenReturn("{\"passed\":false,\"reason\":\"解题思路完全相同\"}");

            QuestionCheckAgent.CheckResult result = questionCheckAgent.check(q, sourceQuestion);
            assertFalse(result.isPassed());
            assertEquals("解题思路完全相同", result.getReason());
        }

        @Test
        @DisplayName("规则失败 → 不调用 Agent，直接返回 fail")
        void should_failFast_when_rulesFail() {
            GeneratedQuestion q = buildQuestion("bad-json", "standard", KP_ID);

            QuestionCheckAgent.CheckResult result = questionCheckAgent.check(q, sourceQuestion);
            assertFalse(result.isPassed());
            verifyNoInteractions(agentScopeClient);
        }

        @Test
        @DisplayName("DETAIL-3：passed=true 时忽略 reason 字段")
        void should_ignoreReason_when_passed() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", KP_ID);
            when(agentScopeClient.call(anyString()))
                    .thenReturn("{\"passed\":true,\"reason\":\"some reason that should be ignored\"}");

            QuestionCheckAgent.CheckResult result = questionCheckAgent.check(q, sourceQuestion);
            assertTrue(result.isPassed());
            assertNull(result.getReason());
        }

        @Test
        @DisplayName("Agent passed=false 且 reason 为空 → 使用默认失败原因")
        void should_defaultReason_when_agentFailWithEmptyReason() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", KP_ID);
            when(agentScopeClient.call(anyString()))
                    .thenReturn("{\"passed\":false,\"reason\":\"\"}");

            QuestionCheckAgent.CheckResult result = questionCheckAgent.check(q, sourceQuestion);
            assertFalse(result.isPassed());
            assertEquals("Agent 判断实质相同", result.getReason());
        }
    }

    // ==================== IMP-16：Agent 输出格式异常 ====================

    @Nested
    @DisplayName("IMP-16：Agent 输出格式异常")
    class Imp16AgentOutputInvalidTests {

        @Test
        @DisplayName("Agent 输出缺少 passed 字段 → 抛 AgentOutputInvalidException")
        void should_throw_when_missingPassedField() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", KP_ID);
            when(agentScopeClient.call(anyString())).thenReturn("{\"reason\":\"some reason\"}");

            assertThrows(AgentOutputInvalidException.class,
                    () -> questionCheckAgent.check(q, sourceQuestion));
        }

        @Test
        @DisplayName("Agent 输出非合法 JSON → 抛 AgentOutputInvalidException")
        void should_throw_when_invalidJson() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", KP_ID);
            when(agentScopeClient.call(anyString())).thenReturn("not a json");

            assertThrows(AgentOutputInvalidException.class,
                    () -> questionCheckAgent.check(q, sourceQuestion));
        }
    }

    // ==================== Agent 超时 → AgentTimeoutException ====================

    @Nested
    @DisplayName("Agent 调用超时")
    class AgentTimeoutTests {

        @Test
        @DisplayName("AgentScopeClient 抛 AGENT_CALL_FAILED → 转为 AgentTimeoutException")
        void should_throwAgentTimeout_when_agentCallFailed() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", KP_ID);
            when(agentScopeClient.call(anyString()))
                    .thenThrow(new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "timeout"));

            assertThrows(AgentTimeoutException.class,
                    () -> questionCheckAgent.check(q, sourceQuestion));
        }

        @Test
        @DisplayName("AgentScopeClient 抛其他 BusinessException → 原样抛出")
        void should_rethrow_when_otherBusinessException() {
            GeneratedQuestion q = buildQuestion(VALID_CONTENT, "standard", KP_ID);
            when(agentScopeClient.call(anyString()))
                    .thenThrow(new BusinessException(ErrorCodes.INTERNAL_ERROR, "server error"));

            assertThrows(BusinessException.class,
                    () -> questionCheckAgent.check(q, sourceQuestion));
            assertDoesNotThrow(() -> {
                try {
                    questionCheckAgent.check(q, sourceQuestion);
                } catch (AgentTimeoutException e) {
                    fail("不应抛出 AgentTimeoutException");
                } catch (BusinessException e) {
                    // expected
                }
            });
        }
    }

    // ==================== CheckResult 工厂方法 ====================

    @Nested
    @DisplayName("CheckResult 静态工厂")
    class CheckResultTests {

        @Test
        @DisplayName("pass() → passed=true, reason=null")
        void should_createPassResult() {
            QuestionCheckAgent.CheckResult result = QuestionCheckAgent.CheckResult.pass();
            assertTrue(result.isPassed());
            assertNull(result.getReason());
        }

        @Test
        @DisplayName("fail(reason) → passed=false, reason=给定原因")
        void should_createFailResult() {
            QuestionCheckAgent.CheckResult result = QuestionCheckAgent.CheckResult.fail("测试原因");
            assertFalse(result.isPassed());
            assertEquals("测试原因", result.getReason());
        }
    }
}
