package com.jiang.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.AgentDegradationLogger;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.common.util.BusinessDate;
import com.jiang.common.util.ExamPaperTypeUtil;
import com.jiang.common.util.ExamQuestionTextCodec;
import com.jiang.common.util.GradeMatcher;
import com.jiang.dto.ExamMasteryEvent;
import com.jiang.dto.ExamParseOutcome;
import com.jiang.dto.GradeRequest;
import com.jiang.dto.ParsedQuestion;
import com.jiang.dto.WrongQuestionRef;
import com.jiang.mapper.ExamAnalysisResultMapper;
import com.jiang.mapper.ExamAnalysisTaskMapper;
import com.jiang.mapper.ErrorQuestionMapper;
import com.jiang.mapper.ExamPaperMapper;
import com.jiang.mapper.ExamQuestionMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.StudentMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.ExamAnalysisResult;
import com.jiang.model.entity.ExamAnalysisTask;
import com.jiang.model.entity.ExamPaper;
import com.jiang.model.entity.ExamQuestion;
import com.jiang.model.entity.Student;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.ErrorQuestionSourceType;
import com.jiang.security.StudentContext;
import com.jiang.service.AnswerGradingService;
import com.jiang.service.ErrorQuestionService;
import com.jiang.service.ExamPaperService;
import com.jiang.service.ExamParseService;
import com.jiang.service.ExamSignalAdjustService;
import com.jiang.service.ExamUploadValidator;
import com.jiang.service.FileStorageService;
import com.jiang.service.MasteryService;
import com.jiang.vo.ExamDiagnosisVO;
import com.jiang.vo.ExamDiagnosisWeakKpVO;
import com.jiang.vo.ExamImageUploadVO;
import com.jiang.vo.ExamPaperDetailVO;
import com.jiang.vo.ExamPaperListItemVO;
import com.jiang.vo.ExamQuestionItemVO;
import com.jiang.vo.ExamSubmitVO;
import com.jiang.vo.ExamTaskProgressVO;
import com.jiang.vo.ExamUploadVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 试卷服务实现（Change 2026-11-11：≤8 图上传 → 视觉解析 → 全量落库 → 自动判分 → 错题/掌握度/学习记录 → 信号）
 *
 * <p>事务边界（约定 #10）：</p>
 * <ul>
 *   <li>文件落盘 / LLM 调用（视觉解析、归因、判分）全部在事务外</li>
 *   <li>TX-A：题目批量插入 + paper=completed（短事务，仅 DB 写）</li>
 *   <li>TX-B：判分结果批量回写 + grade_status CAS + 错题批量入队 + 掌握度批量更新 + 学习记录 + task 完成
 *       （短事务，仅 DB 写；幂等由 grade_status CAS 保证）</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Service
@Slf4j
public class ExamPaperServiceImpl implements ExamPaperService {

    private static final String QUEUE_NAME = "exam_analysis_queue";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 单次批量写入的题目条数上限（design §11.4，禁止逐题单条 insert/update） */
    static final int QUESTION_BATCH_SIZE = 100;

    /** 任务错误信息/告警的最大长度（约定 #12） */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 任务类型：视觉解析 + 归因（design §5.1；Worker 不按 task_type 过滤，兼容旧值） */
    static final String TASK_TYPE_VISION_PARSE = "vision_parse_attribution";

    /** 上传会话复用窗口（分钟）：30 分钟内的 uploading 会话直接续传（design §2.3） */
    static final int UPLOAD_SESSION_REUSE_MINUTES = 30;

    /** 试卷名称最大长度（{@code exam_analysis_result.exam_name} VARCHAR(200)） */
    private static final int EXAM_NAME_MAX_LENGTH = 200;

    /** 历史列表分页上限（design §10.1 / 约定 #3：size ≤ 50） */
    private static final int MAX_PAGE_SIZE = 50;

    /** 薄弱知识点返回上限（诊断结果页只展示 Top 10） */
    static final int WEAK_KP_LIMIT = 10;

    /** 诊断正确率小数位（correctCount/judgedCount，HALF_UP；前端 ×100 渲染百分比） */
    private static final int ACCURACY_SCALE = 4;

    /** 诊断置信说明（固定文案：单份试卷样本量提示，产品口径） */
    private static final String DIAGNOSIS_CONFIDENCE_NOTE =
            "基于 1 份试卷的诊断，仅供参考；建议持续完成每日作业后复看掌握度";

    /** A3：同卷上传串行化分段锁（数组长度取 2 的幂，按 paperId 取模） */
    private static final Object[] UPLOAD_LOCK_STRIPES = new Object[64];

    static {
        for (int i = 0; i < UPLOAD_LOCK_STRIPES.length; i++) {
            UPLOAD_LOCK_STRIPES[i] = new Object();
        }
    }

    @Autowired
    private ExamPaperMapper examPaperMapper;
    @Autowired
    private ExamQuestionMapper examQuestionMapper;
    @Autowired
    private ExamAnalysisTaskMapper examAnalysisTaskMapper;
    @Autowired
    private ExamAnalysisResultMapper examAnalysisResultMapper;
    @Autowired
    private SubjectMapper subjectMapper;
    @Autowired
    private KnowledgePointMapper knowledgePointMapper;
    @Autowired
    private ErrorQuestionMapper errorQuestionMapper;
    @Autowired
    private StudentMapper studentMapper;

    @Autowired
    private FileStorageService fileStorageService;

    /** 配图二次确认（2026-11-12）：把裁剪块再问一次视觉模型，挡住"题号/分值/学生手写"误裁 */
    @Autowired
    private com.jiang.client.AgentScopeClient agentScopeClient;

    @Value("${exam.parse.figure-verify:true}")
    private boolean figureVerifyEnabled;

    /** 配图审核 Prompt 缓存 */
    private volatile String figureCheckPrompt;
    @Autowired
    private MasteryService masteryService;
    @Autowired
    private ErrorQuestionService errorQuestionService;

    /** 视觉解析编排（多图并发 + 合并 + 归因，LLM 调用全部在事务外） */
    @Autowired
    private ExamParseService examParseService;

    /** 上传校验（≤8 张 / 单张 9.5MB / 累计 30MB / 仅 jpg-jpeg-png） */
    @Autowired
    private ExamUploadValidator examUploadValidator;

    /** 判分公共组件（与作业批改同一口径） */
    @Autowired
    private AnswerGradingService answerGradingService;

    /** 结算后异步信号检测与自动调整 */
    @Autowired
    private ExamSignalAdjustService examSignalAdjustService;

    /** 降级日志（脱敏 + 截断 500，约定 #12） */
    @Autowired
    private AgentDegradationLogger degradationLogger;

    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** 判分批处理预算（秒） */
    @Value("${exam.parse.grading-timeout-seconds:60}")
    private int gradingTimeoutSeconds;

    /** 判分并发提示（实际由全局 LlmConcurrencyLimiter 限制在途 ≤3） */
    @Value("${exam.parse.grading-concurrency:3}")
    private int gradingConcurrency;

    // ==================== 上传会话 ====================

