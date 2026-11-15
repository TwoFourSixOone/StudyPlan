package com.jiang.service;

import com.jiang.client.AgentScopeClient;
import com.jiang.dto.GradeRequest;
import com.jiang.service.impl.AnswerGradingServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判分公共组件测试（design D5 / §6.1 S3 契约表逐行断言）
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("AnswerGradingService 判分口径")
class AnswerGradingServiceTest {

    private static final String SUBJECT = "数学";
    private static final String GRADE = "高一";

    private AgentScopeClient agentScopeClient;
    private ThreadPoolExecutor executor;
    private AnswerGradingServiceImpl service;

    @BeforeEach
    void setUp() {
        agentScopeClient = mock(AgentScopeClient.class);
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        service = new AnswerGradingServiceImpl();
        ReflectionTestUtils.setField(service, "agentScopeClient", agentScopeClient);
        ReflectionTestUtils.setField(service, "gradingExecutor", executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private Boolean grade(String formatType, String correctAnswer, String studentAnswer, String answerStatus) {
        return service.grade(formatType, "题干", correctAnswer, studentAnswer, answerStatus, SUBJECT, GRADE);
    }

    @Nested
    @DisplayName("choice")
    class ChoiceTests {

        @Test
        @DisplayName("忽略大小写与首尾空格")
        void should_compareCaseInsensitiveAndTrimmed() {
            assertTrue(grade("choice", "A", " a ", null));
            assertFalse(grade("choice", "A", "B", null));
        }

        @Test
        @DisplayName("多选题：标签集合相等 → 对（顺序无关）")
        void should_gradeMultiChoiceBySetEquality() {
            assertTrue(grade("choice", "ABD", "ABD", null));
            assertTrue(grade("choice", "ABD", "dba", null));
            assertTrue(grade("choice", "ABD", "A、B、D", null));
        }

        @Test
        @DisplayName("多选题：少选/多选都算错（严格集合相等，产品规则 #4）")
        void should_failMultiChoice_when_subsetOrSuperset() {
            assertFalse(grade("choice", "ABD", "AB", null));
            assertFalse(grade("choice", "ABD", "ABCD", null));
            assertFalse(grade("choice", "AB", "A", null));
        }

        @Test
        @DisplayName("v4b：choice 缺标准答案 → null（不可判定，不判错）")
        void should_returnNull_when_choiceAnswerMissing() {
            assertNull(grade("choice", null, "A", null));
            assertNull(grade("choice", "  ", "A", null));
        }

        @Test
        @DisplayName("v4b：choice 学生空 + 答案有 → false（未作答判错）")
        void should_returnFalse_when_choiceStudentAnswerBlank() {
            assertFalse(grade("choice", "A", "", null));
            assertFalse(grade("choice", "A", null, null));
        }
    }

    @Nested
    @DisplayName("numeric/short/self")
    class AiGradingTests {

        @Test
        @DisplayName("AI 返回「正确」→ true")
        void should_returnTrue_when_aiSaysCorrect() {
            when(agentScopeClient.call(anyString())).thenReturn("正确");
            assertTrue(grade("short", "2", "2", null));
        }

        @Test
        @DisplayName("AI 返回「错误」→ false")
        void should_returnFalse_when_aiSaysWrong() {
            when(agentScopeClient.call(anyString())).thenReturn("错误");
            assertFalse(grade("short", "2", "3", null));
        }

        @Test
        @DisplayName("AI 返回不可解析 → 回退字符串比较")
        void should_fallbackToStringCompare_when_aiUnparseable() {
            when(agentScopeClient.call(anyString())).thenReturn("无法判断");
            assertTrue(grade("short", "2", "2", null));
            assertFalse(grade("short", "2", "3", null));
        }

        @Test
        @DisplayName("AI 异常 → 回退字符串比较")
        void should_fallbackToStringCompare_when_aiThrows() {
            when(agentScopeClient.call(anyString())).thenThrow(new RuntimeException("timeout"));
            assertFalse(grade("short", "2", "3", null));
        }

        @Test
        @DisplayName("v4b：非 choice 缺标准答案 → null（且不调用 AI）")
        void should_returnNull_when_nonChoiceAnswerMissing() {
            assertNull(grade("short", null, "2", null));
            assertNull(grade("short", "  ", "2", null));
            org.mockito.Mockito.verifyNoInteractions(agentScopeClient);
        }

        @Test
        @DisplayName("v4b：学生空 + 答案空 → null（双空不可判定）")
        void should_returnNull_when_bothMissing() {
            assertNull(grade("short", null, null, null));
            assertNull(grade("choice", "", "", null));
        }
    }

    @Nested
    @DisplayName("无法判定 / 未作答")
    class UnreadableAndBlankTests {

        @Test
        @DisplayName("unreadable → null（不调用 AI）")
        void should_returnNull_when_unreadable() {
            assertNull(grade("short", "2", "涂改", "unreadable"));
            assertNull(grade("choice", "A", "涂改", "unreadable"));
        }

        @Test
        @DisplayName("blank / 空白作答 → false（未作答按答错，不耗 AI）")
        void should_returnFalse_when_blank() {
            assertFalse(grade("short", "2", "", "blank"));
            assertFalse(grade("short", "2", null, null));
        }
    }

    @Nested
    @DisplayName("gradeBatch")
    class BatchTests {

        @Test
        @DisplayName("choice/空白/unreadable 同步处理，混合批次结果正确")
        void should_handleSyncCasesWithoutAi() {
            when(agentScopeClient.call(anyString())).thenReturn("正确");
            List<GradeRequest> requests = List.of(
                    GradeRequest.builder().key(0).formatType("choice")
                            .correctAnswer("A").studentAnswer("a").build(),
                    GradeRequest.builder().key(1).formatType("choice")
                            .correctAnswer("A").studentAnswer("B").build(),
                    GradeRequest.builder().key(2).formatType("short")
                            .correctAnswer("2").studentAnswer("").build(),
                    GradeRequest.builder().key(3).formatType("short")
                            .correctAnswer("2").studentAnswer("涂改").answerStatus("unreadable").build(),
                    GradeRequest.builder().key(4).formatType("short").questionContent("题干")
                            .correctAnswer("2").studentAnswer("2").build());

            Map<Integer, Boolean> result = service.gradeBatch(requests, SUBJECT, GRADE,
                    Duration.ofSeconds(5), 3);

            assertEquals(5, result.size());
            assertEquals(Boolean.TRUE, result.get(0));
            assertEquals(Boolean.FALSE, result.get(1));
            assertEquals(Boolean.FALSE, result.get(2));
            assertNull(result.get(3));
            assertEquals(Boolean.TRUE, result.get(4));
        }

        @Test
        @DisplayName("预算内未完成 → null（待人工判定）")
        void should_returnNull_when_budgetExhausted() {
            when(agentScopeClient.call(anyString())).thenAnswer(invocation -> {
                Thread.sleep(800L);
                return "正确";
            });
            List<GradeRequest> requests = List.of(
                    GradeRequest.builder().key(0).formatType("short").questionContent("题干")
                            .correctAnswer("2").studentAnswer("2").build());

            long start = System.nanoTime();
            Map<Integer, Boolean> result = service.gradeBatch(requests, SUBJECT, GRADE,
                    Duration.ofMillis(80), 3);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

            assertNull(result.get(0));
            assertTrue(elapsedMillis < 700, "批处理必须在预算附近收尾，实际 " + elapsedMillis + "ms");
        }

        @Test
        @DisplayName("v4b 三态批量：缺答案 → null、双空 → null、学生空+答案有 → false")
        void should_applyThreeStateInBatch() {
            List<GradeRequest> requests = List.of(
                    GradeRequest.builder().key(0).formatType("choice")
                            .correctAnswer("").studentAnswer("A").build(),
                    GradeRequest.builder().key(1).formatType("short")
                            .correctAnswer("").studentAnswer("").build(),
                    GradeRequest.builder().key(2).formatType("short")
                            .correctAnswer("2").studentAnswer("").build());

            Map<Integer, Boolean> result = service.gradeBatch(requests, SUBJECT, GRADE,
                    Duration.ofSeconds(5), 3);

            assertNull(result.get(0), "choice 有作答无答案 → null");
            assertNull(result.get(1), "双空 → null");
            assertEquals(Boolean.FALSE, result.get(2), "学生空+答案有 → false");
            org.mockito.Mockito.verifyNoInteractions(agentScopeClient);
        }

        @Test
        @DisplayName("空列表 → 空结果")
        void should_returnEmpty_when_noRequests() {
            assertTrue(service.gradeBatch(List.of(), SUBJECT, GRADE, Duration.ofSeconds(1), 3).isEmpty());
        }

        // ==================== A1 / A2 评审修复 ====================

        private GradeRequest nonChoice(int key, String studentAnswer) {
            return GradeRequest.builder().key(key).formatType("short")
                    .questionContent("题干" + key).correctAnswer("标准答案")
                    .studentAnswer(studentAnswer).build();
        }

        @Test
        @DisplayName("A1：预算到点后才完成（宽限内）的判分结果仍落库，不被丢弃成 null")
        void should_keepCompletedResult_when_budgetReached() {
            when(agentScopeClient.call(anyString())).thenAnswer(invocation -> {
                String prompt = invocation.getArgument(0);
                if (prompt.contains("慢作答")) {
                    Thread.sleep(300L);
                }
                return "正确";
            });
            List<GradeRequest> requests = List.of(
                    nonChoice(0, "快作答"), nonChoice(1, "慢作答"), nonChoice(2, "快作答"));

            Map<Integer, Boolean> result = service.gradeBatch(
                    requests, SUBJECT, GRADE, Duration.ofMillis(100), 3);

            assertEquals(3, result.size());
            assertEquals(Boolean.TRUE, result.get(0));
            assertEquals(Boolean.TRUE, result.get(1), "预算到点但已完成的题必须保留结果（design §3.5）");
            assertEquals(Boolean.TRUE, result.get(2));
        }

        @Test
        @DisplayName("A2：40 道非选择题分批提交，全部在预算内判定（不触发 CallerRunsPolicy 串行）")
        void should_batchSubmit_when_40NonChoiceQuestions() {
            when(agentScopeClient.call(anyString())).thenReturn("正确");
            List<GradeRequest> requests = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                requests.add(nonChoice(i, "答案" + i));
            }

            long start = System.nanoTime();
            Map<Integer, Boolean> result = service.gradeBatch(
                    requests, SUBJECT, GRADE, Duration.ofSeconds(60), 3);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

            assertEquals(40, result.size());
            assertTrue(result.values().stream().allMatch(Boolean.TRUE::equals),
                    "40 道题都应判定为 true，实际 null 数="
                            + result.values().stream().filter(v -> v == null).count());
            verify(agentScopeClient, times(40)).call(anyString());
            assertTrue(elapsedMillis < 10_000, "40 题应快速完成，实际 " + elapsedMillis + "ms");
        }
    }
}
