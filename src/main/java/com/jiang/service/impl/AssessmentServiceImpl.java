package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.common.util.AssessmentQuestionSelector;
import com.jiang.common.util.MasteryFormulaUtil;
import com.jiang.dto.AnswerItem;
import com.jiang.mapper.AssessmentResultMapper;
import com.jiang.mapper.AssessmentSubmissionDetailMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.AssessmentResult;
import com.jiang.model.entity.AssessmentSubmissionDetail;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.ChangeType;
import com.jiang.model.enums.ErrorQuestionSourceType;
import com.jiang.service.AssessmentService;
import com.jiang.service.ErrorQuestionService;
import com.jiang.service.MasteryService;
import com.jiang.vo.AssessmentDetailVO;
import com.jiang.vo.AssessmentHistoryVO;
import com.jiang.vo.AssessmentQuestionVO;
import com.jiang.vo.AssessmentQuestionWithAnswerVO;
import com.jiang.vo.AssessmentResultVO;
import com.jiang.vo.AssessmentVO;
import com.jiang.vo.KpAccuracyVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 专项评测 Service 实现
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@Slf4j
@Service
public class AssessmentServiceImpl implements AssessmentService {

    private static final BigDecimal MASTERY_WARNING_THRESHOLD = new BigDecimal("0.8");

    @Autowired
    private AssessmentResultMapper assessmentResultMapper;

    @Autowired
    private AssessmentSubmissionDetailMapper submissionDetailMapper;

    @Autowired
    private SubjectMapper subjectMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private StudentKnowledgeStateMapper studentKnowledgeStateMapper;

    @Autowired
    private QuestionBankMapper questionBankMapper;

    @Autowired
    private ErrorQuestionService errorQuestionService;

    @Autowired
    private MasteryService masteryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== createAssessment ====================

    @Override
    public AssessmentVO createAssessment(Long studentId, String tenantId, Long subjectId) {
        // 1. 校验科目存在（带租户隔离）
        Subject subject = subjectMapper.selectOne(
                new LambdaQueryWrapper<Subject>()
                        .eq(Subject::getSubjectId, subjectId)
                        .eq(Subject::getTenantId, tenantId));
        if (subject == null) {
            throw new BusinessException(ErrorCodes.SUBJECT_NOT_FOUND);
        }

        // 2. 查询该科目下所有 active 知识点
        List<KnowledgePoint> activeKps = knowledgePointMapper.selectList(
                new LambdaQueryWrapper<KnowledgePoint>()
                        .eq(KnowledgePoint::getSubject, subject.getName())
                        .eq(KnowledgePoint::getStatus, "active")
                        .eq(KnowledgePoint::getTenantId, tenantId));
        if (activeKps.isEmpty()) {
            throw new BusinessException(ErrorCodes.SUBJECT_NO_ACTIVE_KP);
        }

        // 3. BLOCK-5：超过 5 个知识点时，按掌握度 ASC 选最低的 5 个
        List<KnowledgePoint> selectedKps;
        String warning = null;
        if (activeKps.size() <= AssessmentQuestionSelector.MAX_KPS_PER_ASSESSMENT) {
            selectedKps = activeKps;
        } else {
            selectedKps = selectKpsByMastery(studentId, tenantId, activeKps);
        }

        // IMP-12：检查是否所有知识点掌握度均 ≥ 0.8
        if (selectedKps.size() >= AssessmentQuestionSelector.MAX_KPS_PER_ASSESSMENT) {
            warning = checkAllMasteryAboveThreshold(studentId, tenantId, selectedKps);
        }

        // 4. 对每个知识点抽题
        List<QuestionBank> allSelectedQuestions = new ArrayList<>();
        List<String> selectedKpIds = new ArrayList<>();
        for (KnowledgePoint kp : selectedKps) {
            List<QuestionBank> candidates = questionBankMapper.selectActiveByKp(
                    kp.getKnowledgePointId(), tenantId);
            if (candidates.isEmpty()) {
                log.warn("[createAssessment] 知识点 {} 无可用题目，跳过", kp.getKnowledgePointId());
                continue;
            }
            List<QuestionBank> selected = AssessmentQuestionSelector.select(
                    candidates, AssessmentQuestionSelector.QUESTIONS_PER_KP);
            allSelectedQuestions.addAll(selected);
            selectedKpIds.add(kp.getKnowledgePointId());
        }

        // 5. 所有知识点均无题 → 抛异常
        if (allSelectedQuestions.isEmpty()) {
            throw new BusinessException(ErrorCodes.QUESTION_NOT_FOUND);
        }

        // 6. 构造 results JSON（含 answer 供后续批改比对）
        String resultsJson = buildResultsJson(allSelectedQuestions);

        // 7. 构造 knowledgePointIds JSON
        String kpIdsJson;
        try {
            kpIdsJson = objectMapper.writeValueAsString(selectedKpIds);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "知识点 ID 序列化失败");
        }

