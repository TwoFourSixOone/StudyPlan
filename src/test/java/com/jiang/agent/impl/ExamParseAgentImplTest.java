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
    @DisplayName("2026-11-17：解析 paperNo / paperQuestionTotal；纯勾选答案按老师批改置 blank")
    void should_parsePaperNoAndTotal_andDropTickOnlyAnswer() {
        String json = """
                {"paperQuestionTotal":19,"questions":[{
                  "questionIndex": 1,
                  "paperNo": 2,
                  "stem": "设全集 U={1,2,3}，集合 A={1}，则 A 的补集为（ ）",
                  "formatType": "choice",
                  "choices": [{"label":"A","text":"{2,3}"},{"label":"B","text":"{1}"}],
                  "correctAnswer": "A",
                  "studentAnswer": "√",
                  "answerStatus": "answered",
                  "knowledgePointId": "kp-1"
                }]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(json);

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertEquals(19, result.getPaperQuestionTotal());
        assertEquals(0, result.getDroppedCount());
        ParsedQuestion question = result.getQuestions().get(0);
        assertEquals(2, question.getPaperNo());
        assertEquals("blank", question.getAnswerStatus(), "纯勾叉是老师批改，不是学生答案");
        assertEquals("", question.getStudentAnswer());
    }

    @Test
    @DisplayName("2026-11-17：非法题被丢弃时计入 droppedCount 与 warning")
    void should_countDroppedQuestions() {
        String json = """
                {"questions":[
                  {"questionIndex":1,"paperNo":1,"stem":"题干一","formatType":"short",
                   "studentAnswer":"","answerStatus":"blank","knowledgePointId":"kp-1"},
                  {"questionIndex":2,"stem":"题干二","formatType":"essay",
                   "studentAnswer":"","answerStatus":"blank","knowledgePointId":"kp-1"}
                ]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(json);

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertEquals(1, result.getDroppedCount());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning().contains("已丢弃 1 道"), result.getWarning());
    }

    @Test
    @DisplayName("2026-11-17 双版本：clean 调用强制 studentAnswer 空 + blank")
    void should_forceBlankInCleanPaper() {
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(VALID_JSON);

        ExamParseImageResult result = agent.parseCleanPaper(List.of(IMAGE),
                com.jiang.dto.ExamParsePaperContext.builder()
                        .subjectName("数学").gradeLabel("高三").imageTotal(1).tenantId("default")
                        .build());

        assertEquals(1, result.getQuestions().size());
        assertEquals("", result.getQuestions().get(0).getStudentAnswer());
        assertEquals("blank", result.getQuestions().get(0).getAnswerStatus());
    }

    @Test
    @DisplayName("2026-11-17 双版本：作答版按 paperNo 解析 answers")
    void should_parseStudentAnswers() {
        String json = """
                {"answers":[
                  {"paperNo":2,"studentAnswer":"C","answerStatus":"answered"},
                  {"paperNo":3,"studentAnswer":"","answerStatus":"blank"}
                ]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(json);

        com.jiang.dto.ExamAnswerParseResult result = agent.parseStudentAnswers(List.of(IMAGE),
                com.jiang.dto.ExamAnswerParseContext.builder()
                        .subjectName("数学").gradeLabel("高三").imageTotal(1).tenantId("default")
                        .questionDigest("- 第2题 | 题干：集合")
                        .build());

        assertTrue(result.isSuccess());
        assertEquals(2, result.getAnswers().size());
        assertEquals(2, result.getAnswers().get(0).getPaperNo());
        assertEquals("C", result.getAnswers().get(0).getStudentAnswer());
        assertEquals("blank", result.getAnswers().get(1).getAnswerStatus());
    }

    @Test
    @DisplayName("2026-11-17 双版本：非法作答被丢弃并计入 warning")
    void should_dropInvalidAnswers() {
        String json = """
                {"answers":[
                  {"paperNo":0,"studentAnswer":"C","answerStatus":"answered"},
                  {"paperNo":2,"studentAnswer":"D","answerStatus":"answered"}
                ]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(json);

        com.jiang.dto.ExamAnswerParseResult result = agent.parseStudentAnswers(List.of(IMAGE),
                com.jiang.dto.ExamAnswerParseContext.builder()
                        .subjectName("数学").gradeLabel("高三").imageTotal(1)
                        .questionDigest("- 第2题")
                        .build());

        assertTrue(result.isSuccess());
        assertEquals(1, result.getDroppedCount());
        assertEquals(1, result.getAnswers().size());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning().contains("已丢弃 1 条"), result.getWarning());
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
    @DisplayName("2026-11-17：题干里的 HTML 表格 → 受控 HTML 存库（前端 rich-text 渲染），纯文本按需派生")
    void should_keepControlledTableInStem() {
        String json = """
                {"questions":[{
                  "questionIndex": 1,
                  "paperNo": 19,
                  "stem": "为研究某疾病与超声波检查结果的关系，得到如下的列联表：<table><tr><td></td><td>正常</td><td>不正常</td><td>合计</td></tr><tr><td>患该疾病</td><td>180</td><td>20</td><td>200</td></tr><tr><td>未患该疾病</td><td>780</td><td>20</td><td>800</td></tr><tr><td>合计</td><td>960</td><td>40</td><td>1000</td></tr></table><script>bad()</script>(1) 求 p 的估计值；",
                  "formatType": "short",
                  "studentAnswer": "(1) p=0.9 (2) 有关",
                  "answerStatus": "answered",
                  "knowledgePointId": "kp-1"
                }]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(json);

        String stem = agent.parseImage(IMAGE, context(1, 1)).getQuestions().get(0).getStem();

        // 存储层：保留表格结构（供 rich-text 渲染），但脚本被清除
        assertTrue(stem.toLowerCase().contains("<table"), stem);
        assertFalse(stem.toLowerCase().contains("script"), stem);
        // 纯文本派生（判分入参 / 兼容字段）
        String plain = com.jiang.common.util.HtmlTableTextifier.textifyTablesOnly(stem);
        assertFalse(plain.toLowerCase().contains("<table"), plain);
        assertTrue(plain.contains("患该疾病：正常 180，不正常 20，合计 200"), plain);
        // 富文本（详情页 rich-text）
        String html = com.jiang.common.util.HtmlRichText.toRichText(stem);
        assertTrue(html.contains("<table style="), html);
        assertTrue(html.contains("(1) 求 p 的估计值"), html);
    }

    @Test
    @DisplayName("2026-11-17：标准答案与解析结论不一致 → 以解析为准修正 + warning")
    void should_alignCorrectAnswer_withExplanationConclusion() {
        String json = """
                {"questions":[{
                  "questionIndex": 9,
                  "paperNo": 9,
                  "stem": "关于三棱柱的线面关系，下列说法正确的是（ ）",
                  "formatType": "choice",
                  "choiceMode": "multi",
                  "choices": [{"label":"A","text":"AD⊥平面A₁BC"},{"label":"B","text":"AD⊥平面A₁BC₀"},
                              {"label":"C","text":"CC₁∥平面AAD"},{"label":"D","text":"CC₁∥平面AAD"}],
                  "correctAnswer": "AB",
                  "studentAnswer": "ABD",
                  "answerStatus": "answered",
                  "knowledgePointId": "kp-1",
                  "explanation": "第 1 步：A 对。\\n第 2 步：B 对。\\n第 3 步：C 错。\\n第 4 步：D 对。故选 ABD（按印刷选项修正为 ABD）。"
                }]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(json);

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertEquals("ABD", result.getQuestions().get(0).getCorrectAnswer(), "应按解析结论修正标准答案");
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning().contains("已按解析结论修正 1 道标准答案"), result.getWarning());
        assertEquals("multi", result.getQuestions().get(0).getChoiceMode(), "修正后应识别为多选");
    }

    @Test
    @DisplayName("2026-11-17：单选正确答案与解析一致 → 不修正、不产生告警")
    void should_notTouchAnswer_whenExplanationsAgree() {
        String json = """
                {"questions":[{
                  "questionIndex": 1,
                  "paperNo": 1,
                  "stem": "下列各数中最小的是（ ）",
                  "formatType": "choice",
                  "choices": [{"label":"A","text":"-3"},{"label":"B","text":"0"}],
                  "correctAnswer": "A",
                  "studentAnswer": "A",
                  "answerStatus": "answered",
                  "knowledgePointId": "kp-1",
                  "explanation": "第 1 步：-3 最小。故选 A。"
                }]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(json);

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertEquals("A", result.getQuestions().get(0).getCorrectAnswer());
        assertNull(result.getWarning(), "一致时不应产生告警");
    }

    @Test
    @DisplayName("stripCodeFence 只剥离围栏，不动裸 JSON")
    void should_keepRawJson_when_noFence() {
        assertEquals("{a:1}", ExamParseAgentImpl.stripCodeFence("{a:1}"));
        assertEquals("{\"a\":1}", ExamParseAgentImpl.stripCodeFence("```json\n{\"a\":1}\n```"));
    }

    @Test
    @DisplayName("2026-11-17：只给 paperNo、缺 questionIndex → 不丢题，questionIndex 取 paperNo")
    void should_fallBackToPaperNo_when_questionIndexMissing() {
        String json = """
                {"questions":[{
                  "paperNo": 17,
                  "stem": "已知数列，求通项",
                  "formatType": "short",
                  "studentAnswer": "",
                  "answerStatus": "blank",
                  "knowledgePointId": "kp-1"
                }]}
                """;
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn(json);

        ExamParseImageResult result = agent.parseImage(IMAGE, context(1, 1));

        assertEquals(0, result.getDroppedCount(), "缺 questionIndex 不应整题丢弃");
        assertEquals(1, result.getQuestions().size());
        assertEquals(17, result.getQuestions().get(0).getQuestionIndex());
        assertEquals(17, result.getQuestions().get(0).getPaperNo());
    }

    @Test
    @DisplayName("2026-11-17：作答版首次非法 → 带 retryHint 重试后成功（重试 2 次语义）")
    void should_retryStudentAnswers_when_firstOutputInvalid() {
        String invalid = "{\"answers\":[{\"paperNo\":1,\"studentAnswer\":\"C\"}]}";
        String valid = "{\"answers\":[{\"paperNo\":1,\"studentAnswer\":\"C\",\"answerStatus\":\"answered\"}]}";
        when(agentScopeClient.callVision(anyString(), anyList()))
                .thenReturn(invalid)
                .thenReturn(valid);

        com.jiang.dto.ExamAnswerParseResult result = agent.parseStudentAnswers(List.of(IMAGE),
                com.jiang.dto.ExamAnswerParseContext.builder()
                        .subjectName("数学").gradeLabel("高三").imageTotal(1).tenantId("default")
                        .questionDigest("- 第1题 | 题干：集合")
                        .build());

        assertTrue(result.isSuccess(), String.valueOf(result.getFailureReason()));
        assertEquals(1, result.getRetryCount());
        assertEquals(1, result.getAnswers().size());
        verify(agentScopeClient, times(2)).callVision(anyString(), anyList());
    }

    @Test
    @DisplayName("2026-11-17：作答版返回空 answers → 重试耗尽后失败（不能当成功）")
    void should_failStudentAnswers_when_answersAlwaysEmpty() {
        when(agentScopeClient.callVision(anyString(), anyList())).thenReturn("{\"answers\":[]}");

        com.jiang.dto.ExamAnswerParseResult result = agent.parseStudentAnswers(List.of(IMAGE),
                com.jiang.dto.ExamAnswerParseContext.builder()
                        .subjectName("数学").gradeLabel("高三").imageTotal(1)
                        .questionDigest("- 第1题")
                        .build());

        assertFalse(result.isSuccess());
        assertNotNull(result.getFailureReason());
        assertTrue(result.getAnswers().isEmpty());
    }
}

