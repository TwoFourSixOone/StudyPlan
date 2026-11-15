package com.jiang.service.impl;

import com.jiang.client.AgentScopeClient;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.GradeMatcher;
import com.jiang.common.util.LlmTextSanitizer;
import com.jiang.dto.GradeRequest;
import com.jiang.service.AnswerGradingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 判分公共组件实现（design D5 / §6.1 / S3）
 *
 * <p>从 {@code HomeworkServiceImpl} 的私有判分方法抽取而来（逻辑不变），
 * 供作业批改与试卷结算共用，避免出现两套判分口径。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Service
@Slf4j
public class AnswerGradingServiceImpl implements AnswerGradingService {

    /** 批改 Prompt 模板 */
    static final String GRADING_PROMPT_PATH = "prompts/versions/grading_v1.txt";

    /** 批改 Prompt 中展示的年级：中文名（"12" → "高三"），未设置时"当前"（约定 #23） */
    private static final String DEFAULT_GRADE_LABEL = "当前";

    /** 缺省判分预算（秒，与 exam.parse.grading-timeout-seconds 默认一致） */
    private static final long DEFAULT_GRADING_BUDGET_SECONDS = 60L;

    /** 判分预算上限（秒）：题量估算不得放大契约 */
    private static final long MAX_GRADING_BUDGET_SECONDS = 60L;

    /** 题量估算预算：每题 3s 上限（A2） */
    private static final long PER_QUESTION_BUDGET_MILLIS = 3_000L;

    /** 预算到点后的补收宽限（A1）：只回收已提交任务的已完成结果，不提交新任务 */
    private static final long DRAIN_GRACE_MILLIS = 500L;

    @Autowired
    private AgentScopeClient agentScopeClient;

    /** 判分任务提交到解析池（与视觉解析共用，全局闸门保证在途 LLM ≤3） */
    @Autowired
    @Qualifier("examParseExecutor")
    private ThreadPoolExecutor gradingExecutor;

    @Override
    public Boolean grade(String formatType, String questionContent, String correctAnswer,
                         String studentAnswer, String answerStatus, String subjectName, String gradeLabel) {
        // 1. 作答无法辨识 → 不可判定（null）
        if ("unreadable".equals(answerStatus)) {
            return null;
        }
        boolean hasAnswer = correctAnswer != null && !correctAnswer.isBlank();
        boolean hasStudentAnswer = studentAnswer != null && !studentAnswer.isBlank();

        // 2. 学生空 + 答案空 → null（v4b：双空不可判定，绝不判错）
        if (!hasStudentAnswer && !hasAnswer) {
            return null;
        }
        // 3. 学生空 + 答案有 → false（未作答，不调 AI）
        if (!hasStudentAnswer) {
            return false;
        }
        // 4. 有作答 + 答案缺失 → null（v4b：非选择题也不调 AI，避免"瞎判"）
        if (!hasAnswer) {
            return null;
        }
        // 5. 选择题：选项标签集合比较（2026-11-12 起支持多选；严格集合相等，多选/少选都算错）
        if ("choice".equals(formatType)) {
            return com.jiang.common.util.ChoiceAnswer.sameAnswer(studentAnswer, correctAnswer);
        }
        // 6. 非选择题：AI 批改，失败/不可解析降级为字符串比较
        return aiGrade(questionContent, correctAnswer, studentAnswer, subjectName, gradeLabel);
    }

