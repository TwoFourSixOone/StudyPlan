package com.jiang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.dto.AdminMasteryQueryRequest;
import com.jiang.mapper.GeneratedQuestionMapper;
import com.jiang.mapper.HomeworkSubmissionDetailMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.MasteryChangeLogMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.HomeworkSubmissionDetail;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.MasteryChangeLog;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.enums.ChangeType;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.MasteryServiceImpl;
import com.jiang.vo.MasteryChangeLogVO;
import com.jiang.vo.MasteryDashboardVO;
import com.jiang.vo.MasteryDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MasteryService 单元测试
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasteryService 单元测试")
class MasteryServiceTest {

    @Mock
    private StudentKnowledgeStateMapper stateMapper;

    @Mock
    private MasteryChangeLogMapper changeLogMapper;

    @Mock
    private KnowledgePointMapper knowledgePointMapper;

    @Mock
    private QuestionBankMapper questionBankMapper;

    @Mock
    private HomeworkSubmissionDetailMapper submissionDetailMapper;

    @Mock
    private GeneratedQuestionMapper generatedQuestionMapper;

    @Mock
    private ErrorQuestionService errorQuestionService;

    /** 试卷来源难度读取（Change 2026-11-11）：exam 来源 D_norm 走 exam_question.difficulty */
    @Mock
    private com.jiang.mapper.ExamQuestionMapper examQuestionMapper;

    @InjectMocks
    private MasteryServiceImpl masteryService;

    private static final Long STUDENT_ID = 1L;
    private static final String TENANT_ID = "default";
    private static final String KP_ID = "kp_math_001";

    private MockedStatic<StudentContext> studentContextMock;

    @BeforeEach
    void setUp() {
        studentContextMock = mockStatic(StudentContext.class);
        studentContextMock.when(StudentContext::getStudentId).thenReturn(STUDENT_ID);
        studentContextMock.when(StudentContext::getTenantId).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        studentContextMock.close();
    }

    // ==================== updateMasteryFromResult 测试 ====================

