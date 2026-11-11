package com.jiang.agent.impl;

import com.jiang.client.AgentScopeClient;
import com.jiang.common.util.AgentOutputValidator;
import com.jiang.dto.ExamParseContext;
import com.jiang.dto.ExamParseImageResult;
import com.jiang.dto.ParsedQuestion;
import com.jiang.dto.VisionImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 试卷视觉解析 Agent 测试（LLM 全 mock）
 *
 * <p>覆盖：Prompt 占位符替换、单图重试 2 次、非法题丢弃、选择题归一（约定 #25）、
 * 作答状态归一（answered 但空白 → blank）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamParseAgentImpl 视觉解析")
class ExamParseAgentImplTest {

    private static final VisionImage IMAGE =
            VisionImage.of("image/jpeg", "image-bytes".getBytes(StandardCharsets.UTF_8));

    private static final String VALID_JSON = """
            {"questions":[{
              "questionIndex": 1,
              "stem": "下列各数中，最小的数是（ ）",
              "formatType": "choice",
              "choices": [{"label":"A","text":"-3"},{"label":"B","text":"0"}],
              "correctAnswer": "A",
              "studentAnswer": "B",
              "answerStatus": "answered",
              "knowledgePointId": "kp-1",
              "difficulty": "basic",
              "explanation": "负数最小"
            }]}
            """;

    private AgentScopeClient agentScopeClient;

    private ExamParseAgentImpl agent;

    @BeforeEach
    void setUp() {
        agentScopeClient = mock(AgentScopeClient.class);
        agent = new ExamParseAgentImpl();
        ReflectionTestUtils.setField(agent, "agentScopeClient", agentScopeClient);
        ReflectionTestUtils.setField(agent, "agentOutputValidator", new AgentOutputValidator());
    }

    private ExamParseContext context(int imageSeq, int imageTotal) {
        return ExamParseContext.builder()
                .subjectName("数学")
                .gradeLabel("高一")
                .imageSeq(imageSeq)
                .imageTotal(imageTotal)
                .tenantId("default")
                .build();
    }

    @Test
    @DisplayName("Prompt 占位符全部替换，无残留 {xxx}")
    void should_replaceAllPlaceholders_when_buildPrompt() {
        String prompt = agent.buildPrompt(context(2, 3), "上次输出不合格");

        assertFalse(prompt.contains("{subject}"));
        assertFalse(prompt.contains("{grade}"));
        assertFalse(prompt.contains("{imageSeq}"));
        assertFalse(prompt.contains("{imageTotal}"));
        assertFalse(prompt.contains("{retryHint}"));
        assertTrue(prompt.contains("数学"));
        assertTrue(prompt.contains("高一"));
        assertTrue(prompt.contains("2"));
        assertTrue(prompt.contains("3"));
        assertTrue(prompt.contains("上次输出不合格"));
        assertFalse(prompt.matches("(?s).*\\{[a-zA-Z]+}.*"), "Prompt 不得残留未替换占位符: " + prompt);
    }

    @Test
    @DisplayName("首次输出合法 → 成功，retryCount=0，选择题答案归一为标签")
    void should_succeedOnFirstAttempt_when_outputValid() {
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(VALID_JSON);

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertTrue(result.isSuccess());
        assertEquals(0, result.getRetryCount());
        assertEquals(1, result.getQuestions().size());

        ParsedQuestion question = result.getQuestions().get(0);
        assertEquals("A", question.getCorrectAnswer());
        assertEquals("answered", question.getAnswerStatus());
        assertEquals("B", question.getStudentAnswer());
        assertEquals(2, question.getChoices().size());
        assertEquals("A", question.getChoices().get(0).get("label"));
    }

