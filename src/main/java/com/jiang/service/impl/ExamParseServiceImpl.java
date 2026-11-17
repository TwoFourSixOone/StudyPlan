package com.jiang.service.impl;

import com.jiang.agent.AgentDegradationLogger;
import com.jiang.agent.AttributionAgent;
import com.jiang.agent.ExamParseAgent;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.GradeMatcher;
import com.jiang.common.util.SimilarityDetector;
import com.jiang.dto.ExamParseContext;
import com.jiang.dto.ExamParseImageResult;
import com.jiang.dto.ExamParseOutcome;
import com.jiang.dto.ExamQuestionAttributionDTO;
import com.jiang.dto.ExamQuestionOcrDTO;
import com.jiang.dto.ParsedQuestion;
import com.jiang.dto.VisionImage;
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.Student;
import com.jiang.model.entity.Subject;
import com.jiang.service.ExamParseService;
import com.jiang.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 试卷视觉解析编排实现（design §3.5/§3.6）
 *
 * <p><b>并发与内存</b>：每张图片一个任务提交到 {@code examParseExecutor}（有界池，默认 3 线程），
 * 用 {@link ExecutorCompletionService} 按完成顺序收集；单张图片的字节/base64 只在该任务内驻留，
 * 返回的 DTO 不含任何字节，因此内存峰值 ≈ 并发数 × 单图（design §11.3）。</p>
 *
 * <p><b>超时</b>：整卷 deadline = 开始 + {@code exam.parse.task-timeout-seconds}（默认 180s）；
 * 等待单张结果的时间不超过 {@code exam.parse.image-timeout-seconds}（默认 60s）且不超过剩余预算；
 * 超时未完成的图片 {@code cancel(true)} 并标记为该图解析失败，已完成图片的结果照常保留。</p>
 *
 * <p><b>降级</b>：单图失败 → 该图 0 题 + 告警 + {@code agent_degradation_log}；归因批失败重试 2 次后
 * 该批全部 {@code pending_manual_review}，不影响其他批（design §12）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Service
@Slf4j
public class ExamParseServiceImpl implements ExamParseService {

    /** 跨图重复判定阈值（design §3.6：只合并"同一题被拍两次"，故高于出题查重的 0.8） */
    public static final double CROSS_PAGE_DUPLICATE_THRESHOLD = 0.9;

    /** 归因批大小（attribution_schema.maxItems=50 之下留余量，design §5） */
    static final int ATTRIBUTION_BATCH_SIZE = 40;

    /** 归因批最多重试次数 */
    static final int ATTRIBUTION_MAX_RETRIES = 2;

    /** 兜底候选单片上限（v4b：parse 150 / attribution 500；>500 按 kpId 分片） */
    static final int ATTRIBUTION_CANDIDATE_LIMIT = 500;

    /** 预算到点后的补收宽限（A1）：只回收已提交图片的已完成结果，不再提交新任务 */
    static final long DRAIN_GRACE_MILLIS = 500L;

    /** 整卷单次解析的请求体阈值（字节）：超过则降采样，避免 base64 请求体过大（2026-11-12） */
    static final long SINGLE_CALL_MAX_BYTES = 6L * 1024 * 1024;

    /** 单图告警/降级摘要的最大长度 */
    private static final int WARNING_MAX_LENGTH = 200;

    @Autowired
    private ExamParseAgent examParseAgent;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private AttributionAgent attributionAgent;

    /** 知识点候选（parse 150 / attribution 500 + 缓存，v4 §3.7） */
    @Autowired
    private com.jiang.service.ExamKpCandidateService examKpCandidateService;

    @Autowired
    private SubjectMapper subjectMapper;

    @Autowired
    private StudentMapper studentMapper;

    @Autowired
    private SimilarityDetector similarityDetector;

    @Autowired
    private AgentDegradationLogger degradationLogger;

    /** GLM-OCR 客户端（2026-11-17；无 Key 时内部自动禁用） */
    @Autowired
    private com.jiang.client.ZhipuOcrClient zhipuOcrClient;

    @Autowired
    @Qualifier("examParseExecutor")
    private ThreadPoolExecutor examParseExecutor;

    /** OCR 文本 grounding 开关 */
    @Value("${exam.parse.ocr.grounding:true}")
    private boolean ocrGroundingEnabled;