    @Override
    public ExamUploadVO createPaper(Long subjectId, LocalDate examDate, String paperType, Integer imageCount) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 1. 科目校验（A7：必须带 tenant_id，避免跨租户命中同名科目）
        Subject subject = subjectMapper.selectByIdAndTenant(subjectId, tenantId);
        if (subject == null) {
            throw new BusinessException(ErrorCodes.SUBJECT_NOT_FOUND);
        }
        // 2. 试卷类型白名单（design §2.4 / S13：5 值，空按 midterm，非法 1191）
        String normalizedType = ExamPaperTypeUtil.normalize(paperType);
        if (!ExamPaperTypeUtil.isValid(normalizedType)) {
            throw new BusinessException(ErrorCodes.EXAM_PAPER_TYPE_INVALID,
                    "试卷类型无效，可选：" + ExamPaperTypeUtil.allowedValues());
        }
        // 3. 声明张数 [1,8]
        examUploadValidator.validateImageCount(imageCount);

        // 4. 同学生 + 科目 + 日期去重（1131）；uploading 会话 30 分钟内复用，超期清空后复用
        ExamPaper existing = examPaperMapper.selectByStudentSubjectDate(studentId, subjectId, examDate, tenantId);
        if (existing != null) {
            // 4.1 整卷解析失败且一题未落库 → 允许当天重新上传（产品规则 #4 补充，2026-11-12）
            //     只有 0 题才没有掌握度/错题/学习记录等副作用，重置同一条记录是安全的；
            //     否则学生当天会彻底卡死（既不能重传、也没有可重试的解析结果）。
            if ("failed".equals(existing.getStatus())
                    && !hasPersistedQuestions(existing.getId(), tenantId)
                    && examPaperMapper.resetFailedPaperForReupload(existing.getId(), tenantId) > 0) {
                log.info("解析失败的试卷允许重新上传，已重置上传会话: paperId={}, studentId={}, subjectId={}, examDate={}",
                        existing.getId(), studentId, subjectId, examDate);
                return ExamUploadVO.builder()
                        .paperId(existing.getId())
                        .status("uploading")
                        .imageCount(imageCount)
                        .reused(true)
                        .build();
            }
            if (!"uploading".equals(existing.getStatus())) {
                log.warn("试卷重复上传: studentId={}, subjectId={}, examDate={}, status={}",
                        studentId, subjectId, examDate, existing.getStatus());
                throw new BusinessException(ErrorCodes.EXAM_PAPER_DUPLICATE);
            }
            boolean reusable = existing.getCreateTime() == null
                    || existing.getCreateTime().isAfter(LocalDateTime.now()
                    .minusMinutes(UPLOAD_SESSION_REUSE_MINUTES));
            if (!reusable) {
                examPaperMapper.resetImageUrls(existing.getId(), tenantId);
                log.info("超期 uploading 会话已重置并复用: paperId={}", existing.getId());
            }
            return ExamUploadVO.builder()
                    .paperId(existing.getId())
                    .status("uploading")
                    .imageCount(imageCount)
                    .reused(true)
                    .build();
        }