    @Test
    @DisplayName("updateMasteryFromResult：答对场景 - 掌握度上升")
    void testUpdateMasteryFromResult_correct() {
        // 准备数据
        StudentKnowledgeState existingState = createState(STUDENT_ID, KP_ID, new BigDecimal("0.5000"));
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingState);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);
        when(changeLogMapper.insert(any(MasteryChangeLog.class))).thenReturn(1);

        // 执行
        BigDecimal dNorm = new BigDecimal("0.6");
        masteryService.updateMasteryFromResult(STUDENT_ID, TENANT_ID, KP_ID, ChangeType.HOMEWORK, 100L, true, dNorm);

        // 验证
        verify(stateMapper).updateById(any(StudentKnowledgeState.class));
        verify(changeLogMapper).insert(any(MasteryChangeLog.class));
    }

    @Test
    @DisplayName("updateMasteryFromResult：答错场景 - 掌握度下降")
    void testUpdateMasteryFromResult_incorrect() {
        StudentKnowledgeState existingState = createState(STUDENT_ID, KP_ID, new BigDecimal("0.5000"));
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingState);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);
        when(changeLogMapper.insert(any(MasteryChangeLog.class))).thenReturn(1);

        BigDecimal dNorm = new BigDecimal("0.6");
        masteryService.updateMasteryFromResult(STUDENT_ID, TENANT_ID, KP_ID, ChangeType.EXAM, 200L, false, dNorm);

        verify(stateMapper).updateById(any(StudentKnowledgeState.class));
        verify(changeLogMapper).insert(any(MasteryChangeLog.class));
    }

    @Test
    @DisplayName("updateMasteryFromResult：首次创建状态 - 验证初始化字段")
    void testUpdateMasteryFromResult_firstTime() {
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(stateMapper.insert(any(StudentKnowledgeState.class))).thenReturn(1);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);
        when(changeLogMapper.insert(any(MasteryChangeLog.class))).thenReturn(1);

        BigDecimal dNorm = new BigDecimal("0.6");
        masteryService.updateMasteryFromResult(STUDENT_ID, TENANT_ID, KP_ID, ChangeType.ASSESSMENT, 300L, true, dNorm);

        // 验证 insert 和 updateById 都被调用（首次创建场景）
        ArgumentCaptor<StudentKnowledgeState> insertCaptor = ArgumentCaptor.forClass(StudentKnowledgeState.class);
        verify(stateMapper).insert(insertCaptor.capture());
        StudentKnowledgeState inserted = insertCaptor.getValue();
        // 验证初始插入时的基础字段（这些字段不会被后续计算修改）
        assertEquals(STUDENT_ID, inserted.getStudentId());
        assertEquals(KP_ID, inserted.getKnowledgePointId());
        assertEquals(TENANT_ID, inserted.getTenantId());
        // W-07: 验证首次创建时的来源
        assertEquals(ChangeType.ASSESSMENT.getCode(), inserted.getSource());
        assertNotNull(inserted.getLastAssessedAt());
        // 验证 updateById 也被调用（表示计算后更新）
        ArgumentCaptor<StudentKnowledgeState> updateCaptor = ArgumentCaptor.forClass(StudentKnowledgeState.class);
        verify(stateMapper).updateById(updateCaptor.capture());
        StudentKnowledgeState updated = updateCaptor.getValue();
        // 验证计算后的掌握度大于初始值 0.50（答对场景掌握度上升）
        assertEquals(1, updated.getMasteryScore().compareTo(new BigDecimal("0.50")));
        // 验证置信度已从初始 0.10 递增
        assertEquals(1, updated.getConfidence().compareTo(new BigDecimal("0.10")));
        // 验证变更日志被插入
        verify(changeLogMapper).insert(any(MasteryChangeLog.class));
    }

    @Test
    @DisplayName("updateMasteryFromResult：乐观锁冲突 - 抛出 MASTERY_CONCURRENT_CONFLICT")
    void testUpdateMasteryFromResult_concurrentConflict() {
        StudentKnowledgeState existingState = createState(STUDENT_ID, KP_ID, new BigDecimal("0.5000"));
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingState);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(0); // 模拟乐观锁失败

        BigDecimal dNorm = new BigDecimal("0.6");
        BusinessException exception = assertThrows(BusinessException.class, () ->
            masteryService.updateMasteryFromResult(STUDENT_ID, TENANT_ID, KP_ID, ChangeType.HOMEWORK, 100L, true, dNorm)
        );

        assertEquals(ErrorCodes.MASTERY_CONCURRENT_CONFLICT.getCode(), exception.getCode());
    }

    // ==================== updateMasteryFromHomework 测试 ====================

    @Test
    @DisplayName("updateMasteryFromHomework：正常批量更新")
    void testUpdateMasteryFromHomework_normal() {
        List<Long> submissionIds = Arrays.asList(1L, 2L);
        HomeworkSubmissionDetail sub1 = createSubmission(1L, KP_ID, "bank", 100L, 1);
        HomeworkSubmissionDetail sub2 = createSubmission(2L, KP_ID, "bank", 101L, 0);

        when(submissionDetailMapper.selectBatchIds(submissionIds)).thenReturn(Arrays.asList(sub1, sub2));
        QuestionBank question = new QuestionBank();
        question.setQuestionId(100L);
        question.setDifficulty("standard");
        when(questionBankMapper.selectById(anyLong())).thenReturn(question);
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(stateMapper.insert(any(StudentKnowledgeState.class))).thenReturn(1);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);
        when(changeLogMapper.insert(any(MasteryChangeLog.class))).thenReturn(1);

        masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, submissionIds);

        // 验证同一知识点只更新一次 DB
        verify(stateMapper, times(1)).updateById(any(StudentKnowledgeState.class));
        // 验证两条日志都被插入
        verify(changeLogMapper, times(2)).insert(any(MasteryChangeLog.class));
    }

    @Test
    @DisplayName("updateMasteryFromHomework：同一知识点多次出现 - 增量不丢失（内存缓存验证 + 数值衔接断言）")
    void testUpdateMasteryFromHomework_sameKpMultipleTimes() {
        List<Long> submissionIds = Arrays.asList(1L, 2L, 3L);
        HomeworkSubmissionDetail sub1 = createSubmission(1L, KP_ID, "bank", 100L, 1);
        HomeworkSubmissionDetail sub2 = createSubmission(2L, KP_ID, "bank", 101L, 1);
        HomeworkSubmissionDetail sub3 = createSubmission(3L, KP_ID, "bank", 102L, 1);

        when(submissionDetailMapper.selectBatchIds(submissionIds)).thenReturn(Arrays.asList(sub1, sub2, sub3));
        QuestionBank question = new QuestionBank();
        question.setDifficulty("standard");
        when(questionBankMapper.selectById(anyLong())).thenReturn(question);
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(stateMapper.insert(any(StudentKnowledgeState.class))).thenReturn(1);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);
        when(changeLogMapper.insert(any(MasteryChangeLog.class))).thenReturn(1);

        masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, submissionIds);

        // 验证同一知识点只执行一次 DB 更新
        verify(stateMapper, times(1)).updateById(any(StudentKnowledgeState.class));
        // 验证三条日志都被插入
        verify(changeLogMapper, times(3)).insert(any(MasteryChangeLog.class));

        // S-03: 捕获日志并验证 mastery_before/mastery_after 数值衔接
        ArgumentCaptor<MasteryChangeLog> logCaptor = ArgumentCaptor.forClass(MasteryChangeLog.class);
        verify(changeLogMapper, times(3)).insert(logCaptor.capture());
        List<MasteryChangeLog> logs = logCaptor.getAllValues();

        // 第一条日志的 mastery_before 应为初始值 0.5000
        assertEquals(0, new BigDecimal("0.5000").compareTo(logs.get(0).getMasteryBefore()),
                "第一条日志 mastery_before 应为 0.5000");
        // 第二条日志的 mastery_before 应等于第一条日志的 mastery_after
        assertEquals(0, logs.get(0).getMasteryAfter().compareTo(logs.get(1).getMasteryBefore()),
                "第二条 mastery_before 应等于第一条 mastery_after（数值链衔接）");
        // 第三条日志的 mastery_before 应等于第二条日志的 mastery_after
        assertEquals(0, logs.get(1).getMasteryAfter().compareTo(logs.get(2).getMasteryBefore()),
                "第三条 mastery_before 应等于第二条 mastery_after（数值链衔接）");
    }

    @Test
    @DisplayName("updateMasteryFromHomework：归属校验失败 - 抛异常")
    void testUpdateMasteryFromHomework_ownershipCheckFailed() {
        List<Long> submissionIds = Arrays.asList(1L);
        HomeworkSubmissionDetail sub = createSubmission(1L, KP_ID, "bank", 100L, 1);
        sub.setStudentId(999L); // 不同的学生 ID

        when(submissionDetailMapper.selectBatchIds(submissionIds)).thenReturn(Arrays.asList(sub));

        assertThrows(BusinessException.class, () ->
            masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, submissionIds)
        );
    }

    @Test
    @DisplayName("updateMasteryFromHomework：submission 不存在 - 抛异常")
    void testUpdateMasteryFromHomework_submissionNotFound() {
        List<Long> submissionIds = Arrays.asList(1L, 2L);
        when(submissionDetailMapper.selectBatchIds(submissionIds)).thenReturn(Collections.emptyList());

        assertThrows(BusinessException.class, () ->
            masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, submissionIds)
        );
    }

    @Test
    @DisplayName("updateMasteryFromHomework：空列表 - 直接返回")
    void testUpdateMasteryFromHomework_emptyList() {
        masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, Collections.emptyList());

        verify(submissionDetailMapper, never()).selectBatchIds(anyList());
    }

    // ==================== getStudentDashboard 测试 ====================

    @Test
    @DisplayName("getStudentDashboard：按科目筛选 - JOIN 查询")
    void testGetStudentDashboard_withSubject() {
        // 构造包含瞬态字段的 state（JOIN 查询结果）
        StudentKnowledgeState state = createState(STUDENT_ID, KP_ID, new BigDecimal("0.6000"));
        state.setKpName("测试知识点");
        state.setKpSubject("数学");
        state.setKpStatus("active");
        when(stateMapper.selectActiveStatesWithKpInfo(eq(STUDENT_ID), eq(TENANT_ID), eq("数学"), eq(false)))
                .thenReturn(Arrays.asList(state));

        MasteryChangeLog log = createChangeLog(KP_ID, new BigDecimal("0.05"));
        when(changeLogMapper.selectLatestByKpIds(eq(STUDENT_ID), eq(TENANT_ID), anyList()))
            .thenReturn(Arrays.asList(log));

        List<MasteryDashboardVO> result = masteryService.getStudentDashboard("数学");

        assertEquals(1, result.size());
        assertEquals("数学", result.get(0).getSubject());
        assertEquals("测试知识点", result.get(0).getKnowledgePointName());
        assertEquals("up", result.get(0).getLastChangeDirection());
    }

    @Test
    @DisplayName("getStudentDashboard：无 active 知识点 - 返回空列表")
    void testGetStudentDashboard_noActiveKp() {
        when(stateMapper.selectActiveStatesWithKpInfo(eq(STUDENT_ID), eq(TENANT_ID), any(), eq(false)))
                .thenReturn(Collections.emptyList());

        List<MasteryDashboardVO> result = masteryService.getStudentDashboard(null);

        assertTrue(result.isEmpty());
        // 不应查询变更日志
        verify(changeLogMapper, never()).selectLatestByKpIds(anyLong(), anyString(), anyList());
    }

    @Test
    @DisplayName("getStudentDashboard(showDisabled=false)：隐藏停用知识点")
    void should_hideDisabledKp_when_showDisabledFalse() {
        // 修复 IMP-14：showDisabled=false 时只返回 active 知识点
        when(stateMapper.selectActiveStatesWithKpInfo(eq(STUDENT_ID), eq(TENANT_ID), any(), eq(false)))
                .thenReturn(Collections.emptyList());

        List<MasteryDashboardVO> result = masteryService.getStudentDashboard("数学", false);

        assertTrue(result.isEmpty());
        // 验证传入 includeDisabled=false
        verify(stateMapper).selectActiveStatesWithKpInfo(eq(STUDENT_ID), eq(TENANT_ID), eq("数学"), eq(false));
    }

    @Test
    @DisplayName("getStudentDashboard(showDisabled=true)：显示停用知识点")
    void should_showDisabledKp_when_showDisabledTrue() {
        // 修复 IMP-14：showDisabled=true 时返回所有知识点（含停用）
        StudentKnowledgeState state = createState(STUDENT_ID, KP_ID, new BigDecimal("0.6000"));
        state.setKpName("测试知识点");
        state.setKpSubject("数学");
        state.setKpStatus("disabled");

        when(stateMapper.selectActiveStatesWithKpInfo(eq(STUDENT_ID), eq(TENANT_ID), any(), eq(true)))
                .thenReturn(Arrays.asList(state));
        when(changeLogMapper.selectLatestByKpIds(eq(STUDENT_ID), eq(TENANT_ID), anyList()))
                .thenReturn(Collections.emptyList());

        List<MasteryDashboardVO> result = masteryService.getStudentDashboard("数学", true);

        assertEquals(1, result.size());
        // 验证传入 includeDisabled=true
        verify(stateMapper).selectActiveStatesWithKpInfo(eq(STUDENT_ID), eq(TENANT_ID), eq("数学"), eq(true));
    }

    // ==================== getStudentChangeHistory 测试 ====================

    @Test
    @DisplayName("getStudentChangeHistory：正常分页")
    void testGetStudentChangeHistory_normal() {
        KnowledgePoint kp = createKnowledgePoint(KP_ID, "数学", "active");
        when(changeLogMapper.countHistoryByStudentAndKp(STUDENT_ID, TENANT_ID, KP_ID)).thenReturn(2L);
        MasteryChangeLog log1 = createChangeLog(KP_ID, new BigDecimal("0.05"));
        MasteryChangeLog log2 = createChangeLog(KP_ID, new BigDecimal("0.03"));
        when(changeLogMapper.selectHistoryByStudentAndKp(STUDENT_ID, TENANT_ID, KP_ID, 0, 10))
                .thenReturn(Arrays.asList(log1, log2));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(kp));

        PageResult<MasteryChangeLogVO> result = masteryService.getStudentChangeHistory(KP_ID, 1, 10);

        assertEquals(2L, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getSize());
        assertEquals(2, result.getRecords().size());
    }

    @Test
    @DisplayName("getStudentChangeHistory：空结果")
    void testGetStudentChangeHistory_empty() {
        when(changeLogMapper.countHistoryByStudentAndKp(STUDENT_ID, TENANT_ID, KP_ID)).thenReturn(0L);

        PageResult<MasteryChangeLogVO> result = masteryService.getStudentChangeHistory(KP_ID, 1, 10);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        // 不应查询详细数据
        verify(changeLogMapper, never()).selectHistoryByStudentAndKp(anyLong(), anyString(), anyString(), eq(0), eq(10));
    }

    // ==================== getAdminDashboard 测试 ====================

    @Test
    @DisplayName("getAdminDashboard：多条件筛选 + 分页")
    void testGetAdminDashboard_withFilters() {
        AdminMasteryQueryRequest request = AdminMasteryQueryRequest.builder()
                .studentId(STUDENT_ID)
                .knowledgePointId(KP_ID)
                .subject("数学")
                .page(1)
                .size(10)
                .build();

        KnowledgePoint kp = createKnowledgePoint(KP_ID, "数学", "active");
        // 先筛选知识点
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(kp));

        // 模拟分页查询结果
        StudentKnowledgeState state = createState(STUDENT_ID, KP_ID, new BigDecimal("0.6000"));
        Page<StudentKnowledgeState> pageResult = new Page<>(1, 10);
        pageResult.setRecords(Arrays.asList(state));
        pageResult.setTotal(1);
        when(stateMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(pageResult);

        PageResult<MasteryDetailVO> result = masteryService.getAdminDashboard(request);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals(KP_ID, result.getRecords().get(0).getKnowledgePointId());
        assertEquals("测试知识点", result.getRecords().get(0).getKnowledgePointName());
    }

    @Test
    @DisplayName("getAdminDashboard：无匹配知识点 - 返回空")
    void testGetAdminDashboard_noKpMatch() {
        AdminMasteryQueryRequest request = AdminMasteryQueryRequest.builder()
                .subject("不存在的科目")
                .page(1)
                .size(10)
                .build();

        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        PageResult<MasteryDetailVO> result = masteryService.getAdminDashboard(request);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    // ==================== getAdminChangeHistory 测试 ====================

    @Test
    @DisplayName("getAdminChangeHistory：正常查询")
    void testGetAdminChangeHistory_normal() {
        Long targetStudentId = 2L;
        KnowledgePoint kp = createKnowledgePoint(KP_ID, "数学", "active");
        when(changeLogMapper.countHistoryByStudentAndKp(targetStudentId, TENANT_ID, KP_ID)).thenReturn(1L);
        MasteryChangeLog log = createChangeLog(KP_ID, new BigDecimal("0.05"));
        when(changeLogMapper.selectHistoryByStudentAndKp(targetStudentId, TENANT_ID, KP_ID, 0, 10))
                .thenReturn(Arrays.asList(log));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(kp));

        PageResult<MasteryChangeLogVO> result = masteryService.getAdminChangeHistory(targetStudentId, KP_ID, 1, 10);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
    }

    @Test
    @DisplayName("getAdminChangeHistory：空结果")
    void testGetAdminChangeHistory_empty() {
        Long targetStudentId = 2L;
        when(changeLogMapper.countHistoryByStudentAndKp(targetStudentId, TENANT_ID, KP_ID)).thenReturn(0L);

        PageResult<MasteryChangeLogVO> result = masteryService.getAdminChangeHistory(targetStudentId, KP_ID, 1, 10);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    // ==================== 辅助方法 ====================

    private StudentKnowledgeState createState(Long studentId, String kpId, BigDecimal mastery) {
        StudentKnowledgeState state = new StudentKnowledgeState();
        state.setStudentId(studentId);
        state.setKnowledgePointId(kpId);
        state.setTenantId(TENANT_ID);
        state.setMasteryScore(mastery);
        state.setConfidence(new BigDecimal("0.1000"));
        state.setSource(ChangeType.HOMEWORK.getCode());
        state.setLastAssessedAt(LocalDateTime.now());
        state.setVersion(0);
        return state;
    }

    private HomeworkSubmissionDetail createSubmission(Long submissionId, String kpId,
                                                       String questionSource, Long questionId, int isCorrect) {
        HomeworkSubmissionDetail detail = new HomeworkSubmissionDetail();
        detail.setSubmissionId(submissionId);
        detail.setStudentId(STUDENT_ID);
        detail.setKnowledgePointId(kpId);
        detail.setQuestionSource(questionSource);
        detail.setQuestionId(questionId);
        detail.setIsCorrect(isCorrect);
        detail.setTenantId(TENANT_ID);
        return detail;
    }

    private KnowledgePoint createKnowledgePoint(String kpId, String subject, String status) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setKnowledgePointId(kpId);
        kp.setSubject(subject);
        kp.setName("测试知识点");
        kp.setStatus(status);
        kp.setTenantId(TENANT_ID);
        return kp;
    }

    private MasteryChangeLog createChangeLog(String kpId, BigDecimal delta) {
        MasteryChangeLog log = new MasteryChangeLog();
        log.setLogId(1L);
        log.setStudentId(STUDENT_ID);
        log.setKnowledgePointId(kpId);
        log.setChangeType(ChangeType.HOMEWORK.getCode());
        log.setDelta(delta);
        log.setMasteryBefore(new BigDecimal("0.5000"));
        log.setMasteryAfter(new BigDecimal("0.5500"));
        log.setTenantId(TENANT_ID);
        log.setCreateTime(LocalDateTime.now());
        return log;
    }

    // ==================== 试卷结算批量入口（Change 2026-11-11，design §7.3） ====================

    @org.junit.jupiter.api.Nested
    @DisplayName("updateMasteryFromExam 试卷掌握度批量更新")
    class ExamMasteryTests {

        private StudentKnowledgeState state(String kpId, String mastery, String confidence) {
            StudentKnowledgeState state = new StudentKnowledgeState();
            state.setStudentId(STUDENT_ID);
            state.setKnowledgePointId(kpId);
            state.setMasteryScore(new BigDecimal(mastery));
            state.setConfidence(new BigDecimal(confidence));
            state.setTenantId(TENANT_ID);
            state.setVersion(1);
            return state;
        }

        private java.util.Map<String, Object> difficultyRow(String kpId, String difficulty, int count) {
            java.util.Map<String, Object> row = new java.util.HashMap<>();
            row.put("knowledge_point_id", kpId);
            row.put("difficulty", difficulty);
            row.put("cnt", count);
            return row;
        }

        @Test
        @DisplayName("答对答错都更新：同知识点多题依次演进，只写一次终值 + 每事件一条日志")
        void should_updateBothCorrectAndIncorrect_withAggregatedState() {
            String kpId = "kp-1";
            when(stateMapper.selectByStudentAndKpIds(eq(STUDENT_ID), eq(TENANT_ID), anyList()))
                    .thenReturn(List.of(state(kpId, "0.5000", "0.1000")));
            // 题库同 kp 历史难度：basic 1 道 → 均值 0.3（validated 保留 declared 0.3）
            when(questionBankMapper.selectDifficultyDistributionByKpIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(List.of(difficultyRow(kpId, "basic", 1)));

            masteryService.updateMasteryFromExam(STUDENT_ID, TENANT_ID, 10L, List.of(
                    new com.jiang.dto.ExamMasteryEvent(kpId, true, "basic"),
                    new com.jiang.dto.ExamMasteryEvent(kpId, false, "basic")));

            // 1) state 只写一次（批量 CASE WHEN），终值按事件顺序演进
            ArgumentCaptor<List<StudentKnowledgeState>> stateCaptor = ArgumentCaptor.forClass(List.class);
            verify(stateMapper).batchUpdateMastery(eq(STUDENT_ID), eq(TENANT_ID),
                    stateCaptor.capture(), eq("exam"), any());
            assertEquals(1, stateCaptor.getValue().size());

            BigDecimal m0 = new BigDecimal("0.5000");
            BigDecimal c0 = new BigDecimal("0.1000");
            BigDecimal dNorm = new BigDecimal("0.3");
            BigDecimal m1 = com.jiang.common.util.MasteryFormulaUtil.calculateMasteryCorrect(
                    m0, ChangeType.EXAM.getSourceWeight(), dNorm, c0);
            BigDecimal m2 = com.jiang.common.util.MasteryFormulaUtil.calculateMasteryIncorrect(
                    m1, ChangeType.EXAM.getSourceWeight(), dNorm,
                    com.jiang.common.util.MasteryFormulaUtil.updateConfidence(c0));
            assertEquals(0, m2.compareTo(stateCaptor.getValue().get(0).getMasteryScore()),
                    "终值应为 答对→答错 依次演进的结果");
            assertEquals("exam", stateCaptor.getValue().get(0).getSource());

            // 2) 日志：每事件一条，字段完整（change_type/source_id/delta/before/after）
            ArgumentCaptor<List<MasteryChangeLog>> logCaptor = ArgumentCaptor.forClass(List.class);
            verify(changeLogMapper).batchInsert(logCaptor.capture());
            List<MasteryChangeLog> logs = logCaptor.getValue();
            assertEquals(2, logs.size());
            assertEquals("exam", logs.get(0).getChangeType());
            assertEquals(10L, logs.get(0).getSourceId());
            assertEquals(0, m0.compareTo(logs.get(0).getMasteryBefore()));
            assertEquals(0, m1.compareTo(logs.get(0).getMasteryAfter()));
            assertEquals(0, m1.subtract(m0).compareTo(logs.get(0).getDelta()));
            assertTrue(logs.get(0).getDelta().signum() > 0, "答对 delta 为正");
            assertTrue(logs.get(1).getDelta().signum() < 0, "答错 delta 为负");

            // 3) 掌握度联动错题：按去重 kp 调一次
            verify(errorQuestionService, times(1)).syncPauseStatus(
                    eq(STUDENT_ID), eq(TENANT_ID), eq(kpId), any());
        }

        @Test
        @DisplayName("D_norm 四条断言：阈值内保留 declared / 偏离>0.2 / 无历史 / 非法缺失 → 0.5")
        void should_validateDNorm_when_difficultyDeclared() {
            String withinKp = "kp-within";
            String deviatedKp = "kp-deviated";
            String noHistoryKp = "kp-nohistory";
            String invalidKp = "kp-invalid";

            when(stateMapper.selectByStudentAndKpIds(eq(STUDENT_ID), eq(TENANT_ID), anyList()))
                    .thenReturn(List.of(
                            state(withinKp, "0.5000", "0.1000"),
                            state(deviatedKp, "0.5000", "0.1000"),
                            state(noHistoryKp, "0.5000", "0.1000"),
                            state(invalidKp, "0.5000", "0.1000")));
            when(questionBankMapper.selectDifficultyDistributionByKpIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(List.of(
                            difficultyRow(withinKp, "basic", 2),       // 均值 0.3，declared 0.3 → 保留
                            difficultyRow(deviatedKp, "basic", 1),     // 均值 0.3，declared 0.9 → 降级 0.5
                            difficultyRow(invalidKp, "standard", 1)));  // 均值存在但 declared 非法 → 0.5

            masteryService.updateMasteryFromExam(STUDENT_ID, TENANT_ID, 10L, List.of(
                    new com.jiang.dto.ExamMasteryEvent(withinKp, true, "basic"),
                    new com.jiang.dto.ExamMasteryEvent(deviatedKp, true, "challenge"),
                    new com.jiang.dto.ExamMasteryEvent(noHistoryKp, true, "standard"),
                    new com.jiang.dto.ExamMasteryEvent(invalidKp, true, "weird")));

            ArgumentCaptor<List<StudentKnowledgeState>> stateCaptor = ArgumentCaptor.forClass(List.class);
            verify(stateMapper).batchUpdateMastery(eq(STUDENT_ID), eq(TENANT_ID),
                    stateCaptor.capture(), eq("exam"), any());

            java.util.Map<String, BigDecimal> masteryByKp = new java.util.HashMap<>();
            for (StudentKnowledgeState state : stateCaptor.getValue()) {
                masteryByKp.put(state.getKnowledgePointId(), state.getMasteryScore());
            }
            BigDecimal m0 = new BigDecimal("0.5000");
            BigDecimal c0 = new BigDecimal("0.1000");
            BigDecimal w = ChangeType.EXAM.getSourceWeight();

            // ① 阈值内：declared 0.3 保留
            assertEquals(0, com.jiang.common.util.MasteryFormulaUtil.calculateMasteryCorrect(
                            m0, w, new BigDecimal("0.3"), c0).compareTo(masteryByKp.get(withinKp)));
            // ② 偏离 >0.2：0.9 与均值 0.3 相差 0.6 → 0.5
            assertEquals(0, com.jiang.common.util.MasteryFormulaUtil.calculateMasteryCorrect(
                            m0, w, new BigDecimal("0.5"), c0).compareTo(masteryByKp.get(deviatedKp)));
            // ③ 无历史 → 0.5
            assertEquals(0, com.jiang.common.util.MasteryFormulaUtil.calculateMasteryCorrect(
                            m0, w, new BigDecimal("0.5"), c0).compareTo(masteryByKp.get(noHistoryKp)));
            // ④ 非法难度 → 0.5
            assertEquals(0, com.jiang.common.util.MasteryFormulaUtil.calculateMasteryCorrect(
                            m0, w, new BigDecimal("0.5"), c0).compareTo(masteryByKp.get(invalidKp)));
        }

        @Test
        @DisplayName("缺失 state → 批量 INSERT IGNORE 后重查一次，再批量更新（无历史也能建档并写日志）")
        void should_createMissingStatesInBatch() {
            String kpId = "kp-new";
            when(stateMapper.selectByStudentAndKpIds(eq(STUDENT_ID), eq(TENANT_ID), anyList()))
                    .thenReturn(List.of())
                    .thenReturn(List.of(state(kpId, "0.5000", "0.1000")));
            when(questionBankMapper.selectDifficultyDistributionByKpIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(List.of());

            masteryService.updateMasteryFromExam(STUDENT_ID, TENANT_ID, 10L,
                    List.of(new com.jiang.dto.ExamMasteryEvent(kpId, true, null)));

            // 1) 无记录时按 findOrCreateState 语义批量建档：初始掌握度 0.5 / 置信度 0.1 / source=exam
            ArgumentCaptor<List<StudentKnowledgeState>> createdCaptor = ArgumentCaptor.forClass(List.class);
            verify(stateMapper).batchInsertIgnore(createdCaptor.capture());
            StudentKnowledgeState created = createdCaptor.getValue().get(0);
            assertEquals(kpId, created.getKnowledgePointId());
            assertEquals(0, com.jiang.common.util.MasteryFormulaUtil.MASTERY_INITIAL
                    .compareTo(created.getMasteryScore()));
            assertEquals(0, com.jiang.common.util.MasteryFormulaUtil.CONFIDENCE_INITIAL
                    .compareTo(created.getConfidence()));
            assertEquals("exam", created.getSource());
            assertEquals(TENANT_ID, created.getTenantId());

            verify(stateMapper, times(2)).selectByStudentAndKpIds(eq(STUDENT_ID), eq(TENANT_ID), anyList());
            verify(stateMapper).batchUpdateMastery(eq(STUDENT_ID), eq(TENANT_ID), anyList(), eq("exam"), any());

            // 2) 没有掌握度数据也必须写一条 mastery_change_log
            ArgumentCaptor<List<MasteryChangeLog>> logCaptor = ArgumentCaptor.forClass(List.class);
            verify(changeLogMapper).batchInsert(logCaptor.capture());
            assertEquals(1, logCaptor.getValue().size());
            assertEquals("exam", logCaptor.getValue().get(0).getChangeType());
            assertEquals(10L, logCaptor.getValue().get(0).getSourceId());
            assertTrue(logCaptor.getValue().get(0).getDelta().signum() > 0);
        }

        @Test
        @DisplayName("空事件 / 全空知识点 → 不写库")
        void should_skip_when_noEvents() {
            masteryService.updateMasteryFromExam(STUDENT_ID, TENANT_ID, 10L, List.of());
            masteryService.updateMasteryFromExam(STUDENT_ID, TENANT_ID, 10L,
                    List.of(new com.jiang.dto.ExamMasteryEvent(null, true, "basic")));

            verify(stateMapper, never()).batchUpdateMastery(any(), any(), anyList(), any(), any());
            verify(changeLogMapper, never()).batchInsert(anyList());
        }

        @Test
        @DisplayName("exam 来源作业题 D_norm：difficulty→getDNorm→validateDNorm（阈值内保留）")
        void should_useExamQuestionDifficulty_forExamSourceDNorm() {
            String kpId = "kp-exam";
            HomeworkSubmissionDetail submission = examSubmission(500L, kpId);
            when(submissionDetailMapper.selectBatchIds(anyList())).thenReturn(List.of(submission));

            com.jiang.model.entity.ExamQuestion question = new com.jiang.model.entity.ExamQuestion();
            question.setId(500L);
            question.setDifficulty("standard");
            when(examQuestionMapper.selectById(500L)).thenReturn(question);
            when(questionBankMapper.selectAvgDifficultyByKp(kpId, TENANT_ID))
                    .thenReturn(new BigDecimal("0.6"));

            StudentKnowledgeState existing = state(kpId, "0.5000", "0.1000");
            when(stateMapper.selectOne(any())).thenReturn(existing);
            when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);

            masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, List.of(1L));

            BigDecimal expected = com.jiang.common.util.MasteryFormulaUtil.calculateMasteryCorrect(
                    new BigDecimal("0.5000"), ChangeType.HOMEWORK.getSourceWeight(),
                    new BigDecimal("0.6"), new BigDecimal("0.1000"));
            assertEquals(0, expected.compareTo(existing.getMasteryScore()), "dNorm 应保留 declared 0.6");
        }

        @Test
        @DisplayName("exam 来源作业题 D_norm：无题库历史 → 0.5")
        void should_fallbackExamDNorm_when_noHistory() {
            String kpId = "kp-exam-nohistory";
            when(submissionDetailMapper.selectBatchIds(anyList()))
                    .thenReturn(List.of(examSubmission(501L, kpId)));

            com.jiang.model.entity.ExamQuestion question = new com.jiang.model.entity.ExamQuestion();
            question.setId(501L);
            question.setDifficulty("challenge");
            when(examQuestionMapper.selectById(501L)).thenReturn(question);
            when(questionBankMapper.selectAvgDifficultyByKp(kpId, TENANT_ID)).thenReturn(null);

            StudentKnowledgeState existing = state(kpId, "0.5000", "0.1000");
            when(stateMapper.selectOne(any())).thenReturn(existing);
            when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);

            masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, List.of(1L));

            BigDecimal expected = com.jiang.common.util.MasteryFormulaUtil.calculateMasteryCorrect(
                    new BigDecimal("0.5000"), ChangeType.HOMEWORK.getSourceWeight(),
                    new BigDecimal("0.5000"), new BigDecimal("0.1000"));
            assertEquals(0, expected.compareTo(existing.getMasteryScore()), "无历史 → D_NORM_DEFAULT 0.5");
        }

        @Test
        @DisplayName("exam 来源作业题 D_norm：难度缺失/非法 → 0.5（不是枚举兜底 0.6）")
        void should_fallbackExamDNorm_when_difficultyInvalid() {
            String kpId = "kp-exam-invalid";
            when(submissionDetailMapper.selectBatchIds(anyList()))
                    .thenReturn(List.of(examSubmission(502L, kpId)));

            com.jiang.model.entity.ExamQuestion question = new com.jiang.model.entity.ExamQuestion();
            question.setId(502L);
            question.setDifficulty("weird");
            when(examQuestionMapper.selectById(502L)).thenReturn(question);

            StudentKnowledgeState existing = state(kpId, "0.5000", "0.1000");
            when(stateMapper.selectOne(any())).thenReturn(existing);
            when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);

            masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, List.of(1L));

            BigDecimal expected = com.jiang.common.util.MasteryFormulaUtil.calculateMasteryCorrect(
                    new BigDecimal("0.5000"), ChangeType.HOMEWORK.getSourceWeight(),
                    new BigDecimal("0.5000"), new BigDecimal("0.1000"));
            assertEquals(0, expected.compareTo(existing.getMasteryScore()), "非法难度 → 0.5");
            verify(questionBankMapper, never()).selectAvgDifficultyByKp(anyString(), anyString());
        }

        private HomeworkSubmissionDetail examSubmission(Long questionId, String kpId) {
            HomeworkSubmissionDetail submission = new HomeworkSubmissionDetail();
            submission.setSubmissionId(1L);
            submission.setQuestionId(questionId);
            submission.setQuestionSource("exam");
            submission.setKnowledgePointId(kpId);
            submission.setIsCorrect(1);
            submission.setStudentId(STUDENT_ID);
            submission.setTenantId(TENANT_ID);
            return submission;
        }
    }
}
