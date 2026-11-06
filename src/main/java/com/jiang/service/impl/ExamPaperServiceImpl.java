package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.AttributionAgent;
import com.jiang.common.exception.AttributionException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.exception.OcrException;
import com.jiang.common.result.PageResult;
import com.jiang.dto.ExamGradeItemVO;
import com.jiang.dto.ExamGradeRequest;
import com.jiang.dto.ExamQuestionAttributionDTO;
import com.jiang.dto.ExamQuestionOcrDTO;
import com.jiang.mapper.*;
import com.jiang.model.entity.ExamAnalysisTask;
import com.jiang.model.entity.ExamPaper;
import com.jiang.model.entity.ExamQuestion;
import com.jiang.model.entity.Subject;
import com.jiang.security.StudentContext;
import com.jiang.service.ErrorQuestionService;
import com.jiang.service.ExamPaperService;
import com.jiang.service.FileStorageService;
import com.jiang.service.MasteryService;
import com.jiang.service.OcrService;
import com.jiang.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 试卷服务实现
 * 修复 BLOCK-5/6 + IMP-6/7/11/25/26
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Service
@Slf4j
public class ExamPaperServiceImpl implements ExamPaperService {

    private static final String QUEUE_NAME = "exam_analysis_queue";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private ExamPaperMapper examPaperMapper;
    @Autowired
    private ExamQuestionMapper examQuestionMapper;
    @Autowired
    private ExamAnalysisTaskMapper examAnalysisTaskMapper;
    @Autowired
    private SubjectMapper subjectMapper;
    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private OcrService ocrService;
    @Autowired
    private AttributionAgent attributionAgent;
    @Autowired
    private MasteryService masteryService;
    @Autowired
    private ErrorQuestionService errorQuestionService;

    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 上传试卷
     * 修复 IMP-6：文件上传在事务外，仅 DB 写入在事务内
     */
    @Override
    public ExamUploadVO uploadExamPaper(Long subjectId, LocalDate examDate,
                                         String paperType, List<MultipartFile> files) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 1. 校验科目存在
        Subject subject = subjectMapper.selectById(subjectId);
        if (subject == null) {
            throw new BusinessException(ErrorCodes.SUBJECT_NOT_FOUND);
        }

        // 2. 校验文件大小与类型（限制单张 ≤ 10MB，仅 jpg/jpeg/png）
        validateFiles(files);

        // 3. 文件上传（事务外）
        List<String> imageUrls = files.stream()
                .map(file -> fileStorageService.upload(file, "exam-papers/" + studentId))
                .collect(Collectors.toList());