    @Test
    @DisplayName("首次输出非法 → 带 retryHint 重试一次后成功")
    void should_retryWithHint_when_firstOutputInvalid() {
        when(agentScopeClient.callVision(anyString(), anyList()))
                .thenReturn("这不是 JSON")
                .thenReturn(VALID_JSON);

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getRetryCount());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentScopeClient, times(2)).callVision(promptCaptor.capture(), anyList());
        assertTrue(promptCaptor.getAllValues().get(0).contains("重试提示：无"));
        assertTrue(promptCaptor.getAllValues().get(1).contains("不符合要求"),
                "第二次 Prompt 必须带上次失败原因");
    }

    @Test
    @DisplayName("连续 3 次输出非法 → 整图失败，retryCount=2，不抛异常")
    void should_failAfterTwoRetries_when_outputAlwaysInvalid() {
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn("{oops}");

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertFalse(result.isSuccess());
        assertNotNull(result.getFailureReason());
        assertEquals(2, result.getRetryCount());
        assertTrue(result.getQuestions().isEmpty());
        verify(agentScopeClient, times(3)).callVision(anyString(), anyList());
    }

    @Test
    @DisplayName("一题合法一题非法 → 只丢弃非法题（不重试整图）")
    void should_dropInvalidItem_when_partiallyValid() {
        String json = """
                {"questions":[
                  {"questionIndex":1,"stem":"题干一","formatType":"short","studentAnswer":"答案","answerStatus":"answered","knowledgePointId":"kp-1"},
                  {"questionIndex":2,"stem":"题干二","formatType":"essay","studentAnswer":"","answerStatus":"blank"}
                ]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(json);

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getQuestions().size());
        assertEquals(0, result.getRetryCount(), "单题非法不重试整图");
        verify(agentScopeClient, times(1)).callVision(anyString(), anyList());
    }

    @Test
    @DisplayName("模型标 answered 但作答为空 → 归一为 blank")
    void should_normalizeToBlank_when_answeredButEmpty() {
        String json = """
                {"questions":[{"questionIndex":1,"stem":"题干","formatType":"short",
                  "studentAnswer":"","answerStatus":"answered","knowledgePointId":""}]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(json);

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertEquals("blank", result.getQuestions().get(0).getAnswerStatus());
        assertEquals("", result.getQuestions().get(0).getStudentAnswer());
    }

    @Test
    @DisplayName("v4：解析直出的 knowledgePointId 被保留；超长/多 ID 归一为 null")
    void should_parseKnowledgePointId_andNormalizeIllegalValue() {
        String withKp = """
                {"questions":[{"questionIndex":1,"stem":"题干","formatType":"short",
                  "studentAnswer":"答案","answerStatus":"answered","knowledgePointId":"kp_math_001"}]}
                """;
        String illegalKp = """
                {"questions":[{"questionIndex":1,"stem":"题干","formatType":"short",
                  "studentAnswer":"答案","answerStatus":"answered","knowledgePointId":"kp_1,kp_2"}]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList()))
                .thenReturn(withKp)
                .thenReturn(illegalKp);

        assertEquals("kp_math_001", agent.parseImage(IMAGE, context(1, 1))
                .getQuestions().get(0).getKnowledgePointId());
        assertNull(agent.parseImage(IMAGE, context(1, 1))
                .getQuestions().get(0).getKnowledgePointId(), "多 ID 必须置空");
    }

    @Test
    @DisplayName("v4：Prompt 注入候选知识点区块（- ID | 名称），空清单输出固定文案")
    void should_injectKpCandidatesIntoPrompt() {
        com.jiang.dto.ExamParseContext withCandidates = context(1, 1);
        withCandidates.setKpCandidates(List.of(
                new com.jiang.dto.KpCandidate("kp-1", "集合与函数"),
                new com.jiang.dto.KpCandidate("kp-2", "导数的应用")));

        String prompt = agent.buildPrompt(withCandidates, "无");

        assertTrue(prompt.contains("- kp-1 | 集合与函数"));
        assertTrue(prompt.contains("- kp-2 | 导数的应用"));
        assertFalse(prompt.contains("{knowledgePoints}"));

        String emptyPrompt = agent.buildPrompt(context(1, 1), "无");
        assertTrue(emptyPrompt.contains("（本卷无候选知识点）"));
    }

    @Test
    @DisplayName("Markdown 代码块包裹的 JSON → 去围栏后正常解析")
    void should_stripCodeFence_when_outputWrapped() {
        when(agentScopeClient.callVision(anyString(), anyList()))
                .thenReturn("```json\n" + VALID_JSON + "\n```");

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getQuestions().size());
    }

    @Test
    @DisplayName("空 questions + imageWarning（封面页）→ 合法 0 题")
    void should_acceptEmptyQuestions_when_pageHasNoQuestion() {
        when(agentScopeClient.callVision(anyString(), anyList()))
                .thenReturn("{\"questions\":[],\"imageWarning\":\"本页为封面\"}");

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 8));

        assertTrue(result.isSuccess());
        assertTrue(result.getQuestions().isEmpty());
        assertEquals("本页为封面", result.getWarning());
        assertNull(result.getFailureReason());
    }

    @Test
    @DisplayName("stripCodeFence 只剥离围栏，不动裸 JSON")
    void should_keepRawJson_when_noFence() {
        assertEquals("{a:1}", ExamParseAgentImpl.stripCodeFence("{a:1}"));
        assertEquals("{\"a\":1}", ExamParseAgentImpl.stripCodeFence("```json\n{\"a\":1}\n```"));
    }
}