    @Override
    public Map<Integer, Boolean> gradeBatch(List<GradeRequest> requests, String subjectName,
                                            String gradeLabel, Duration budget, int concurrency) {
        Map<Integer, Boolean> results = new LinkedHashMap<>();
        if (requests == null || requests.isEmpty()) {
            return results;
        }

        List<GradeRequest> pending = new ArrayList<>();
        for (GradeRequest request : requests) {
            if ("unreadable".equals(request.getAnswerStatus())) {
                results.put(request.getKey(), null);
                continue;
            }
            boolean hasAnswer = request.getCorrectAnswer() != null && !request.getCorrectAnswer().isBlank();
            boolean hasStudentAnswer = request.getStudentAnswer() != null
                    && !request.getStudentAnswer().isBlank();
            if (!hasStudentAnswer && !hasAnswer) {
                // v4b：双空 → 不可判定
                results.put(request.getKey(), null);
            } else if (!hasStudentAnswer) {
                // 未作答但答案有 → 判错（不调 AI）
                results.put(request.getKey(), false);
            } else if (!hasAnswer) {
                // 有作答但标准答案缺失 → 不可判定（choice/非 choice 都不调 AI）
                results.put(request.getKey(), null);
            } else if ("choice".equals(request.getFormatType())) {
                // 多选/单选统一按标签集合判定（2026-11-12）
                results.put(request.getKey(), com.jiang.common.util.ChoiceAnswer.sameAnswer(
                        request.getStudentAnswer(), request.getCorrectAnswer()));
            } else {
                pending.add(request);
            }
        }

        if (pending.isEmpty()) {
            return results;
        }

        Duration effectiveBudget = resolveBudget(budget, pending.size());
        long deadlineNanos = System.nanoTime() + effectiveBudget.toNanos();
        // A2：分批提交，避免一次性把全部题塞进 queue=16 的池触发 CallerRunsPolicy（结算线程自己跑 LLM）
        int submitBatch = resolveSubmitBatch();
        log.info("判分批量开始: 题目总数={}, 需 AI 判分={}, 预算={}s, 提交批大小={}, 并发提示={}（全局闸门约束在途 LLM ≤3）",
                requests.size(), pending.size(), effectiveBudget.getSeconds(), submitBatch, concurrency);

        ExecutorCompletionService<Map.Entry<Integer, Boolean>> completionService =
                new ExecutorCompletionService<>(gradingExecutor);
        List<Future<Map.Entry<Integer, Boolean>>> futures = new ArrayList<>(pending.size());

        int nextToSubmit = 0;
        int inFlight = 0;
        int finished = 0;
        while (finished < pending.size()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                log.warn("判分批处理预算耗尽，未完成题目按无法判定处理: unfinished={}",
                        pending.size() - finished);
                break;
            }
            // A2：只补到 in-flight ≤ 批大小，队列不会被打满（CallerRunsPolicy 不会触发）
            while (nextToSubmit < pending.size() && inFlight < submitBatch) {
                GradeRequest request = pending.get(nextToSubmit++);
                futures.add(completionService.submit(() -> Map.entry(request.getKey(),
                        aiGrade(request.getQuestionContent(), request.getCorrectAnswer(),
                                request.getStudentAnswer(), subjectName, gradeLabel))));
                inFlight++;
            }
            Future<Map.Entry<Integer, Boolean>> completed;
            try {
                completed = completionService.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (completed == null) {
                continue;
            }
            inFlight--;
            finished++;
            collect(completed, results);
        }

        // A1：预算到点/中断后先 drain——已提交且已完成但未被 poll 的结果必须回收（design §3.5）
        int drained = drainCompleted(completionService, futures, results);
        if (drained > 0) {
            log.info("判分预算到点后补收已完成结果: drained={}", drained);
        }

        for (Future<Map.Entry<Integer, Boolean>> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        // 预算内未完成的题 → null（待人工判定）
        for (GradeRequest request : pending) {
            results.putIfAbsent(request.getKey(), null);
        }
        return results;
    }

    /**
     * A2：提交批大小 = 池核心线程数（≥1）
     *
     * <p>判分受 {@code LlmConcurrencyLimiter} 全局闸门约束在途 LLM ≤3，in-flight 超过核心线程数没有收益，
     * 反而会打满 queue=16 触发 {@code CallerRunsPolicy}（让结算线程串行跑 LLM，预算被单批吃满）。</p>
     */
    private int resolveSubmitBatch() {
        int coreSize = gradingExecutor == null ? 0 : gradingExecutor.getCorePoolSize();
        return Math.max(1, coreSize);
    }

    /**
     * A2：预算 = 调用方预算；仅在调用方没给（null/0/负）时按题量估算
     *
     * <p>题量估算取 {@code min(60s, 题量 × 3s)}，但<b>不放大调用方显式传入的预算</b>
     * （配置项 {@code exam.parse.grading-timeout-seconds} 已是 60s，属契约的一部分）。</p>
     */
    private Duration resolveBudget(Duration budget, int pendingCount) {
        if (budget != null && !budget.isZero() && !budget.isNegative()) {
            return budget;
        }
        long estimateNanos = Math.min(TimeUnit.SECONDS.toNanos(MAX_GRADING_BUDGET_SECONDS),
                (long) pendingCount * PER_QUESTION_BUDGET_MILLIS * 1_000_000L);
        long defaultNanos = TimeUnit.SECONDS.toNanos(DEFAULT_GRADING_BUDGET_SECONDS);
        return Duration.ofNanos(Math.max(defaultNanos, estimateNanos));
    }

    /**
     * A1：把已完成的结果从 completionService 与 futures 里补收回来（幂等覆盖，不重复计算）
     *
     * <p>先 {@code poll()} 排空完成队列；再对 {@code isDone()} 的 future 取一次结果（覆盖原先只有
     * "未完成才 cancel" 的单向处理）；仅对仍未完成的 future 取消。</p>
     */
    private int drainCompleted(ExecutorCompletionService<Map.Entry<Integer, Boolean>> completionService,
                               List<Future<Map.Entry<Integer, Boolean>>> futures,
                               Map<Integer, Boolean> results) {
        int drained = 0;
        long graceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_GRACE_MILLIS);
        while (true) {
            long remaining = graceDeadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            Future<Map.Entry<Integer, Boolean>> done;
            try {
                done = completionService.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (done == null) {
                break;
            }
            collect(done, results);
            drained++;
        }
        for (Future<Map.Entry<Integer, Boolean>> future : futures) {
            if (future.isDone() && !future.isCancelled()) {
                collect(future, results);
                drained++;
            }
        }
        return drained;
    }

    /**
     * 取单个已完成任务的结果（异常只记日志，不覆盖已有结果）
     */
    private void collect(Future<Map.Entry<Integer, Boolean>> future, Map<Integer, Boolean> results) {
        try {
            Map.Entry<Integer, Boolean> entry = future.get();
            results.put(entry.getKey(), entry.getValue());
        } catch (ExecutionException e) {
            log.error("判分任务异常", e.getCause());
        } catch (java.util.concurrent.CancellationException e) {
            log.debug("判分任务已取消，跳过");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 非选择题 AI 批改；AI 不可用/返回不可解析时降级为字符串比较
     */
    private Boolean aiGrade(String questionContent, String correctAnswer,
                            String studentAnswer, String subjectName, String gradeLabel) {
        if (studentAnswer == null || studentAnswer.isBlank()) {
            return false;
        }
        // 降级：题目内容或正确答案缺失 → 字符串比较
        if (questionContent == null || questionContent.isBlank()
                || correctAnswer == null || correctAnswer.isBlank()) {
            return studentAnswer.trim().equalsIgnoreCase(correctAnswer == null ? "" : correctAnswer.trim());
        }
        try {
            String prompt = loadGradingPromptTemplate()
                    .replace("{subject}", subjectName != null ? subjectName : "")
                    .replace("{grade}", gradeLabel(gradeLabel))
                    .replace("{questionContent}", questionContent)
                    .replace("{correctAnswer}", correctAnswer)
                    .replace("{studentAnswer}", studentAnswer);

            String result = agentScopeClient.call(prompt);
            if (result != null) {
                String trimmed = result.trim();
                if (trimmed.contains("正确")) {
                    return true;
                }
                if (trimmed.contains("错误")) {
                    return false;
                }
            }
            log.warn("AI 批改结果无法解析，降级为字符串比较: {}", result);
            return studentAnswer.trim().equalsIgnoreCase(correctAnswer.trim());
        } catch (Exception e) {
            log.warn("AI 批改失败，降级为字符串比较: error={}", e.getMessage());
            return studentAnswer.trim().equalsIgnoreCase(correctAnswer.trim());
        }
    }

    private String gradeLabel(String grade) {
        if (grade == null || grade.isBlank()) {
            return DEFAULT_GRADE_LABEL;
        }
        String label = GradeMatcher.displayName(grade);
        return (label == null || label.isEmpty()) ? DEFAULT_GRADE_LABEL : label;
    }

    private String loadGradingPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource(GRADING_PROMPT_PATH);
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("加载批改 Prompt 模板失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "批改 Prompt 模板加载失败");
        }
    }
}