        // 8. INSERT AssessmentResult
        AssessmentResult result = new AssessmentResult();
        result.setStudentId(studentId);
        result.setSubjectId(subjectId);
        result.setKnowledgePointIds(kpIdsJson);
        result.setResults(resultsJson);
        result.setStatus("pending");
        result.setScore(null);
        result.setTotalScore(null);
        result.setAssessedAt(null);
        result.setTenantId(tenantId);
        assessmentResultMapper.insert(result);

        // 9. 返回 AssessmentVO
        return AssessmentVO.builder()
                .assessmentId(result.getAssessmentId())
                .subjectId(subjectId)
                .subjectName(subject.getName())
                .knowledgePointIds(selectedKpIds)
                .questionCount(allSelectedQuestions.size())
                .status("pending")
                .warning(warning)
                .createdAt(result.getCreateTime())
                .build();
    }

    // ==================== getAssessmentDetail ====================

    @Override
    public AssessmentDetailVO getAssessmentDetail(Long studentId, String tenantId, Long assessmentId) {
        // 1. 查询 AssessmentResult（带租户隔离 + 归属校验）
        AssessmentResult assessment = assessmentResultMapper.selectOne(
                new LambdaQueryWrapper<AssessmentResult>()
                        .eq(AssessmentResult::getAssessmentId, assessmentId)
                        .eq(AssessmentResult::getTenantId, tenantId)
                        .eq(AssessmentResult::getStudentId, studentId));
        if (assessment == null) {
            throw new BusinessException(ErrorCodes.ASSESSMENT_NOT_FOUND);
        }

        // 2. 解析 results JSON，使用字段白名单映射（不泄露 answer/analysis）
        List<AssessmentQuestionVO> questions = parseQuestionsWithWhitelist(assessment.getResults());

        // 4. 查询科目名称
        Subject subject = subjectMapper.selectById(assessment.getSubjectId());
        String subjectName = subject != null ? subject.getName() : null;

        // 5. 查询知识点名称
        List<String> kpIds = questions.stream()
                .map(AssessmentQuestionVO::getKnowledgePointId)
                .distinct()
                .collect(Collectors.toList());
        Map<String, String> kpNameMap = resolveKpNames(kpIds, tenantId);
        questions.forEach(q -> q.setKnowledgePointName(kpNameMap.get(q.getKnowledgePointId())));

        // 6. 返回 AssessmentDetailVO
        return AssessmentDetailVO.builder()
                .assessmentId(assessmentId)
                .subjectId(assessment.getSubjectId())
                .subjectName(subjectName)
                .status(assessment.getStatus())
                .questions(questions)
                .totalQuestions(questions.size())
                .createdAt(assessment.getCreateTime())
                .build();
    }

    // ==================== submitAndGrade ====================

    @Transactional(rollbackFor = Exception.class)
    @Override
    public AssessmentResultVO submitAndGrade(Long studentId, String tenantId,
                                              Long assessmentId, List<AnswerItem> answers) {
        // 1. 查询 AssessmentResult（带租户隔离 + 归属校验）
        AssessmentResult assessment = assessmentResultMapper.selectOne(
                new LambdaQueryWrapper<AssessmentResult>()
                        .eq(AssessmentResult::getAssessmentId, assessmentId)
                        .eq(AssessmentResult::getTenantId, tenantId)
                        .eq(AssessmentResult::getStudentId, studentId));
        if (assessment == null) {
            throw new BusinessException(ErrorCodes.ASSESSMENT_NOT_FOUND);
        }

        // 2. 状态校验（IMP-11：状态机校验）
        if ("graded".equals(assessment.getStatus())) {
            throw new BusinessException(ErrorCodes.ASSESSMENT_ALREADY_GRADED);
        }
        if (!"pending".equals(assessment.getStatus())) {
            throw new BusinessException(ErrorCodes.ASSESSMENT_INVALID_STATUS);
        }

        // 3. 从 results JSON 解析题目列表
        List<Map<String, Object>> questionMaps = parseResultsToMaps(assessment.getResults());

        // 4. 构造答案映射（questionId → studentAnswer）
        Map<Long, String> answerMap = new HashMap<>();
        for (AnswerItem item : answers) {
            answerMap.put(item.getQuestionId(), item.getAnswer());
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        // 5. BLOCK-1：逐题批改
        List<AssessmentSubmissionDetail> submissionDetails = gradeQuestions(
                questionMaps, answerMap, assessmentId, studentId, tenantId, now);
        int correctCount = (int) submissionDetails.stream()
                .filter(d -> d.getIsCorrect() == 1).count();

        int totalQuestions = questionMaps.size();
        BigDecimal score = BigDecimal.valueOf(correctCount);
        BigDecimal totalScore = BigDecimal.valueOf(totalQuestions);

        // 6. 构造各知识点正确率（从 submissionDetails + questionMaps 重建 gradedQuestionMaps）
        List<Map<String, Object>> gradedQuestionMaps = buildGradedQuestionMaps(questionMaps, submissionDetails);
        List<String> kpIds = gradedQuestionMaps.stream()
                .map(q -> (String) q.get("knowledgePointId"))
                .distinct()
                .collect(Collectors.toList());
        Map<String, String> kpNameMap = resolveKpNames(kpIds, tenantId);
        List<KpAccuracyVO> kpAccuracies = calculateKpAccuracies(gradedQuestionMaps, kpNameMap);

        // 7. 构造更新后的 results JSON（追加 answers + statistics）
        Map<String, Object> updatedResults = new LinkedHashMap<>();
        updatedResults.put("questions", gradedQuestionMaps);
        updatedResults.put("statistics", Map.of(
                "totalQuestions", totalQuestions,
                "correctCount", correctCount,
                "kpAccuracies", kpAccuracies));
        String updatedResultsJson;
        try {
            updatedResultsJson = objectMapper.writeValueAsString(updatedResults);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "结果 JSON 序列化失败");
        }

        // 8. IMP-3：批量 INSERT AssessmentSubmissionDetail
        for (AssessmentSubmissionDetail detail : submissionDetails) {
            submissionDetailMapper.insert(detail);
        }

        // 9. AssessmentResultMapper.gradeWithCheck（状态机更新）
        int rows = assessmentResultMapper.gradeWithCheck(
                assessmentId, tenantId, score, totalScore, updatedResultsJson, now);
        if (rows == 0) {
            throw new BusinessException(ErrorCodes.ASSESSMENT_CONCURRENT_MODIFIED);
        }

        // 10. BLOCK-2：错题入队（先于掌握度更新）
        recordWrongAnswers(submissionDetails, studentId, tenantId, assessmentId, today);

        // 11. IMP-4：掌握度更新（按 results JSON 顺序，逐题更新）
        updateMasteryScores(gradedQuestionMaps, studentId, tenantId, assessmentId);

        // 12. IMP-5：构造 questionsWithAnswers
        List<AssessmentQuestionWithAnswerVO> questionsWithAnswers = buildQuestionsWithAnswers(gradedQuestionMaps);

        // 13. 返回 AssessmentResultVO
        return AssessmentResultVO.builder()
                .assessmentId(assessmentId)
                .score(score)
                .totalScore(totalScore)
                .status("graded")
                .knowledgePointResults(kpAccuracies)
                .questionsWithAnswers(questionsWithAnswers)
                .assessedAt(now)
                .build();
    }

    // ==================== getAssessmentHistory ====================

    @Override
    public PageResult<AssessmentHistoryVO> getAssessmentHistory(Long studentId, String tenantId,
                                                                  int page, int size) {
        // 1. 分页查询（使用 MyBatis-Plus Page API，避免 .last() 字符串拼接）
        Page<AssessmentResult> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<AssessmentResult> wrapper = new LambdaQueryWrapper<AssessmentResult>()
                .eq(AssessmentResult::getStudentId, studentId)
                .eq(AssessmentResult::getTenantId, tenantId)
                .orderByDesc(AssessmentResult::getCreateTime);

        Page<AssessmentResult> pageResult = assessmentResultMapper.selectPage(pageParam, wrapper);
        List<AssessmentResult> assessments = pageResult.getRecords();

        // 2. 批量预查科目名称，避免 N+1 查询
        List<Long> subjectIds = assessments.stream()
                .map(AssessmentResult::getSubjectId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Subject> subjectMap = subjectIds.isEmpty()
                ? Map.of()
                : subjectMapper.selectBatchIds(subjectIds).stream()
                        .collect(Collectors.toMap(Subject::getSubjectId, Function.identity()));

        // 3. IMP-6：返回所有状态（含 pending 和 graded）
        List<AssessmentHistoryVO> records = assessments.stream()
                .map(a -> {
                    Subject subject = subjectMap.get(a.getSubjectId());
                    int questionCount = countQuestionsFromResults(a.getResults());
                    return AssessmentHistoryVO.builder()
                            .assessmentId(a.getAssessmentId())
                            .subjectId(a.getSubjectId())
                            .subjectName(subject != null ? subject.getName() : null)
                            .score(a.getScore())
                            .totalScore(a.getTotalScore())
                            .status(a.getStatus())
                            .questionCount(questionCount)
                            .assessedAt(a.getAssessedAt())
                            .createdAt(a.getCreateTime())
                            .build();
                })
                .collect(Collectors.toList());

        return PageResult.of(pageResult.getTotal(), page, size, records);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * BLOCK-5：按掌握度 ASC 选最低的 5 个知识点
     * IMP-8：使用 QueryWrapper 查询 StudentKnowledgeStateMapper
     */
    private List<KnowledgePoint> selectKpsByMastery(Long studentId, String tenantId,
                                                      List<KnowledgePoint> activeKps) {
        List<String> kpIds = activeKps.stream()
                .map(KnowledgePoint::getKnowledgePointId)
                .collect(Collectors.toList());

        // 查询掌握度记录
        QueryWrapper<StudentKnowledgeState> queryWrapper = new QueryWrapper<StudentKnowledgeState>()
                .eq("student_id", studentId)
                .eq("tenant_id", tenantId)
                .in("knowledge_point_id", kpIds);
        List<StudentKnowledgeState> states = studentKnowledgeStateMapper.selectList(queryWrapper);

        // 构造 kpId → masteryScore 映射
        Map<String, BigDecimal> masteryMap = new HashMap<>();
        for (StudentKnowledgeState state : states) {
            masteryMap.put(state.getKnowledgePointId(), state.getMasteryScore());
        }

        // 排序：有掌握度记录按 masteryScore ASC，无记录视为 0
        // 若掌握度相同，按 knowledgePointId 字典序（稳定排序）
        List<KnowledgePoint> sorted = new ArrayList<>(activeKps);
        sorted.sort(Comparator
                .comparing((KnowledgePoint kp) ->
                        masteryMap.getOrDefault(kp.getKnowledgePointId(), BigDecimal.ZERO))
                .thenComparing(KnowledgePoint::getKnowledgePointId));

        // 取前 5 个
        return sorted.subList(0, AssessmentQuestionSelector.MAX_KPS_PER_ASSESSMENT);
    }

    /**
     * IMP-12：检查所有选中知识点掌握度是否均 ≥ 0.8
     */
    private String checkAllMasteryAboveThreshold(Long studentId, String tenantId,
                                                   List<KnowledgePoint> selectedKps) {
        List<String> kpIds = selectedKps.stream()
                .map(KnowledgePoint::getKnowledgePointId)
                .collect(Collectors.toList());

        QueryWrapper<StudentKnowledgeState> queryWrapper = new QueryWrapper<StudentKnowledgeState>()
                .eq("student_id", studentId)
                .eq("tenant_id", tenantId)
                .in("knowledge_point_id", kpIds);
        List<StudentKnowledgeState> states = studentKnowledgeStateMapper.selectList(queryWrapper);

        // 若全部无记录，视为 mastery=0，不触发 warning
        if (states.isEmpty()) {
            return null;
        }

        // 检查所有有记录的知识点是否均 ≥ 0.8，且无记录的知识点视为 0（不满足阈值）
        Map<String, BigDecimal> masteryMap = new HashMap<>();
        for (StudentKnowledgeState state : states) {
            masteryMap.put(state.getKnowledgePointId(), state.getMasteryScore());
        }

        boolean allAboveThreshold = kpIds.stream().allMatch(kpId ->
                masteryMap.containsKey(kpId) &&
                        masteryMap.get(kpId).compareTo(MASTERY_WARNING_THRESHOLD) >= 0);

        return allAboveThreshold ? "当前知识点掌握良好，评测可能偏简单" : null;
    }

    /**
     * 构造 results JSON（题目列表，含 answer 供批改比对）
     */
    private String buildResultsJson(List<QuestionBank> questions) {
        try {
            List<Map<String, Object>> questionMaps = questions.stream()
                    .map(q -> {
                        Map<String, Object> map = new LinkedHashMap<>();
map.put("questionId", q.getQuestionId());
                        map.put("knowledgePointId", q.getKnowledgePointId());
                        map.put("content", q.getContent());
                        map.put("choices", parseChoices(q.getChoices()));
                        map.put("difficulty", q.getDifficulty());
                        map.put("type", q.getType());
                        map.put("answer", q.getAnswer());
                        map.put("analysis", q.getAnalysis());
                        return map;
                    })
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(questionMaps);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "题目 JSON 序列化失败");
        }
    }

    /**
     * 解析 choices JSON 字符串为 List<String>
     */
    private List<String> parseChoices(String choicesJson) {
        if (choicesJson == null || choicesJson.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(choicesJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("[parseChoices] choices 解析失败: {}", choicesJson);
            return null;
        }
    }

    /**
     * BLOCK-4：使用字段白名单解析 results JSON 为 AssessmentQuestionVO 列表
     * IMP-9：逐字段提取，禁止 answer/analysis 泄露
     */
    private List<AssessmentQuestionVO> parseQuestionsWithWhitelist(String resultsJson) {
        List<Map<String, Object>> questionMaps = parseResultsToMaps(resultsJson);
        return questionMaps.stream()
                .map(map -> AssessmentQuestionVO.builder()
                        .questionId(toLong(map.get("questionId")))
                        .knowledgePointId((String) map.get("knowledgePointId"))
                        .content((String) map.get("content"))
                        .choices(toStringList(map.get("choices")))
                        .difficulty((String) map.get("difficulty"))
                        .type((String) map.get("type"))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 解析 results JSON 为 List<Map<String, Object>>
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseResultsToMaps(String resultsJson) {
        try {
            Object parsed = objectMapper.readValue(resultsJson, Object.class);
            if (parsed instanceof List) {
                return (List<Map<String, Object>>) (List<?>) parsed;
            } else if (parsed instanceof Map) {
                Map<String, Object> root = (Map<String, Object>) parsed;
                Object questions = root.get("questions");
                if (questions instanceof List) {
                    return (List<Map<String, Object>>) (List<?>) questions;
                }
            }
            throw new BusinessException(ErrorCodes.CONTENT_PARSE_ERROR, "results JSON 结构异常");
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCodes.CONTENT_PARSE_ERROR, "results JSON 解析失败");
        }
    }

    /**
     * 解析知识点名称映射
     */
    private Map<String, String> resolveKpNames(List<String> kpIds, String tenantId) {
        if (kpIds == null || kpIds.isEmpty()) {
            return Map.of();
        }
        List<KnowledgePoint> kps = knowledgePointMapper.selectList(
                new LambdaQueryWrapper<KnowledgePoint>()
                        .in(KnowledgePoint::getKnowledgePointId, kpIds)
                        .eq(KnowledgePoint::getTenantId, tenantId));
        return kps.stream()
                .collect(Collectors.toMap(
                        KnowledgePoint::getKnowledgePointId,
                        KnowledgePoint::getName,
                        (a, b) -> a));
    }

    /**
     * BLOCK-1：按题型分流批改
     */
    private boolean judgeAnswer(String type, String studentAnswer, String correctAnswer) {
        // 未作答判定：studentAnswer 为 null 或空字符串 → 判错
        if (studentAnswer == null || studentAnswer.trim().isEmpty()) {
            return false;
        }

        if (correctAnswer == null || correctAnswer.trim().isEmpty()) {
            return false;
        }

        if ("choice".equals(type)) {
            return judgeChoice(studentAnswer, correctAnswer);
        } else if ("numeric".equals(type)) {
            return judgeNumeric(studentAnswer, correctAnswer);
        } else {
            // short/self 类型：字符串比对（忽略首尾空格 + 不区分大小写）
            return studentAnswer.trim().equalsIgnoreCase(correctAnswer.trim());
        }
    }

    /**
     * choice 类型批改：
     * IMP-13：假设选项为单个大写字母（A-Z），字符排序仅适用于多选；
     * 若答案含非字母字符，退化为字符串比对（忽略大小写+首尾空格）
     */
    private boolean judgeChoice(String studentAnswer, String correctAnswer) {
        String sa = studentAnswer.trim().toUpperCase();
        String ca = correctAnswer.trim().toUpperCase();

        // 检查是否全部为大写字母（多选场景如 "AB"）
        if (sa.matches("[A-Z]+") && ca.matches("[A-Z]+")) {
            // 字符排序比对（"BA" → "AB"）
            char[] saChars = sa.toCharArray();
            char[] caChars = ca.toCharArray();
            Arrays.sort(saChars);
            Arrays.sort(caChars);
            return new String(saChars).equals(new String(caChars));
        } else {
            // 含非字母字符，退化为字符串比对
            return sa.equals(ca);
        }
    }

    /**
     * numeric 类型批改：尝试解析为 BigDecimal 比对
     * IMP-10：题库录入规范要求 numeric 题 answer 必须为纯数字
     */
    private boolean judgeNumeric(String studentAnswer, String correctAnswer) {
        try {
            BigDecimal saValue = new BigDecimal(studentAnswer.trim());
            BigDecimal caValue = new BigDecimal(correctAnswer.trim());
            return saValue.compareTo(caValue) == 0;
        } catch (NumberFormatException e) {
            log.warn("[judgeNumeric] 答案解析失败: studentAnswer={}, correctAnswer={}",
                    studentAnswer, correctAnswer);
            return false;
        }
    }

    /**
     * 计算各知识点正确率（含知识点名称填充）
     */
    private List<KpAccuracyVO> calculateKpAccuracies(List<Map<String, Object>> gradedQuestionMaps,
                                                       Map<String, String> kpNameMap) {
        // 按知识点分组统计
        Map<String, List<Map<String, Object>>> kpGroups = gradedQuestionMaps.stream()
                .collect(Collectors.groupingBy(
                        q -> (String) q.get("knowledgePointId"),
                        LinkedHashMap::new,
                        Collectors.toList()));

        return kpGroups.entrySet().stream()
                .map(entry -> {
                    String kpId = entry.getKey();
                    List<Map<String, Object>> questions = entry.getValue();
                    int total = questions.size();
                    int correct = (int) questions.stream()
                            .filter(q -> Boolean.TRUE.equals(q.get("isCorrect")))
                            .count();
                    BigDecimal accuracy = total > 0
                            ? BigDecimal.valueOf(correct).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return KpAccuracyVO.builder()
                            .knowledgePointId(kpId)
                            .knowledgePointName(kpNameMap.get(kpId))
                            .totalQuestions(total)
                            .correctCount(correct)
                            .accuracy(accuracy)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 从 results JSON 统计题目数量
     */
    private int countQuestionsFromResults(String resultsJson) {
        if (resultsJson == null || resultsJson.isEmpty()) {
            return 0;
        }
        try {
            List<Map<String, Object>> maps = parseResultsToMaps(resultsJson);
            return maps.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 安全转换 Object → Long
     */
    private Long toLong(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Long) {
            return (Long) obj;
        }
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        return Long.parseLong(obj.toString());
    }

    /**
     * 逐题批改：构造提交明细列表
     */
    private List<AssessmentSubmissionDetail> gradeQuestions(
            List<Map<String, Object>> questionMaps, Map<Long, String> answerMap,
            Long assessmentId, Long studentId, String tenantId, LocalDateTime now) {
        List<AssessmentSubmissionDetail> details = new ArrayList<>();
        for (Map<String, Object> qMap : questionMaps) {
            Long questionId = toLong(qMap.get("questionId"));
            String kpId = (String) qMap.get("knowledgePointId");
            String correctAnswer = (String) qMap.get("answer");
            String difficulty = (String) qMap.get("difficulty");
            String type = (String) qMap.get("type");
            String studentAnswer = answerMap.get(questionId);

            boolean isCorrect = judgeAnswer(type, studentAnswer, correctAnswer);

            AssessmentSubmissionDetail detail = new AssessmentSubmissionDetail();
            detail.setAssessmentId(assessmentId);
            detail.setStudentId(studentId);
            detail.setQuestionId(questionId);
            detail.setKnowledgePointId(kpId);
            detail.setStudentAnswer(studentAnswer);
            detail.setIsCorrect(isCorrect ? 1 : 0);
            detail.setDifficulty(difficulty);
            detail.setSubmittedAt(now);
            detail.setTenantId(tenantId);
            details.add(detail);
        }
        return details;
    }

    /**
     * 从 questionMaps + submissionDetails 重建带批改信息的 questionMap 列表
     */
    private List<Map<String, Object>> buildGradedQuestionMaps(
            List<Map<String, Object>> questionMaps,
            List<AssessmentSubmissionDetail> submissionDetails) {
        List<Map<String, Object>> gradedMaps = new ArrayList<>();
        for (int i = 0; i < questionMaps.size(); i++) {
            Map<String, Object> gradedMap = new LinkedHashMap<>(questionMaps.get(i));
            gradedMap.put("studentAnswer", submissionDetails.get(i).getStudentAnswer());
            gradedMap.put("isCorrect", submissionDetails.get(i).getIsCorrect() == 1);
            gradedMaps.add(gradedMap);
        }
        return gradedMaps;
    }

    /**
     * BLOCK-2：错题入队（先于掌握度更新）
     */
    private void recordWrongAnswers(List<AssessmentSubmissionDetail> submissionDetails,
                                     Long studentId, String tenantId,
                                     Long assessmentId, LocalDate today) {
        for (AssessmentSubmissionDetail detail : submissionDetails) {
            if (detail.getIsCorrect() == 0) {
                errorQuestionService.recordWrongAnswer(
                        studentId, tenantId,
                        detail.getQuestionId(),
                        detail.getKnowledgePointId(),
                        ErrorQuestionSourceType.ASSESSMENT,
                        assessmentId, today);
            }
        }
    }

    /**
     * IMP-4：逐题更新掌握度
     */
    private void updateMasteryScores(List<Map<String, Object>> gradedQuestionMaps,
                                      Long studentId, String tenantId, Long assessmentId) {
        for (Map<String, Object> qMap : gradedQuestionMaps) {
            String kpId = (String) qMap.get("knowledgePointId");
            String difficulty = (String) qMap.get("difficulty");
            boolean isCorrect = Boolean.TRUE.equals(qMap.get("isCorrect"));

            BigDecimal dNorm = MasteryFormulaUtil.mapDifficulty(difficulty);
            masteryService.updateMasteryFromResult(
                    studentId, tenantId, kpId,
                    ChangeType.ASSESSMENT, assessmentId,
                    isCorrect, dNorm);
        }
    }

    /**
     * IMP-5：构造 questionsWithAnswers
     */
    private List<AssessmentQuestionWithAnswerVO> buildQuestionsWithAnswers(
            List<Map<String, Object>> gradedQuestionMaps) {
        return gradedQuestionMaps.stream()
                .map(qMap -> AssessmentQuestionWithAnswerVO.builder()
                        .questionId(toLong(qMap.get("questionId")))
                        .knowledgePointId((String) qMap.get("knowledgePointId"))
                        .content((String) qMap.get("content"))
                        .difficulty((String) qMap.get("difficulty"))
                        .type((String) qMap.get("type"))
                        .studentAnswer((String) qMap.get("studentAnswer"))
                        .correctAnswer((String) qMap.get("answer"))
                        .isCorrect(Boolean.TRUE.equals(qMap.get("isCorrect")))
                        .analysis((String) qMap.get("analysis"))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 安全转换 Object → List<String>
     */
    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof List) {
            return (List<String>) obj;
        }
        return null;
    }
}
