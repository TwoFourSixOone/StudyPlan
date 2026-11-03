package com.jiang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.AnswerItem;
import com.jiang.mapper.AssessmentResultMapper;
import com.jiang.mapper.AssessmentSubmissionDetailMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.AssessmentResult;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.entity.Subject;
import com.jiang.service.impl.AssessmentServiceImpl;
import com.jiang.vo.AssessmentDetailVO;
import com.jiang.vo.AssessmentResultVO;
import com.jiang.vo.AssessmentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AssessmentService 单元测试
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssessmentService 单元测试")
class AssessmentServiceTest {

    @Mock
    private AssessmentResultMapper assessmentResultMapper;

    @Mock
    private AssessmentSubmissionDetailMapper submissionDetailMapper;

    @Mock
    private SubjectMapper subjectMapper;

    @Mock
    private KnowledgePointMapper knowledgePointMapper;

    @Mock
    private StudentKnowledgeStateMapper studentKnowledgeStateMapper;

    @Mock
    private QuestionBankMapper questionBankMapper;

    @Mock
    private ErrorQuestionService errorQuestionService;

    @Mock
    private MasteryService masteryService;

    @InjectMocks
    private AssessmentServiceImpl assessmentService;

    private static final Long STUDENT_ID = 1L;
    private static final String TENANT_ID = "default";
    private static final Long SUBJECT_ID = 1L;
    private static final Long ASSESSMENT_ID = 100L;
    private static final String KP_ID_1 = "kp_math_001";
    private static final String KP_ID_2 = "kp_math_002";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Subject buildSubject() {
        Subject s = new Subject();
        s.setSubjectId(SUBJECT_ID);
        s.setName("数学");
        s.setStage("初中");
        s.setTenantId(TENANT_ID);
        return s;
    }