    /** OCR 阶段总预算（秒），超时则停止剩余页、用已拿到的文本继续解析 */
    @Value("${exam.parse.ocr.budget-seconds:90}")
    private int ocrBudgetSeconds;

    /** 单页 OCR 文本最多注入的字符数（防止 prompt 爆炸） */
    @Value("${exam.parse.ocr.page-max-chars:6000}")
    private int ocrPageMaxChars;

    @Value("${exam.parse.task-timeout-seconds:300}")
    private int taskTimeoutSeconds;

    @Value("${exam.parse.image-timeout-seconds:60}")
    private int imageTimeoutSeconds;

    /** 整卷单次调用开关（2026-11-12 v5；关闭后退回逐图解析） */
    @Value("${exam.parse.single-call:true}")
    private boolean singleCallEnabled;

    @Value("${exam.parse.max-questions:200}")
    private int maxQuestions;

    @Override
    public ExamParseOutcome parsePaper(Long examPaperId, Long subjectId, Long studentId,
                                       String tenantId, List<String> imageUrls) {
        List<String> urls = (imageUrls == null ? List.<String>of() : imageUrls).stream()
                .filter(url -> url != null && !url.isBlank())
                .toList();
        if (urls.isEmpty()) {
            throw new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, "试卷没有可解析的图片");
        }

