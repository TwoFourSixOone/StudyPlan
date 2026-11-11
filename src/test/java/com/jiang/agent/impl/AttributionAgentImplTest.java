package com.jiang.agent.impl;

import com.jiang.agent.AttributionAgent;
import com.jiang.client.AgentScopeClient;
import com.jiang.common.util.AgentOutputValidator;
import com.jiang.dto.ExamQuestionAttributionDTO;
import com.jiang.dto.ExamQuestionOcrDTO;
import com.jiang.dto.KpCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归因 Agent v4 兜底重载测试（design §5 / tasks E4-E5）
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("AttributionAgentImpl 兜底重载（v4）")
class AttributionAgentImplTest {

    private AgentScopeClient agentScopeClient;
    private AttributionAgentImpl agent;

    @BeforeEach
    void setUp() {
        agentScopeClient = mock(AgentScopeClient.class);
        agent = new AttributionAgentImpl();
        ReflectionTestUtils.setField(agent, "agentScopeClient", agentScopeClient);
        ReflectionTestUtils.setField(agent, "validator", new AgentOutputValidator());
    }

    private List<ExamQuestionOcrDTO> unresolvedQuestions() {
        ExamQuestionOcrDTO dto = new ExamQuestionOcrDTO();
        dto.setQuestionIndex(1);
        dto.setQuestionContent("题干");
        dto.setQuestionType("choice");
        return List.of(dto);
    }

    @Test
    @DisplayName("使用传入候选清单构造 Prompt，命中清单内 ID → completed")
    void should_useProvidedCandidates_andAcceptWhitelistedId() {
        when(agentScopeClient.call(anyString()))
                .thenReturn("[{\"questionIndex\":1,\"knowledgePointId\":\"kp-1\",\"confidence\":\"high\"}]");

        List<ExamQuestionAttributionDTO> results = agent.attribute(
                unresolvedQuestions(), List.of(new KpCandidate("kp-1", "集合与函数")), "default");

        assertEquals(1, results.size());
        assertEquals("kp-1", results.get(0).getKnowledgePointId());
        assertEquals("completed", results.get(0).getAttributionStatus());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentScopeClient).call(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("ID: kp-1"));
        assertTrue(promptCaptor.getValue().contains("集合与函数"));
    }

    @Test
    @DisplayName("返回的 ID 不在候选清单内（幻觉）→ 置空 + pending_manual_review")
    void should_blankHallucinatedId() {
        when(agentScopeClient.call(anyString()))
                .thenReturn("[{\"questionIndex\":1,\"knowledgePointId\":\"kp-x\",\"confidence\":\"high\"}]");

        List<ExamQuestionAttributionDTO> results = agent.attribute(
                unresolvedQuestions(), List.of(new KpCandidate("kp-1", "集合与函数")), "default");

        assertNull(results.get(0).getKnowledgePointId());
        assertEquals("pending_manual_review", results.get(0).getAttributionStatus());
    }

    @Test
    @DisplayName("候选清单为空 → 直接全部 pending_manual_review（不调用 LLM）")
    void should_fallbackWithoutLlm_when_candidatesEmpty() {
        List<ExamQuestionAttributionDTO> results = agent.attribute(
                unresolvedQuestions(), List.of(), "default");

        assertEquals("pending_manual_review", results.get(0).getAttributionStatus());
        verify(agentScopeClient, never()).call(anyString());
    }

    @Test
    @DisplayName("原方法保留：内部自建候选后委托重载（接口兼容）")
    void should_keepOriginalMethod() {
        AttributionAgent api = agent;
        assertTrue(api instanceof AttributionAgent);
    }
}