    private KnowledgePoint buildKp(String kpId, String name) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setKnowledgePointId(kpId);
        kp.setName(name);
        kp.setSubject("数学");
        kp.setStatus("active");
        kp.setTenantId(TENANT_ID);
        return kp;
    }

    private QuestionBank buildQuestion(Long qId, String kpId, String difficulty, String type, String answer) {
        QuestionBank q = new QuestionBank();
        q.setQuestionId(qId);
        q.setKnowledgePointId(kpId);
        q.setContent("题目内容_" + qId);
        q.setDifficulty(difficulty);
        q.setType(type);
        q.setAnswer(answer);
        q.setAnalysis("解析_" + qId);
        q.setChoices(type.equals("choice") ? "[\"A. 选项1\",\"B. 选项2\",\"C. 选项3\"]" : null);
        q.setTenantId(TENANT_ID);
        return q;
    }

    private AssessmentResult buildAssessment(String status, String resultsJson) {
        AssessmentResult r = new AssessmentResult();
        r.setAssessmentId(ASSESSMENT_ID);
        r.setStudentId(STUDENT_ID);
        r.setSubjectId(SUBJECT_ID);
        r.setStatus(status);
        r.setResults(resultsJson);
        r.setKnowledgePointIds("[\"" + KP_ID_1 + "\"]");
        r.setScore(null);
        r.setTotalScore(null);
        r.setAssessedAt(null);
        r.setCreateTime(LocalDateTime.of(2026, 11, 3, 10, 0));
        r.setTenantId(TENANT_ID);
        return r;
    }

    private String buildResultsJson(List<QuestionBank> questions) {
        try {
            List<Map<String, Object>> maps = new ArrayList<>();
            for (QuestionBank q : questions) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("questionId", q.getQuestionId());
                map.put("knowledgePointId", q.getKnowledgePointId());
                map.put("content", q.getContent());
                map.put("choices", q.getChoices() != null ?
                        objectMapper.readValue(q.getChoices(), new TypeReference<List<String>>() {}) : null);
                map.put("difficulty", q.getDifficulty());
                map.put("type", q.getType());
                map.put("answer", q.getAnswer());
                map.put("analysis", q.getAnalysis());
                maps.add(map);
            }
            return objectMapper.writeValueAsString(maps);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== createAssessment ====================

    @Nested
    @DisplayName("createAssessment")
    class CreateAssessmentTests {

        @Test
        @DisplayName("正常创建评测")
        void should_createAssessment_when_subjectHasActiveKps() throws Exception {
            Subject subject = buildSubject();
            KnowledgePoint kp = buildKp(KP_ID_1, "二次函数");
            List<QuestionBank> questions = Arrays.asList(
                    buildQuestion(1L, KP_ID_1, "basic", "choice", "A"),
                    buildQuestion(2L, KP_ID_1, "standard", "choice", "B"),
                    buildQuestion(3L, KP_ID_1, "challenge", "numeric", "3.14"));

            when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(subject);
            when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.singletonList(kp));
            when(studentKnowledgeStateMapper.selectList(any(QueryWrapper.class)))
                    .thenReturn(Collections.emptyList());
            when(questionBankMapper.selectActiveByKp(eq(KP_ID_1), eq(TENANT_ID)))
                    .thenReturn(questions);
            when(assessmentResultMapper.insert(any(AssessmentResult.class))).thenReturn(1);

            AssessmentVO vo = assessmentService.createAssessment(STUDENT_ID, TENANT_ID, SUBJECT_ID);

            assertNotNull(vo);
            assertEquals(SUBJECT_ID, vo.getSubjectId());
            assertEquals("数学", vo.getSubjectName());
            assertEquals("pending", vo.getStatus());
            assertEquals(3, vo.getQuestionCount());
            verify(assessmentResultMapper).insert(any(AssessmentResult.class));
        }

        @Test
        @DisplayName("科目不存在 → 抛 SUBJECT_NOT_FOUND")
        void should_throwException_when_subjectNotFound() {
            when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> assessmentService.createAssessment(STUDENT_ID, TENANT_ID, SUBJECT_ID));
            assertEquals(ErrorCodes.SUBJECT_NOT_FOUND.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("科目下无 active 知识点 → 抛 SUBJECT_NO_ACTIVE_KP")
        void should_throwException_when_noActiveKp() {
            when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildSubject());
            when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.emptyList());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> assessmentService.createAssessment(STUDENT_ID, TENANT_ID, SUBJECT_ID));
            assertEquals(ErrorCodes.SUBJECT_NO_ACTIVE_KP.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("题库无题 → 抛 QUESTION_NOT_FOUND")
        void should_throwException_when_questionBankEmpty() {
            when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildSubject());
            KnowledgePoint kp = buildKp(KP_ID_1, "二次函数");
            when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.singletonList(kp));
            when(studentKnowledgeStateMapper.selectList(any(QueryWrapper.class)))
                    .thenReturn(Collections.emptyList());
            when(questionBankMapper.selectActiveByKp(eq(KP_ID_1), eq(TENANT_ID)))
                    .thenReturn(Collections.emptyList());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> assessmentService.createAssessment(STUDENT_ID, TENANT_ID, SUBJECT_ID));
            assertEquals(ErrorCodes.QUESTION_NOT_FOUND.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("BLOCK-5：知识点超过 5 个时优先选掌握度最低的")
        void should_selectLowestMasteryKps_when_multipleKps() {
            Subject subject = buildSubject();
            List<KnowledgePoint> kps = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                kps.add(buildKp("kp_" + String.format("%03d", i), "知识点" + i));
            }

            // 模拟掌握度：kp_001=0.9, kp_002=0.3, kp_003=0.5, kp_004=0.7, kp_005=0.1, kp_006=0.8, kp_007=0.6
            List<StudentKnowledgeState> states = Arrays.asList(
                    buildState("kp_001", new BigDecimal("0.9000")),
                    buildState("kp_002", new BigDecimal("0.3000")),
                    buildState("kp_003", new BigDecimal("0.5000")),
                    buildState("kp_004", new BigDecimal("0.7000")),
                    buildState("kp_005", new BigDecimal("0.1000")),
                    buildState("kp_006", new BigDecimal("0.8000")),
                    buildState("kp_007", new BigDecimal("0.6000")));

            when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(subject);
            when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(kps);
            when(studentKnowledgeStateMapper.selectList(any(QueryWrapper.class))).thenReturn(states);

            // 为选中的知识点模拟题目（kp_005=0.1, kp_002=0.3, kp_003=0.5, kp_007=0.6, kp_004=0.7）
            for (StudentKnowledgeState state : states) {
                when(questionBankMapper.selectActiveByKp(eq(state.getKnowledgePointId()), eq(TENANT_ID)))
                        .thenReturn(Collections.singletonList(
                                buildQuestion(Long.valueOf(state.getKnowledgePointId().hashCode()), state.getKnowledgePointId(), "basic", "choice", "A")));
            }
            when(assessmentResultMapper.insert(any(AssessmentResult.class))).thenReturn(1);

            AssessmentVO vo = assessmentService.createAssessment(STUDENT_ID, TENANT_ID, SUBJECT_ID);

            assertNotNull(vo);
            // 应该选掌握度最低的 5 个：kp_005(0.1), kp_002(0.3), kp_003(0.5), kp_007(0.6), kp_004(0.7)
            assertEquals(5, vo.getKnowledgePointIds().size());
            assertTrue(vo.getKnowledgePointIds().contains("kp_005"));
            assertTrue(vo.getKnowledgePointIds().contains("kp_002"));
            assertFalse(vo.getKnowledgePointIds().contains("kp_001")); // 0.9 不应选中
        }

        @Test
        @DisplayName("IMP-12：所有知识点掌握度 ≥ 0.8 时返回 warning 提示")
        void should_returnWarning_when_allMasteryAboveThreshold() {
            Subject subject = buildSubject();
            // 需要 ≥ 5 个知识点才能触发 warning 检查
            List<KnowledgePoint> kps = Arrays.asList(
                    buildKp("kp_001", "二次函数"),
                    buildKp("kp_002", "一次函数"),
                    buildKp("kp_003", "三次函数"),
                    buildKp("kp_004", "四次函数"),
                    buildKp("kp_005", "五次函数"),
                    buildKp("kp_006", "六次函数"),
                    buildKp("kp_007", "七次函数"));

            // 所有知识点掌握度均 ≥ 0.8
            List<StudentKnowledgeState> states = Arrays.asList(
                    buildState("kp_001", new BigDecimal("0.9000")),
                    buildState("kp_002", new BigDecimal("0.8500")),
                    buildState("kp_003", new BigDecimal("0.8000")),
                    buildState("kp_004", new BigDecimal("0.9500")),
                    buildState("kp_005", new BigDecimal("0.8800")),
                    buildState("kp_006", new BigDecimal("0.8200")),
                    buildState("kp_007", new BigDecimal("0.9100")));

            when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(subject);
            when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(kps);
            when(studentKnowledgeStateMapper.selectList(any(QueryWrapper.class))).thenReturn(states);
            // 为每个知识点模拟题目
            for (KnowledgePoint kp : kps) {
                when(questionBankMapper.selectActiveByKp(eq(kp.getKnowledgePointId()), eq(TENANT_ID)))
                        .thenReturn(Arrays.asList(
                                buildQuestion(kp.getKnowledgePointId().hashCode() * 10L + 1, kp.getKnowledgePointId(), "basic", "choice", "A"),
                                buildQuestion(kp.getKnowledgePointId().hashCode() * 10L + 2, kp.getKnowledgePointId(), "standard", "choice", "B"),
                                buildQuestion(kp.getKnowledgePointId().hashCode() * 10L + 3, kp.getKnowledgePointId(), "challenge", "choice", "C")));
            }
            when(assessmentResultMapper.insert(any(AssessmentResult.class))).thenReturn(1);

            AssessmentVO vo = assessmentService.createAssessment(STUDENT_ID, TENANT_ID, SUBJECT_ID);

            assertNotNull(vo.getWarning());
            assertTrue(vo.getWarning().contains("掌握良好"));
        }
    }

    // ==================== getAssessmentDetail ====================

    @Nested
    @DisplayName("getAssessmentDetail")
    class GetDetailTests {

        @Test
        @DisplayName("正常查看详情")
        void should_getDetail_when_assessmentExists() throws Exception {
            List<QuestionBank> questions = Arrays.asList(
                    buildQuestion(1L, KP_ID_1, "basic", "choice", "A"),
                    buildQuestion(2L, KP_ID_1, "standard", "numeric", "3.14"));
            String resultsJson = buildResultsJson(questions);
            AssessmentResult assessment = buildAssessment("pending", resultsJson);

            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(assessment);
            when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildSubject());
            when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.singletonList(buildKp(KP_ID_1, "二次函数")));

            AssessmentDetailVO detail = assessmentService.getAssessmentDetail(STUDENT_ID, TENANT_ID, ASSESSMENT_ID);

            assertNotNull(detail);
            assertEquals(ASSESSMENT_ID, detail.getAssessmentId());
            assertEquals("pending", detail.getStatus());
            assertEquals(2, detail.getQuestions().size());
        }

        @Test
        @DisplayName("评测不存在 → 抛 ASSESSMENT_NOT_FOUND")
        void should_throwException_when_assessmentNotFound() {
            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> assessmentService.getAssessmentDetail(STUDENT_ID, TENANT_ID, 999L));
            assertEquals(ErrorCodes.ASSESSMENT_NOT_FOUND.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("归属校验失败（查询含 studentId 过滤）→ 抛 ASSESSMENT_NOT_FOUND")
        void should_throwException_when_studentMismatch() {
            // selectOne 查询包含 studentId + tenantId 过滤，不匹配时返回 null → ASSESSMENT_NOT_FOUND
            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> assessmentService.getAssessmentDetail(STUDENT_ID, TENANT_ID, ASSESSMENT_ID));
            assertEquals(ErrorCodes.ASSESSMENT_NOT_FOUND.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("BLOCK-4：getAssessmentDetail 返回的 VO 中无 answer/analysis 字段")
        void should_notLeakAnswer_when_getDetail() throws Exception {
            List<QuestionBank> questions = Collections.singletonList(
                    buildQuestion(1L, KP_ID_1, "basic", "choice", "A"));
            String resultsJson = buildResultsJson(questions);
            AssessmentResult assessment = buildAssessment("pending", resultsJson);

            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(assessment);
            when(subjectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildSubject());
            when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.singletonList(buildKp(KP_ID_1, "二次函数")));

            AssessmentDetailVO detail = assessmentService.getAssessmentDetail(STUDENT_ID, TENANT_ID, ASSESSMENT_ID);

            // AssessmentQuestionVO 类型本身不含 answer/analysis getter
            // 验证返回的题目列表中每个 VO 的 content 不为 null（说明确实解析了题干）
            assertNotNull(detail.getQuestions());
            assertEquals(1, detail.getQuestions().size());
            assertNotNull(detail.getQuestions().get(0).getContent());
        }
    }

    // ==================== submitAndGrade ====================

    @Nested
    @DisplayName("submitAndGrade")
    class SubmitAndGradeTests {

        @Test
        @DisplayName("正常提交批改")
        void should_gradeAndReturn_when_submitCorrectly() throws Exception {
            List<QuestionBank> questions = Arrays.asList(
                    buildQuestion(1L, KP_ID_1, "basic", "choice", "A"),
                    buildQuestion(2L, KP_ID_1, "standard", "choice", "B"),
                    buildQuestion(3L, KP_ID_1, "challenge", "numeric", "3.14"));
            String resultsJson = buildResultsJson(questions);
            AssessmentResult assessment = buildAssessment("pending", resultsJson);

            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(assessment);
            when(assessmentResultMapper.gradeWithCheck(anyLong(), anyString(), any(), any(), anyString(), any()))
                    .thenReturn(1);
            when(submissionDetailMapper.insert(any())).thenReturn(1);

            List<AnswerItem> answers = Arrays.asList(
                    answerItem(1L, "A"),
                    answerItem(2L, "B"),
                    answerItem(3L, "3.14"));

            AssessmentResultVO result = assessmentService.submitAndGrade(
                    STUDENT_ID, TENANT_ID, ASSESSMENT_ID, answers);

            assertNotNull(result);
            assertEquals("graded", result.getStatus());
            assertEquals(3, result.getTotalScore().intValue());
            verify(errorQuestionService, never()).recordWrongAnswer(anyLong(), anyString(), anyLong(), anyString(), any(), anyLong(), any());
            verify(masteryService, times(3)).updateMasteryFromResult(anyLong(), anyString(), anyString(), any(), anyLong(), anyBoolean(), any());
        }

        @Test
        @DisplayName("重复提交（已批改）→ 抛 ASSESSMENT_ALREADY_GRADED")
        void should_throwException_when_alreadyGraded() {
            AssessmentResult assessment = buildAssessment("graded", "{}");
            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(assessment);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> assessmentService.submitAndGrade(STUDENT_ID, TENANT_ID, ASSESSMENT_ID, Collections.emptyList()));
            assertEquals(ErrorCodes.ASSESSMENT_ALREADY_GRADED.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("状态非法 → 抛 ASSESSMENT_INVALID_STATUS")
        void should_throwException_when_statusInvalid() {
            AssessmentResult assessment = buildAssessment("unknown", "{}");
            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(assessment);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> assessmentService.submitAndGrade(STUDENT_ID, TENANT_ID, ASSESSMENT_ID, Collections.emptyList()));
            assertEquals(ErrorCodes.ASSESSMENT_INVALID_STATUS.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("BLOCK-1：未作答判错")
        void should_judgeWrong_when_answerBlank() throws Exception {
            List<QuestionBank> questions = Collections.singletonList(
                    buildQuestion(1L, KP_ID_1, "basic", "choice", "A"));
            String resultsJson = buildResultsJson(questions);
            AssessmentResult assessment = buildAssessment("pending", resultsJson);

            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(assessment);
            when(assessmentResultMapper.gradeWithCheck(anyLong(), anyString(), any(), any(), anyString(), any()))
                    .thenReturn(1);
            when(submissionDetailMapper.insert(any())).thenReturn(1);

            List<AnswerItem> answers = Collections.singletonList(answerItem(1L, ""));

            AssessmentResultVO result = assessmentService.submitAndGrade(
                    STUDENT_ID, TENANT_ID, ASSESSMENT_ID, answers);

            assertEquals(BigDecimal.ZERO, result.getScore());
            verify(errorQuestionService).recordWrongAnswer(eq(STUDENT_ID), eq(TENANT_ID),
                    eq(1L), eq(KP_ID_1), any(), eq(ASSESSMENT_ID), any());
        }

        @Test
        @DisplayName("BLOCK-1：choice 类型大小写+排序比对")
        void should_compareChoiceCaseInsensitive() throws Exception {
            List<QuestionBank> questions = Collections.singletonList(
                    buildQuestion(1L, KP_ID_1, "basic", "choice", "AB"));
            String resultsJson = buildResultsJson(questions);
            AssessmentResult assessment = buildAssessment("pending", resultsJson);

            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(assessment);
            when(assessmentResultMapper.gradeWithCheck(anyLong(), anyString(), any(), any(), anyString(), any()))
                    .thenReturn(1);
            when(submissionDetailMapper.insert(any())).thenReturn(1);

            // 学生答案 "ba" → 排序后 "AB" == "AB" → 正确
            List<AnswerItem> answers = Collections.singletonList(answerItem(1L, "ba"));

            AssessmentResultVO result = assessmentService.submitAndGrade(
                    STUDENT_ID, TENANT_ID, ASSESSMENT_ID, answers);

            assertEquals(BigDecimal.ONE, result.getScore());
        }

        @Test
        @DisplayName("BLOCK-1：numeric 类型数值比对（0.5 = 0.50）")
        void should_compareNumericByValue() throws Exception {
            List<QuestionBank> questions = Collections.singletonList(
                    buildQuestion(1L, KP_ID_1, "basic", "numeric", "0.5"));
            String resultsJson = buildResultsJson(questions);
            AssessmentResult assessment = buildAssessment("pending", resultsJson);

            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(assessment);
            when(assessmentResultMapper.gradeWithCheck(anyLong(), anyString(), any(), any(), anyString(), any()))
                    .thenReturn(1);
            when(submissionDetailMapper.insert(any())).thenReturn(1);

            List<AnswerItem> answers = Collections.singletonList(answerItem(1L, "0.50"));

            AssessmentResultVO result = assessmentService.submitAndGrade(
                    STUDENT_ID, TENANT_ID, ASSESSMENT_ID, answers);

            assertEquals(BigDecimal.ONE, result.getScore());
        }

        @Test
        @DisplayName("BLOCK-1：numeric 解析失败判错")
        void should_judgeWrong_when_numericParseFail() throws Exception {
            List<QuestionBank> questions = Collections.singletonList(
                    buildQuestion(1L, KP_ID_1, "basic", "numeric", "0.5"));
            String resultsJson = buildResultsJson(questions);
            AssessmentResult assessment = buildAssessment("pending", resultsJson);

            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(assessment);
            when(assessmentResultMapper.gradeWithCheck(anyLong(), anyString(), any(), any(), anyString(), any()))
                    .thenReturn(1);
            when(submissionDetailMapper.insert(any())).thenReturn(1);

            List<AnswerItem> answers = Collections.singletonList(answerItem(1L, "1/2"));

            AssessmentResultVO result = assessmentService.submitAndGrade(
                    STUDENT_ID, TENANT_ID, ASSESSMENT_ID, answers);

            assertEquals(BigDecimal.ZERO, result.getScore());
        }

        @Test
        @DisplayName("BLOCK-2：错题入队先于掌握度更新")
        void should_recordErrorQuestionBeforeMasteryUpdate() throws Exception {
            List<QuestionBank> questions = Collections.singletonList(
                    buildQuestion(1L, KP_ID_1, "basic", "choice", "A"));
            String resultsJson = buildResultsJson(questions);
            AssessmentResult assessment = buildAssessment("pending", resultsJson);

            when(assessmentResultMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(assessment);
            when(assessmentResultMapper.gradeWithCheck(anyLong(), anyString(), any(), any(), anyString(), any()))
                    .thenReturn(1);
            when(submissionDetailMapper.insert(any())).thenReturn(1);

            // 学生答错
            List<AnswerItem> answers = Collections.singletonList(answerItem(1L, "B"));

            // 使用 InOrder 验证调用顺序
            var inOrder = inOrder(errorQuestionService, masteryService);

            assessmentService.submitAndGrade(STUDENT_ID, TENANT_ID, ASSESSMENT_ID, answers);

            inOrder.verify(errorQuestionService).recordWrongAnswer(
                    anyLong(), anyString(), anyLong(), anyString(), any(), anyLong(), any());
            inOrder.verify(masteryService).updateMasteryFromResult(
                    anyLong(), anyString(), anyString(), any(), anyLong(), anyBoolean(), any());
        }
    }

    // ==================== 辅助方法 ====================

    private StudentKnowledgeState buildState(String kpId, BigDecimal mastery) {
        StudentKnowledgeState state = new StudentKnowledgeState();
        state.setStudentId(STUDENT_ID);
        state.setKnowledgePointId(kpId);
        state.setMasteryScore(mastery);
        state.setConfidence(new BigDecimal("0.5000"));
        state.setTenantId(TENANT_ID);
        return state;
    }

    private AnswerItem answerItem(Long questionId, String answer) {
        AnswerItem item = new AnswerItem();
        item.setQuestionId(questionId);
        item.setAnswer(answer);
        return item;
    }
}