        String subjectName = resolveSubjectName(subjectId);
        String gradeLabel = resolveGradeLabel(studentId);
        // v4 §3.7：候选快照一卷构造 1 次（缓存复用），8 图共用 parse 切片
        Integer studentGrade = resolveStudentGrade(studentId);
        com.jiang.service.ExamKpCandidateService.CandidateSnapshot kpSnapshot =
                examKpCandidateService.build(tenantId, subjectId, studentId, studentGrade);
        int total = urls.size();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, taskTimeoutSeconds));
        long imageBudgetMillis = TimeUnit.SECONDS.toMillis(Math.max(1, imageTimeoutSeconds));

        log.info("试卷视觉解析开始: paperId={}, imageCount={}, subject={}, grade={}, singleCall={}",
                examPaperId, total, subjectName, gradeLabel, singleCallEnabled);

        // 2026-11-12 v5：整卷一次调用（跨页题目/配图、答题卡答案、多选判断）
        if (singleCallEnabled) {
            ExamParseImageResult whole = tryParseWholePaper(examPaperId, urls, subjectName, gradeLabel,
                    tenantId, kpSnapshot);
            if (whole != null && whole.getQuestions() != null && !whole.getQuestions().isEmpty()) {
                Map<Integer, ImageTaskResult> wholeResults = new HashMap<>();
                wholeResults.put(1, new ImageTaskResult(1, whole.getQuestions(), whole.getRetryCount(),
                        whole.getWarning(), null));
                return mergeAndAttribute(examPaperId, subjectId, tenantId, total, wholeResults, kpSnapshot);
            }
            log.warn("整卷单次解析未产出题目，回退逐图解析: paperId={}", examPaperId);
        }

        ExecutorCompletionService<ImageTaskResult> completionService =
                new ExecutorCompletionService<>(examParseExecutor);
        List<Future<ImageTaskResult>> futures = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            final int imageSeq = index + 1;
            final String url = urls.get(index);
            futures.add(completionService.submit(() -> parseSingleImage(
                    examPaperId, imageSeq, total, url, subjectName, gradeLabel, tenantId, kpSnapshot)));
        }

        Map<Integer, ImageTaskResult> results = new HashMap<>();
        int finished = 0;
        while (finished < total) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                log.warn("试卷解析总预算耗尽，取消未完成图片: paperId={}, finished={}/{}",
                        examPaperId, finished, total);
                break;
            }
            long waitMillis = Math.max(1L, Math.min(remainingNanos / 1_000_000L, imageBudgetMillis));
            Future<ImageTaskResult> completed;
            try {
                completed = completionService.poll(waitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("试卷解析等待被中断: paperId={}", examPaperId);
                break;
            }
            if (completed == null) {
                // 单图预算到点仍未完成：继续等待（受整卷 deadline 收敛），最终超时的图会被取消
                continue;
            }
            finished++;
            try {
                ImageTaskResult result = completed.get();
                results.put(result.imageSeq(), result);
            } catch (ExecutionException e) {
                log.error("试卷图片解析任务异常: paperId={}", examPaperId, e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // A1：预算到点/中断后先 drain——已提交且已完成但未被 poll 的图片结果必须回收（design §3.5「已完成图也要落库」）
        int drained = 0;
        long graceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_GRACE_MILLIS);
        while (true) {
            long remaining = graceDeadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            Future<ImageTaskResult> doneFuture;
            try {
                doneFuture = completionService.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (doneFuture == null) {
                break;
            }
            drained += collectImageResult(doneFuture, results, examPaperId);
        }

        for (int index = 0; index < futures.size(); index++) {
            Future<ImageTaskResult> future = futures.get(index);
            int imageSeq = index + 1;
            if (future.isDone() && !future.isCancelled()) {
                // 幂等补收：已 drain 过的会覆盖成同值，未 drain 的在这里兜住
                if (!results.containsKey(imageSeq)) {
                    drained += collectImageResult(future, results, examPaperId);
                }
                continue;
            }
            if (!future.isDone()) {
                future.cancel(true);
                if (!results.containsKey(imageSeq)) {
                    // 超时未完成 → 该图解析失败（design §3.5），不阻塞其他图落库
                    results.put(imageSeq, ImageTaskResult.failed(imageSeq,
                            "第 " + imageSeq + " 张图片超过解析预算（" + imageTimeoutSeconds + "s/图，"
                                    + taskTimeoutSeconds + "s/卷），已取消"));
                    degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                            "paperId=" + examPaperId + ", imageSeq=" + imageSeq,
                            "单图解析超时（budget=" + imageTimeoutSeconds + "s, task=" + taskTimeoutSeconds + "s）",
                            "timeout_skip_image", tenantId);
                }
            }
        }
        if (drained > 0) {
            log.info("试卷解析预算到点后补收已完成图片: paperId={}, drained={}", examPaperId, drained);
        }

        return mergeAndAttribute(examPaperId, subjectId, tenantId, total, results, kpSnapshot);
    }

    /**
     * 取单张图片的解析结果（异常只记日志并返回 0，不覆盖已有结果）
     */
    private int collectImageResult(Future<ImageTaskResult> future,
                                   Map<Integer, ImageTaskResult> results, Long examPaperId) {
        try {
            ImageTaskResult result = future.get();
            results.put(result.imageSeq(), result);
            return 1;
        } catch (java.util.concurrent.CancellationException e) {
            return 0;
        } catch (ExecutionException e) {
            log.error("试卷图片解析任务异常: paperId={}", examPaperId, e.getCause());
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /**
     * 整卷单次解析（2026-11-12 v5）：全部图片一次发给视觉模型
     *
     * <p>失败/为空返回 null，由调用方回退逐图解析（保证可用性）。</p>
     */
    private ExamParseImageResult tryParseWholePaper(Long examPaperId, List<String> urls, String subjectName,
                                                    String gradeLabel, String tenantId,
                                                    com.jiang.service.ExamKpCandidateService.CandidateSnapshot kpSnapshot) {
        try {
            List<VisionImage> images = new ArrayList<>(urls.size());
            List<byte[]> raw = new ArrayList<>(urls.size());
            long totalBytes = 0L;
            for (String url : urls) {
                byte[] bytes = fileStorageService.readBytes(url);
                if (bytes == null || bytes.length == 0) {
                    log.warn("整卷解析读取图片为空，回退逐图: paperId={}, url={}", examPaperId, url);
                    return null;
                }
                raw.add(bytes);
                totalBytes += bytes.length;
            }
            // 控制请求体：8 张手机原图 base64 后可能超网关限制，超过 6MB 统一降采样到 1600px/82
            boolean downscale = totalBytes > SINGLE_CALL_MAX_BYTES;
            List<byte[]> forOcr = new ArrayList<>(raw.size());
            List<String> mediaTypes = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                byte[] bytes = raw.get(i);
                if (downscale) {
                    bytes = com.jiang.common.util.ImageDownscaler.downscaleJpeg(bytes, 1600, 0.82f);
                }
                String mediaType = detectMediaType(urls.get(i));
                images.add(VisionImage.of(mediaType, bytes));
                forOcr.add(bytes);
                mediaTypes.add(mediaType);
            }
            if (downscale) {
                log.info("整卷解析请求体过大（{}KB），已降采样到 1600px: paperId={}", totalBytes / 1024, examPaperId);
            }
            // GLM-OCR 文本 grounding（2026-11-17）：失败/超时/无 Key 都静默降级为纯视觉解析
            OcrGrounding ocr = buildOcrGrounding(examPaperId, forOcr, mediaTypes, tenantId);
            log.info("整卷单次解析开始: paperId={}, images={}, ocrPages={}",
                    examPaperId, images.size(), ocr == null ? 0 : ocr.rectsByPage().size());
            ExamParseImageResult result = examParseAgent.parsePaper(images,
                    com.jiang.dto.ExamParsePaperContext.builder()
                            .subjectName(subjectName)
                            .gradeLabel(gradeLabel)
                            .imageTotal(images.size())
                            .tenantId(tenantId)
                            .ocrText(ocr == null ? null : ocr.text())
                            .kpCandidates(kpSnapshot == null ? List.of() : kpSnapshot.getParseCandidates())
                            .build());
            if (result == null) {
                return null;
            }
            if (ocr != null && !ocr.rectsByPage().isEmpty() && result.getQuestions() != null) {
                com.jiang.common.util.OcrFigureSnapper.snap(result.getQuestions(), ocr.rectsByPage());
            }
            log.info("整卷单次解析结束: paperId={}, questions={}, retry={}, failure={}",
                    examPaperId,
                    result.getQuestions() == null ? 0 : result.getQuestions().size(),
                    result.getRetryCount(), result.getFailureReason());
            return result;
        } catch (Exception e) {
            log.warn("整卷单次解析异常，回退逐图解析: paperId={}, reason={}", examPaperId, e.getMessage());
            return null;
        }
    }

    /**
     * 构建 GLM-OCR grounding（2026-11-17）
     *
     * <p>降级策略：通道未启用/无 Key → null；单页失败跳过；总预算超时停止剩余页；
     * 全部失败 → 写降级日志并返回 null（纯视觉解析，不影响可用性）。</p>
     */
    private OcrGrounding buildOcrGrounding(Long examPaperId, List<byte[]> images, List<String> mediaTypes,
                                           String tenantId) {
        if (!ocrGroundingEnabled || images == null || images.isEmpty() || !zhipuOcrClient.isEnabled()) {
            return null;
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                Math.max(10, ocrBudgetSeconds));
        StringBuilder text = new StringBuilder();
        Map<Integer, List<java.util.Map<String, Double>>> rectsByPage = new HashMap<>();
        int successPages = 0;
        int skippedByBudget = 0;
        for (int i = 0; i < images.size(); i++) {
            if (System.nanoTime() > deadline) {
                skippedByBudget = images.size() - i;
                log.warn("GLM-OCR 预算到点，跳过剩余 {} 页: paperId={}", skippedByBudget, examPaperId);
                break;
            }
            com.jiang.dto.OcrPageResult page;
            try {
                page = zhipuOcrClient.parse(images.get(i), mediaTypes.get(i));
            } catch (Exception e) {
                log.warn("GLM-OCR 单页异常，跳过: paperId={}, page={}, reason={}",
                        examPaperId, i + 1, e.getMessage());
                continue;
            }
            if (page == null || page.isEmpty()) {
                continue;
            }
            successPages++;
            text.append("\n\n### 第 ").append(i + 1).append(" 张图片 OCR\n")
                    .append(truncate(page.markdown(), ocrPageMaxChars));
            if (page.imageRects() != null && !page.imageRects().isEmpty()) {
                rectsByPage.put(i + 1, page.imageRects());
            }
        }
        if (text.length() == 0) {
            degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                    "paperId=" + examPaperId + ", images=" + images.size(),
                    "GLM-OCR 全部失败或无文本，回退纯视觉解析", "ocr_grounding_skip", tenantId);
            return null;
        }
        log.info("GLM-OCR grounding 完成: paperId={}, successPages={}, skippedByBudget={}, chars={}",
                examPaperId, successPages, skippedByBudget, text.length());
        if (skippedByBudget > 0 || successPages < images.size()) {
            degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                    "paperId=" + examPaperId + ", success=" + successPages + "/" + images.size(),
                    "GLM-OCR 部分页失败/超时，已用可用文本继续解析", "ocr_grounding_partial", tenantId);
        }
        return new OcrGrounding(text.toString(), rectsByPage);
    }

    /**
     * OCR grounding 结果
     */
    private record OcrGrounding(String text,
                                Map<Integer, List<java.util.Map<String, Double>>> rectsByPage) {
    }

    /**
     * 单图解析任务（在 examParseExecutor 线程内执行）
     *
     * <p>图片原始字节只在本方法内驻留：方法返回后 {@link ImageTaskResult} 只带结构化字段，
     * base64 已在 {@code AgentScopeClient#callVision} 内部按图生成并随调用返回释放（design §11.3）。</p>
     */
    private ImageTaskResult parseSingleImage(Long examPaperId, int imageSeq, int total, String imageUrl,
                                             String subjectName, String gradeLabel, String tenantId,
                                             com.jiang.service.ExamKpCandidateService.CandidateSnapshot kpSnapshot) {
        try {
            byte[] bytes = fileStorageService.readBytes(imageUrl);
            VisionImage image = VisionImage.of(detectMediaType(imageUrl), bytes);
            ExamParseContext context = ExamParseContext.builder()
                    .subjectName(subjectName)
                    .gradeLabel(gradeLabel)
                    .imageSeq(imageSeq)
                    .imageTotal(total)
                    .retryHint("无")
                    .tenantId(tenantId)
                    .kpCandidates(kpSnapshot == null ? List.of() : kpSnapshot.getParseCandidates())
                    .build();

            ExamParseImageResult result = examParseAgent.parseImage(image, context);
            if (!result.isSuccess()) {
                degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                        "paperId=" + examPaperId + ", imageSeq=" + imageSeq + ", bytes=" + bytes.length,
                        result.getFailureReason(), "skip_image", tenantId);
            }
            List<ParsedQuestion> questions = result.getQuestions() == null
                    ? List.of() : result.getQuestions();
            return new ImageTaskResult(imageSeq, questions, result.getRetryCount(),
                    truncate(result.getWarning()), result.getFailureReason());
        } catch (BusinessException e) {
            log.warn("试卷图片读取/解析失败: paperId={}, imageSeq={}, reason={}",
                    examPaperId, imageSeq, e.getMessage());
            degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                    "paperId=" + examPaperId + ", imageSeq=" + imageSeq,
                    "图片读取失败: " + e.getMessage(), "skip_image", tenantId);
            return ImageTaskResult.failed(imageSeq, truncate("图片读取失败: " + e.getMessage()));
        } catch (Exception e) {
            log.error("试卷图片解析异常: paperId={}, imageSeq={}", examPaperId, imageSeq, e);
            degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                    "paperId=" + examPaperId + ", imageSeq=" + imageSeq,
                    "图片解析异常: " + e.getMessage(), "skip_image", tenantId);
            return ImageTaskResult.failed(imageSeq, truncate("图片解析异常: " + e.getMessage()));
        }
    }

    /**
     * 合并各图结果 → 题号重排 → 跨图去重 → 题数截断 → 归因
     */
    private ExamParseOutcome mergeAndAttribute(Long examPaperId, Long subjectId, String tenantId,
                                               int imageTotal, Map<Integer, ImageTaskResult> resultsByImage,
                                               com.jiang.service.ExamKpCandidateService.CandidateSnapshot kpSnapshot) {
        List<ImageTaskResult> ordered = new ArrayList<>(resultsByImage.values());
        ordered.sort(Comparator.comparingInt(ImageTaskResult::imageSeq));

        List<ParsedQuestion> merged = new ArrayList<>();
        List<String> keptStems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int succeededImages = 0;
        int failedImages = 0;
        int cutRetryCount = 0;
        int duplicateCount = 0;
        boolean truncated = false;

        for (ImageTaskResult result : ordered) {
            cutRetryCount += result.retryCount();
            if (result.failureReason() != null) {
                failedImages++;
                warnings.add(truncate("第 " + result.imageSeq() + " 张图片解析失败：" + result.failureReason()));
                continue;
            }
            succeededImages++;
            if (result.warning() != null && !result.warning().isBlank()) {
                warnings.add(truncate("第 " + result.imageSeq() + " 张图片：" + result.warning()));
            }
            for (ParsedQuestion question : result.questions()) {
                if (merged.size() >= maxQuestions) {
                    truncated = true;
                    break;
                }
                if (isCrossPageDuplicate(question, keptStems)) {
                    duplicateCount++;
                    log.info("跨图重复题目已合并: paperId={}, imageSeq={}, stemPrefix={}",
                            examPaperId, result.imageSeq(), truncate(question.getStem(), 30));
                    continue;
                }
                keptStems.add(question.getStem());
                merged.add(question);
            }
            if (truncated) {
                break;
            }
        }

        // 题号重排：忽略模型给的题号，按"图序 → 图内顺序"重排为 1..N（design §3.6）
        for (int index = 0; index < merged.size(); index++) {
            merged.get(index).setQuestionIndex(index + 1);
        }

        // v4：直出为主 + 兜底归因（白名单 + 四档统计）
        KpAttributeResult kpResult = attributeQuestions(merged, kpSnapshot, tenantId, examPaperId);
        int attributionRetryCount = kpResult.retries();

        if (truncated) {
            warnings.add("题目数超过单卷上限 " + maxQuestions + "，已截断");
            degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                    "paperId=" + examPaperId + ", parsedCount>" + maxQuestions,
                    "题目数超过单卷上限 " + maxQuestions + "，已截断", "truncate_questions", tenantId);
        }
        for (String warning : warnings) {
            log.warn("试卷解析告警: paperId={}, {}", examPaperId, warning);
        }

        boolean allFailed = merged.isEmpty() && failedImages == imageTotal;
        int kpTotal = kpSnapshot == null ? 0 : kpSnapshot.getKpTotal();
        int kpTruncated = kpSnapshot == null ? 0 : kpSnapshot.getKpTruncated();
        log.info("试卷视觉解析完成: paperId={}, questions={}, succeededImages={}, failedImages={}, "
                        + "duplicates={}, cutRetry={}, attributionRetry={}, truncated={}, "
                        + "kpDirect={}, kpFallback={}, kpUnresolved={}, kpTotal={}, kpTruncated={}",
                examPaperId, merged.size(), succeededImages, failedImages, duplicateCount,
                cutRetryCount, attributionRetryCount, truncated,
                kpResult.directCount(), kpResult.fallbackCount(), kpResult.unresolvedCount(),
                kpTotal, kpTruncated);

        return ExamParseOutcome.builder()
                .questions(merged)
                .succeededImageCount(succeededImages)
                .failedImageCount(failedImages)
                .cutRetryCount(cutRetryCount)
                .attributionRetryCount(attributionRetryCount)
                .duplicateCount(duplicateCount)
                .truncated(truncated)
                .allImagesFailed(allFailed)
                .imageWarnings(warnings)
                .directKpCount(kpResult.directCount())
                .fallbackKpCount(kpResult.fallbackCount())
                .unresolvedKpCount(kpResult.unresolvedCount())
                .kpTotal(kpTotal)
                .kpTruncated(kpTruncated)
                .build();
    }

    /**
     * 跨图去重：新题题干与已保留题干的相似度 ≥ {@link #CROSS_PAGE_DUPLICATE_THRESHOLD} 时丢弃后出现者
     */
    private boolean isCrossPageDuplicate(ParsedQuestion question, List<String> keptStems) {
        if (keptStems.isEmpty() || question.getStem() == null || question.getStem().isBlank()) {
            return false;
        }
        return similarityDetector.detectSimilarity(question.getStem(), keptStems)
                >= CROSS_PAGE_DUPLICATE_THRESHOLD;
    }

    /**
     * 归因：v4 起"直出为主 + AttributionAgent 兜底"
     *
     * <p>三档互斥（design §5）：</p>
     * <ol>
     *   <li>直出：模型给出的 kp 在注入清单内 → completed（direct）</li>
     *   <li>兜底：直出为空 / 被白名单置空 → 按 ≤40 题/批、批间串行调用归因（该批失败重试 ≤2），
     *       候选为 attribution 切片（≤500；超过按 kpId 分片，未决题对每片各调一次）</li>
     *   <li>仍无 kp → kp=NULL + pending_manual_review（不入错题/不更新掌握度）</li>
     * </ol>
     *
     * <p>attribution_status 只表达 kp 归因结果（v4b）：作答无法辨识由 {@code is_correct=NULL} 表达。</p>
     */
    private KpAttributeResult attributeQuestions(List<ParsedQuestion> questions,
                                                 com.jiang.service.ExamKpCandidateService.CandidateSnapshot snapshot,
                                                 String tenantId, Long examPaperId) {
        if (questions.isEmpty()) {
            return new KpAttributeResult(0, 0, 0, 0);
        }

        // 1. 白名单直出
        int direct = 0;
        List<ParsedQuestion> pending = new ArrayList<>();
        for (ParsedQuestion question : questions) {
            String kpId = question.getKnowledgePointId();
            if (kpId != null && snapshot != null && snapshot.isAllowed(kpId)) {
                direct++;
                continue;
            }
            if (kpId != null) {
                log.warn("直出知识点不在注入清单内，置空（规则 #8）: paperId={}, questionIndex={}",
                        examPaperId, question.getQuestionIndex());
            }
            question.setKnowledgePointId(null);
            pending.add(question);
        }

        // 2. 兜底归因（仅未决题）
        int fallback = 0;
        int retries = 0;
        if (!pending.isEmpty() && snapshot != null && !snapshot.getAttributionCandidates().isEmpty()) {
            List<List<com.jiang.dto.KpCandidate>> shards = shardCandidates(snapshot.getAttributionCandidates());
            for (int from = 0; from < pending.size(); from += ATTRIBUTION_BATCH_SIZE) {
                int to = Math.min(from + ATTRIBUTION_BATCH_SIZE, pending.size());
                List<ParsedQuestion> batch = pending.subList(from, to);
                Map<Integer, String> resolved = new HashMap<>();
                for (List<com.jiang.dto.KpCandidate> shard : shards) {
                    if (resolved.size() >= batch.size()) {
                        break;
                    }
                    List<ParsedQuestion> stillPending = batch.stream()
                            .filter(q -> !resolved.containsKey(q.getQuestionIndex()))
                            .toList();
                    if (stillPending.isEmpty()) {
                        break;
                    }
                    retries += attributeBatch(stillPending, shard, tenantId, examPaperId, resolved);
                }
                for (ParsedQuestion question : batch) {
                    String kpId = resolved.get(question.getQuestionIndex());
                    if (kpId != null) {
                        question.setKnowledgePointId(kpId);
                        fallback++;
                    } else {
                        question.setKnowledgePointId(null);
                    }
                }
            }
        }

        // 3. attribution_status 只表达 kp 结果（v4b）；无 kp → 待人工归因
        int unresolved = 0;
        for (ParsedQuestion question : questions) {
            if (question.getKnowledgePointId() == null) {
                question.setAttributionStatus("pending_manual_review");
                unresolved++;
            } else {
                question.setAttributionStatus("completed");
            }
        }
        return new KpAttributeResult(direct, fallback, unresolved, retries);
    }

    /**
     * 兜底候选分片：≤500 一片；>500 按 kpId 升序每 500 一片（未决题对每片各调一次）
     */
    static List<List<com.jiang.dto.KpCandidate>> shardCandidates(List<com.jiang.dto.KpCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() <= ATTRIBUTION_CANDIDATE_LIMIT) {
            return List.of(candidates);
        }
        List<List<com.jiang.dto.KpCandidate>> shards = new ArrayList<>();
        for (int from = 0; from < candidates.size(); from += ATTRIBUTION_CANDIDATE_LIMIT) {
            int to = Math.min(from + ATTRIBUTION_CANDIDATE_LIMIT, candidates.size());
            shards.add(candidates.subList(from, to));
        }
        return shards;
    }

    private int attributeBatch(List<ParsedQuestion> batch,
                               List<com.jiang.dto.KpCandidate> candidates,
                               String tenantId, Long examPaperId,
                               Map<Integer, String> resolved) {
        List<ExamQuestionOcrDTO> ocrResults = new ArrayList<>(batch.size());
        for (ParsedQuestion question : batch) {
            ExamQuestionOcrDTO dto = new ExamQuestionOcrDTO();
            dto.setQuestionIndex(question.getQuestionIndex());
            dto.setQuestionContent(question.getStem());
            dto.setQuestionType(question.getFormatType());
            dto.setDifficulty(question.getDifficulty());
            ocrResults.add(dto);
        }
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        candidates.forEach(candidate -> allowed.add(candidate.knowledgePointId()));

        int failures = 0;
        String lastError = null;
        for (int attempt = 0; attempt <= ATTRIBUTION_MAX_RETRIES; attempt++) {
            try {
                List<ExamQuestionAttributionDTO> attributionResults =
                        attributionAgent.attribute(ocrResults, candidates, tenantId);
                Map<Integer, ExamQuestionAttributionDTO> byIndex = new HashMap<>();
                for (ExamQuestionAttributionDTO dto : attributionResults) {
                    if (dto.getQuestionIndex() != null) {
                        byIndex.put(dto.getQuestionIndex(), dto);
                    }
                }
                for (ParsedQuestion question : batch) {
                    ExamQuestionAttributionDTO dto = byIndex.get(question.getQuestionIndex());
                    if (dto == null || dto.getKnowledgePointId() == null) {
                        continue;
                    }
                    if (!allowed.contains(dto.getKnowledgePointId())) {
                        log.warn("兜底归因返回的 ID 不在候选清单内，忽略: paperId={}, questionIndex={}",
                                examPaperId, question.getQuestionIndex());
                        continue;
                    }
                    resolved.put(question.getQuestionIndex(), dto.getKnowledgePointId());
                }
                return Math.min(failures, ATTRIBUTION_MAX_RETRIES);
            } catch (Exception e) {
                // 归因 Agent 内部已吞掉技术异常并降级输出；这里兜底处理批量失败（约定 #14）
                failures++;
                lastError = e.getMessage();
                log.warn("归因批失败，重试 {}/{}: paperId={}, reason={}",
                        failures, ATTRIBUTION_MAX_RETRIES, examPaperId, lastError);
            }
        }

        // 重试耗尽：该批未决题保持 null（上层置 pending_manual_review），不影响其他批
        degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                "paperId=" + examPaperId + ", batchSize=" + batch.size(),
                "兜底归因重试 " + ATTRIBUTION_MAX_RETRIES + " 次后仍失败: " + lastError,
                "pending_manual_review", tenantId);
        return Math.min(failures, ATTRIBUTION_MAX_RETRIES);
    }

    /**
     * 直出/兜底/未决统计（v4 §5；只记数量，不打印 kp 清单）
     */
    record KpAttributeResult(int directCount, int fallbackCount, int unresolvedCount, int retries) {
    }

    private String resolveSubjectName(Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        Subject subject = subjectMapper.selectById(subjectId);
        return subject == null ? null : subject.getName();
    }

    private String resolveGradeLabel(Long studentId) {
        if (studentId == null) {
            return "当前";
        }
        Student student = studentMapper.selectById(studentId);
        if (student == null || student.getGrade() == null || student.getGrade().isBlank()) {
            return "当前";
        }
        String displayName = GradeMatcher.displayName(student.getGrade());
        return (displayName == null || displayName.isBlank()) ? "当前" : displayName;
    }

    /**
     * 学生年级数值（用于 kp 候选年级过滤；空/非法 → null 表示不过滤）
     */
    private Integer resolveStudentGrade(Long studentId) {
        if (studentId == null) {
            return null;
        }
        Student student = studentMapper.selectById(studentId);
        return student == null ? null : GradeMatcher.normalizeStudentGrade(student.getGrade());
    }

    /**
     * 由图片 URL 推断媒体类型（本 change 只允许 jpg/jpeg/png）
     */
    static String detectMediaType(String imageUrl) {
        if (imageUrl != null) {
            String lower = imageUrl.toLowerCase();
            if (lower.endsWith(".png")) {
                return "image/png";
            }
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return "image/jpeg";
            }
        }
        return VisionImage.DEFAULT_MEDIA_TYPE;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > WARNING_MAX_LENGTH ? text.substring(0, WARNING_MAX_LENGTH) : text;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    /**
     * 单图任务结果（只含结构化字段，不含图片字节）
     */
    record ImageTaskResult(int imageSeq, List<ParsedQuestion> questions, int retryCount,
                           String warning, String failureReason) {

        static ImageTaskResult failed(int imageSeq, String failureReason) {
            return new ImageTaskResult(imageSeq, List.of(), 0, null, failureReason);
        }
    }
}