        // 4. 事务内写入 DB
        try {
            return transactionTemplate.execute(status -> {
                // 4a. 创建 exam_paper 记录
                ExamPaper examPaper = new ExamPaper();
                examPaper.setStudentId(studentId);
                examPaper.setSubjectId(subjectId);
                examPaper.setExamDate(examDate);
                examPaper.setPaperType(paperType);
                examPaper.setImageUrls(toJson(imageUrls));
                examPaper.setStatus("pending");
                examPaper.setGradeStatus("ungraded");
                examPaper.setTenantId(tenantId);
                examPaperMapper.insert(examPaper);

                // 4b. 创建 exam_analysis_task 记录
                ExamAnalysisTask task = new ExamAnalysisTask();
                task.setExamPaperId(examPaper.getId());
                task.setTaskType("ocr_attribution");
                task.setStatus("pending");
                task.setTenantId(tenantId);
                examAnalysisTaskMapper.insert(task);

                // 4c. 推入 Redis 队列
                redisTemplate.opsForList().leftPush(QUEUE_NAME, task.getId());

                // 4d. 返回
                return ExamUploadVO.builder()
                        .taskId(task.getId())
                        .examPaperId(examPaper.getId())
                        .status("pending")
                        .build();
            });
        } catch (DuplicateKeyException e) {
            // 修复 BLOCK-4：同一学生同一科目同一天重复上传
            log.warn("试卷重复上传: studentId={}, subjectId={}, examDate={}", studentId, subjectId, examDate);
            throw new BusinessException(ErrorCodes.EXAM_PAPER_DUPLICATE);
        }
    }

    /**
     * 处理试卷解析任务（由 Worker 调用）
     * 修复 BLOCK-5：同步处理，不使用 @Async
     * 修复 IMP-7：最多重试 2 次 = 总共 3 次尝试
     * 修复 IMP-11：task 状态变更时联动更新 paper 状态
     * 修复 IMP-26：OCR 全部降级时跳过归因 Agent
     */
    @Override
    public void processExamAnalysis(Long taskId) {
        // 1. 查询任务
        ExamAnalysisTask task = examAnalysisTaskMapper.selectById(taskId);
        if (task == null || !"pending".equals(task.getStatus())) {
            return;
        }

        // 2. 更新 task 状态为 processing，联动 paper 状态
        updateTaskAndPaperStatus(task, "processing");

        try {
            // 3. OCR 切题（修复 IMP-7：最多重试 2 次）
            List<ExamQuestionOcrDTO> ocrResults = ocrWithRetry(task, 2);

            // 修复 IMP-26：判断 OCR 是否全部降级
            boolean allOcrDegraded = ocrResults.stream()
                    .allMatch(r -> "pending_manual_review".equals(r.getAttributionStatus()));

            List<ExamQuestionAttributionDTO> attributionResults;
            if (allOcrDegraded) {
                // OCR 全部失败，跳过归因 Agent，直接标记待人工处理
                log.warn("OCR 全部降级，跳过归因 Agent: taskId={}", task.getId());
                attributionResults = ocrResults.stream()
                        .map(ocr -> {
                            ExamQuestionAttributionDTO dto = new ExamQuestionAttributionDTO();
                            dto.setQuestionContent(ocr.getQuestionContent());
                            dto.setAttributionStatus("pending_manual_review");
                            dto.setKnowledgePointId(null);
                            return dto;
                        })
                        .collect(Collectors.toList());
            } else {
                // 4. 归因 Agent（最多重试 2 次）
                attributionResults = attributionWithRetry(ocrResults, task, 2);
            }

            // 5. 事务内写入数据库
            // 修复 Sev-2：Worker 线程无 StudentContext，从 task 对象获取 tenantId 并显式传入
            String tenantId = task.getTenantId();
            transactionTemplate.execute(status -> {
                saveExamQuestions(task.getExamPaperId(), attributionResults, tenantId);
                updateTaskAndPaperStatus(task, "completed");
                return null;
            });

            // 6. 不再自动触发掌握度更新和错题入队
            // 等待学生通过 submitExamGrade 手动录入成绩后触发

        } catch (Exception e) {
            log.error("试卷解析失败: taskId={}", taskId, e);
            updateTaskAndPaperStatus(task, "failed");
            task.setErrorMessage(truncate(e.getMessage(), 500));
            examAnalysisTaskMapper.updateById(task);
        }
    }

    /**
     * 提交考试成绩
     * 修复 BLOCK-6：学生手动录入成绩后触发掌握度更新和错题入队
     * 修复 IMP-25：错题入队先于掌握度更新
     * 修复 BLOCK-A：方法签名删除 request 参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitExamGrade(Long resultId, ExamGradeRequest request) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 1. 查询试卷
        ExamPaper examPaper = examPaperMapper.selectById(resultId);
        if (examPaper == null || !tenantId.equals(examPaper.getTenantId())) {
            throw new BusinessException(ErrorCodes.EXAM_PAPER_NOT_FOUND);
        }
        if ("graded".equals(examPaper.getGradeStatus())) {
            throw new BusinessException(ErrorCodes.EXAM_PAPER_ALREADY_GRADED);
        }
        if (!"completed".equals(examPaper.getStatus())) {
            throw new BusinessException(ErrorCodes.EXAM_PAPER_NOT_READY_FOR_GRADE);
        }

        // 2. 逐题更新成绩
        List<ExamQuestion> questions = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>()
                        .eq(ExamQuestion::getExamPaperId, resultId)
                        .eq(ExamQuestion::getTenantId, tenantId));
        for (ExamGradeItemVO item : request.getItems()) {
            ExamQuestion question = questions.stream()
                    .filter(q -> q.getQuestionIndex().equals(item.getQuestionIndex()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCodes.EXAM_QUESTION_NOT_FOUND));

            question.setStudentAnswer(item.getStudentAnswer());
            question.setIsCorrect(item.getIsCorrect());
            question.setScore(item.getScore());
            question.setFullScore(item.getFullScore());
            examQuestionMapper.updateById(question);
        }

        // 3. 更新试卷状态
        examPaper.setGradeStatus("graded");
        examPaper.setTotalScore(request.getTotalScore());
        examPaperMapper.updateById(examPaper);

        // 4. 错题入队（修复 IMP-25：先于掌握度更新）
        recordWrongAnswers(resultId, questions);

        // 5. 更新掌握度（W_source=1.0）
        updateMasteryFromExam(resultId, questions);
    }

    @Override
    public ExamTaskProgressVO getTaskProgress(Long taskId) {
        String tenantId = StudentContext.getTenantId();
        ExamAnalysisTask task = examAnalysisTaskMapper.selectById(taskId);
        if (task == null || !tenantId.equals(task.getTenantId())) {
            throw new BusinessException(ErrorCodes.EXAM_TASK_NOT_FOUND);
        }

        return ExamTaskProgressVO.builder()
                .taskId(task.getId())
                .status(task.getStatus())
                .ocrRetryCount(task.getOcrRetryCount())
                .attributionRetryCount(task.getAttributionRetryCount())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .errorMessage(task.getErrorMessage())
                .build();
    }

    @Override
    public ExamResultDetailVO getResultDetail(Long resultId) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        ExamPaper examPaper = examPaperMapper.selectById(resultId);
        if (examPaper == null || !tenantId.equals(examPaper.getTenantId())
                || !studentId.equals(examPaper.getStudentId())) {
            throw new BusinessException(ErrorCodes.EXAM_RESULT_NOT_FOUND);
        }

        // 查询题目列表
        List<ExamQuestion> questions = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>()
                        .eq(ExamQuestion::getExamPaperId, resultId)
                        .eq(ExamQuestion::getTenantId, tenantId)
                        .orderByAsc(ExamQuestion::getQuestionIndex));

        // 查询科目名称
        Subject subject = subjectMapper.selectById(examPaper.getSubjectId());
        String subjectName = subject != null ? subject.getName() : null;

        // 构建题目详情
        List<ExamQuestionDetailVO> questionDetails = questions.stream()
                .map(q -> {
                    String kpName = null;
                    if (q.getKnowledgePointId() != null) {
                        var kp = knowledgePointMapper.selectById(q.getKnowledgePointId());
                        kpName = kp != null ? kp.getName() : null;
                    }
                    return ExamQuestionDetailVO.builder()
                            .questionId(q.getId())
                            .questionIndex(q.getQuestionIndex())
                            .questionContent(q.getQuestionContent())
                            .questionImageUrl(q.getQuestionImageUrl())
                            .knowledgePointId(q.getKnowledgePointId())
                            .knowledgePointName(kpName)
                            .attributionStatus(q.getAttributionStatus())
                            .studentAnswer(q.getStudentAnswer())
                            .isCorrect(q.getIsCorrect())
                            .score(q.getScore())
                            .fullScore(q.getFullScore())
                            .questionType(q.getQuestionType())
                            .difficulty(q.getDifficulty())
                            .build();
                })
                .collect(Collectors.toList());

        return ExamResultDetailVO.builder()
                .examPaperId(examPaper.getId())
                .subjectId(examPaper.getSubjectId())
                .subjectName(subjectName)
                .examDate(examPaper.getExamDate())
                .paperType(examPaper.getPaperType())
                .imageUrls(fromJson(examPaper.getImageUrls()))
                .status(examPaper.getStatus())
                .gradeStatus(examPaper.getGradeStatus())
                .totalScore(examPaper.getTotalScore())
                .questions(questionDetails)
                .createTime(examPaper.getCreateTime())
                .build();
    }

    @Override
    public PageResult<ExamResultListVO> getResultHistory(Integer page, Integer size) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 修复 Issue-4：统一使用 MyBatis-Plus Page 分页
        Page<ExamPaper> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ExamPaper> wrapper = new LambdaQueryWrapper<ExamPaper>()
                .eq(ExamPaper::getStudentId, studentId)
                .eq(ExamPaper::getTenantId, tenantId)
                .orderByDesc(ExamPaper::getCreateTime);

        Page<ExamPaper> result = examPaperMapper.selectPage(pageParam, wrapper);
        List<ExamPaper> papers = result.getRecords();

        // 修复 Issue-1：批量查询科目名称，避免 N+1
        List<Long> subjectIds = papers.stream()
                .map(ExamPaper::getSubjectId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> subjectNameMap = subjectIds.isEmpty()
                ? Collections.emptyMap()
                : subjectMapper.selectNamesByIds(subjectIds).stream()
                        .collect(Collectors.toMap(
                                m -> ((Number) m.get("subject_id")).longValue(),
                                m -> (String) m.get("name")));

        List<ExamResultListVO> records = papers.stream()
                .map(p -> ExamResultListVO.builder()
                .examPaperId(p.getId())
                .subjectId(p.getSubjectId())
                .subjectName(subjectNameMap.getOrDefault(p.getSubjectId(), null))
                .examDate(p.getExamDate())
                .paperType(p.getPaperType())
                .status(p.getStatus())
                .gradeStatus(p.getGradeStatus())
                .totalScore(p.getTotalScore())
                .build())
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, records);
    }

    // ========== 私有方法 ==========

    private void validateFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCodes.FILE_UPLOAD_FAILED);
        }
        for (MultipartFile file : files) {
            if (file.getSize() > 10 * 1024 * 1024) {
                throw new BusinessException(ErrorCodes.FILE_TYPE_INVALID);
            }
            String contentType = file.getContentType();
            if (contentType == null || !contentType.matches("image/(jpeg|jpg|png)")) {
                throw new BusinessException(ErrorCodes.FILE_TYPE_INVALID);
            }
        }
    }

    private List<ExamQuestionOcrDTO> ocrWithRetry(ExamAnalysisTask task, int maxRetries) {
        int retryCount = 0;
        Exception lastException = null;

        ExamPaper examPaper = examPaperMapper.selectById(task.getExamPaperId());
        List<String> imageUrls = fromJson(examPaper.getImageUrls());

        while (retryCount <= maxRetries) {
            try {
                List<ExamQuestionOcrDTO> results = new ArrayList<>();
                for (String imageUrl : imageUrls) {
                    results.addAll(ocrService.recognize(imageUrl));
                }
                return results;
            } catch (OcrException e) {
                lastException = e;
                retryCount++;
                task.setOcrRetryCount(retryCount);
                examAnalysisTaskMapper.updateById(task);
                log.warn("OCR 切题失败，重试 {}/{}: taskId={}", retryCount, maxRetries, task.getId());
            }
        }

        // 重试耗尽：降级处理
        log.error("OCR 切题重试耗尽，降级为待人工归因: taskId={}", task.getId(), lastException);
        return imageUrls.stream()
                .map(url -> {
                    ExamQuestionOcrDTO dto = new ExamQuestionOcrDTO();
                    dto.setQuestionContent("[OCR 识别失败，待人工处理]");
                    dto.setAttributionStatus("pending_manual_review");
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private List<ExamQuestionAttributionDTO> attributionWithRetry(
            List<ExamQuestionOcrDTO> ocrResults, ExamAnalysisTask task, int maxRetries) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount <= maxRetries) {
            try {
                ExamPaper paper = examPaperMapper.selectById(task.getExamPaperId());
                return attributionAgent.attribute(ocrResults, paper.getSubjectId(), task.getTenantId());
            } catch (AttributionException e) {
                lastException = e;
                retryCount++;
                task.setAttributionRetryCount(retryCount);
                examAnalysisTaskMapper.updateById(task);
                log.warn("归因 Agent 失败，重试 {}/{}: taskId={}", retryCount, maxRetries, task.getId());
            }
        }

        // 重试耗尽：降级处理
        log.error("归因 Agent 重试耗尽，降级为待人工归因: taskId={}", task.getId(), lastException);
        return ocrResults.stream()
                .map(ocr -> {
                    ExamQuestionAttributionDTO dto = new ExamQuestionAttributionDTO();
                    dto.setQuestionContent(ocr.getQuestionContent());
                    dto.setAttributionStatus("pending_manual_review");
                    dto.setKnowledgePointId(null);
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /**
     * 修复 Sev-2：tenantId 由调用方传入，不再依赖 StudentContext
     */
    private void saveExamQuestions(Long examPaperId, List<ExamQuestionAttributionDTO> results, String tenantId) {
        for (int i = 0; i < results.size(); i++) {
            ExamQuestionAttributionDTO dto = results.get(i);
            ExamQuestion question = new ExamQuestion();
            question.setExamPaperId(examPaperId);
            question.setQuestionIndex(dto.getQuestionIndex() != null ? dto.getQuestionIndex() : i + 1);
            question.setQuestionContent(dto.getQuestionContent());
            question.setKnowledgePointId(dto.getKnowledgePointId());
            question.setAttributionStatus(dto.getAttributionStatus());
            question.setQuestionType(dto.getQuestionType());
            question.setDifficulty(dto.getDifficulty());
            question.setTenantId(tenantId);
            examQuestionMapper.insert(question);
        }
    }

    private void updateTaskAndPaperStatus(ExamAnalysisTask task, String newTaskStatus) {
        task.setStatus(newTaskStatus);
        if ("processing".equals(newTaskStatus)) {
            task.setStartedAt(LocalDateTime.now());
        } else if ("completed".equals(newTaskStatus) || "failed".equals(newTaskStatus)) {
            task.setCompletedAt(LocalDateTime.now());
        }
        examAnalysisTaskMapper.updateById(task);

        // 联动更新 paper 状态
        ExamPaper paper = examPaperMapper.selectById(task.getExamPaperId());
        if (paper != null) {
            paper.setStatus(aggregatePaperStatus(paper.getId(), task.getTenantId()));
            examPaperMapper.updateById(paper);
        }
    }

    private String aggregatePaperStatus(Long examPaperId, String tenantId) {
        List<ExamAnalysisTask> tasks = examAnalysisTaskMapper.selectList(
                new LambdaQueryWrapper<ExamAnalysisTask>()
                        .eq(ExamAnalysisTask::getExamPaperId, examPaperId)
                        .eq(ExamAnalysisTask::getTenantId, tenantId));
        boolean hasFailed = tasks.stream().anyMatch(t -> "failed".equals(t.getStatus()));
        boolean hasProcessing = tasks.stream().anyMatch(t -> "processing".equals(t.getStatus()));
        boolean allCompleted = tasks.stream().allMatch(t -> "completed".equals(t.getStatus()));

        if (allCompleted) return "completed";
        if (hasProcessing) return "processing";
        if (hasFailed) return "failed";
        return "pending";
    }

    /**
     * 修复 BLOCK-A：删除 request 参数，仅使用 questions
     */
    private void updateMasteryFromExam(Long examPaperId, List<ExamQuestion> questions) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        for (ExamQuestion q : questions) {
            if (q.getKnowledgePointId() == null || q.getIsCorrect() == null) {
                continue;
            }
            // 将 difficulty 转换为 dNorm
            BigDecimal dNorm = convertDifficultyToDNorm(q.getDifficulty());
            masteryService.updateMasteryFromResult(
                    studentId, tenantId,
                    q.getKnowledgePointId(),
                    com.jiang.model.enums.ChangeType.EXAM,
                    examPaperId,
                    q.getIsCorrect() == 1,
                    dNorm
            );
        }
    }

    /**
     * 修复 Sev-1：使用 QuestionDifficulty 枚举统一 D_norm 映射，避免与产品规则不一致
     */
    private BigDecimal convertDifficultyToDNorm(String difficulty) {
        try {
            return com.jiang.model.enums.QuestionDifficulty.fromCode(difficulty).getDNorm();
        } catch (IllegalArgumentException e) {
            return new BigDecimal("0.5");
        }
    }

    /**
     * 修复 BLOCK-A：删除 request 参数，仅使用 questions
     */
    private void recordWrongAnswers(Long examPaperId, List<ExamQuestion> questions) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        LocalDate today = LocalDate.now();
        for (ExamQuestion q : questions) {
            if (q.getIsCorrect() != null && q.getIsCorrect() == 0 && q.getKnowledgePointId() != null) {
                // 考试错题入队
                // 注意：exam_question 没有 question_id 字段（OCR 切题后未关联题库），
                // 这里传 null 作为 questionId，表示这是一道"游离"错题
                errorQuestionService.recordWrongAnswer(
                        studentId, tenantId,
                        null, // questionId（考试 OCR 未关联题库）
                        q.getKnowledgePointId(),
                        com.jiang.model.enums.ErrorQuestionSourceType.EXAM,
                        examPaperId,
                        today
                );
            }
        }
    }

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return OBJECT_MAPPER.readValue(json, typeRef);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> fromJson(String json) {
        return fromJson(json, new TypeReference<List<String>>() {});
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) : str;
    }
}