        // 5. 新建会话（image_urls NOT NULL，先写空数组）
        ExamPaper paper = new ExamPaper();
        paper.setStudentId(studentId);
        paper.setSubjectId(subjectId);
        paper.setExamDate(examDate);
        paper.setPaperType(normalizedType);
        paper.setImageUrls("[]");
        paper.setStatus("uploading");
        paper.setGradeStatus("ungraded");
        paper.setTenantId(tenantId);
        try {
            examPaperMapper.insert(paper);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCodes.EXAM_PAPER_DUPLICATE);
        }

        log.info("试卷上传会话已创建: paperId={}, studentId={}, subjectId={}, imageCount={}",
                paper.getId(), studentId, subjectId, imageCount);
        return ExamUploadVO.builder()
                .paperId(paper.getId())
                .status("uploading")
                .imageCount(imageCount)
                .reused(false)
                .build();
    }

    @Override
    public ExamImageUploadVO uploadImage(Long paperId, MultipartFile file) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 1. 归属 + 状态快速校验
        requireOwnedPaper(paperId, studentId, tenantId);
        // 2. 单张校验（空/大小/类型/魔数）
        examUploadValidator.validateSingleFile(file);

        // 3. A3：同卷追加串行化（单实例 JVM 内分段锁）+ 追加前复核张数/累计大小，消除 TOCTOU
        synchronized (uploadLock(paperId)) {
            ExamPaper paper = requireOwnedPaper(paperId, studentId, tenantId);
            if (!"uploading".equals(paper.getStatus())) {
                throw new BusinessException(ErrorCodes.EXAM_UPLOAD_SESSION_NOT_FOUND);
            }
            List<String> imageUrls = fromJson(paper.getImageUrls());
            int maxImages = examUploadValidator.getMaxImages();
            if (imageUrls.size() >= maxImages) {
                throw new BusinessException(ErrorCodes.EXAM_PAPER_IMAGE_LIMIT_REACHED);
            }
            long accumulatedBytes = sumImageSizes(imageUrls);
            examUploadValidator.validateTotalBytes(accumulatedBytes, file.getSize());

            // 4. 文件落盘（事务外）
            String url = fileStorageService.upload(file, "exam-papers/" + studentId);

            // 5. 原子追加 URL（单条 JSON_ARRAY_APPEND + JSON_LENGTH 守卫，>=8 张时不生效）
            int appended = examPaperMapper.appendImageUrl(paperId, tenantId, url, maxImages);
            if (appended == 0) {
                // 条件更新失败：状态已被提交/复用，或并发追加把张数打满
                ExamPaper latest = examPaperMapper.selectById(paperId);
                if (latest != null && "uploading".equals(latest.getStatus())
                        && fromJson(latest.getImageUrls()).size() >= maxImages) {
                    throw new BusinessException(ErrorCodes.EXAM_PAPER_IMAGE_LIMIT_REACHED);
                }
                throw new BusinessException(ErrorCodes.EXAM_UPLOAD_SESSION_NOT_FOUND);
            }
            int uploadedCount = imageUrls.size() + 1;

            // 6. 达到 8 张上限自动触发解析（兜底路径；正常路径由 submit 显式触发）
            Long taskId = null;
            if (uploadedCount >= maxImages) {
                taskId = triggerAnalysis(paperId, tenantId);
            }
            return ExamImageUploadVO.builder()
                    .paperId(paperId)
                    .uploadedCount(uploadedCount)
                    .status(taskId == null ? "uploading" : "pending")
                    .taskId(taskId)
                    .build();
        }
    }

    /**
     * 同卷上传分段锁（A3）：只在单实例内串行化"复核 + 落盘 + 追加"，配合 SQL 的
     * {@code JSON_LENGTH(image_urls) < 8} 条件在多发实例下兜底。
     */
    private Object uploadLock(Long paperId) {
        long key = paperId == null ? 0L : paperId;
        int index = (int) Math.floorMod(key, UPLOAD_LOCK_STRIPES.length);
        return UPLOAD_LOCK_STRIPES[index];
    }

    private long sumImageSizes(List<String> imageUrls) {
        long accumulatedBytes = 0L;
        for (String url : imageUrls) {
            accumulatedBytes += fileStorageService.size(url);
        }
        return accumulatedBytes;
    }

    @Override
    public ExamSubmitVO submitPaper(Long paperId) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        ExamPaper paper = requireOwnedPaper(paperId, studentId, tenantId);
        List<String> imageUrls = fromJson(paper.getImageUrls());
        if (imageUrls.isEmpty()) {
            throw new BusinessException(ErrorCodes.EXAM_UPLOAD_INCOMPLETE);
        }

        // CAS uploading → pending（与"达量自动触发"只生效一次）
        Long taskId = triggerAnalysis(paperId, tenantId);
        if (taskId == null) {
            throw new BusinessException(ErrorCodes.EXAM_UPLOAD_SESSION_NOT_FOUND);
        }
        return ExamSubmitVO.builder()
                .paperId(paperId)
                .taskId(taskId)
                .status("pending")
                .build();
    }

    @Override
    public ExamSubmitVO retryPaper(Long paperId) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        ExamPaper paper = requireOwnedPaper(paperId, studentId, tenantId);
        if (!"failed".equals(paper.getStatus())) {
            throw new BusinessException(ErrorCodes.EXAM_RETRY_NOT_ALLOWED);
        }
        ExamAnalysisTask task = examAnalysisTaskMapper.selectLatestByPaper(paperId, tenantId);
        if (task == null) {
            throw new BusinessException(ErrorCodes.EXAM_TASK_NOT_FOUND);
        }
        // 整卷重试 CAS：failed → pending 且 ocr_retry_count < 2（0 行 = 不可重试）
        int retried = examAnalysisTaskMapper.casFailedToPendingForRetry(task.getId(), tenantId);
        if (retried == 0) {
            throw new BusinessException(ErrorCodes.EXAM_RETRY_NOT_ALLOWED);
        }
        if (examPaperMapper.casFailedToPending(paperId, tenantId) == 0) {
            throw new BusinessException(ErrorCodes.EXAM_RETRY_NOT_ALLOWED);
        }
        redisTemplate.opsForList().leftPush(QUEUE_NAME, task.getId());

        int retryCount = (task.getOcrRetryCount() == null ? 0 : task.getOcrRetryCount()) + 1;
        log.info("试卷解析重试已入队: paperId={}, taskId={}, ocrRetryCount={}", paperId, task.getId(), retryCount);
        return ExamSubmitVO.builder()
                .paperId(paperId)
                .taskId(task.getId())
                .status("pending")
                .ocrRetryCount(retryCount)
                .build();
    }

    /**
     * 创建解析任务并入队（CAS uploading → pending；0 行表示已被提交过）
     */
    private Long triggerAnalysis(Long paperId, String tenantId) {
        int cas = examPaperMapper.casToPending(paperId, tenantId);
        if (cas == 0) {
            return null;
        }
        ExamAnalysisTask task = new ExamAnalysisTask();
        task.setExamPaperId(paperId);
        task.setTaskType(TASK_TYPE_VISION_PARSE);
        task.setStatus("pending");
        task.setTenantId(tenantId);
        examAnalysisTaskMapper.insert(task);
        redisTemplate.opsForList().leftPush(QUEUE_NAME, task.getId());
        log.info("试卷解析任务已入队: paperId={}, taskId={}", paperId, task.getId());
        return task.getId();
    }

    /**
     * 归属校验：不存在 / 非本人 / 非本租户统一 1130（不区分存在性，避免探测）
     */
    private ExamPaper requireOwnedPaper(Long paperId, Long studentId, String tenantId) {
        ExamPaper paper = examPaperMapper.selectById(paperId);
        if (paper == null || !studentId.equals(paper.getStudentId())
                || !tenantId.equals(paper.getTenantId())) {
            throw new BusinessException(ErrorCodes.EXAM_PAPER_NOT_FOUND);
        }
        return paper;
    }

    // ==================== Worker：解析 → 判分 → 结算 ====================

    @Override
    public void processExamAnalysis(Long taskId) {
        ExamAnalysisTask task = examAnalysisTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("试卷解析任务不存在，忽略: taskId={}", taskId);
            return;
        }

        // 1. CAS 抢占：只有 pending → processing 成功者才消费（重复投递/并发消费只处理一次）
        int cas = examAnalysisTaskMapper.casToProcessing(taskId, LocalDateTime.now());
        if (cas == 0) {
            log.info("试卷解析任务已被处理或状态非 pending，跳过: taskId={}", taskId);
            return;
        }
        task = examAnalysisTaskMapper.selectById(taskId);
        String tenantId = task.getTenantId();

        // 2. Worker 线程没有 HTTP 上下文：design §9.1 要求身份显式传参，这里不再 set/clear StudentContext
        //    （下游 parsePaper / settle / 掌握度 / 错题 / 学习记录 / 信号调整全部接收显式 tenantId+studentId）
        try {
            ExamPaper paper = examPaperMapper.selectById(task.getExamPaperId());
            if (paper == null) {
                failAnalysis(task, "试卷不存在: paperId=" + task.getExamPaperId());
                return;
            }
            // 消费时试卷已 completed/failed → 直接返回（重投不重跑）
            if (!"pending".equals(paper.getStatus()) && !"processing".equals(paper.getStatus())) {
                log.info("试卷状态非 pending/processing，跳过解析: paperId={}, status={}",
                        paper.getId(), paper.getStatus());
                return;
            }
            markPaperProcessing(paper);

            // 3. B7：TX-A 已提交过（库中已有题目）→ 跳过视觉解析与归因，直接用库中行判分
            ExamParseOutcome outcome = null;
            if (!hasPersistedQuestions(paper.getId(), tenantId)) {
                List<String> imageUrls = fromJson(paper.getImageUrls());
                outcome = examParseService.parsePaper(
                        paper.getId(), paper.getSubjectId(), paper.getStudentId(), tenantId, imageUrls);
                if (outcome.getQuestions().isEmpty()) {
                    String reason = "试卷解析失败：所有图片均未识别出有效题目";
                    if (outcome.getImageWarnings() != null && !outcome.getImageWarnings().isEmpty()) {
                        reason = reason + "（" + String.join("；", outcome.getImageWarnings()) + "）";
                    }
                    throw new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, reason);
                }
                persistQuestions(task, paper, outcome);
            } else {
                log.info("试卷已存在题目，跳过视觉解析与归因（重试幂等）: paperId={}, taskId={}",
                        paper.getId(), taskId);
            }

            // 4. 自动判分 + TX-B 结算（错题 / 掌握度 / 学习记录 / task 完成）
            settle(task, paper, outcome);
        } catch (Exception e) {
            log.error("试卷解析/结算失败: taskId={}", taskId, e);
            failAnalysis(task, e.getMessage());
        }
    }

    /**
     * TX-A：批量插入题目（≤100/批，题号升序稳定），同时写试卷状态与告警备注
     *
     * <p>所有解析出的题目都落库：答对/答错/未作答/无法判定（{@code is_correct} 统一为 NULL，
     * 判分在 TX-B 写），因此 0 题的图只留下告警，不影响其他题。</p>
     */
    private void persistQuestions(ExamAnalysisTask task, ExamPaper paper, ExamParseOutcome outcome) {
        String tenantId = task.getTenantId();
        List<ParsedQuestion> questions = outcome.getQuestions();
        String imageWarning = buildImageWarning(outcome);

        // 2026-11-12：题目配图（按模型给的归一化区域从原卷裁剪）。文件 IO 必须在事务外做。
        Map<Integer, FigureData> figures = cropQuestionFigures(paper, questions);

        transactionTemplate.execute(status -> {
            // 题号稳定升序落库（防御：即使上游顺序抖动也按 questionIndex 排一次）
            List<ParsedQuestion> sortedQuestions = questions.stream()
                    .sorted(Comparator.comparingInt(q -> q.getQuestionIndex() == null
                            ? Integer.MAX_VALUE : q.getQuestionIndex()))
                    .collect(Collectors.toList());

            List<ExamQuestion> entities = new ArrayList<>(sortedQuestions.size());
            for (ParsedQuestion question : sortedQuestions) {
                ExamQuestion entity = new ExamQuestion();
                entity.setExamPaperId(paper.getId());
                entity.setQuestionIndex(question.getQuestionIndex());
                // 零 DDL：题干/选项/正确答案/解析/作答状态编码进 question_content（design §4.1）
                entity.setQuestionContent(ExamQuestionTextCodec.encode(
                        question.getStem(), question.getChoices(),
                        question.getCorrectAnswer(), question.getExplanation(),
                        question.getAnswerStatus()));
                entity.setKnowledgePointId(question.getKnowledgePointId());
                entity.setAttributionStatus(question.getAttributionStatus() == null
                        ? "pending_manual_review" : question.getAttributionStatus());
                entity.setStudentAnswer(question.getStudentAnswer());
                entity.setQuestionType(question.getFormatType());
                entity.setChoiceMode(question.getChoiceMode());
                entity.setDifficulty(question.getDifficulty());
                // 题目配图（裁剪件 base64 直接落库，页面不依赖文件服务地址；失败时仅保留整页原图 URL）
                FigureData figure = figures.get(question.getQuestionIndex());
                entity.setQuestionImageUrl(figure == null ? null : figure.url());
                entity.setFigureBase64(figure == null ? null : figure.base64());
                entity.setTenantId(tenantId);
                entities.add(entity);
            }

            // 批量插入，禁止逐题单条 insert（design §11.4）
            for (int from = 0; from < entities.size(); from += QUESTION_BATCH_SIZE) {
                int to = Math.min(from + QUESTION_BATCH_SIZE, entities.size());
                examQuestionMapper.batchInsert(entities.subList(from, to));
            }

            // 归因重试次数落库（单图切题重试只在内存，不写 ocr_retry_count，design D14）
            ExamAnalysisTask taskUpdate = new ExamAnalysisTask();
            taskUpdate.setId(task.getId());
            taskUpdate.setAttributionRetryCount(outcome.getAttributionRetryCount());
            examAnalysisTaskMapper.updateById(taskUpdate);

            // 题目已落库 → 试卷 completed（grade_status 仍 ungraded，TX-B 结算后置 graded）
            ExamPaper paperUpdate = new ExamPaper();
            paperUpdate.setId(paper.getId());
            paperUpdate.setStatus("completed");
            paperUpdate.setRemark(imageWarning);
            examPaperMapper.updateById(paperUpdate);
            return null;
        });
    }

    /**
     * 题目配图：按模型给出的归一化区域从来源图片裁剪（2026-11-12）
     *
     * <p>裁剪失败/区域非法时回退为「整页原图」，保证学生至少能看到图形；文件 IO
     * （读原图、写裁剪件）全部在事务外完成，避免长事务。</p>
     */
    private Map<Integer, FigureData> cropQuestionFigures(ExamPaper paper, List<ParsedQuestion> questions) {
        if (questions == null || questions.isEmpty()) {
            return Map.of();
        }
        List<String> imageUrls = fromJson(paper.getImageUrls());
        Map<Integer, FigureData> figures = new HashMap<>();
        for (ParsedQuestion question : questions) {
            if (question.getQuestionIndex() == null) {
                continue;
            }
            int index = question.getSourceImageIndex() - 1;
            if (index < 0 || index >= imageUrls.size()) {
                continue;
            }
            String sourceUrl = imageUrls.get(index);
            // 模型判断有印刷图形、但没给区域 → 回退整页配图（学生草稿不算图，模型 hasFigure=false 时不挂）
            if (!question.hasFigure()) {
                if (Boolean.TRUE.equals(question.getHasPrintedFigure())) {
                    figures.put(question.getQuestionIndex(), pageFigure(sourceUrl));
                    log.info("模型标注有印刷图形但未给区域，回退整页配图: paperId={}, questionIndex={}",
                            paper.getId(), question.getQuestionIndex());
                }
                continue;
            }
            try {
                byte[] source = fileStorageService.readBytes(sourceUrl);
                byte[] cropped = com.jiang.common.util.ImageCropper.cropJpeg(source, question.getFigureRect());
                if (cropped == null || cropped.length == 0) {
                    log.warn("题目配图裁剪失败，回退整页原图: paperId={}, questionIndex={}",
                            paper.getId(), question.getQuestionIndex());
                    figures.put(question.getQuestionIndex(), pageFigure(sourceUrl));
                    continue;
                }
                String url = fileStorageService.uploadBytes(cropped, "exam-figures/" + paper.getStudentId(), ".jpg");
                // 二次确认：裁出来的必须确实是印刷图形（否则是题号/分值/学生手写 → 回退整页）
                if (figureVerifyEnabled && !isPrintedFigure(cropped)) {
                    log.warn("配图二次确认不通过（疑似文字/题号/手写），回退整页原图: paperId={}, questionIndex={}",
                            paper.getId(), question.getQuestionIndex());
                    figures.put(question.getQuestionIndex(), pageFigure(sourceUrl));
                    continue;
                }
                // 2026-11-12：配图同时落 base64（data URL），小程序端不依赖文件服务地址直接渲染
                String base64 = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(cropped);
                figures.put(question.getQuestionIndex(), new FigureData(url, base64));
            } catch (Exception e) {
                log.warn("题目配图处理失败，回退整页原图: paperId={}, questionIndex={}, reason={}",
                        paper.getId(), question.getQuestionIndex(), e.getMessage());
                figures.put(question.getQuestionIndex(), pageFigure(sourceUrl));
            }
        }
        if (!figures.isEmpty()) {
            log.info("题目配图处理完成: paperId={}, figures={}", paper.getId(), figures.size());
        }
        return figures;
    }

    /**
     * 整页配图回退：把原卷整页降采样成 base64（1000px / 0.72），保证真机也能看到图
     *
     * <p>只在"模型没给区域但题干提到图 / 裁剪失败"时使用；降采样控制详情接口体积。</p>
     */
    private FigureData pageFigure(String sourceUrl) {
        try {
            byte[] source = fileStorageService.readBytes(sourceUrl);
            byte[] scaled = com.jiang.common.util.ImageDownscaler.downscaleJpeg(source, 1000, 0.72f);
            String base64 = "data:image/jpeg;base64,"
                    + java.util.Base64.getEncoder().encodeToString(scaled);
            return new FigureData(sourceUrl, base64);
        } catch (Exception e) {
            log.warn("整页配图回退失败（仅保留 URL）: reason={}", e.getMessage());
            return new FigureData(sourceUrl, null);
        }
    }

    /**
     * 裁剪块二次确认（2026-11-12）：只接受「试卷印刷图形」；题号/分值/学生手写/空白 → false
     *
     * <p>调用失败或回答含糊时保留裁剪块（true），避免网络抖动把配图全部丢掉。</p>
     */
    private boolean isPrintedFigure(byte[] cropped) {
        try {
            com.jiang.dto.VisionImage image = com.jiang.dto.VisionImage.of("image/jpeg", cropped);
            String answer = agentScopeClient.callVision(figureCheckPrompt(), java.util.List.of(image));
            Boolean decision = com.jiang.common.util.FigureCheckDecision.classify(answer);
            if (Boolean.FALSE.equals(decision)) {
                log.info("配图审核判定不合格: answer={}", answer);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("配图二次确认调用失败，保留裁剪块: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 配图审核 Prompt（首次加载后缓存）
     */
    private String figureCheckPrompt() {
        String cached = figureCheckPrompt;
        if (cached != null) {
            return cached;
        }
        try (java.io.InputStream is = new org.springframework.core.io.ClassPathResource(
                "prompts/versions/figure_check_v1.txt").getInputStream()) {
            figureCheckPrompt = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return figureCheckPrompt;
        } catch (Exception e) {
            throw new IllegalStateException("加载配图审核 Prompt 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 题干是否明显提到图（保守关键词：避免把纯文字题都挂整页图）
     */
    private boolean looksLikeFigureQuestion(String stem) {
        if (stem == null || stem.isBlank()) {
            return false;
        }
        String[] hints = {"如图", "图中", "下图", "上图", "所示", "示意图", "图象", "图像",
                "坐标图", "图1", "图2", "图3", "曲线", "几何图"};
        for (String hint : hints) {
            if (stem.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 配图数据：{@code url} 为文件地址（回退整页原图时用），{@code base64} 为裁剪件 data URL
     */
    private record FigureData(String url, String base64) {
    }

    /**
     * 自动判分 + TX-B 结算
     *
     * <p>判分输入统一来自库中行（按 {@code exam_question.id} 对齐，design B7），因此首次解析与
     * "重试直接用库中行"两条路径行为一致；无法判定的题（unreadable）保持 {@code is_correct = NULL}，
     * 不参与掌握度/错题记账。</p>
     */
    private void settle(ExamAnalysisTask task, ExamPaper paper, ExamParseOutcome outcome) {
        Long paperId = paper.getId();
        Long studentId = paper.getStudentId();
        Long subjectId = paper.getSubjectId();
        String tenantId = task.getTenantId();

        // 1. 判分输入（按题号升序，显式列名）
        List<ExamQuestion> questions = examQuestionMapper.selectByPaperOrderByIndex(paperId, tenantId);
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, "试卷没有可判分的题目");
        }

        // 2. 判分（LLM 调用在事务外，≤3 并发 + 60s 预算；choice/空白同步处理）
        Map<Long, Boolean> judgedByQuestionId = judgeQuestions(questions, subjectId, studentId, tenantId);

        // 3. A6：判分 key 一致性校验已前移到 judgeQuestions（gradeBatch 返回处），此处不再重复
        //    （原 size 比较形同虚设：judgeQuestions 总是按题目 id 逐条 get，条数恒等）

        // 4. 组装判分结果（1 题 1 分制；is_correct NULL → score/full_score 都 NULL）
        int judgedCount = 0;
        int correctCount = 0;
        List<ExamQuestion> judgedRows = new ArrayList<>(questions.size());
        for (ExamQuestion question : questions) {
            Boolean isCorrect = judgedByQuestionId.get(question.getId());
            ExamQuestion row = new ExamQuestion();
            row.setId(question.getId());
            if (isCorrect == null) {
                row.setIsCorrect(null);
                row.setScore(null);
                row.setFullScore(null);
            } else {
                judgedCount++;
                if (isCorrect) {
                    correctCount++;
                }
                row.setIsCorrect(isCorrect ? 1 : 0);
                row.setScore(isCorrect ? BigDecimal.ONE : BigDecimal.ZERO);
                row.setFullScore(BigDecimal.ONE);
            }
            judgedRows.add(row);
        }

        final int finalJudgedCount = judgedCount;
        final int finalCorrectCount = correctCount;
        // v4b：判分不可判定（is_correct NULL）单独计数，与 kp 维度独立
        final int finalUnjudgedCount = questions.size() - judgedCount;
        final Map<String, Object> finalStats = buildOutcomeStats(outcome, questions, finalUnjudgedCount);

        // 5. TX-B（短事务、仅 DB 写）
        Boolean settled = transactionTemplate.execute(status -> {
            // 5a. 结算 CAS：0 行 = 已被结算过 → 跳过错题/掌握度/学习记录，只把 task 收尾
            int cas = examPaperMapper.casToGraded(paperId, tenantId, BigDecimal.valueOf(finalJudgedCount));
            if (cas == 0) {
                log.info("试卷已结算过，跳过记账: paperId={}", paperId);
                examAnalysisTaskMapper.casToCompleted(task.getId(), LocalDateTime.now());
                return false;
            }

            // 5b. 判分结果批量回写（CASE WHEN，≤100/批）
            for (int from = 0; from < judgedRows.size(); from += QUESTION_BATCH_SIZE) {
                int to = Math.min(from + QUESTION_BATCH_SIZE, judgedRows.size());
                examQuestionMapper.batchUpdateJudgeResult(paperId, tenantId, judgedRows.subList(from, to));
            }

            // 5c. 错题批量入队（负 ID 命名空间；无知识点的题不入队）
            List<WrongQuestionRef> wrongItems = new ArrayList<>();
            for (ExamQuestion question : questions) {
                Boolean isCorrect = judgedByQuestionId.get(question.getId());
                if (Boolean.FALSE.equals(isCorrect) && question.getKnowledgePointId() != null) {
                    wrongItems.add(new WrongQuestionRef(-question.getId(), question.getKnowledgePointId()));
                }
            }
            if (!wrongItems.isEmpty()) {
                errorQuestionService.recordWrongAnswers(studentId, tenantId, wrongItems,
                        ErrorQuestionSourceType.EXAM, paperId, BusinessDate.today());
            }

            // 5d. 掌握度批量更新（答对答错都更新，W_source=1.0；D_norm 内部走 validateDNorm）
            List<ExamMasteryEvent> events = new ArrayList<>();
            for (ExamQuestion question : questions) {
                Boolean isCorrect = judgedByQuestionId.get(question.getId());
                if (isCorrect == null || question.getKnowledgePointId() == null) {
                    continue;
                }
                events.add(new ExamMasteryEvent(question.getKnowledgePointId(), isCorrect,
                        question.getDifficulty()));
            }
            if (!events.isEmpty()) {
                masteryService.updateMasteryFromExam(studentId, tenantId, paperId, events);
            }

            // 5e. 学习记录（规则 #9：一条试卷一条记录，写前按 exam_name 查重）
            writeLearningRecord(paper, questions, judgedByQuestionId, finalJudgedCount, finalCorrectCount,
                    finalStats, tenantId);

            // 5f. task 完成
            examAnalysisTaskMapper.casToCompleted(task.getId(), LocalDateTime.now());
            return true;
        });

        log.info("试卷结算完成: paperId={}, taskId={}, judged={}, correct={}, settled={}",
                paperId, task.getId(), finalJudgedCount, finalCorrectCount, settled);

        // 6. 结算提交后异步检测信号并自动调整（不影响试卷状态）
        examSignalAdjustService.triggerAfterSettlement(paperId, studentId, subjectId, tenantId);
    }

    /**
     * 判分：按 {@code exam_question.id} 对齐返回结果（value 为 null 表示无法判定）
     */
    private Map<Long, Boolean> judgeQuestions(List<ExamQuestion> questions, Long subjectId,
                                             Long studentId, String tenantId) {
        String subjectName = getSubjectName(subjectId, tenantId);
        String gradeLabel = getStudentGradeLabel(studentId);

        List<GradeRequest> requests = new ArrayList<>(questions.size());
        Map<Integer, Long> indexToQuestionId = new LinkedHashMap<>();
        for (int index = 0; index < questions.size(); index++) {
            ExamQuestion question = questions.get(index);
            ExamQuestionTextCodec.Decoded decoded = ExamQuestionTextCodec.decode(question.getQuestionContent());
            requests.add(GradeRequest.builder()
                    .key(index)
                    .formatType(question.getQuestionType())
                    .questionContent(decoded.stem())
                    .correctAnswer(decoded.correctAnswer())
                    .studentAnswer(question.getStudentAnswer())
                    .answerStatus(decoded.answerStatus())
                    .build());
            indexToQuestionId.put(index, question.getId());
        }

        Map<Integer, Boolean> batchResult = answerGradingService.gradeBatch(requests, subjectName, gradeLabel,
                Duration.ofSeconds(Math.max(1, gradingTimeoutSeconds)), Math.max(1, gradingConcurrency));

        // A6：判分返回的 key 集合必须与待判题索引集合一致（原 settle 里的 size 比较不可达：
        // 这里按 indexToQuestionId 逐条 get，key 永远"齐全"；真正丢判定只会表现为 value 缺失）
        Set<Integer> expectedKeys = indexToQuestionId.keySet();
        if (!batchResult.keySet().equals(expectedKeys)) {
            Set<Integer> missing = new HashSet<>(expectedKeys);
            missing.removeAll(batchResult.keySet());
            Set<Integer> unexpected = new HashSet<>(batchResult.keySet());
            unexpected.removeAll(expectedKeys);
            String reason = "判分结果 key 与待判题索引不一致: missing=" + missing
                    + ", unexpected=" + unexpected + ", questions=" + questions.size();
            log.warn("{}: studentId={}", reason, studentId);
            degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                    "studentId=" + studentId + ", questionCount=" + questions.size(), reason,
                    "judge_key_mismatch", tenantId);
            // 缺失 key 的题保持 NULL（不写 is_correct），不整体失败
        }

        Map<Long, Boolean> judgedByQuestionId = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> entry : indexToQuestionId.entrySet()) {
            judgedByQuestionId.put(entry.getValue(), batchResult.get(entry.getKey()));
        }
        return judgedByQuestionId;
    }

    /**
     * 学习记录写入（design §7.5 / 规则 #9）
     *
     * <p>字段：{@code exam_name = 科目 + 试卷类型中文 + 空格 + 考试日期}；得分为 1 题 1 分制；
     * {@code questions} 只存轻量摘要（内嵌 paperId，不存题干全文）；写前按 exam_name 查重（一条试卷一条记录）。</p>
     */
    private void writeLearningRecord(ExamPaper paper, List<ExamQuestion> questions,
                                     Map<Long, Boolean> judgedByQuestionId,
                                     int judgedCount, int correctCount,
                                     Map<String, Object> stats, String tenantId) {
        String subjectName = getSubjectName(paper.getSubjectId(), tenantId);
        String examName = truncate(buildExamName(subjectName, paper.getPaperType(), paper.getExamDate()),
                EXAM_NAME_MAX_LENGTH);

        int exists = examAnalysisResultMapper.countCompletedByExamName(
                paper.getStudentId(), tenantId, examName);
        if (exists > 0) {
            log.warn("学习记录已存在，跳过写入: studentId={}, examName={}", paper.getStudentId(), examName);
            return;
        }

        List<Map<String, Object>> items = new ArrayList<>(questions.size());
        for (ExamQuestion question : questions) {
            Boolean isCorrect = judgedByQuestionId.get(question.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionIndex", question.getQuestionIndex());
            item.put("knowledgePointId", question.getKnowledgePointId());
            item.put("questionType", question.getQuestionType());
            item.put("difficulty", question.getDifficulty());
            item.put("isCorrect", isCorrect == null ? null : (isCorrect ? 1 : 0));
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paperId", paper.getId());
        payload.put("source", "exam_paper");
        // v4/v4b：四档统计（kp 直出/兜底/未决 + 判分不可判定），零 DDL 存 JSON
        payload.put("stats", stats);
        payload.put("items", items);

        ExamAnalysisResult record = new ExamAnalysisResult();
        record.setStudentId(paper.getStudentId());
        record.setSubjectId(paper.getSubjectId());
        record.setExamName(examName);
        record.setImageUrls(paper.getImageUrls());
        record.setQuestions(toJson(payload));
        record.setTotalScore(BigDecimal.valueOf(judgedCount));
        record.setUserScore(BigDecimal.valueOf(correctCount));
        record.setStatus("completed");
        record.setAnalyzedAt(LocalDateTime.now());
        record.setTenantId(tenantId);
        examAnalysisResultMapper.insert(record);
        log.info("学习记录已写入: studentId={}, examName={}, userScore={}, totalScore={}",
                paper.getStudentId(), examName, correctCount, judgedCount);
    }

    /**
     * 试卷名称：科目 + 试卷类型中文 + 空格 + 考试日期（如"数学期中考试 2026-11-11"）
     */
    private String buildExamName(String subjectName, String paperType, LocalDate examDate) {
        String subject = (subjectName == null || subjectName.isBlank()) ? "考试" : subjectName;
        String type = ExamPaperTypeUtil.displayName(paperType);
        String date = examDate == null ? "" : " " + examDate;
        return subject + type + date;
    }

    private String getSubjectName(Long subjectId, String tenantId) {
        if (subjectId == null) {
            return null;
        }
        Subject subject = subjectMapper.selectByIdAndTenant(subjectId, tenantId);
        return subject == null ? null : subject.getName();
    }

    /**
     * 年级中文名（约定 #23；空值"当前"）
     */
    private String getStudentGradeLabel(Long studentId) {
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
     * 学习记录 stats（v4 §5 / v4b）：直出/兜底/未决 + kp 总量与截断 + 判分不可判定
     *
     * <p>B7 重试路径没有解析 outcome：此时 direct 用"库中行有 kp"近似、fallback=0、unresolved=无 kp 题数。</p>
     */
    private Map<String, Object> buildOutcomeStats(ExamParseOutcome outcome, List<ExamQuestion> questions,
                                                  int unjudgedCount) {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (outcome != null) {
            stats.put("kpDirect", outcome.getDirectKpCount());
            stats.put("kpFallback", outcome.getFallbackKpCount());
            stats.put("kpUnresolved", outcome.getUnresolvedKpCount());
            stats.put("kpTotal", outcome.getKpTotal());
            stats.put("kpTruncated", outcome.getKpTruncated());
        } else {
            long withKp = questions.stream().filter(q -> q.getKnowledgePointId() != null).count();
            stats.put("kpDirect", (int) withKp);
            stats.put("kpFallback", 0);
            stats.put("kpUnresolved", questions.size() - (int) withKp);
            stats.put("kpTotal", null);
            stats.put("kpTruncated", null);
        }
        stats.put("unjudged", unjudgedCount);
        stats.put("judged", questions.size() - unjudgedCount);
        return stats;
    }

    /**
     * 部分图片未识别时的用户提示（零 DDL：存 {@code exam_paper.remark}，由详情接口带出）
     */
    private String buildImageWarning(ExamParseOutcome outcome) {
        List<String> warnings = outcome.getImageWarnings();
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        String joined = warnings.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining("；"));
        if (joined.isBlank()) {
            return null;
        }
        String sanitized = AgentDegradationLogger.sanitize(joined);
        return truncate(sanitized, ERROR_MESSAGE_MAX_LENGTH);
    }

    /**
     * 任务失败收尾：task/paper 均置 failed，error_message 脱敏截断 500 + 降级日志
     */
    private void failAnalysis(ExamAnalysisTask task, String reason) {
        String message = truncate(AgentDegradationLogger.sanitize(
                reason == null ? "试卷解析失败" : reason), ERROR_MESSAGE_MAX_LENGTH);

        try {
            ExamAnalysisTask taskUpdate = new ExamAnalysisTask();
            taskUpdate.setId(task.getId());
            taskUpdate.setStatus("failed");
            taskUpdate.setCompletedAt(LocalDateTime.now());
            taskUpdate.setErrorMessage(message);
            examAnalysisTaskMapper.updateById(taskUpdate);

            ExamPaper paperUpdate = new ExamPaper();
            paperUpdate.setId(task.getExamPaperId());
            paperUpdate.setStatus("failed");
            examPaperMapper.updateById(paperUpdate);
        } catch (Exception e) {
            log.error("试卷解析失败状态落库异常: taskId={}", task.getId(), e);
        }

        degradationLogger.log(AgentDegradationLogger.AGENT_TYPE_EXAM_PARSE,
                "taskId=" + task.getId() + ", paperId=" + task.getExamPaperId(),
                message, "task_failed", task.getTenantId());
    }

    private boolean hasPersistedQuestions(Long examPaperId, String tenantId) {
        return examQuestionMapper.countByPaper(examPaperId, tenantId) > 0;
    }

    private void markPaperProcessing(ExamPaper paper) {
        ExamPaper update = new ExamPaper();
        update.setId(paper.getId());
        update.setStatus("processing");
        examPaperMapper.updateById(update);
        paper.setStatus("processing");
    }

    // ==================== 历史试卷读接口（design §10.1/§10.2，固定 SQL 条数） ====================

    @Override
    public PageResult<ExamPaperListItemVO> getPapers(Integer page, Integer size) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // clamp：page ≥ 1，size 1..50（design §10.1 / 约定 #3）
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (size == null || size < 1) ? 10 : Math.min(size, MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safeSize;

        // 固定 2 条 SQL：count + 分页（派生表聚合题数/正确数/已判定数）
        long total = examPaperMapper.countByStudent(studentId, tenantId);
        List<Map<String, Object>> rows = examPaperMapper.selectPageWithStats(
                studentId, tenantId, offset, safeSize);

        List<ExamPaperListItemVO> records = rows.stream()
                .map(this::toPaperListItem)
                .collect(Collectors.toList());
        return PageResult.of(total, safePage, safeSize, records);
    }

    @Override
    public ExamPaperDetailVO getPaperDetail(Long paperId) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 固定 3 条 SQL：① paper + 科目名（含归属校验）② 题目按题号升序 ③ 知识点名称批量
        Map<String, Object> paperRow = examPaperMapper.selectDetailWithSubject(paperId, studentId, tenantId);
        if (paperRow == null) {
            // 不存在 / 非本人 / 非本租户统一 1136，不区分存在性（避免枚举探测）
            throw new BusinessException(ErrorCodes.EXAM_RESULT_NOT_FOUND);
        }

        List<ExamQuestion> questions = examQuestionMapper.selectByPaperOrderByIndex(paperId, tenantId);
        // 复制为可变列表后排序（防御性：Mapper 可能返回不可变集合；题号升序稳定）
        questions = questions == null ? new ArrayList<>() : new ArrayList<>(questions);
        questions.sort(Comparator.comparingInt(q -> q.getQuestionIndex() == null
                ? Integer.MAX_VALUE : q.getQuestionIndex()));

        Set<String> kpIds = questions.stream()
                .map(ExamQuestion::getKnowledgePointId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> kpNames = new HashMap<>();
        if (!kpIds.isEmpty()) {
            List<Map<String, Object>> kpRows = knowledgePointMapper.selectNamesByIds(
                    new ArrayList<>(kpIds), tenantId);
            if (kpRows != null) {
                for (Map<String, Object> row : kpRows) {
                    kpNames.put(String.valueOf(row.get("knowledge_point_id")),
                            (String) row.get("name"));
                }
            }
        }

        List<ExamQuestionItemVO> questionItems = new ArrayList<>(questions.size());
        int correctCount = 0;
        int judgedCount = 0;
        for (ExamQuestion question : questions) {
            if (question.getIsCorrect() != null) {
                judgedCount++;
                if (question.getIsCorrect() == 1) {
                    correctCount++;
                }
            }
            questionItems.add(toQuestionItem(question, kpNames.get(question.getKnowledgePointId())));
        }

        List<String> imageUrls = fromJson((String) paperRow.get("imageUrls"));
        String paperType = (String) paperRow.get("paperType");

        return ExamPaperDetailVO.builder()
                .paperId(toLong(paperRow.get("paperId")))
                .subjectId(toLong(paperRow.get("subjectId")))
                .subjectName((String) paperRow.get("subjectName"))
                .examDate(toLocalDate(paperRow.get("examDate")))
                .paperType(paperType)
                .paperTypeName(ExamPaperTypeUtil.displayName(paperType))
                .status((String) paperRow.get("status"))
                .gradeStatus((String) paperRow.get("gradeStatus"))
                .imageUrls(imageUrls)
                .imageCount(imageUrls.size())
                .imageWarning((String) paperRow.get("remark"))
                .questionCount(questions.size())
                .correctCount(correctCount)
                .judgedCount(judgedCount)
                .userScore(BigDecimal.valueOf(correctCount))
                .totalScore(BigDecimal.valueOf(judgedCount))
                .createTime(toLocalDateTime(paperRow.get("createTime")))
                .questions(questionItems)
                .build();
    }

    /**
     * 列表行 → VO（Map 来自 selectPageWithStats，1 题 1 分制）
     */
    private ExamPaperListItemVO toPaperListItem(Map<String, Object> row) {
        int correctCount = toInt(row.get("correctCount"));
        int judgedCount = toInt(row.get("judgedCount"));
        String paperType = (String) row.get("paperType");
        return ExamPaperListItemVO.builder()
                .paperId(toLong(row.get("paperId")))
                .subjectId(toLong(row.get("subjectId")))
                .subjectName((String) row.get("subjectName"))
                .examDate(toLocalDate(row.get("examDate")))
                .paperType(paperType)
                .paperTypeName(ExamPaperTypeUtil.displayName(paperType))
                .status((String) row.get("status"))
                .gradeStatus((String) row.get("gradeStatus"))
                .questionCount(toInt(row.get("questionCount")))
                .correctCount(correctCount)
                .judgedCount(judgedCount)
                .userScore(BigDecimal.valueOf(correctCount))
                .totalScore(BigDecimal.valueOf(judgedCount))
                .createTime(toLocalDateTime(row.get("createTime")))
                .build();
    }

    /**
     * 题目实体 → 详情 VO（零 DDL：题干/选项/答案/解析由 codec 解码，约定 #25 选项只放文本）
     */
    private ExamQuestionItemVO toQuestionItem(ExamQuestion question, String knowledgePointName) {
        ExamQuestionTextCodec.Decoded decoded = ExamQuestionTextCodec.decode(question.getQuestionContent());
        List<String> choices = decoded.choices() == null ? List.of()
                : decoded.choices().stream()
                .map(option -> option.get("text"))
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.toList());

        Boolean isCorrect = question.getIsCorrect() == null ? null : question.getIsCorrect() == 1;
        String answerState;
        if ("unreadable".equals(decoded.answerStatus())) {
            answerState = "unreadable";
        } else if (question.getStudentAnswer() == null || question.getStudentAnswer().isBlank()) {
            answerState = "blank";
        } else {
            answerState = "judged";
        }

        return ExamQuestionItemVO.builder()
                .questionId(question.getId())
                .questionIndex(question.getQuestionIndex())
                .questionType(question.getQuestionType())
                .difficulty(question.getDifficulty())
                .questionContent(decoded.stem())
                .stem(decoded.stem())
                .choices(choices)
                .correctAnswer(decoded.correctAnswer())
                .explanation(decoded.explanation())
                .studentAnswer(question.getStudentAnswer())
                .isCorrect(isCorrect)
                .score(question.getScore())
                .fullScore(question.getFullScore())
                .knowledgePointId(question.getKnowledgePointId())
                .knowledgePointName(knowledgePointName)
                .attributionStatus(question.getAttributionStatus())
                .answerState(answerState)
                // 多选标记：优先用解析时模型判定的 choiceMode（真实卷出现"多选只给一个正确标签"），
                // 旧数据没有 choice_mode 时回退按答案标签数推断（2026-11-12）
                .multiChoice("choice".equals(question.getQuestionType())
                        && ("multi".equals(question.getChoiceMode())
                        || com.jiang.common.util.ChoiceAnswer.isMulti(decoded.correctAnswer())
                        || com.jiang.common.util.ChoiceAnswer.isMulti(question.getStudentAnswer())))
                .questionImageUrl(question.getQuestionImageUrl())
                .figureBase64(question.getFigureBase64())
                .build();
    }

    // ==================== 试卷诊断聚合（新用户引导方案 A，固定 4 条 SQL） ====================

    @Override
    public ExamDiagnosisVO getDiagnosis(Long paperId) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // SQL ①：paper + 科目名（含归属校验；不存在/非本人/非本租户统一 1136，不区分存在性）
        Map<String, Object> paperRow = examPaperMapper.selectDetailWithSubject(paperId, studentId, tenantId);
        if (paperRow == null) {
            throw new BusinessException(ErrorCodes.EXAM_RESULT_NOT_FOUND);
        }

        // SQL ②：题目按卷（复用详情接口同一查询，题号升序）；未解析完成 / 0 题时为空列表
        List<ExamQuestion> questions = examQuestionMapper.selectByPaperOrderByIndex(paperId, tenantId);
        questions = questions == null ? new ArrayList<>() : questions;

        // 计数（1 题 1 分制；未判定题不计入分母、不算答错；kpId 为 null 的答错题不计入薄弱点）
        int judgedCount = 0;
        int correctCount = 0;
        Map<String, Integer> wrongCountByKp = new LinkedHashMap<>();
        for (ExamQuestion question : questions) {
            Integer isCorrect = question.getIsCorrect();
            if (isCorrect == null) {
                continue;
            }
            judgedCount++;
            if (isCorrect == 1) {
                correctCount++;
            } else {
                String kpId = question.getKnowledgePointId();
                if (kpId != null && !kpId.isBlank()) {
                    wrongCountByKp.merge(kpId, 1, Integer::sum);
                }
            }
        }

        // SQL ③：该卷来源的活跃错题数（约定 #26：exam 来源 question_id = -exam_question.id）
        int errorQuestionCount = errorQuestionMapper.countActiveExamErrorsByPaper(studentId, tenantId, paperId);

        // SQL ④：薄弱知识点名称 + 当前掌握度，一次批量取回（禁止 N+1；无薄弱点时跳过）
        Map<String, String> kpNames = new HashMap<>();
        Map<String, BigDecimal> masteryByKp = new HashMap<>();
        if (!wrongCountByKp.isEmpty()) {
            List<Map<String, Object>> kpRows = knowledgePointMapper.selectNamesWithMasteryByIds(
                    new ArrayList<>(wrongCountByKp.keySet()), tenantId, studentId);
            if (kpRows != null) {
                for (Map<String, Object> row : kpRows) {
                    String kpId = String.valueOf(row.get("knowledgePointId"));
                    kpNames.put(kpId, (String) row.get("name"));
                    masteryByKp.put(kpId, toBigDecimal(row.get("masteryScore")));
                }
            }
        }

        // 薄弱知识点排序：wrongCount desc → masteryScore asc（无掌握度按 0 视为最弱）→ kpId asc（稳定兜底），
        // 截断前 10
        List<ExamDiagnosisWeakKpVO> weakPoints = wrongCountByKp.entrySet().stream()
                .map(entry -> ExamDiagnosisWeakKpVO.builder()
                        .knowledgePointId(entry.getKey())
                        .knowledgePointName(kpNames.get(entry.getKey()))
                        .wrongCount(entry.getValue())
                        .masteryScore(masteryByKp.get(entry.getKey()))
                        .build())
                .sorted(Comparator
                        .comparingInt(ExamDiagnosisWeakKpVO::getWrongCount).reversed()
                        .thenComparing(w -> w.getMasteryScore() == null
                                ? BigDecimal.ZERO : w.getMasteryScore())
                        .thenComparing(ExamDiagnosisWeakKpVO::getKnowledgePointId))
                .limit(WEAK_KP_LIMIT)
                .collect(Collectors.toList());

        // 正确率：judged=0 时为 null（不可判定题不折算）
        BigDecimal accuracy = judgedCount == 0 ? null
                : BigDecimal.valueOf(correctCount)
                .divide(BigDecimal.valueOf(judgedCount), ACCURACY_SCALE, RoundingMode.HALF_UP);

        return ExamDiagnosisVO.builder()
                .paperId(toLong(paperRow.get("paperId")))
                .subjectId(toLong(paperRow.get("subjectId")))
                .subjectName((String) paperRow.get("subjectName"))
                .examDate(toLocalDate(paperRow.get("examDate")))
                .paperType((String) paperRow.get("paperType"))
                .totalQuestions(questions.size())
                .judgedCount(judgedCount)
                .correctCount(correctCount)
                .userScore(BigDecimal.valueOf(correctCount))
                .totalScore(BigDecimal.valueOf(judgedCount))
                .accuracy(accuracy)
                .weakKnowledgePoints(weakPoints)
                .errorQuestionCount(errorQuestionCount)
                .confidenceNote(DIAGNOSIS_CONFIDENCE_NOTE)
                .build();
    }

    @Override
    public ExamTaskProgressVO getTaskProgress(Long taskId) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        ExamAnalysisTask task = examAnalysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCodes.EXAM_TASK_NOT_FOUND);
        }
        // B4：task → paper 校验 student_id + tenant_id（越权/不存在统一 1135，不区分存在性）
        ExamPaper paper = examPaperMapper.selectById(task.getExamPaperId());
        if (paper == null || !studentId.equals(paper.getStudentId())
                || !tenantId.equals(paper.getTenantId())) {
            throw new BusinessException(ErrorCodes.EXAM_TASK_NOT_FOUND);
        }

        return ExamTaskProgressVO.builder()
                .taskId(task.getId())
                .paperId(task.getExamPaperId())
                .status(task.getStatus())
                .ocrRetryCount(task.getOcrRetryCount())
                .attributionRetryCount(task.getAttributionRetryCount())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .errorMessage(task.getErrorMessage())
                .build();
    }

    // ==================== 工具方法 ====================

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> values = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
            return values == null ? new ArrayList<>() : values.stream()
                    .filter(url -> url != null && !url.isBlank())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("解析试卷图片列表失败，按空处理: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) {
            return null;
        }
        return str.length() > maxLen ? str.substring(0, maxLen) : str;
    }

    // ==================== Map 行转换（读接口显式列名 → VO） ====================

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    /**
     * Map 行 → BigDecimal（诊断用：mastery_score 等 DECIMAL 列；null 透传 null）
     */
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    private LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDate localDate) {
            return localDate.atStartOfDay();
        }
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}
